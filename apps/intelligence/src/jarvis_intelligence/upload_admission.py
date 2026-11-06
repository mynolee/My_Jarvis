"""Inactive JVS-19/20 document admission coordinator. No route or native adapter.

Ports are trusted integration requirements, not evidence of real authentication,
atomic quotas, scanning or kernel isolation. They must enforce their bounds before
allocation/execution. Audio formats, PID limits and converter registration remain
deployment selections; no audio format is enabled here. No default ports exist.
"""
from dataclasses import dataclass
import hashlib
import math
from typing import Protocol
from uuid import UUID

MIB = 1024 * 1024
DOCUMENT_BYTES, DOCUMENT_PAGES = 20 * MIB, 200
VOICE_BYTES, VOICE_MILLISECONDS = 10 * MIB, 30_000
STORED_BYTES, DAILY_UPLOADS = 100 * MIB, 20
ZIP_ENTRIES, ZIP_EXPANDED, ZIP_RATIO = 1000, 50 * MIB, 100
PARSER_CPU, PARSER_MEMORY, PARSER_SECONDS, OUTPUT_BYTES = 1, 512 * MIB, 30, 2 * MIB
CLEANUP_SECONDS = 3600
FORMATS = {'.pdf': ('PDF', 'application/pdf'), '.txt': ('TXT', 'text/plain'),
           '.md': ('MD', 'text/markdown'),
           '.docx': ('DOCX', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document')}


class AdmissionError(ValueError):
    def __init__(self):
        super().__init__('EXTRACTION_NOT_ALLOWED')


def _need(value):
    if not value:
        raise AdmissionError()


def _integer(value, low, high):
    return type(value) is int and low <= value <= high


def _uuid(value):
    try:
        return type(value) is str and str(UUID(value)) == value
    except (ValueError, TypeError, AttributeError):
        return False


def _same_ticket(value, expected):
    return (type(value) is Ticket and all(type(getattr(value, name)) is type(getattr(expected, name)) and
            getattr(value, name) == getattr(expected, name) for name in Ticket.__dataclass_fields__))


@dataclass(frozen=True)
class Ticket:
    owner_id: str
    upload_id: str
    bytes: int
    sha256: str
    revision: int
    stored_after: int
    uploads_today: int


@dataclass(frozen=True)
class Seal:
    ticket: Ticket
    regular: bool
    nlink: int
    private_quarantine: bool
    outside_execution_and_public_roots: bool


@dataclass(frozen=True)
class FormatEvidence:
    format: str
    mime: str
    ambiguous: bool


@dataclass(frozen=True)
class ZipEntry:
    name: str
    kind: str
    compressed: int
    expanded: int
    link_target: str | None


@dataclass(frozen=True)
class Scan:
    ticket: Ticket
    status: str


@dataclass(frozen=True)
class ParserProfile:
    """Required registered converter/cage contract; not a kernel attestation."""
    pid_limit: int
    executable: str
    argv: tuple[str, ...]
    cpu: int
    memory: int
    seconds: int
    output_bytes: int
    nonroot: bool
    readonly_root: bool
    network: bool
    host_mounts: bool
    secrets: bool
    docker_socket: bool
    external_entities_scripts_macros_or_fetch: bool


@dataclass(frozen=True)
class Work:
    ticket: Ticket
    resource_id: str


@dataclass(frozen=True)
class Parsed:
    ticket: Ticket
    resource_id: str
    status: str
    text: bytes
    pages: int


@dataclass(frozen=True)
class Cleanup:
    upload_id: str
    resource_id: str
    status: str


@dataclass(frozen=True)
class Published:
    ticket: Ticket
    text_sha256: str


@dataclass(frozen=True)
class Result:
    upload_id: str | None
    state: str
    code: str
    quarantine_retained: bool | None
    cleanup: str


class UploadPorts(Protocol):
    # reserve must authenticate context/owner, CSRF and rate, and atomically
    # reserve both quotas. Retained quarantine continues to consume storage.
    def new_id(self) -> str: ...
    def reserve(self, context, upload_id, size, digest, stored_limit, daily_limit) -> Ticket: ...
    # UUID storage is exclusive/new-only and immutable; display_name is metadata.
    def quarantine(self, ticket, data, display_name) -> Seal: ...
    def inspect_format(self, seal) -> FormatEvidence: ...
    def docx_entries(self, seal, entry_limit, expanded_limit, ratio_limit) -> tuple[ZipEntry, ...]: ...
    def claim_current(self, ticket) -> Ticket | None: ...
    def current(self, ticket) -> Ticket | None: ...
    # A scanner owns/cleans its internal temporary resources before returning;
    # an unavailable/failed scanner never authorizes extraction.
    def scan(self, seal) -> Scan: ...
    def prepare(self, seal, profile, deadline) -> Work: ...
    def extract(self, work, seal, profile, deadline, page_limit) -> Parsed: ...
    def cleanup(self, work, deadline) -> Cleanup: ...
    # Publish extracted text only; retain the immutable quarantined original.
    # Atomically recheck current owner/ticket and commit once. An uncertain
    # response must be reconciled, never automatically sent again.
    def publish(self, seal, text) -> Published: ...


def validate_docx_entries(entries, archive_bytes):
    """Validate inventory before decompression; inspector must bound iteration."""
    _need(type(entries) is tuple and 0 < len(entries) <= ZIP_ENTRIES and _integer(archive_bytes, 1, DOCUMENT_BYTES))
    names = set()
    compressed = expanded = 0
    for entry in entries:
        _need(type(entry) is ZipEntry and type(entry.kind) is str and entry.kind in ('file', 'directory') and entry.link_target is None)
        name = entry.name
        _need(type(name) is str and name and not name.startswith(('/', '\\')) and '\\' not in name and '\0' not in name)
        path = name[:-1] if entry.kind == 'directory' and name.endswith('/') else name
        parts = path.split('/')
        _need(all(part not in ('', '.', '..') for part in parts) and not (len(parts[0]) >= 2 and parts[0][1] == ':'))
        _need(path not in names)
        names.add(path)
        _need(_integer(entry.compressed, 0, DOCUMENT_BYTES) and _integer(entry.expanded, 0, ZIP_EXPANDED))
        _need(entry.kind != 'directory' or entry.compressed == entry.expanded == 0)
        compressed += entry.compressed
        expanded += entry.expanded
        _need(expanded <= ZIP_EXPANDED and compressed <= archive_bytes)
    _need(expanded <= ZIP_RATIO * compressed)


def validate_voice_bounds(size, duration_numerator, duration_denominator):
    """Exact seconds ratio from a required decoder; does not admit a codec.

    No float rounding or metadata-only duration is promoted to decoder proof.
    Voice extension/MIME/codec selection and its real decoder remain unbound.
    """
    _need(_integer(size, 1, VOICE_BYTES) and type(duration_numerator) is int and
          duration_numerator >= 0 and type(duration_denominator) is int and
          duration_denominator > 0 and duration_numerator <= 30 * duration_denominator)


class DocumentAdmission:
    def __init__(self, ports: UploadPorts, clock, profile: ParserProfile):
        # Audio format/codec policy and real converter/cage registration are not
        # inferred. pid_limit/executable/argv must be selected by integration.
        _need(callable(clock) and type(profile) is ParserProfile)
        _need(type(profile.pid_limit) is int and profile.pid_limit > 0 and type(profile.executable) is str and bool(profile.executable))
        _need(type(profile.argv) is tuple and profile.argv and all(type(x) is str for x in profile.argv) and profile.argv[0] == profile.executable)
        _need(all(_integer(value, limit, limit) for value, limit in
                  ((profile.cpu, PARSER_CPU), (profile.memory, PARSER_MEMORY),
                   (profile.seconds, PARSER_SECONDS), (profile.output_bytes, OUTPUT_BYTES))))
        _need(profile.nonroot is True and profile.readonly_root is True and all(x is False for x in
              (profile.network, profile.host_mounts, profile.secrets, profile.docker_socket, profile.external_entities_scripts_macros_or_fetch)))
        for name in ('new_id', 'reserve', 'quarantine', 'inspect_format', 'docx_entries', 'claim_current',
                     'current', 'scan', 'prepare', 'extract', 'cleanup', 'publish'):
            _need(callable(getattr(ports, name, None)))
        self._ports, self._clock, self._profile = ports, clock, profile
        self._last = None
        self._pending = {}

    def _now(self):
        value = self._clock()
        _need(type(value) in (int, float) and math.isfinite(value) and (self._last is None or value >= self._last))
        self._last = value
        return value

    def _current(self, ticket):
        value = self._ports.current(ticket)
        _need(_same_ticket(value, ticket))

    def _cleanup(self, work, origin):
        try:
            _need(self._now() <= origin + CLEANUP_SECONDS)
            result = self._ports.cleanup(work, origin + CLEANUP_SECONDS)
            _need(type(result) is Cleanup and type(result.upload_id) is str and type(result.resource_id) is str and
                  result.upload_id == work.ticket.upload_id and result.resource_id == work.resource_id)
            _need(type(result.status) is str and result.status in ('CLEAN', 'RETRYABLE', 'UNKNOWN'))
            _need(self._now() <= origin + CLEANUP_SECONDS)
        except Exception:
            return 'UNKNOWN'  # Permission/close uncertainty is never retried.
        if result.status == 'RETRYABLE':
            self._pending[work.ticket.upload_id] = (work, origin)
        return result.status

    def submit(self, context, display_name, mime, data):
        """Input bytes must already have a bounded upload transport. No routes."""
        upload_id = None
        retained = False
        work = None
        cleanup = 'NOT_ACQUIRED'
        origin = None
        parsing_started = False
        publication_attempted = False
        try:
            _need(type(data) is bytes and 0 < len(data) <= DOCUMENT_BYTES)
            _need(type(display_name) is str and display_name not in ('', '.', '..') and
                  all(x not in display_name for x in ('/', '\\', '\0')))
            extension = '.' + display_name.rsplit('.', 1)[-1].lower() if '.' in display_name else ''
            _need(type(mime) is str and extension in FORMATS and (FORMATS[extension][1] == mime))
            kind = FORMATS[extension][0]
            if kind == 'PDF':
                _need(data.startswith(b'%PDF-'))
            elif kind == 'DOCX':
                _need(data.startswith(b'PK\x03\x04'))
            else:
                data.decode('utf-8', 'strict')
                _need(b'\0' not in data)
            self._now()
            candidate_id = self._ports.new_id()
            _need(_uuid(candidate_id))
            upload_id = candidate_id
            digest = hashlib.sha256(data).hexdigest()
            ticket = self._ports.reserve(context, upload_id, len(data), digest, STORED_BYTES, DAILY_UPLOADS)
            _need(type(ticket) is Ticket and _uuid(ticket.owner_id) and type(ticket.upload_id) is str and ticket.upload_id == upload_id and
                  ticket.bytes == len(data) and type(ticket.bytes) is int and type(ticket.sha256) is str and ticket.sha256 == digest and
                  type(ticket.revision) is int and ticket.revision > 0 and _integer(ticket.stored_after, len(data), STORED_BYTES) and
                  _integer(ticket.uploads_today, 1, DAILY_UPLOADS))
            retained = None  # A failing storage operation is not proof of absence.
            seal = self._ports.quarantine(ticket, data, display_name)
            _need(type(seal) is Seal and _same_ticket(seal.ticket, ticket) and seal.regular is True and seal.nlink == 1 and
                  type(seal.nlink) is int and seal.private_quarantine is True and seal.outside_execution_and_public_roots is True)
            retained = True
            # Metadata bounds precede any deeper format inspection/decompression.
            if kind == 'DOCX':
                validate_docx_entries(self._ports.docx_entries(seal, ZIP_ENTRIES, ZIP_EXPANDED, ZIP_RATIO), ticket.bytes)
            evidence = self._ports.inspect_format(seal)
            _need(type(evidence) is FormatEvidence and type(evidence.format) is str and type(evidence.mime) is str and
                  evidence.format == kind and evidence.mime == mime and evidence.ambiguous is False)
            claim = self._ports.claim_current(ticket)
            _need(_same_ticket(claim, ticket))
            scan = self._ports.scan(seal)
            _need(type(scan) is Scan and _same_ticket(scan.ticket, ticket) and type(scan.status) is str and scan.status == 'CLEAN')
            self._current(ticket)
            origin = self._now()
            deadline = origin + PARSER_SECONDS
            parsing_started = True
            candidate = self._ports.prepare(seal, self._profile, deadline)
            _need(type(candidate) is Work and _same_ticket(candidate.ticket, ticket) and _uuid(candidate.resource_id))
            work = candidate  # Only a verified owned resource may be cleaned.
            _need(self._now() < deadline)
            parsed = self._ports.extract(work, seal, self._profile, deadline, DOCUMENT_PAGES)
            _need(type(parsed) is Parsed and _same_ticket(parsed.ticket, ticket) and type(parsed.resource_id) is str and parsed.resource_id == work.resource_id and
                  type(parsed.status) is str and parsed.status == 'OK' and type(parsed.text) is bytes and len(parsed.text) <= OUTPUT_BYTES and
                  _integer(parsed.pages, 1, DOCUMENT_PAGES) and self._now() < deadline)
            parsed.text.decode('utf-8', 'strict')
            cleanup = self._cleanup(work, origin)
            work = None  # Consume this cleanup attempt, including UNKNOWN.
            _need(cleanup == 'CLEAN')
            self._current(ticket)
            publication_attempted = True
            published = self._ports.publish(seal, parsed.text)
            _need(type(published) is Published and _same_ticket(published.ticket, ticket) and type(published.text_sha256) is str and
                  published.text_sha256 == hashlib.sha256(parsed.text).hexdigest())
            return Result(upload_id, 'COMPLETE', 'EXTRACTED', retained, cleanup)
        except BaseException as primary:
            if work is not None and type(work) is Work:
                cleanup = self._cleanup(work, origin)
            if not isinstance(primary, Exception):
                raise
            state = 'UNKNOWN' if publication_attempted or retained is None else 'ERROR' if parsing_started else 'QUARANTINED' if retained else 'REJECTED'
            return Result(upload_id, state, 'EXTRACTION_ERROR' if parsing_started else 'EXTRACTION_NOT_ALLOWED', retained, cleanup)

    def retry_cleanup(self, upload_id):
        # Only an explicitly RETRYABLE owned resource can enter this path. A
        # retry never parses/publishes or upgrades the original extraction.
        item = self._pending.pop(upload_id, None)
        if item is None:
            return 'UNKNOWN'
        work, origin = item
        try:
            if self._now() - origin > CLEANUP_SECONDS:
                return 'EXPIRED'
        except Exception:
            return 'UNKNOWN'
        return self._cleanup(work, origin)

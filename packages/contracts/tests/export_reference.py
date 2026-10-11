"""Synthetic sequential export oracle; not real auth/SQL/HTTP/browser evidence."""
import copy
import hashlib
import re
import sys
from datetime import datetime, timedelta
from functools import lru_cache
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from canonical import canonical_bytes, loads

TIME = re.compile(r'[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]{1,6})?Z')
CHECKER = FormatChecker()
LIMITS = loads((ROOT / 'fixtures/exports/implementation-limits.json').read_text())
ITEM_FIELDS = ('id', 'key', 'kind', 'value', 'change_reason', 'version',
               'confirmed_at', 'created_at', 'updated_at')


@CHECKER.checks('date-time', raises=(ValueError, TypeError, OverflowError))
def utc_instant(value):
    return not isinstance(value, str) or bool(TIME.fullmatch(value)) and bool(datetime.fromisoformat(value))


def instant(value):
    if not isinstance(value, str) or not utc_instant(value):
        raise ValueError('INVALID_TIMESTAMP')
    return datetime.fromisoformat(value)


def stamp(value):
    return value.isoformat().replace('+00:00', 'Z')


@lru_cache(maxsize=None)
def validator(name):
    schema = loads((ROOT / 'schemas' / f'export-{name}.schema.json').read_text())
    Draft202012Validator.check_schema(schema)
    return Draft202012Validator(schema, format_checker=CHECKER)


def valid(name, value):
    try:
        canonical_bytes(value)
        return validator(name).is_valid(value)
    except (ValueError, TypeError, UnicodeError):
        return False


def parse_create(raw, key, query=()):
    if query:
        raise ValueError('INVALID_REQUEST')
    encoded = raw.encode('utf-8') if isinstance(raw, str) else raw
    if len(encoded) > LIMITS['transport_raw_body_bytes']:
        raise ValueError('BODY_TOO_LARGE')
    try:
        body = loads(encoded.decode('utf-8'))
        if not valid('create-request', body) or not valid('headers', {'Idempotency-Key': key}):
            raise ValueError('INVALID_REQUEST')
        canonical = canonical_bytes(body)
    except (ValueError, UnicodeError, TypeError) as error:
        raise ValueError('INVALID_REQUEST') from error
    if len(canonical) > LIMITS['request_canonical_utf8_bytes']:
        raise ValueError('BODY_TOO_LARGE')
    return body, hashlib.sha256(canonical).hexdigest()


def parse_read(query=(), body=b''):
    if query or body:
        raise ValueError('INVALID_REQUEST')


def project_item(row):
    """Build every output level positively; do not copy a DB/payload map."""
    item = {field: copy.deepcopy(row[field]) for field in ITEM_FIELDS}
    item['source'] = {field: copy.deepcopy(row['source'][field])
                      for field in ('type', 'submitted_at')}
    return item


def validate_job(job):
    if not valid('job', job):
        return False
    created, expires, checked = [instant(job[field]) for field in ('created_at', 'expires_at', 'checked_at')]
    return (expires == created + timedelta(seconds=LIMITS['job_lifetime_seconds']) and checked >= created
            and job['status'] == ('AVAILABLE' if checked < expires else 'EXPIRED'))


def validate_document(document, job=None):
    if not valid('document', document):
        return False
    ids = [item['id'] for item in document['items']]
    if ids != sorted(set(ids)) or len(canonical_bytes(document)) > LIMITS['export_utf8_bytes']:
        return False
    if job is not None:
        if document['export_job_id'] != job['id']:
            return False
        if any(document[field] != job[field] for field in ('kind', 'scope', 'project_id', 'semantics')):
            return False
        if not instant(job['created_at']) <= instant(document['exported_at']) < instant(job['expires_at']):
            return False
    for item in document['items']:
        created, updated, confirmed, submitted = [instant(item[field]) for field in ('created_at', 'updated_at', 'confirmed_at')] + [instant(item['source']['submitted_at'])]
        if not created <= updated <= instant(document['exported_at']) or confirmed != updated or submitted != confirmed:
            return False
    return True


class SyntheticExports:
    """Caller serializes these methods. Session labels are non-credential fixtures."""
    def __init__(self):
        self.sessions = {}
        self.epochs = {}
        self.paused = set()
        self.projects = {}
        self.rows = []
        self.jobs = {}
        self.next_id = 1
        self.creation_events = 0

    def add_session(self, label, owner, *, recent=True):
        self.epochs.setdefault(owner, 1)
        self.sessions[label] = {'owner': owner, 'epoch': self.epochs[owner], 'live': True, 'recent': recent}

    def authenticate(self, label, recent=False):
        if label not in self.sessions:
            raise ValueError('AUTHENTICATION_REQUIRED')
        session = self.sessions[label]
        if not session['live'] or session['epoch'] != self.epochs[session['owner']]:
            raise ValueError('SESSION_REVOKED')
        if recent and not session['recent']:
            raise ValueError('REAUTHENTICATION_REQUIRED')
        return session

    def project(self, owner, request):
        if request['scope'] == 'PROJECT' and self.projects.get(request['project_id']) != owner:
            raise ValueError('NOT_FOUND')

    def binding(self, label, internal, recent=False):
        session = self.authenticate(label, recent)
        if internal['owner'] != session['owner']:
            raise ValueError('NOT_FOUND')
        if internal['session'] != label or internal['epoch'] != session['epoch']:
            raise ValueError('AUTHORITY_NOT_VALID')
        self.project(session['owner'], internal['request'])
        return session

    def view(self, internal, now):
        request = internal['request']
        result = {'id': internal['id'], **copy.deepcopy(request), 'semantics': 'CURRENT_AT_DOWNLOAD',
                  'status': 'AVAILABLE' if instant(now) < instant(internal['expires_at']) else 'EXPIRED',
                  'created_at': internal['created_at'], 'expires_at': internal['expires_at'], 'checked_at': now}
        if not validate_job(result):
            raise ValueError('INTERNAL_ERROR')
        return result

    def create(self, label, raw, key, now, query=()):
        request, digest = parse_create(raw, key, query)
        session = self.authenticate(label, recent=True)
        self.project(session['owner'], request)
        instant(now)
        prior = next((j for j in self.jobs.values() if j['owner'] == session['owner'] and j['key'] == key), None)
        if prior is not None:
            self.binding(label, prior)
            if digest != prior['digest']:
                raise ValueError('IDEMPOTENCY_CONFLICT')
            return self.view(prior, now)
        if session['owner'] in self.paused:
            raise ValueError('EXECUTION_PAUSED')
        # Stage all changes first: an error must not partially purge/insert.
        cutoff = instant(now) - timedelta(seconds=LIMITS['metadata_retention_seconds'])
        retained = {i: j for i, j in self.jobs.items()
                    if not (j['owner'] == session['owner'] and instant(j['created_at']) <= cutoff)}
        if sum(j['owner'] == session['owner'] for j in retained.values()) >= LIMITS['retained_jobs_per_owner']:
            raise ValueError('EXPORT_JOB_LIMIT')
        identifier = f'70000000-0000-4000-8000-{self.next_id:012d}'
        internal = {'id': identifier, 'owner': session['owner'], 'session': label, 'epoch': session['epoch'],
                    'key': key, 'digest': digest, 'request': copy.deepcopy(request), 'created_at': now,
                    'expires_at': stamp(instant(now) + timedelta(seconds=LIMITS['job_lifetime_seconds']))}
        result = self.view(internal, now)
        retained[identifier] = internal
        self.jobs = retained
        self.next_id += 1
        self.creation_events += 1
        return result

    def lookup(self, label, identifier, recent=False):
        self.authenticate(label, recent)
        if not valid('job-path', {'job_id': identifier}):
            raise ValueError('INVALID_REQUEST')
        if identifier not in self.jobs:
            raise ValueError('NOT_FOUND')
        job = self.jobs[identifier]
        self.binding(label, job, recent)
        return job

    def status(self, label, identifier, now, query=(), body=b''):
        parse_read(query, body)
        return self.view(self.lookup(label, identifier), now)

    def receipt(self, label, key, now, query=(), body=b''):
        parse_read(query, body)
        session = self.authenticate(label)
        if not valid('request-path', {'key': key}):
            raise ValueError('INVALID_REQUEST')
        found = next((j for j in self.jobs.values() if j['owner'] == session['owner'] and j['key'] == key), None)
        if found is None:
            return {'status': 'NOT_OBSERVED', 'job': None}
        self.binding(label, found)
        return {'status': 'COMMITTED', 'job': self.view(found, now)}

    def download(self, label, identifier, now, *, elapsed_ms=0, final_check=None,
                 final_now=None, query=(), body=b''):
        parse_read(query, body)
        internal = self.lookup(label, identifier, recent=True)
        request = internal['request']
        if instant(now) >= instant(internal['expires_at']):
            raise ValueError('EXPORT_EXPIRED')
        if elapsed_ms >= LIMITS['transaction_build_ms']:
            raise ValueError('EXPORT_TIME_LIMIT')
        rows = sorted((r for r in self.rows if r['owner'] == internal['owner'] and r['active']
                       and r['scope'] == request['scope'] and r['project_id'] == request['project_id']),
                      key=lambda row: row['id'])
        if len(rows) > LIMITS['export_rows']:
            raise ValueError('EXPORT_ROW_LIMIT')
        document = {'schema_version': 1, 'kind': 'EXPLICIT_MEMORIES', 'scope': request['scope'],
                    'project_id': request['project_id'], 'export_job_id': identifier,
                    'exported_at': final_now or now, 'semantics': 'CURRENT_AT_DOWNLOAD',
                    'items': [project_item(row) for row in rows]}
        encoded = canonical_bytes(document)
        if len(encoded) > LIMITS['export_utf8_bytes']:
            raise ValueError('EXPORT_BYTE_LIMIT')
        if not validate_document(document, self.view(internal, final_now or now)):
            if instant(final_now or now) >= instant(internal['expires_at']):
                raise ValueError('EXPORT_EXPIRED')
            raise ValueError('INTERNAL_ERROR')
        if final_check:
            final_check(self)
        self.lookup(label, identifier, recent=True)
        # Returned bytes represent the already accepted release point. No copy is
        # stored in jobs; the caller owns any returned download just like a client.
        return encoded


class SyntheticExportClient:
    """No browser is present. Tracks uncertainty, not a filesystem-save result."""
    def __init__(self):
        self.generation = 0
        self.create_outcome = None
        self.job = None
        self.download_outcome = None
        self.local_save_verified = False
        self.post_count = 0

    def explicit_create(self):
        if self.create_outcome == 'PENDING':
            raise ValueError('OVERLAP')
        self.create_outcome = 'PENDING'
        self.post_count += 1

    def lose_create_response(self):
        self.create_outcome = 'UNKNOWN'

    def reconcile(self, receipt, generation):
        if generation != self.generation or not valid('receipt', receipt):
            return False
        if receipt['status'] == 'COMMITTED' and validate_job(receipt['job']):
            self.create_outcome = 'COMMITTED'
            self.job = copy.deepcopy(receipt['job'])
        # NOT_OBSERVED remains inconclusive; never starts a POST.
        return True

    def change_session_or_scope(self):
        self.generation += 1
        self.job = None
        # Unknown create attribution is retained, not falsely cleared by logout.

    def downloaded_response(self, encoded, generation):
        if generation != self.generation or self.job is None:
            return False
        try:
            document = loads(encoded.decode('utf-8'))
            if not validate_document(document, self.job):
                return False
        except (ValueError, UnicodeError, TypeError):
            return False
        self.download_outcome = 'RESPONSE_VALIDATED'
        # This cannot establish a browser download or a successful local file save.
        return True

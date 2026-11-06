"""Synthetic ports only; no native files, scanners, parsers or cage proof."""
from dataclasses import replace
import hashlib
import unittest

from jarvis_intelligence.upload_admission import (
    AdmissionError, Cleanup, DAILY_UPLOADS, DOCUMENT_BYTES, DOCUMENT_PAGES,
    DocumentAdmission, FormatEvidence, MIB, OUTPUT_BYTES, PARSER_MEMORY,
    Parsed, ParserProfile, Published, Scan, Seal, STORED_BYTES, Ticket, Work,
    VOICE_BYTES, ZIP_ENTRIES, ZIP_EXPANDED, ZipEntry, validate_docx_entries, validate_voice_bounds,
)

OWNER = '00000000-0000-4000-8000-000000000001'
UPLOAD = '00000000-0000-4000-8000-000000000002'
RESOURCE = '00000000-0000-4000-8000-000000000003'
CONTEXT = object()
PDF = b'%PDF-1.7\nsynthetic opaque fixture; not a real PDF parser input'
DOCX = b'PK\x03\x04' + b'x' * 200
# PID=1 and converter name are explicit synthetic selections, not product policy.
PROFILE = ParserProfile(1, 'synthetic-converter', ('synthetic-converter',),
                        1, PARSER_MEMORY, 30, OUTPUT_BYTES, True, True,
                        False, False, False, False, False)


class Clock:
    def __init__(self): self.value = 0.0
    def __call__(self): return self.value


class MemoryPorts:
    def __init__(self, clock):
        self.clock = clock
        self.events = []
        self.objects = {}
        self.current_ticket = None
        self.claimed = False
        self.scan_status = 'CLEAN'
        self.detected = FormatEvidence('PDF', 'application/pdf', False)
        self.entries = (ZipEntry('word/document.xml', 'file', 20, 100, None),)
        self.stored = None
        self.daily = 1
        self.stale_after_scan = False
        self.seal_mutation = {}
        self.parse_status = 'OK'
        self.text = b'synthetic extracted text'
        self.pages = 1
        self.extract_seconds = 1
        self.cleanup_status = 'CLEAN'
        self.cleanup_error = None
        self.publish_error = None

    def new_id(self):
        self.events.append('id')
        return UPLOAD

    def reserve(self, context, upload_id, size, digest, stored_limit, daily_limit):
        self.events.append('reserve')
        if context is not CONTEXT: raise PermissionError('synthetic-secret-sentinel')
        assert (stored_limit, daily_limit) == (STORED_BYTES, DAILY_UPLOADS)
        self.current_ticket = Ticket(OWNER, upload_id, size, digest, 1,
                                     size if self.stored is None else self.stored, self.daily)
        return self.current_ticket

    def quarantine(self, ticket, data, display_name):
        self.events.append('quarantine')
        assert ticket.upload_id not in self.objects
        self.objects[ticket.upload_id] = (data, display_name)
        return replace(Seal(ticket, True, 1, True, True), **self.seal_mutation)

    def inspect_format(self, seal):
        self.events.append('inspect')
        assert seal.ticket.upload_id in self.objects
        return self.detected

    def docx_entries(self, seal, count, expanded, ratio):
        self.events.append('inventory')
        assert (count, expanded, ratio) == (1000, 50 * MIB, 100)
        return self.entries

    def claim_current(self, ticket):
        self.events.append('claim')
        if self.claimed or self.current_ticket != ticket: return None
        self.claimed = True
        return self.current_ticket

    def current(self, ticket):
        self.events.append('current')
        return self.current_ticket

    def scan(self, seal):
        self.events.append('scan')
        if self.stale_after_scan:
            self.current_ticket = replace(self.current_ticket, revision=2)
        return Scan(seal.ticket, self.scan_status)

    def prepare(self, seal, profile, deadline):
        self.events.append('prepare')
        assert profile == PROFILE and deadline == self.clock.value + 30
        return Work(seal.ticket, RESOURCE)

    def extract(self, work, seal, profile, deadline, page_limit):
        self.events.append('extract')
        assert work.ticket == seal.ticket and page_limit == 200 and profile == PROFILE
        self.clock.value += self.extract_seconds
        return Parsed(work.ticket, work.resource_id, self.parse_status, self.text, self.pages)

    def cleanup(self, work, deadline):
        self.events.append('cleanup')
        assert work.resource_id == RESOURCE and deadline == 3600
        if self.cleanup_error is not None: raise self.cleanup_error
        return Cleanup(work.ticket.upload_id, work.resource_id, self.cleanup_status)

    def publish(self, seal, text):
        self.events.append('publish')
        if self.publish_error is not None: raise self.publish_error
        assert self.events[-3:] == ['cleanup', 'current', 'publish']
        return Published(seal.ticket, hashlib.sha256(text).hexdigest())


class UploadAdmissionTests(unittest.TestCase):
    def make(self):
        clock = Clock(); ports = MemoryPorts(clock)
        return DocumentAdmission(ports, clock, PROFILE), ports, clock

    def send(self, subject, *, context=CONTEXT, name='display.pdf', mime='application/pdf', data=PDF):
        return subject.submit(context, name, mime, data)

    def test_pdf_positive_uses_uuid_and_clean_scan_before_parse_then_cleanup_before_publish(self):
        subject, ports, _ = self.make()
        result = self.send(subject)
        self.assertEqual((result.state, result.code, result.cleanup), ('COMPLETE', 'EXTRACTED', 'CLEAN'))
        self.assertEqual(set(ports.objects), {UPLOAD})
        self.assertEqual(ports.objects[UPLOAD], (PDF, 'display.pdf'))
        self.assertEqual(ports.events, ['id', 'reserve', 'quarantine', 'inspect', 'claim', 'scan',
                                      'current', 'prepare', 'extract', 'cleanup', 'current', 'publish'])

    def test_docx_positive_inventory_is_bounded_before_scan_or_extract(self):
        subject, ports, _ = self.make()
        ports.detected = FormatEvidence('DOCX', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', False)
        result = self.send(subject, name='a.docx', mime=ports.detected.mime, data=DOCX)
        self.assertEqual(result.state, 'COMPLETE')
        self.assertLess(ports.events.index('inventory'), ports.events.index('scan'))
        self.assertLess(ports.events.index('inventory'), ports.events.index('inspect'))

    def test_spoof_path_mime_generic_archive_and_unselected_audio_never_reach_ports(self):
        for arguments in ({'name': '../a.pdf'}, {'name': 'C:\\a.pdf'}, {'name': 'a.exe'},
                          {'name': 'a.zip'}, {'name': 'a.wav', 'mime': 'audio/wav'},
                          {'mime': 'text/html'}, {'data': b'<html>not PDF</html>'}):
            with self.subTest(arguments=arguments):
                subject, ports, _ = self.make()
                result = self.send(subject, **arguments)
                self.assertEqual(result.code, 'EXTRACTION_NOT_ALLOWED')
                self.assertEqual(ports.events, [])

    def test_format_evidence_ambiguity_or_different_magic_keeps_quarantine_and_no_scan(self):
        for evidence in (FormatEvidence('HTML', 'application/pdf', False),
                         FormatEvidence('PDF', 'application/pdf', True)):
            subject, ports, _ = self.make(); ports.detected = evidence
            result = self.send(subject)
            self.assertTrue(result.quarantine_retained)
            self.assertNotIn('scan', ports.events); self.assertNotIn('extract', ports.events)

    def test_auth_quota_and_stale_ticket_block_downstream_real_subject(self):
        for change in ('owner', 'daily', 'stored', 'stale'):
            with self.subTest(change=change):
                subject, ports, _ = self.make()
                if change == 'daily': ports.daily = DAILY_UPLOADS + 1
                if change == 'stored': ports.stored = STORED_BYTES + 1
                if change == 'stale': ports.stale_after_scan = True
                result = self.send(subject, context=object() if change == 'owner' else CONTEXT)
                self.assertNotEqual(result.state, 'COMPLETE')
                self.assertNotIn('prepare', ports.events); self.assertNotIn('publish', ports.events)

    def test_storage_type_link_and_public_root_receipts_do_not_authorize_scanning(self):
        for mutation in ({'regular': False}, {'nlink': 2}, {'private_quarantine': False},
                         {'outside_execution_and_public_roots': False}):
            subject, ports, _ = self.make(); ports.seal_mutation = mutation
            result = self.send(subject)
            self.assertEqual(result.state, 'UNKNOWN')
            self.assertNotIn('scan', ports.events); self.assertNotIn('prepare', ports.events)

    def test_zip_traversal_links_special_entries_bombs_and_ratio_have_no_scanner_or_parse(self):
        bad = ((ZipEntry('../x', 'file', 1, 1, None),),
               (ZipEntry('/x', 'file', 1, 1, None),),
               (ZipEntry('x', 'symlink', 1, 1, 'outside'),),
               (ZipEntry('x', 'fifo', 0, 0, None),),
               (ZipEntry('x', 'file', 1, 101, None),),
               (ZipEntry('x', 'file', 1, ZIP_EXPANDED + 1, None),),
               tuple(ZipEntry(str(i), 'file', 0, 0, None) for i in range(ZIP_ENTRIES + 1)))
        for entries in bad:
            with self.subTest(entries=len(entries)):
                subject, ports, _ = self.make(); ports.entries = entries
                ports.detected = FormatEvidence('DOCX', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', False)
                result = self.send(subject, name='x.docx', mime=ports.detected.mime, data=DOCX)
                self.assertEqual(result.code, 'EXTRACTION_NOT_ALLOWED')
                self.assertNotIn('scan', ports.events); self.assertNotIn('extract', ports.events)

    def test_scanner_missing_failed_or_timeout_retains_quarantine_without_work(self):
        for status in ('UNAVAILABLE', 'ERROR', 'TIMEOUT', 'INFECTED'):
            subject, ports, _ = self.make(); ports.scan_status = status
            result = self.send(subject)
            self.assertEqual(result.state, 'QUARANTINED'); self.assertTrue(result.quarantine_retained)
            self.assertNotIn('prepare', ports.events); self.assertNotIn('cleanup', ports.events)

    def test_parser_timeout_oom_error_or_postbounds_is_error_and_owned_cleanup_once(self):
        for change in ('TIMEOUT', 'OOM', 'ERROR', 'pages', 'output', 'deadline'):
            with self.subTest(change=change):
                subject, ports, _ = self.make()
                if change in ('TIMEOUT', 'OOM', 'ERROR'): ports.parse_status = change
                if change == 'pages': ports.pages = DOCUMENT_PAGES + 1
                if change == 'output': ports.text = b'x' * (OUTPUT_BYTES + 1)
                if change == 'deadline': ports.extract_seconds = 30
                result = self.send(subject)
                self.assertEqual((result.state, result.code), ('ERROR', 'EXTRACTION_ERROR'))
                self.assertEqual(ports.events.count('cleanup'), 1)
                self.assertNotIn('publish', ports.events)

    def test_cleanup_retry_is_owned_time_bounded_and_never_replays_parse_or_publish(self):
        subject, ports, clock = self.make(); ports.cleanup_status = 'RETRYABLE'
        result = self.send(subject)
        self.assertEqual(result.state, 'ERROR'); ports.cleanup_status = 'CLEAN'; clock.value = 3599
        self.assertEqual(subject.retry_cleanup(UPLOAD), 'CLEAN')
        self.assertEqual(ports.events.count('extract'), 1); self.assertNotIn('publish', ports.events)
        subject, ports, clock = self.make(); ports.cleanup_status = 'RETRYABLE'; self.send(subject)
        clock.value = 3601
        self.assertEqual(subject.retry_cleanup(UPLOAD), 'EXPIRED')
        self.assertEqual(ports.events.count('cleanup'), 1)

    def test_cleanup_denial_or_unknown_never_retries_and_publish_unknown_never_resends(self):
        subject, ports, _ = self.make(); ports.cleanup_error = PermissionError('private-sentinel')
        result = self.send(subject)
        self.assertEqual(result.cleanup, 'UNKNOWN'); self.assertEqual(subject.retry_cleanup(UPLOAD), 'UNKNOWN')
        self.assertEqual(ports.events.count('cleanup'), 1); self.assertNotIn('publish', ports.events)
        subject, ports, _ = self.make(); ports.publish_error = RuntimeError('private-sentinel')
        result = self.send(subject)
        self.assertEqual(result.state, 'UNKNOWN'); self.assertNotIn('private-sentinel', str(result))
        self.assertEqual(ports.events.count('publish'), 1)

    def test_profile_has_no_unsafe_fallback_and_limits_are_typed(self):
        for profile in (replace(PROFILE, network=True), replace(PROFILE, cpu=True),
                        replace(PROFILE, pid_limit=0), replace(PROFILE, output_bytes=OUTPUT_BYTES + 1)):
            with self.assertRaises(AdmissionError): DocumentAdmission(MemoryPorts(Clock()), Clock(), profile)
        with self.assertRaises(AdmissionError): DocumentAdmission(object(), Clock(), PROFILE)
        with self.assertRaises(AdmissionError): validate_docx_entries((), True)

    def test_document_byte_limit_and_docx_ratio_boundary_use_actual_subject(self):
        subject, ports, _ = self.make()
        result = self.send(subject, data=b'%PDF-' + b'x' * (DOCUMENT_BYTES - 5))
        self.assertEqual(result.state, 'COMPLETE')
        subject, ports, _ = self.make()
        self.assertEqual(self.send(subject, data=b'x' * (DOCUMENT_BYTES + 1)).state, 'REJECTED')
        self.assertEqual(ports.events, [])
        self.assertIsNone(validate_docx_entries((ZipEntry('x', 'file', 1, 100, None),), 1))
        for entries in ((ZipEntry('x', 'file', 1, 1, None), ZipEntry('x', 'file', 1, 1, None)),
                        (ZipEntry('C:/x', 'file', 1, 1, None),),
                        (ZipEntry('x', 'hardlink', 1, 1, 'outside'),)):
            with self.assertRaises(AdmissionError): validate_docx_entries(entries, 100)

    def test_typed_current_ticket_and_invalid_owned_work_do_not_gain_cleanup_authority(self):
        subject, ports, _ = self.make()
        ports.current = lambda ticket: replace(ticket, revision=True)
        self.assertNotEqual(self.send(subject).state, 'COMPLETE')
        self.assertNotIn('prepare', ports.events)
        subject, ports, _ = self.make()
        ports.prepare = lambda seal, profile, deadline: Work(replace(seal.ticket, owner_id=RESOURCE), RESOURCE)
        result = self.send(subject)
        self.assertEqual(result.state, 'ERROR')
        self.assertNotIn('cleanup', ports.events); self.assertNotIn('extract', ports.events)

    def test_interruption_propagates_after_one_owned_cleanup_without_publication(self):
        subject, ports, _ = self.make()
        def interrupted(*args): raise KeyboardInterrupt()
        ports.extract = interrupted
        with self.assertRaises(KeyboardInterrupt): self.send(subject)
        self.assertEqual(ports.events.count('cleanup'), 1)
        self.assertNotIn('publish', ports.events)

    def test_voice_exact_maxima_do_not_select_a_codec_or_round_overflow_down(self):
        self.assertIsNone(validate_voice_bounds(VOICE_BYTES, 30, 1))
        self.assertIsNone(validate_voice_bounds(1, 29999, 1000))
        for arguments in ((VOICE_BYTES + 1, 30, 1), (1, 30001, 1000), (1, 1, 0),
                          (True, 1, 1), (1, True, 1), (1, 1, True)):
            with self.assertRaises(AdmissionError): validate_voice_bounds(*arguments)

    def test_invalid_generated_identifier_is_not_reflected_or_used_for_storage(self):
        subject, ports, _ = self.make()
        ports.new_id = lambda: 'private-sentinel'
        result = self.send(subject)
        self.assertIsNone(result.upload_id)
        self.assertNotIn('private-sentinel', str(result))
        self.assertEqual(ports.events, [])

    def test_late_extractor_cannot_begin_cleanup_after_original_one_hour_endpoint(self):
        subject, ports, _ = self.make(); ports.extract_seconds = 3601
        result = self.send(subject)
        self.assertEqual((result.state, result.code, result.cleanup),
                         ('ERROR', 'EXTRACTION_ERROR', 'UNKNOWN'))
        self.assertEqual(ports.events.count('extract'), 1)
        self.assertNotIn('cleanup', ports.events); self.assertNotIn('publish', ports.events)
        self.assertEqual(subject.retry_cleanup(UPLOAD), 'UNKNOWN')
        self.assertNotIn('cleanup', ports.events)

    def test_parser_resource_id_exact_type_and_value_are_required_before_publication(self):
        class StringAlias(str): pass
        for resource in (StringAlias(RESOURCE), OWNER):
            with self.subTest(resource_type=type(resource).__name__):
                subject, ports, _ = self.make()
                original = ports.extract
                def altered(*args): return replace(original(*args), resource_id=resource)
                ports.extract = altered
                result = self.send(subject)
                self.assertEqual((result.state, result.code), ('ERROR', 'EXTRACTION_ERROR'))
                self.assertEqual(ports.events.count('cleanup'), 1)
                self.assertNotIn('publish', ports.events)


if __name__ == '__main__':
    unittest.main()

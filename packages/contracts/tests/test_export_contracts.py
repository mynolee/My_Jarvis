"""Synthetic export contract checks; browser download and mobile save NOT_RUN."""
import copy
from datetime import timedelta
import unittest

from export_reference import (
    ITEM_FIELDS, LIMITS, ROOT, SyntheticExportClient, SyntheticExports,
    canonical_bytes, instant, loads, parse_create, parse_read, project_item, stamp,
    valid, validate_document, validate_job, validator,
)

OWNER = '11111111-1111-4111-8111-111111111111'
OTHER = '22222222-2222-4222-8222-222222222222'
PROJECT = '72000000-0000-4000-8000-000000000001'
FOREIGN_PROJECT = '72000000-0000-4000-8000-000000000002'
NOW = '2026-10-10T12:00:00Z'
LATER = '2026-10-10T12:01:00Z'
KEY = '73000000-0000-4000-8000-000000000001'
GLOBAL = {'kind': 'EXPLICIT_MEMORIES', 'scope': 'GLOBAL', 'project_id': None, 'format': 'JSON_UTF8'}


def fixture(name):
    return loads((ROOT / 'fixtures/exports' / f'{name}.json').read_text())


def key(number):
    return f'73000000-0000-4000-8000-{number:012d}'


def row(number=1, owner=OWNER, project=None, active=True):
    result = copy.deepcopy(fixture('document-current')['items'][0])
    result.update(id=f'71000000-0000-4000-8000-{number:012d}', owner=owner,
                  scope='GLOBAL' if project is None else 'PROJECT', project_id=project,
                  active=active, payload_ref='synthetic-private-storage-ref',
                  owner_id=owner, generation=8, authority={'approved': True})
    result['source'].update(submission_id=key(number), source_hash='a' * 64)
    return result


def service():
    result = SyntheticExports()
    result.add_session('initial', OWNER)
    result.add_session('replacement', OWNER)
    result.add_session('foreign', OTHER)
    result.projects = {PROJECT: OWNER, FOREIGN_PROJECT: OTHER}
    result.rows = [row()]
    return result


def create(result, request=GLOBAL, request_key=KEY, now=NOW, label='initial'):
    return result.create(label, canonical_bytes(request), request_key, now)


class ExportContractsTest(unittest.TestCase):
    def test_all_eight_schemas_and_positive_fixtures(self):
        names = ('create-request', 'headers', 'query', 'job-path', 'request-path', 'job', 'receipt', 'document')
        self.assertEqual(len(list((ROOT / 'schemas').glob('export-*.schema.json'))), 8)
        for name in names:
            validator(name)
        for name, schema in [('create-global', 'create-request'), ('create-project', 'create-request'),
                             ('job-available', 'job'), ('job-expired', 'job'),
                             ('receipt-committed', 'receipt'), ('receipt-not-observed', 'receipt'),
                             ('document-current', 'document'), ('document-empty', 'document')]:
            self.assertTrue(valid(schema, fixture(name)), name)
        self.assertTrue(validate_document(fixture('document-current'), fixture('job-available')))

    def test_all_objects_are_closed_recursively(self):
        def walk(node):
            if isinstance(node, dict):
                if node.get('type') == 'object':
                    self.assertIs(node.get('additionalProperties'), False)
                    self.assertEqual(set(node['required']), set(node['properties']))
                for value in node.values():
                    walk(value)
            elif isinstance(node, list):
                for value in node:
                    walk(value)
        for path in (ROOT / 'schemas').glob('export-*.schema.json'):
            walk(loads(path.read_text()))

    def test_explicit_one_scope_no_implicit_global_or_all(self):
        for request in ({}, {'scope': 'GLOBAL'}, {**GLOBAL, 'scope': 'ALL'},
                        {**GLOBAL, 'project_id': PROJECT}, {**GLOBAL, 'scope': 'PROJECT'},
                        {**GLOBAL, 'include_global': True}, {**GLOBAL, 'project_ids': [PROJECT]},
                        {**GLOBAL, 'kind': 'CONVERSATIONS'}, {**GLOBAL, 'format': 'CSV'}):
            self.assertFalse(valid('create-request', request), request)
        self.assertTrue(valid('create-request', {**GLOBAL, 'scope': 'PROJECT', 'project_id': PROJECT}))

    def test_request_has_no_authority_credentials_or_client_time(self):
        for field in ('owner_id', 'session_id', 'security_epoch', 'recent_auth', 'approved',
                      'grant', 'payload_ref', 'download_url', 'expires_at', 'max_rows', 'timezone'):
            self.assertFalse(valid('create-request', {**GLOBAL, field: True}), field)

    def test_strict_parser_and_header_single_value_contract(self):
        for raw in ('{"kind":"EXPLICIT_MEMORIES","kind":"EXPLICIT_MEMORIES"}',
                    '{"schema_version":1.0}', '{"x":1e0}', '{"x":NaN}',
                    '{"x":9007199254740992}', '{"x":"\\ud800"}', b'\xff', b'[]', b'null'):
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                parse_create(raw, KEY)
        for supplied in (None, [KEY], KEY + ',' + KEY, KEY.upper(), KEY + '\n'):
            # Numeric-only sample has no letters, so use an alphabetic UUID for uppercase rejection.
            if supplied == KEY:
                continue
            with self.assertRaises(ValueError):
                parse_create(canonical_bytes(GLOBAL), supplied)
        self.assertFalse(valid('headers', {'Idempotency-Key': 'ABCDEF01-2345-6789-ABCD-EF0123456789'}))
        self.assertFalse(valid('headers', {'Idempotency-Key': KEY, 'owner': OWNER}))

    def test_no_query_or_get_body_even_empty_json(self):
        for query in ([('owner', OWNER)], [('x', '1'), ('x', '2')], [('scope', 'GLOBAL')]):
            with self.assertRaisesRegex(ValueError, 'INVALID_REQUEST'):
                parse_read(query)
            with self.assertRaisesRegex(ValueError, 'INVALID_REQUEST'):
                parse_create(canonical_bytes(GLOBAL), KEY, query)
        for body in (b'{}', b' ', b'null'):
            with self.assertRaisesRegex(ValueError, 'INVALID_REQUEST'):
                parse_read(body=body)
        parse_read()
        with self.assertRaisesRegex(ValueError, 'BODY_TOO_LARGE'):
            parse_create(b' ' * 262145, KEY)

    def test_actual_calendar_utc_and_safe_integer_validation(self):
        job = fixture('job-available')
        for timestamp in ('2026-02-30T00:00:00Z', '0000-01-01T00:00:00Z',
                          '2026-10-10T12:00:00+00:00', '2026-10-10T12:00:00.1234567Z', NOW + '\n'):
            self.assertFalse(valid('job', {**job, 'checked_at': timestamp}))
        for version in (0, -1, True, 1.0, 9007199254740992):
            document = fixture('document-current')
            document['items'][0]['version'] = version
            self.assertFalse(valid('document', document))

    def test_job_clock_semantics_not_just_schema(self):
        job = fixture('job-available')
        self.assertTrue(validate_job(job))
        self.assertTrue(validate_job(fixture('job-expired')))
        self.assertFalse(validate_job({**job, 'status': 'EXPIRED'}))
        self.assertFalse(validate_job({**job, 'expires_at': LATER}))
        self.assertFalse(validate_job({**job, 'checked_at': '2026-10-09T12:00:00Z'}))

    def test_creation_is_metadata_only_and_not_artifact_preparation(self):
        result = service()
        job = create(result)
        self.assertEqual(job, fixture('job-available'))
        persisted = repr(result.jobs)
        for secret in ('가상의 기억', 'synthetic.preference', 'synthetic-private-storage-ref', 'submission_id', 'source_hash'):
            self.assertNotIn(secret, persisted)
        self.assertEqual(result.creation_events, 1)
        self.assertNotIn('download_url', job)
        self.assertNotIn('memory_count', job)

    def test_exact_duplicate_replay_never_refreshes_lifetime_or_events(self):
        result = service()
        first = create(result)
        replay = create(result, now=LATER)
        self.assertEqual(replay['id'], first['id'])
        self.assertEqual(replay['created_at'], first['created_at'])
        self.assertEqual(replay['expires_at'], first['expires_at'])
        self.assertEqual(replay['checked_at'], LATER)
        self.assertEqual(len(result.jobs), 1)
        self.assertEqual(result.creation_events, 1)
        expired = create(result, now=first['expires_at'])
        self.assertEqual(expired['status'], 'EXPIRED')
        self.assertEqual(result.creation_events, 1)

    def test_changed_key_body_and_session_replays_are_rejected(self):
        result = service()
        create(result)
        with self.assertRaisesRegex(ValueError, 'IDEMPOTENCY_CONFLICT'):
            create(result, {**GLOBAL, 'scope': 'PROJECT', 'project_id': PROJECT})
        with self.assertRaisesRegex(ValueError, 'AUTHORITY_NOT_VALID'):
            create(result, label='replacement')
        self.assertEqual(result.creation_events, 1)

    def test_foreign_and_missing_jobs_indistinguishable(self):
        result = service()
        job = create(result)
        for identifier in (job['id'], '79999999-0000-4000-8000-000000000001'):
            with self.assertRaisesRegex(ValueError, '^NOT_FOUND$'):
                result.status('foreign', identifier, LATER)
        with self.assertRaisesRegex(ValueError, 'NOT_FOUND'):
            create(result, {**GLOBAL, 'scope': 'PROJECT', 'project_id': FOREIGN_PROJECT}, request_key=key(2))

    def test_current_session_epoch_and_recent_auth_on_create_download(self):
        result = service()
        job = create(result)
        result.sessions['initial']['recent'] = False
        self.assertEqual(result.status('initial', job['id'], LATER)['id'], job['id'])
        self.assertEqual(result.receipt('initial', KEY, LATER)['status'], 'COMMITTED')
        for action in (lambda: create(result, request_key=key(2)),
                       lambda: result.download('initial', job['id'], LATER)):
            with self.assertRaisesRegex(ValueError, 'REAUTHENTICATION_REQUIRED'):
                action()
        result.sessions['initial']['recent'] = True
        result.epochs[OWNER] += 1
        with self.assertRaisesRegex(ValueError, 'SESSION_REVOKED'):
            result.status('initial', job['id'], LATER)

    def test_reauthentication_rotation_never_rebinds_old_job(self):
        result = service()
        job = create(result)
        result.sessions['initial']['live'] = False
        for action in (lambda: result.status('replacement', job['id'], LATER),
                       lambda: result.receipt('replacement', KEY, LATER),
                       lambda: result.download('replacement', job['id'], LATER)):
            with self.assertRaisesRegex(ValueError, 'AUTHORITY_NOT_VALID'):
                action()
        fresh = create(result, request_key=key(2), now=LATER, label='replacement')
        self.assertNotEqual(fresh['id'], job['id'])

    def test_pause_blocks_new_create_but_not_reads_or_matching_replay(self):
        result = service()
        job = create(result)
        result.paused.add(OWNER)
        with self.assertRaisesRegex(ValueError, 'EXECUTION_PAUSED'):
            create(result, request_key=key(2))
        self.assertEqual(create(result, now=LATER)['id'], job['id'])
        result.status('initial', job['id'], LATER)
        result.receipt('initial', KEY, LATER)
        self.assertTrue(result.download('initial', job['id'], LATER))

    def test_exact_scope_owner_active_isolation_and_deterministic_order(self):
        result = service()
        result.rows = [row(5), row(2, project=PROJECT), row(3, owner=OTHER),
                       row(4, active=False), row(1)]
        job = create(result)
        document = loads(result.download('initial', job['id'], LATER).decode())
        self.assertEqual([item['id'] for item in document['items']], [row(1)['id'], row(5)['id']])
        project_job = create(result, {**GLOBAL, 'scope': 'PROJECT', 'project_id': PROJECT}, key(2))
        project_document = loads(result.download('initial', project_job['id'], LATER).decode())
        self.assertEqual([item['id'] for item in project_document['items']], [row(2)['id']])
        result.projects[PROJECT] = OTHER
        with self.assertRaisesRegex(ValueError, 'NOT_FOUND'):
            result.download('initial', project_job['id'], LATER)

    def test_projection_never_copies_extra_db_or_source_fields(self):
        record = row()
        record.update(api_key='synthetic-forbidden', cookie='synthetic-forbidden', previous_value='old')
        projected = project_item(record)
        self.assertEqual(set(projected), set(ITEM_FIELDS) | {'source'})
        self.assertEqual(set(projected['source']), {'type', 'submitted_at'})
        document = fixture('document-current')
        for field in ('owner_id', 'session_hash', 'security_epoch', 'payload_ref', 'authority',
                      'previous_value', 'generation', 'download_url', 'source_hash', 'api_key'):
            self.assertFalse(valid('document', {**document, field: True}))
            bad = copy.deepcopy(document)
            bad['items'][0][field] = True
            self.assertFalse(valid('document', bad))
            bad = copy.deepcopy(document)
            bad['items'][0]['source'][field] = True
            self.assertFalse(valid('document', bad))

    def test_unicode_text_is_exact_not_trimmed_normalized_or_secret_scrubbed(self):
        result = service()
        text = '  가상 e\u0301 🐱 owner-entered token-like text  '
        result.rows[0]['value'] = text
        job = create(result)
        encoded = result.download('initial', job['id'], LATER)
        self.assertEqual(loads(encoded.decode())['items'][0]['value'], text)
        self.assertIn('🐱'.encode(), encoded)
        self.assertFalse(encoded.startswith(b'\xef\xbb\xbf'))
        self.assertFalse(encoded.endswith(b'\n'))

    def test_current_download_includes_later_revision_and_addition(self):
        result = service()
        job = create(result)
        first = loads(result.download('initial', job['id'], LATER).decode())
        result.rows[0]['value'] = 'Revised synthetic current value'
        result.rows[0]['version'] += 1
        result.rows.append(row(2))
        second = loads(result.download('initial', job['id'], LATER).decode())
        self.assertNotEqual(first['items'][0]['value'], second['items'][0]['value'])
        self.assertEqual(len(second['items']), 2)
        self.assertEqual(first['semantics'], 'CURRENT_AT_DOWNLOAD')

    def test_deletion_before_release_never_resurrects_through_old_job(self):
        result = service()
        job = create(result)
        result.rows[0]['active'] = False
        document = loads(result.download('initial', job['id'], LATER).decode())
        self.assertEqual(document['items'], [])
        self.assertNotIn('가상의 기억', repr(result.jobs))

    def test_deletion_after_release_cannot_recall_downloaded_bytes(self):
        result = service()
        job = create(result)
        released = result.download('initial', job['id'], LATER)
        result.rows[0]['active'] = False
        self.assertEqual(len(loads(released.decode())['items']), 1)
        self.assertEqual(loads(result.download('initial', job['id'], LATER).decode())['items'], [])

    def test_empty_export_is_valid_current_data(self):
        result = service()
        result.rows = []
        job = create(result)
        document = loads(result.download('initial', job['id'], LATER).decode())
        self.assertEqual(document, fixture('document-empty'))

    def test_row_limit_exact_boundary_and_never_truncates(self):
        result = service()
        result.rows = [row(i) for i in range(1, 1001)]
        job = create(result)
        self.assertEqual(len(loads(result.download('initial', job['id'], LATER).decode())['items']), 1000)
        result.rows.append(row(1001))
        before = copy.deepcopy(result.jobs)
        with self.assertRaisesRegex(ValueError, '^EXPORT_ROW_LIMIT$'):
            result.download('initial', job['id'], LATER)
        self.assertEqual(result.jobs, before)

    def test_utf8_and_json_escape_bytes_not_character_count(self):
        result = service()
        result.rows = [row(i) for i in range(1, 1001)]
        for item in result.rows:
            item['value'] = '\x01' * 1500
        job = create(result)
        # 1.5M scalar value chars expand to 9M JSON escape bytes; no truncation.
        with self.assertRaisesRegex(ValueError, '^EXPORT_BYTE_LIMIT$'):
            result.download('initial', job['id'], LATER)
        for item in result.rows:
            item['value'] = '🐱' * 2000
            item['change_reason'] = '🐱' * 300
        with self.assertRaisesRegex(ValueError, '^EXPORT_BYTE_LIMIT$'):
            result.download('initial', job['id'], LATER)

    def test_exact_document_byte_boundary_then_one_byte_over(self):
        result = service()
        result.rows = [row(i) for i in range(1, 1001)]
        for item in result.rows:
            item['value'] = 'x' * 2000
            item['change_reason'] = 'x' * 300
        job = create(result)
        baseline = result.download('initial', job['id'], LATER)
        deficit = LIMITS['export_utf8_bytes'] - len(baseline)
        replacements, remainder = divmod(deficit, 3)
        self.assertGreater(deficit, 0)
        self.assertLess(replacements + int(bool(remainder)), 2000000)
        for item in result.rows:
            count = min(replacements, 2000)
            item['value'] = '🐱' * count + 'x' * (2000 - count)
            replacements -= count
        # A two-byte scalar adds one byte and a three-byte scalar adds two.
        if remainder:
            result.rows[-1]['value'] = ('é' if remainder == 1 else '가') + result.rows[-1]['value'][1:]
        encoded = result.download('initial', job['id'], LATER)
        self.assertEqual(len(encoded), LIMITS['export_utf8_bytes'])
        result.rows[-1]['change_reason'] = 'é' + result.rows[-1]['change_reason'][1:]
        with self.assertRaisesRegex(ValueError, '^EXPORT_BYTE_LIMIT$'):
            result.download('initial', job['id'], LATER)

    def test_late_download_response_after_scope_change_is_discarded(self):
        client = SyntheticExportClient()
        client.reconcile(fixture('receipt-committed'), 0)
        client.change_session_or_scope()
        self.assertFalse(client.downloaded_response(canonical_bytes(fixture('document-current')), 0))
        self.assertIsNone(client.download_outcome)
        self.assertFalse(client.local_save_verified)

    def test_deadline_and_expiry_fail_without_changing_job(self):
        result = service()
        job = create(result)
        before = copy.deepcopy(result.jobs)
        for elapsed in (5000, 5001):
            with self.assertRaisesRegex(ValueError, 'EXPORT_TIME_LIMIT'):
                result.download('initial', job['id'], LATER, elapsed_ms=elapsed)
        result.download('initial', job['id'], LATER, elapsed_ms=4999)
        with self.assertRaisesRegex(ValueError, 'EXPORT_EXPIRED'):
            result.download('initial', job['id'], job['expires_at'])
        with self.assertRaisesRegex(ValueError, 'EXPORT_EXPIRED'):
            result.download('initial', job['id'], LATER, final_now=job['expires_at'])
        self.assertEqual(result.jobs, before)

    def test_final_live_session_recent_auth_and_project_recheck(self):
        for mutation, expected in ((lambda s: s.sessions['initial'].update(live=False), 'SESSION_REVOKED'),
                                   (lambda s: s.sessions['initial'].update(recent=False), 'REAUTHENTICATION_REQUIRED'),
                                   (lambda s: s.projects.update({PROJECT: OTHER}), 'NOT_FOUND')):
            result = service()
            job = create(result, {**GLOBAL, 'scope': 'PROJECT', 'project_id': PROJECT})
            with self.assertRaisesRegex(ValueError, expected):
                result.download('initial', job['id'], LATER, final_check=mutation)

    def test_capacity_bound_and_cleanup_eligibility_is_not_background_sla(self):
        result = service()
        for n in range(1, 101):
            create(result, request_key=key(n))
        before = copy.deepcopy(result.jobs)
        with self.assertRaisesRegex(ValueError, 'EXPORT_JOB_LIMIT'):
            create(result, request_key=key(101))
        self.assertEqual(result.jobs, before)
        # Status at 24h does not clean or silently erase receipts.
        tomorrow = stamp(instant(NOW) + timedelta(days=1))
        self.assertEqual(result.receipt('initial', KEY, tomorrow)['status'], 'COMMITTED')
        self.assertEqual(len(result.jobs), 100)
        create(result, request_key=key(101), now=tomorrow)
        self.assertEqual(len(result.jobs), 1)
        self.assertEqual(result.receipt('initial', KEY, tomorrow)['status'], 'NOT_OBSERVED')

    def test_capacity_and_cleanup_do_not_touch_other_owners(self):
        result = service()
        foreign = create(result, label='foreign')
        create(result)
        tomorrow = stamp(instant(NOW) + timedelta(days=1))
        create(result, request_key=key(2), now=tomorrow)
        self.assertEqual(len(result.jobs), 2)
        self.assertIn(foreign['id'], result.jobs)

    def test_receipt_cross_field_constraints(self):
        self.assertFalse(valid('receipt', {'status': 'NOT_OBSERVED', 'job': fixture('job-available')}))
        self.assertFalse(valid('receipt', {'status': 'COMMITTED', 'job': None}))
        self.assertFalse(valid('receipt', {'status': 'SAVED', 'job': fixture('job-available')}))

    def test_lost_create_not_observed_and_late_responses_never_auto_retry(self):
        client = SyntheticExportClient()
        client.explicit_create()
        with self.assertRaisesRegex(ValueError, 'OVERLAP'):
            client.explicit_create()
        client.lose_create_response()
        client.reconcile(fixture('receipt-not-observed'), client.generation)
        self.assertEqual(client.create_outcome, 'UNKNOWN')
        self.assertEqual(client.post_count, 1)
        client.change_session_or_scope()
        self.assertFalse(client.reconcile(fixture('receipt-committed'), client.generation - 1))
        self.assertIsNone(client.job)
        self.assertEqual(client.create_outcome, 'UNKNOWN')
        self.assertEqual(client.post_count, 1)

    def test_committed_receipt_and_download_response_do_not_prove_local_save(self):
        client = SyntheticExportClient()
        client.explicit_create()
        client.lose_create_response()
        self.assertTrue(client.reconcile(fixture('receipt-committed'), 0))
        self.assertEqual(client.create_outcome, 'COMMITTED')
        self.assertIsNone(client.download_outcome)
        self.assertTrue(client.downloaded_response(canonical_bytes(fixture('document-current')), 0))
        self.assertEqual(client.download_outcome, 'RESPONSE_VALIDATED')
        self.assertFalse(client.local_save_verified)
        self.assertEqual(client.post_count, 1)

    def test_document_job_binding_order_duplicate_and_timestamp_semantics(self):
        document = fixture('document-current')
        job = fixture('job-available')
        for field, value in [('export_job_id', key(2)), ('scope', 'PROJECT'),
                             ('exported_at', job['expires_at'])]:
            self.assertFalse(validate_document({**document, field: value}, job))
        document['items'].append(copy.deepcopy(document['items'][0]))
        self.assertFalse(validate_document(document, job))
        document = fixture('document-current')
        document['items'][0]['updated_at'] = '2026-10-11T12:00:00Z'
        self.assertFalse(validate_document(document, job))

    def test_safety_limits_and_kst_presentation_are_explicit(self):
        self.assertEqual(LIMITS['export_rows'], 1000)
        self.assertEqual(LIMITS['export_utf8_bytes'], 8 * 1024 * 1024)
        self.assertEqual(LIMITS['transaction_build_ms'], 5000)
        self.assertEqual(LIMITS['job_lifetime_seconds'], 900)
        self.assertEqual(LIMITS['retained_jobs_per_owner'], 100)
        self.assertEqual(LIMITS['metadata_retention_seconds'], 86400)
        self.assertEqual((LIMITS['display_timezone'], LIMITS['file_timezone']), ('Asia/Seoul', 'UTC'))
        self.assertEqual(LIMITS['semantics'], 'CURRENT_AT_DOWNLOAD')


if __name__ == '__main__':
    unittest.main()

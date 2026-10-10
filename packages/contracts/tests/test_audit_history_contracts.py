"""Synthetic wire/reference checks, not HTTP, DB, browser or provider evidence."""
import copy
import unittest
from audit_history_reference import (ROOT, EVENT_KINDS, GROUPS, STATES, SyntheticCursor,
                                     canonical_bytes, loads, page, parse_query, project, valid, validator)


def uid(number):
    return f'00000000-0000-4000-8000-{number:012d}'


class AuditHistoryContractsTest(unittest.TestCase):
    def setUp(self):
        self.case = loads((ROOT / 'fixtures/audit-history/projection-input.json').read_text())
        self.empty = loads((ROOT / 'fixtures/audit-history/empty.json').read_text())

    def result(self, **options):
        return page(self.case['events'], self.case['runs'], self.case['owner_id'],
                    options.pop('now', self.case['checked_at']), **options)

    def item(self):
        return next(item for item in self.result()['items'] if item['run'] is not None)

    def test_schema_definitions(self):
        for name in ('query', 'item', 'response'):
            validator(name)

    def test_query_defaults_and_bounds(self):
        self.assertEqual(parse_query([]), ('ALL', 20, None))
        for scope in ('ALL', 'RUNS'):
            for limit in ('1', '20', '100'):
                self.assertEqual(parse_query([('scope', scope), ('limit', limit)]), (scope, int(limit), None))

    def test_reject_noncanonical_limit(self):
        for value in ('', '0', '101', '01', '+1', '-1', ' 1', '1 ', '1.0', '1e1', '١', '１', '20\n', '20\r\n', 20, True):
            with self.subTest(value=value), self.assertRaises(ValueError):
                parse_query([('limit', value)])

    def test_reject_unknown_duplicate_and_empty_query(self):
        for pairs in ([('scope', 'all')], [('scope', '')], [('cursor', '')], [('owner_id', uid(1))],
                      [('project_id', uid(1))], [('status', 'SUCCEEDED')], [('event_type', 'RUN_ACCEPTED')],
                      [('scope', 'ALL'), ('scope', 'ALL')], [('limit', '20'), ('limit', '20')],
                      [('cursor', 'x'), ('cursor', 'y')]):
            with self.subTest(pairs=pairs), self.assertRaises(ValueError):
                parse_query(pairs)

    def test_reject_body(self):
        with self.assertRaises(ValueError):
            parse_query([], b'{}')

    def test_empty_available_page(self):
        self.assertTrue(valid('response', self.empty, 20))
        result = page([], [], self.case['owner_id'], self.case['checked_at'])
        self.assertEqual(result, self.empty)

    def test_all_scope_owner_filter_and_exact_descending_order(self):
        result = self.result()
        self.assertTrue(valid('response', result, 20))
        self.assertEqual([item['id'] for item in result['items']], self.case['expected_all_ids'])
        self.assertNotIn(uid(107), [item['id'] for item in result['items']])

    def test_synthetic_response_fixtures_match_projection(self):
        for scope, name in [('ALL', 'mixed-page.json'), ('RUNS', 'runs-page.json')]:
            expected = loads((ROOT / 'fixtures/audit-history' / name).read_text())
            self.assertTrue(valid('response', expected, 20))
            self.assertEqual(self.result(scope=scope), expected)

    def test_runs_scope_uses_exact_event_mapping(self):
        result = self.result(scope='RUNS')
        self.assertEqual([item['id'] for item in result['items']], self.case['expected_run_ids'])
        self.assertTrue(all(item['resource_kind'] == 'RUN' for item in result['items']))

    def test_every_allowlisted_event_has_one_kind(self):
        self.assertEqual(sum(map(len, GROUPS.values())), len(EVENT_KINDS))
        row = copy.deepcopy(self.case['events'][0])
        for event, kind in EVENT_KINDS.items():
            with self.subTest(event=event):
                result = project({**row, 'event_type': event}, self.case['owner_id'], self.case['runs'])
                self.assertEqual(result['event_type'], event)
                self.assertEqual(result['resource_kind'], kind)
                self.assertTrue(valid('item', result))

    def test_approval_events_reference_run_not_approval(self):
        result = next(item for item in self.result()['items'] if item['event_type'] == 'APPROVAL_CONSUMED')
        self.assertEqual(result['resource_kind'], 'RUN')
        self.assertEqual(result['run']['id'], result['resource_id'])

    def test_reminder_delivery_references_notification(self):
        result = next(item for item in self.result()['items'] if item['event_type'] == 'REMINDER_INBOX_CREATED')
        self.assertEqual(result['resource_kind'], 'NOTIFICATION')
        self.assertIsNone(result['run'])

    def test_unknown_event_sanitized_without_text_or_identity(self):
        result = next(item for item in self.result()['items'] if item['id'] == uid(105))
        self.assertEqual((result['event_type'], result['resource_kind'], result['resource_id'], result['run']),
                         ('OTHER', 'OTHER', None, None))
        wire = canonical_bytes(self.result()).decode()
        for marker in ('private marker', uid(999), 'result_ref', 'storage marker', 'owner_id'):
            self.assertNotIn(marker, wire)

    def test_no_event_prefix_fallback(self):
        row = self.case['events'][0]
        for event in ('RUN_future_secret', 'RUN_FAILED', 'LOCAL_INFERENCE_FUTURE', 'APPROVAL_NEW', 'TASK_PRIVATE'):
            result = project({**row, 'event_type': event}, self.case['owner_id'], self.case['runs'])
            self.assertEqual(result['event_type'], 'OTHER')
            self.assertIsNone(result['resource_id'])
            self.assertIsNone(result['run'])

    def test_known_nonrun_uuid_collision_does_not_join_run(self):
        row = {**self.case['events'][0], 'event_type': 'TASK_CREATED'}
        result = project(row, self.case['owner_id'], self.case['runs'])
        self.assertEqual(result['resource_kind'], 'TASK')
        self.assertIsNone(result['run'])

    def test_missing_run_preserves_only_audit_reference(self):
        result = next(item for item in self.result()['items'] if item['id'] == uid(106))
        self.assertEqual(result['resource_id'], uid(12))
        self.assertEqual(result['resource_kind'], 'RUN')
        self.assertIsNone(result['run'])
        self.assertTrue(valid('item', result))

    def test_cross_owner_run_never_supplies_current_status(self):
        result = next(item for item in self.result()['items'] if item['id'] == uid(108))
        self.assertEqual(result['resource_id'], uid(13))
        self.assertIsNone(result['run'])
        self.assertNotIn('SUCCEEDED', canonical_bytes(result).decode())

    def test_current_state_is_not_event_time_state(self):
        result = next(item for item in self.result()['items'] if item['event_type'] == 'RUN_ACCEPTED')
        self.assertEqual(result['run']['current_status'], 'UNKNOWN')
        self.assertNotIn('event_status', result)
        self.case['runs'][0]['status'] = 'FAILED'
        changed = next(item for item in self.result()['items'] if item['event_type'] == 'RUN_ACCEPTED')
        self.assertEqual(changed['run']['current_status'], 'FAILED')
        self.assertEqual(changed['event_type'], result['event_type'])

    def test_success_does_not_invent_provider_or_verification(self):
        self.case['runs'][0]['status'] = 'SUCCEEDED'
        result = self.item()
        self.assertEqual(result['run'], {'id': uid(11), 'current_status': 'SUCCEEDED'})
        for key in ('execution_mode', 'verified', 'verified_real', 'provider', 'policy', 'verification_at', 'url'):
            self.assertNotIn(key, canonical_bytes(result).decode())
            self.assertFalse(valid('item', {**result, key: 'invented'}))
            self.assertFalse(valid('item', {**result, 'run': {**result['run'], key: 'invented'}}))

    def test_resource_kind_event_and_run_id_correlations(self):
        item = self.item()
        for patch in ({'resource_kind': 'TASK'}, {'resource_id': None},
                      {'run': {**item['run'], 'id': uid(999)}}, {'resource_kind': 'OTHER'}):
            self.assertFalse(valid('item', {**item, **patch}))
        other = next(item for item in self.result()['items'] if item['event_type'] == 'OTHER')
        self.assertFalse(valid('item', {**other, 'resource_id': uid(999)}))
        self.assertFalse(valid('item', {**other, 'run': item['run']}))

    def test_state_enum_is_closed(self):
        item = self.item()
        for status in STATES:
            self.assertTrue(valid('item', {**item, 'run': {**item['run'], 'current_status': status}}))
        for status in ('COMPLETED', 'VERIFIED', 'success', '', None):
            self.assertFalse(valid('item', {**item, 'run': {**item['run'], 'current_status': status}}))

    def test_closed_fields_and_required_fields(self):
        item = self.item()
        for key in ('owner_id', 'metadata_redacted', 'payload', 'text', 'title', 'token', 'result_ref', 'action_id'):
            self.assertFalse(valid('item', {**item, key: 'synthetic'}))
        for key in item:
            result = copy.deepcopy(item)
            del result[key]
            self.assertFalse(valid('item', result))
        for key in self.empty:
            result = copy.deepcopy(self.empty)
            del result[key]
            self.assertFalse(valid('response', result))
        self.assertFalse(valid('response', {**self.empty, 'owner_id': uid(1)}))

    def test_strict_json_and_uuid_formats(self):
        for raw in ('{"scope":"ALL","scope":"RUNS"}', '{"n":1.0}', '{"n":9007199254740992}', '"\\ud800"'):
            with self.assertRaises((ValueError, UnicodeError)):
                loads(raw)
        item = self.item()
        for value in ('00000000-0000-4000-8000-ABCDEFABCDEF', '1', '', None):
            self.assertFalse(valid('item', {**item, 'id': value}))

    def test_strict_timestamp_and_microsecond_order(self):
        item = self.item()
        for value in ('2030-01-01T00:00:00+00:00', '2030-01-01T00:00:00.1234567Z',
                      '2030-02-30T00:00:00Z', '0000-01-01T00:00:00Z', '2030-01-01T00:00:60Z', '2030-01-01'):
            self.assertFalse(valid('item', {**item, 'happened_at': value}))
        newer = {**item, 'id': uid(10), 'happened_at': '2030-01-01T00:00:00.000002Z'}
        older = {**item, 'id': uid(20), 'happened_at': '2030-01-01T00:00:00.000001Z'}
        self.assertTrue(valid('response', {**self.empty, 'items': [newer, older]}))
        self.assertFalse(valid('response', {**self.empty, 'items': [older, newer]}))

    def test_wrong_order_duplicates_and_scope_mismatch(self):
        result = self.result()
        self.assertFalse(valid('response', {**result, 'items': list(reversed(result['items']))}))
        self.assertFalse(valid('response', {**result, 'items': [result['items'][0]] * 2}))
        self.assertFalse(valid('response', {**result, 'scope': 'RUNS'}))

    def test_page_bounds_and_terminal_cursor(self):
        first = self.result(limit=2)
        self.assertEqual(len(first['items']), 2)
        self.assertIsNotNone(first['next_cursor'])
        self.assertTrue(valid('response', first, 2))
        self.assertFalse(valid('response', first, 3))
        self.assertFalse(valid('response', first, 1))
        self.assertFalse(valid('response', {**self.empty, 'next_cursor': first['next_cursor']}, 20))
        self.assertIsNone(self.result(limit=7)['next_cursor'])
        self.assertFalse(valid('response', {**self.empty, 'items': [self.item()] * 101}))

    def test_traversal_ties_no_duplicates_or_omissions(self):
        cursor, ids = None, []
        while True:
            result = self.result(limit=2, cursor=cursor)
            ids.extend(item['id'] for item in result['items'])
            cursor = result['next_cursor']
            if cursor is None:
                break
        self.assertEqual(ids, self.case['expected_all_ids'])
        self.assertEqual(len(ids), len(set(ids)))

    def test_cursor_binds_owner_scope_session_and_epoch(self):
        token = self.result(limit=2)['next_cursor']
        context = dict(owner=self.case['owner_id'], scope='ALL', session='synthetic-session-a', epoch=1, now=self.case['checked_at'])
        SyntheticCursor.boundary(token, **context)
        for change in ({'owner': uid(2)}, {'scope': 'RUNS'}, {'session': 'synthetic-session-b'}, {'epoch': 2}):
            with self.assertRaisesRegex(ValueError, 'INVALID_CURSOR'):
                SyntheticCursor.boundary(token, **{**context, **change})
        decoded = SyntheticCursor.decode(token.split('.')[0]).decode()
        self.assertNotIn('synthetic-session-a', decoded)

    def test_cursor_expiry_equality_and_future_issue(self):
        token = self.result(limit=2)['next_cursor']
        for now in ('2030-01-01T00:16:00Z', '2030-01-01T00:16:01Z'):
            with self.assertRaisesRegex(ValueError, 'AUDIT_CURSOR_EXPIRED'):
                self.result(limit=2, cursor=token, now=now)
        with self.assertRaisesRegex(ValueError, 'INVALID_CURSOR'):
            self.result(limit=2, cursor=token, now='2030-01-01T00:00:59Z')
        self.assertEqual(len(self.result(limit=2, cursor=token, now='2030-01-01T00:15:59.999999Z')['items']), 2)

    def test_traversal_expiry_does_not_slide_with_each_page(self):
        first = self.result(limit=1)
        second = self.result(limit=1, cursor=first['next_cursor'], now='2030-01-01T00:15:00Z')
        self.assertIsNotNone(second['next_cursor'])
        with self.assertRaisesRegex(ValueError, 'AUDIT_CURSOR_EXPIRED'):
            self.result(limit=1, cursor=second['next_cursor'], now='2030-01-01T00:16:00Z')

    def test_cursor_tamper_malformed_size_and_alphabet(self):
        token = self.result(limit=2)['next_cursor']
        for value in ('x', token + '=', token.replace('.', '..'), token[:-1] + ('A' if token[-1] != 'A' else 'B'),
                      'A' * 1025 + '.' + 'A' * 43, token.replace('.', '+'), '\n' + token):
            with self.subTest(value=value[:40]), self.assertRaisesRegex(ValueError, 'INVALID_CURSOR'):
                self.result(cursor=value)
        self.assertTrue(valid('query', {'cursor': token}))

    def test_page_size_change_does_not_change_scope(self):
        first = self.result(limit=2)
        rest = self.result(limit=100, cursor=first['next_cursor'])
        self.assertEqual([item['id'] for item in first['items'] + rest['items']], self.case['expected_all_ids'])
        self.assertIsNone(rest['next_cursor'])

    def test_fresh_snapshot_insert_visibility_is_not_frozen(self):
        first = self.result(limit=2)
        base = self.case['events'][0]
        self.case['events'] += [
            {**base, 'id': uid(901), 'happened_at': '2030-01-01T00:00:10Z'},
            {**base, 'id': uid(902), 'happened_at': '2030-01-01T00:00:02.5Z'}]
        rest = self.result(limit=100, cursor=first['next_cursor'])
        ids = [item['id'] for item in rest['items']]
        self.assertNotIn(uid(901), ids)
        self.assertIn(uid(902), ids)
        self.assertIn(uid(901), [item['id'] for item in self.result()['items']])

    def test_true_end_anchor_rejects_trailing_controls(self):
        token = self.result(limit=2)['next_cursor']
        for suffix in ('\n', '\r\n', '\r', '\t', '\x00'):
            self.assertFalse(valid('query', {'limit': '20' + suffix}))
            self.assertFalse(valid('query', {'cursor': token + suffix}))
            self.assertFalse(valid('item', {**self.item(), 'id': uid(3) + suffix}))
            self.assertFalse(valid('item', {**self.item(), 'happened_at': '2030-01-01T00:00:00Z' + suffix}))
            with self.assertRaisesRegex(ValueError, 'INVALID_REQUEST'):
                parse_query([('limit', '20' + suffix)])
            with self.assertRaisesRegex(ValueError, 'INVALID_CURSOR'):
                parse_query([('cursor', token + suffix)])

    def test_query_error_categories(self):
        for value in ('', None, True, 'x' * 1025):
            with self.assertRaisesRegex(ValueError, 'INVALID_REQUEST'):
                parse_query([('cursor', value)])
        for value in ('x', 'a.' + 'A' * 42, 'a.' + 'A' * 44, 'a.' + 'A' * 43 + '='):
            with self.assertRaisesRegex(ValueError, 'INVALID_CURSOR'):
                parse_query([('cursor', value)])

    def test_reference_never_mutates_input_records(self):
        before = copy.deepcopy(self.case)
        self.result(scope='ALL', limit=2)
        self.result(scope='RUNS', limit=2)
        self.assertEqual(self.case, before)


if __name__ == '__main__':
    unittest.main()

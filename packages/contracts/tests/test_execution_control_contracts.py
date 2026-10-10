"""Synthetic execution-control contracts; not runtime, provider or release proof."""
import copy
import unittest

from execution_control_reference import (
    MAX_INTEGER, ROOT, SyntheticBoundary, SyntheticObservation, canonical_bytes,
    loads, mutation_result_matches, parse_request, parse_status_request, valid, validator,
)

T0 = '2026-01-02T03:04:05Z'
T1 = '2026-01-02T03:04:06.123456Z'
T2 = '2026-01-02T03:04:07Z'


def request(version):
    return canonical_bytes({'expected_version': version})


class ExecutionControlContractsTest(unittest.TestCase):
    def fixture(self, name):
        return loads((ROOT / 'fixtures/execution-control' / f'{name}.json').read_text())

    def test_schema_definitions_and_wire_fixtures(self):
        for name in ('state', 'request'):
            validator(name)
        for name in ('active', 'paused', 'resumed'):
            self.assertTrue(valid('state', self.fixture(name)))
        for name in ('pause-request', 'resume-request'):
            self.assertTrue(valid('request', self.fixture(name)))

    def test_request_has_one_safe_positive_integer_and_nothing_else(self):
        for value in (1, 7, MAX_INTEGER):
            self.assertEqual(parse_request(request(value)), value)
        for value in (0, -1, MAX_INTEGER + 1, True, False, None, '7', 7.0):
            self.assertFalse(valid('request', {'expected_version': value}))
        for value in ({}, [], None, 7, {'version': 7}):
            self.assertFalse(valid('request', value))

    def test_no_caller_selected_authority_or_scope(self):
        for field in ('owner_id', 'scope', 'execution_paused', 'security_epoch', 'session_id',
                      'approved', 'authorization', 'idempotency_key', 'operation'):
            with self.subTest(field=field):
                self.assertFalse(valid('request', {'expected_version': 7, field: True}))

    def test_raw_json_strictness(self):
        for raw in ('{"expected_version":7,"expected_version":7}',
                    '{"expected_version":7.0}', '{"expected_version":7e0}',
                    '{"expected_version":NaN}', '{"expected_version":Infinity}',
                    '{"expected_version":9007199254740992}',
                    '{"expected_version":7,"\\ud800":true}',
                    '{"expected_version":7} {}', '{"expected_version":+7}'):
            with self.subTest(raw=raw), self.assertRaises((ValueError, UnicodeError)):
                parse_request(raw)

    def test_read_rejects_any_body_and_any_query(self):
        parse_status_request()
        for query in ([('owner_id', 'synthetic-owner')], [('scope', 'ACCOUNT')],
                      [('version', '7'), ('version', '7')], [('unknown', '')]):
            with self.subTest(query=query), self.assertRaisesRegex(ValueError, 'INVALID_REQUEST'):
                parse_status_request(query)
        for body in (b'{}', b'null', b' ', b'[]'):
            with self.subTest(body=body), self.assertRaisesRegex(ValueError, 'INVALID_REQUEST'):
                parse_status_request(body=body)

    def test_mutation_rejects_any_query(self):
        for query in ([('scope', 'ACCOUNT')], [('expected_version', '7')], [('x', '')]):
            with self.subTest(query=query), self.assertRaisesRegex(ValueError, 'INVALID_REQUEST'):
                parse_request(request(7), query)

    def test_closed_state_has_no_receipt_or_cancellation_claim(self):
        state = self.fixture('paused')
        for field in ('owner_id', 'security_epoch', 'all_cancelled', 'request_committed',
                      'mutation_id', 'receipt', 'cancelled_count', 'provider_result', 'status'):
            self.assertFalse(valid('state', {**state, field: True}))
        for field in state:
            without = {key: value for key, value in state.items() if key != field}
            self.assertFalse(valid('state', without))
        for value in (1, 0, 'true', None):
            self.assertFalse(valid('state', {**state, 'execution_paused': value}))

    def test_snapshot_version_bounds_and_types(self):
        for value in (0, -1, MAX_INTEGER + 1, True, 1.0, '1', None):
            self.assertFalse(valid('state', {**self.fixture('active'), 'version': value}))
        for value in (1, MAX_INTEGER):
            self.assertTrue(valid('state', {**self.fixture('active'), 'version': value}))

    def test_timestamp_is_a_valid_utc_instant(self):
        for value in ('0001-01-01T00:00:00Z', '9999-12-31T23:59:59.999999Z',
                      '2024-02-29T03:04:05.1Z'):
            self.assertTrue(valid('state', {**self.fixture('active'), 'checked_at': value}))
        for value in ('2026-02-29T03:04:05Z', '0000-01-01T00:00:00Z',
                      '2026-01-02T03:04:05+00:00', '2026-01-02T03:04:05',
                      '2026-01-02T03:04:05.1234567Z', '2026-01-02T03:04:60Z',
                      '2026-01-02T03:04:05Z\n', '2026-01-02t03:04:05z', None):
            with self.subTest(value=value):
                self.assertFalse(valid('state', {**self.fixture('active'), 'checked_at': value}))

    def test_read_does_not_require_recent_reauth_or_mutate_domain(self):
        boundary = SyntheticBoundary()
        boundary.sessions['initiator']['recent'] = False
        before = copy.deepcopy(boundary.__dict__)
        self.assertEqual(boundary.state('initiator', T0), self.fixture('active'))
        self.assertEqual(boundary.__dict__, before)

    def test_transition_matches_fixture_and_advances_epoch_once(self):
        boundary = SyntheticBoundary()
        self.assertEqual(boundary.transition('initiator', True, request(7), T1), self.fixture('paused'))
        self.assertEqual((boundary.version, boundary.epoch, boundary.events),
                         (8, 4, ['EXECUTION_PAUSED']))
        self.assertEqual(boundary.sessions['initiator']['epoch'], 4)
        self.assertEqual(boundary.sessions['other']['epoch'], 3)

    def test_current_version_same_state_is_noop(self):
        for target in (False, True):
            boundary = SyntheticBoundary(paused=target)
            before = copy.deepcopy(boundary.__dict__)
            snapshot = boundary.transition('initiator', target, request(7), T1)
            self.assertEqual(snapshot, {'execution_paused': target, 'version': 7, 'checked_at': T1})
            self.assertEqual(boundary.__dict__, before)

    def test_stale_same_state_is_conflict_before_noop(self):
        boundary = SyntheticBoundary(paused=True)
        before = copy.deepcopy(boundary.__dict__)
        with self.assertRaisesRegex(ValueError, 'PRECONDITION_CHANGED'):
            boundary.transition('initiator', True, request(6), T1)
        self.assertEqual(boundary.__dict__, before)

    def test_duplicate_committed_request_has_no_second_effect(self):
        boundary = SyntheticBoundary()
        boundary.transition('initiator', True, request(7), T1)
        before = copy.deepcopy(boundary.__dict__)
        with self.assertRaisesRegex(ValueError, 'PRECONDITION_CHANGED'):
            boundary.transition('initiator', True, request(7), T2)
        self.assertEqual(boundary.__dict__, before)

    def test_other_session_is_invalidated_for_reads_and_writes(self):
        boundary = SyntheticBoundary()
        boundary.transition('initiator', True, request(7), T1)
        with self.assertRaisesRegex(ValueError, 'SESSION_REVOKED'):
            boundary.state('other', T2)
        with self.assertRaisesRegex(ValueError, 'SESSION_REVOKED'):
            boundary.transition('other', False, request(8), T2)
        self.assertEqual(boundary.version, 8)

    def test_noop_still_requires_live_session_and_recent_reauth(self):
        for flag, code in (('live', 'SESSION_REVOKED'), ('recent', 'REAUTHENTICATION_REQUIRED')):
            boundary = SyntheticBoundary()
            boundary.sessions['initiator'][flag] = False
            before = copy.deepcopy(boundary.__dict__)
            with self.assertRaisesRegex(ValueError, code):
                boundary.transition('initiator', False, request(7), T1)
            self.assertEqual(boundary.__dict__, before)

    def test_missing_session_cannot_read_or_write(self):
        boundary = SyntheticBoundary()
        with self.assertRaisesRegex(ValueError, 'AUTHENTICATION_REQUIRED'):
            boundary.state('missing', T0)
        with self.assertRaisesRegex(ValueError, 'AUTHENTICATION_REQUIRED'):
            boundary.transition('missing', True, request(7), T1)

    def test_shared_owner_settings_version_race_blocks_control(self):
        boundary = SyntheticBoundary()
        boundary.version += 1  # Synthetic owner-profile update.
        before = copy.deepcopy(boundary.__dict__)
        with self.assertRaisesRegex(ValueError, 'PRECONDITION_CHANGED'):
            boundary.transition('initiator', True, request(7), T1)
        self.assertEqual(boundary.__dict__, before)

    def test_noop_at_ceiling_allowed_but_change_cannot_overflow(self):
        for version, epoch in ((MAX_INTEGER, 3), (7, MAX_INTEGER)):
            boundary = SyntheticBoundary(version=version, epoch=epoch)
            before = copy.deepcopy(boundary.__dict__)
            boundary.transition('initiator', False, request(version), T1)
            self.assertEqual(boundary.__dict__, before)
            with self.assertRaisesRegex(ValueError, 'VERSION_EXHAUSTED'):
                boundary.transition('initiator', True, request(version), T2)
            self.assertEqual(boundary.__dict__, before)

    def test_pause_distinguishes_unstarted_inflight_and_terminal_work(self):
        boundary = SyntheticBoundary()
        boundary.transition('initiator', True, request(7), T1)
        self.assertEqual([job['status'] for job in boundary.work['jobs']],
                         ['CANCELLED', 'CANCELLED', 'UNKNOWN', 'UNKNOWN', 'SUCCEEDED'])
        self.assertEqual(boundary.work['runs'], ['CANCELLED', 'UNKNOWN', 'UNKNOWN', 'SUCCEEDED', 'CANCELLED'])
        self.assertEqual([item['status'] for item in boundary.work['approvals']],
                         ['REVOKED', 'CONSUMED', 'REJECTED'])
        self.assertTrue(boundary.paused)
        self.assertIn('UNKNOWN', boundary.work['runs'])

    def test_resume_never_resurrects_old_work_or_authority(self):
        boundary = SyntheticBoundary()
        boundary.transition('initiator', True, request(7), T1)
        paused_work = copy.deepcopy(boundary.work)
        self.assertEqual(boundary.transition('initiator', False, request(8), T2), self.fixture('resumed'))
        self.assertEqual(boundary.work, paused_work)
        self.assertEqual(boundary.epoch, 5)
        self.assertEqual(boundary.interrupt_attempts, 1)
        for authority in boundary.work['authority']:
            self.assertFalse(boundary.authority_eligible(authority))
        self.assertTrue(boundary.authority_eligible({'security_epoch': 5}))
        self.assertEqual(boundary.events, ['EXECUTION_PAUSED', 'EXECUTION_RESUMED'])

    def test_invalid_output_time_does_not_partially_mutate_reference(self):
        boundary = SyntheticBoundary()
        before = copy.deepcopy(boundary.__dict__)
        with self.assertRaisesRegex(ValueError, 'INTERNAL_ERROR'):
            boundary.transition('initiator', True, request(7), 'not a time')
        self.assertEqual(boundary.__dict__, before)

    def test_success_response_must_match_requested_transition(self):
        self.assertTrue(mutation_result_matches(self.fixture('active'), True, self.fixture('paused')))
        self.assertTrue(mutation_result_matches(self.fixture('paused'), False, self.fixture('resumed')))
        self.assertTrue(mutation_result_matches(self.fixture('active'), False, self.fixture('active')))
        for response in (self.fixture('active'), {**self.fixture('paused'), 'version': 7},
                         {**self.fixture('paused'), 'version': 9},
                         {**self.fixture('paused'), 'request_committed': True}):
            self.assertFalse(mutation_result_matches(self.fixture('active'), True, response))

    def test_current_status_cannot_attribute_lost_write(self):
        client = SyntheticObservation()
        client.refresh(self.fixture('active'), 0)
        client.lose_response()
        # This same snapshot could result from this request or another operation.
        client.refresh(self.fixture('paused'), 0)
        self.assertEqual(client.request_outcome, 'UNKNOWN')
        self.assertTrue(client.snapshot['execution_paused'])
        self.assertTrue(client.can_pause())  # New explicit action, not automatic retry.

    def test_fresh_active_read_allows_only_separately_requested_new_work(self):
        client = SyntheticObservation()
        client.refresh(self.fixture('active'), 0)
        client.lose_response()
        self.assertFalse(client.can_new_ordinary_work(explicit_owner_action=True))
        client.refresh(self.fixture('resumed'), 0)
        self.assertEqual(client.request_outcome, 'UNKNOWN')
        self.assertFalse(client.can_new_ordinary_work())
        self.assertTrue(client.can_new_ordinary_work(explicit_owner_action=True))
        self.assertFalse(client.can_new_ordinary_work(explicit_owner_action=True, live_session=False))
        self.assertFalse(client.can_new_ordinary_work(explicit_owner_action=True, csrf_known=False))
        self.assertEqual(client.request_outcome, 'UNKNOWN')

    def test_fresh_execution_read_does_not_clear_other_domain_work_fence(self):
        client = SyntheticObservation()
        for outcome in ('PENDING', 'UNKNOWN'):
            client.other_request_outcome = outcome
            client.lose_response()
            client.refresh(self.fixture('resumed'), 0)
            self.assertFalse(client.can_new_ordinary_work(explicit_owner_action=True))
            self.assertTrue(client.can_pause())
            self.assertEqual(client.other_request_outcome, outcome)
            self.assertEqual(client.request_outcome, 'UNKNOWN')

    def test_paused_missing_or_unverified_state_cannot_allow_new_work(self):
        client = SyntheticObservation()
        self.assertFalse(client.can_new_ordinary_work(explicit_owner_action=True))
        client.refresh(self.fixture('paused'), 0)
        self.assertFalse(client.can_new_ordinary_work(explicit_owner_action=True))
        client.refresh(self.fixture('active'), 0)
        client.lose_response()
        self.assertFalse(client.refresh({**self.fixture('resumed'), 'version': None}, 0))
        self.assertFalse(client.can_new_ordinary_work(explicit_owner_action=True))
        client.session_changed()
        self.assertFalse(client.refresh(self.fixture('resumed'), 0))
        self.assertFalse(client.can_new_ordinary_work(explicit_owner_action=True))

    def test_other_uncertainty_does_not_disable_emergency_pause(self):
        client = SyntheticObservation()
        client.refresh(self.fixture('active'), 0)
        client.other_request_outcome = 'UNKNOWN'
        self.assertTrue(client.can_pause())
        self.assertFalse(client.can_pause(live_session=False))
        self.assertFalse(client.can_pause(csrf_known=False))
        self.assertFalse(client.can_pause(reauth_recent=False))
        client.refresh(self.fixture('paused'), 0)
        self.assertEqual(client.other_request_outcome, 'UNKNOWN')

    def test_reauthentication_uncertainty_cannot_be_resolved_by_status(self):
        client = SyntheticObservation()
        client.refresh(self.fixture('active'), 0)
        client.lose_response()
        client.session_changed()
        client.refresh(self.fixture('paused'), 1)
        self.assertEqual(client.request_outcome, 'UNKNOWN')
        self.assertFalse(client.can_pause(csrf_known=False))

    def test_fail_closed_without_snapshot_and_reject_late_session_response(self):
        client = SyntheticObservation()
        self.assertFalse(client.can_pause())
        client.refresh(self.fixture('active'), 0)
        client.session_changed()
        self.assertFalse(client.refresh(self.fixture('paused'), 0))
        self.assertIsNone(client.snapshot)
        self.assertFalse(client.can_pause())
        self.assertTrue(client.refresh(self.fixture('paused'), 1))

    def test_invalid_refresh_does_not_retime_old_observation(self):
        client = SyntheticObservation()
        client.refresh(self.fixture('active'), 0)
        before = copy.deepcopy(client.snapshot)
        self.assertFalse(client.refresh({**self.fixture('paused'), 'checked_at': None}, 0))
        self.assertEqual(client.snapshot, before)
        self.assertFalse(client.can_pause())
        self.assertFalse(client.can_new_ordinary_work(explicit_owner_action=True))

    def test_old_session_refresh_is_inert_for_current_action_eligibility(self):
        client = SyntheticObservation()
        client.session_changed()
        client.refresh(self.fixture('resumed'), 1)
        before = copy.deepcopy(client.__dict__)
        self.assertFalse(client.refresh({'invalid': True}, 0))
        self.assertEqual(client.__dict__, before)
        self.assertTrue(client.can_pause())
        self.assertTrue(client.can_new_ordinary_work(explicit_owner_action=True))


if __name__ == '__main__':
    unittest.main()

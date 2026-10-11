"""Synthetic accounting contracts; no real inference, provider or runtime proof."""
import copy
from datetime import timedelta
import unittest

from usage_reference import (
    COUNTS, IDENTITY, MAX_INTEGER, METRICS, ROOT, SyntheticLedger, bounds,
    canonical_bytes, checked_sum, instant, loads, parse_read_request, valid, validator,
)

OWNER = '11111111-1111-4111-8111-111111111111'
OTHER = '22222222-2222-4222-8222-222222222222'
T0 = '2026-04-15T00:00:00Z'
T1 = '2026-04-15T00:01:00Z'
NOW = '2026-04-15T12:00:00.123456Z'


def row(number=1, *, owner=OWNER, status='STARTED', created_at=T0, started_at=T1, legacy=False):
    return {'owner_id': owner, 'run_id': f'00000000-0000-4000-8000-{number:012d}',
            'reservation_id': f'10000000-0000-4000-8000-{number:012d}',
            'inference_request_id': f'20000000-0000-4000-8000-{number:012d}',
            'model_id': 'local-mlx-v1', 'model_manifest_hash': 'a' * 64,
            'budget_revision': 1, 'max_input_tokens': 3584, 'max_output_tokens': 512,
            'max_compute_ms': 60000, 'created_at': created_at, 'started_at': started_at,
            'status': status, 'accounting_version': 0 if legacy else 1}


def identity(reservation):
    return {key: reservation[key] for key in IDENTITY}


def response(reservation, kind='ANSWER', *, input_tokens=30, output_tokens=10, compute_ms=125, peak=4096):
    usage = {key: reservation[key] for key in ('inference_request_id', 'model_manifest_hash', 'reservation_id')}
    usage.update(input_tokens=input_tokens, output_tokens=output_tokens, compute_ms=compute_ms, peak_memory_bytes=peak)
    if kind == 'ANSWER':
        return {'kind': kind, 'reply_text': 'Synthetic answer.', 'cited_source_ids': [], 'usage': usage}
    if kind == 'NEED_INPUT':
        return {'kind': kind, 'question': 'Synthetic clarification?', 'missing_fields': [], 'usage': usage}
    return {'kind': kind, 'error_code': 'CANCELLED', 'retryable': False, 'usage': usage}


def record(ledger, reservation, reply=None, at='2026-04-15T00:02:00Z'):
    return ledger.observe(reservation['inference_request_id'], identity(reservation), reply or response(reservation), at)


def mixed_ledger():
    rows = [row(1, status='SUCCEEDED'), row(2, status='QUEUED', started_at=None),
            row(3), row(4, status='CANCELLED', started_at=None), row(5, status='UNKNOWN'),
            row(6, status='SUCCEEDED', legacy=True), row(7, status='FAILED'),
            row(8, status='SUCCEEDED')]
    ledger = SyntheticLedger(rows)
    record(ledger, rows[0])
    record(ledger, rows[6], response(rows[6], 'ERROR', input_tokens=0, output_tokens=0, compute_ms=0, peak=0))
    record(ledger, rows[7], response(rows[7], 'NEED_INPUT', input_tokens=20, output_tokens=5, compute_ms=100, peak=8192))
    return ledger


class UsageContractsTest(unittest.TestCase):
    def fixture(self, name):
        return loads((ROOT / 'fixtures' / 'usage' / f'{name}.json').read_text())

    def test_schemas_and_all_synthetic_fixtures(self):
        for name in ('response', 'query'):
            validator(name)
        for path in (ROOT / 'fixtures' / 'usage').glob('*.json'):
            with self.subTest(fixture=path.name):
                self.assertTrue(valid('response', loads(path.read_text())))

    def test_no_query_or_request_body(self):
        parse_read_request()
        self.assertTrue(valid('query', {}))
        for query in ([('period', 'day')], [('timezone', 'UTC')], [('owner_id', OWNER)],
                      [('start_at', T0)], [('x', '1'), ('x', '1')]):
            with self.subTest(query=query), self.assertRaisesRegex(ValueError, 'INVALID_REQUEST'):
                parse_read_request(query)
        for body in (b'{}', b' ', b'null', b'[]', b'0'):
            with self.subTest(body=body), self.assertRaisesRegex(ValueError, 'INVALID_REQUEST'):
                parse_read_request(body=body)
        for query in ({'period': 'day'}, {'timezone': 'UTC'}, {'owner_id': OWNER}, []):
            self.assertFalse(valid('query', query))

    def test_empty_subtotal_is_not_fabricated_native_measurement(self):
        result = SyntheticLedger().read(OWNER, NOW, 'UTC')
        self.assertEqual(result, self.fixture('empty-utc'))
        for name in ('day', 'month'):
            actual = result[name]['measured']
            self.assertEqual(actual, {'request_count': 0, 'input_tokens': 0, 'output_tokens': 0,
                                     'compute_ms': 0, 'peak_memory_bytes': None, 'external_cost_usd_micro': None})
            self.assertEqual(result[name]['request_count'], 0)

    def test_six_disjoint_categories_and_nonadditive_reservations(self):
        result = mixed_ledger().read(OWNER, NOW, 'Asia/Seoul')
        self.assertEqual(result, self.fixture('mixed-seoul'))
        for name in ('day', 'month'):
            period = result[name]
            self.assertEqual(period['request_count'], 8)
            self.assertEqual(period['counts'], {'measured': 2, 'pending': 1, 'in_progress': 1,
                                              'unstarted': 1, 'uncertain': 2, 'legacy_unavailable': 1})
            self.assertEqual(period['conservative_reserved'], {'request_count': 7, 'max_compute_ms': 420000, 'max_output_tokens': 3584})
            self.assertEqual(period['measured']['compute_ms'], 225)

    def test_peak_is_max_not_sum_or_reservation(self):
        result = mixed_ledger().read(OWNER, NOW, 'UTC')
        self.assertEqual(result['day']['measured']['peak_memory_bytes'], 8192)
        self.assertNotEqual(result['day']['measured']['peak_memory_bytes'], 4096 + 8192)

    def test_actual_zero_peak_is_allowed_only_with_a_measurement(self):
        reservation = row()
        ledger = SyntheticLedger([reservation])
        record(ledger, reservation, response(reservation, input_tokens=0, output_tokens=0, compute_ms=0, peak=0))
        result = ledger.read(OWNER, NOW, 'UTC')
        self.assertTrue(valid('response', result))
        self.assertEqual(result['day']['measured']['peak_memory_bytes'], 0)
        self.assertEqual(result['day']['counts']['measured'], 1)

    def test_zero_shaped_error_remains_uncertain_even_before_settlement(self):
        reservation = row()
        ledger = SyntheticLedger([reservation])
        record(ledger, reservation, response(reservation, 'ERROR', input_tokens=0, output_tokens=0, compute_ms=0, peak=0))
        result = ledger.read(OWNER, NOW, 'UTC')
        self.assertEqual(result['day']['counts']['uncertain'], 1)
        self.assertEqual(result['day']['counts']['in_progress'], 0)
        self.assertEqual(result['day']['measured']['request_count'], 0)
        self.assertIsNone(result['day']['measured']['peak_memory_bytes'])
        self.assertEqual(result['day']['conservative_reserved']['max_compute_ms'], 60000)

    def test_nonzero_error_metrics_are_still_not_measured(self):
        reservation = row()
        ledger = SyntheticLedger([reservation])
        record(ledger, reservation, response(reservation, 'ERROR'))
        result = ledger.read(OWNER, NOW, 'UTC')
        self.assertEqual(result['day']['counts']['uncertain'], 1)
        self.assertEqual(result['day']['measured']['compute_ms'], 0)

    def test_error_observation_does_not_block_later_actual_evidence(self):
        reservation = row()
        ledger = SyntheticLedger([reservation])
        record(ledger, reservation, response(reservation, 'ERROR', input_tokens=0, output_tokens=0, compute_ms=0, peak=0))
        self.assertEqual(record(ledger, reservation), 'RECORDED')
        self.assertEqual(ledger.read(OWNER, NOW, 'UTC')['day']['counts']['measured'], 1)
        self.assertEqual(len(ledger.errors), 1)
        self.assertEqual(len(ledger.measurements), 1)

    def test_error_never_erases_an_existing_measurement(self):
        reservation = row()
        ledger = SyntheticLedger([reservation])
        record(ledger, reservation)
        before = copy.deepcopy(ledger.measurements)
        record(ledger, reservation, response(reservation, 'ERROR'))
        self.assertEqual(ledger.measurements, before)
        self.assertEqual(ledger.read(OWNER, NOW, 'UTC')['day']['counts']['measured'], 1)

    def test_positive_error_observation_overrides_legacy_uncertainty(self):
        reservation = row(legacy=True)
        ledger = SyntheticLedger([reservation])
        record(ledger, reservation, response(reservation, 'ERROR'))
        counts = ledger.read(OWNER, NOW, 'UTC')['day']['counts']
        self.assertEqual(counts['uncertain'], 1)
        self.assertEqual(counts['legacy_unavailable'], 0)

    def test_legacy_success_is_never_synthesized_as_actual(self):
        reservation = row(status='SUCCEEDED', legacy=True)
        ledger = SyntheticLedger([reservation])
        result = ledger.read(OWNER, NOW, 'UTC')
        self.assertEqual(result['day']['counts']['legacy_unavailable'], 1)
        self.assertEqual(result['day']['measured']['request_count'], 0)
        record(ledger, reservation)
        result = ledger.read(OWNER, NOW, 'UTC')
        self.assertEqual(result['day']['counts']['measured'], 1)
        self.assertEqual(result['day']['counts']['legacy_unavailable'], 0)

    def test_started_cancellation_is_uncertain_not_unstarted(self):
        ledger = SyntheticLedger([row(status='CANCELLED')])
        result = ledger.read(OWNER, NOW, 'UTC')
        self.assertEqual(result['day']['counts']['uncertain'], 1)
        self.assertEqual(result['day']['counts']['unstarted'], 0)
        self.assertEqual(result['day']['conservative_reserved']['request_count'], 0)

    def test_queued_and_started_are_not_automatically_uncertain(self):
        ledger = SyntheticLedger([row(1, status='QUEUED', started_at=None), row(2)])
        counts = ledger.read(OWNER, NOW, 'UTC')['day']['counts']
        self.assertEqual((counts['pending'], counts['in_progress'], counts['uncertain']), (1, 1, 0))

    def test_inconsistent_dispatch_markers_are_uncertain_not_pending_or_active(self):
        ledger = SyntheticLedger([row(1, status='QUEUED'), row(2, status='STARTED', started_at=None)])
        counts = ledger.read(OWNER, NOW, 'UTC')['day']['counts']
        self.assertEqual(counts['uncertain'], 2)
        self.assertEqual(counts['pending'], 0)
        self.assertEqual(counts['in_progress'], 0)

    def test_error_receipt_is_immutable_but_not_the_measurement_slot(self):
        reservation = row()
        ledger = SyntheticLedger([reservation])
        reply = response(reservation, 'ERROR')
        record(ledger, reservation, reply)
        before = copy.deepcopy(ledger.__dict__)
        record(ledger, reservation, reply, at=NOW)
        self.assertEqual(ledger.__dict__, before)
        changed = response(reservation, 'ERROR', compute_ms=126)
        with self.assertRaisesRegex(ValueError, 'USAGE_OBSERVATION_CONFLICT'):
            record(ledger, reservation, changed)
        self.assertEqual(ledger.__dict__, before)
        self.assertEqual(record(ledger, reservation), 'RECORDED')

    def test_same_measurement_replay_is_noop_with_original_recorded_at(self):
        reservation = row()
        ledger = SyntheticLedger([reservation])
        self.assertEqual(record(ledger, reservation), 'RECORDED')
        before = copy.deepcopy(ledger.__dict__)
        self.assertEqual(record(ledger, reservation, at=NOW), 'REPLAY')
        self.assertEqual(ledger.__dict__, before)

    def test_conflicting_measurement_cannot_overwrite(self):
        for metric in METRICS:
            with self.subTest(metric=metric):
                reservation = row()
                ledger = SyntheticLedger([reservation])
                record(ledger, reservation)
                changed = response(reservation)
                changed['usage'][metric] += 1
                before = copy.deepcopy(ledger.__dict__)
                with self.assertRaisesRegex(ValueError, 'USAGE_MEASUREMENT_CONFLICT'):
                    record(ledger, reservation, changed)
                self.assertEqual(ledger.__dict__, before)

    def test_each_trusted_identity_binding_is_required(self):
        reservation = row()
        for field in IDENTITY:
            with self.subTest(field=field):
                ledger = SyntheticLedger([reservation])
                wrong = identity(reservation)
                wrong[field] = 2 if field == 'budget_revision' else 'unrelated-value'
                before = copy.deepcopy(ledger.__dict__)
                with self.assertRaisesRegex(ValueError, 'MEASUREMENT_BINDING_MISMATCH'):
                    ledger.observe(reservation['inference_request_id'], wrong, response(reservation), NOW)
                self.assertEqual(ledger.__dict__, before)
        wrong = identity(reservation)
        wrong['budget_revision'] = True
        with self.assertRaisesRegex(ValueError, 'MEASUREMENT_BINDING_MISMATCH'):
            SyntheticLedger([reservation]).observe(reservation['inference_request_id'], wrong, response(reservation), NOW)

    def test_each_returned_identity_binding_is_required(self):
        reservation = row()
        for field in ('inference_request_id', 'reservation_id', 'model_manifest_hash'):
            with self.subTest(field=field):
                reply = response(reservation)
                reply['usage'][field] = 'b' * 64 if field == 'model_manifest_hash' else OTHER
                with self.assertRaisesRegex(ValueError, 'MEASUREMENT_BINDING_MISMATCH'):
                    record(SyntheticLedger([reservation]), reservation, reply)

    def test_untrusted_observation_cannot_create_accounting(self):
        reservation = row()
        for authenticated in (False, 'true', None, 1):
            ledger = SyntheticLedger([reservation])
            before = copy.deepcopy(ledger.__dict__)
            with self.assertRaisesRegex(ValueError, 'UNAUTHENTICATED_OBSERVATION'):
                ledger.observe(reservation['inference_request_id'], identity(reservation), response(reservation), NOW, authenticated=authenticated)
            self.assertEqual(ledger.__dict__, before)

    def test_unknown_or_undispatched_request_cannot_claim_measurement(self):
        reservation = row()
        with self.assertRaisesRegex(ValueError, 'UNKNOWN_RESERVATION'):
            record(SyntheticLedger(), reservation)
        for state in ('QUEUED', 'CANCELLED'):
            reservation = row(status=state, started_at=None)
            with self.assertRaisesRegex(ValueError, 'MEASUREMENT_BEFORE_DISPATCH'):
                record(SyntheticLedger([reservation]), reservation)
        reservation = row()
        with self.assertRaisesRegex(ValueError, 'MEASUREMENT_BEFORE_DISPATCH'):
            record(SyntheticLedger([reservation]), reservation, at=T0)

    def test_full_response_must_be_valid_before_usage_is_accepted(self):
        reservation = row()
        base = response(reservation)
        invalid = [{**base, 'authority': True}, {**base, 'reply_text': ''},
                   {**base, 'cited_source_ids': ['not-allowed']},
                   {**base, 'kind': 'TOOL_PROPOSALS'}, {'kind': 'ANSWER', 'usage': base['usage']},
                   {**response(reservation, 'ERROR'), 'retryable': True}]
        for reply in invalid:
            with self.subTest(reply=reply), self.assertRaises(ValueError):
                record(SyntheticLedger([reservation]), reservation, reply)

    def test_metric_types_bounds_and_request_ceilings(self):
        reservation = row()
        for field in METRICS:
            for value in (-1, True, 1.0, None, '1', MAX_INTEGER + 1):
                with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                    reply = response(reservation)
                    reply['usage'][field] = value
                    record(SyntheticLedger([reservation]), reservation, reply)
        for field, value in (('input_tokens', 3585), ('output_tokens', 513), ('compute_ms', 60001)):
            reply = response(reservation)
            reply['usage'][field] = value
            with self.assertRaisesRegex(ValueError, 'USAGE_LIMIT_EXCEEDED'):
                record(SyntheticLedger([reservation]), reservation, reply)
        ledger = SyntheticLedger([reservation])
        record(ledger, reservation, response(reservation, input_tokens=3584, output_tokens=512, compute_ms=60000, peak=MAX_INTEGER))
        self.assertTrue(valid('response', ledger.read(OWNER, NOW, 'UTC')))

    def test_late_actual_never_revives_run_output_or_authority(self):
        for reason in ('PAUSED', 'LOGGED_OUT', 'CONTEXT_INVALID', 'UNKNOWN', 'CANCELLED'):
            with self.subTest(reason=reason):
                reservation = row(status='UNKNOWN')
                ledger = SyntheticLedger([reservation])
                ledger.run_state = {'status': reason, 'output': None, 'authority': False}
                domain_before = copy.deepcopy((ledger.rows, ledger.run_state))
                record(ledger, reservation)
                self.assertEqual((ledger.rows, ledger.run_state), domain_before)
                self.assertEqual(ledger.read(OWNER, NOW, 'UTC')['day']['counts']['measured'], 1)

    def test_late_measurement_keeps_original_reservation_period(self):
        reservation = row(created_at='2026-03-31T23:50:00Z', started_at='2026-03-31T23:51:00Z', status='UNKNOWN')
        ledger = SyntheticLedger([reservation])
        record(ledger, reservation)
        result = ledger.read(OWNER, NOW, 'UTC')
        self.assertEqual(result['month']['request_count'], 0)
        self.assertEqual(result['month']['measured']['request_count'], 0)
        self.assertEqual(len(ledger.measurements), 1)

    def test_observations_after_checked_at_are_not_pretended_already_seen(self):
        reservation = row()
        ledger = SyntheticLedger([reservation])
        record(ledger, reservation, at=NOW)
        earlier = ledger.read(OWNER, '2026-04-15T00:01:30Z', 'UTC')
        self.assertEqual(earlier['day']['counts']['in_progress'], 1)
        self.assertEqual(earlier['day']['counts']['measured'], 0)

    def test_half_open_periods_future_rows_and_owner_isolation(self):
        ledger = SyntheticLedger([
            row(1, created_at='2026-03-31T23:59:59.999999Z'), row(2, created_at='2026-04-01T00:00:00Z'),
            row(3, created_at='2026-04-14T23:59:59.999999Z'), row(4, created_at=T0),
            row(5, created_at=NOW), row(6, created_at='2026-04-15T12:00:00.123457Z'),
            row(7, created_at='2026-04-16T00:00:00Z'), row(8, created_at='2026-05-01T00:00:00Z'),
            row(9, owner=OTHER)])
        result = ledger.read(OWNER, NOW, 'UTC')
        self.assertEqual(result['day']['request_count'], 2)
        self.assertEqual(result['month']['request_count'], 4)

    def test_read_is_owner_only_live_session_and_side_effect_free(self):
        ledger = mixed_ledger()
        before = copy.deepcopy(ledger.__dict__)
        self.assertTrue(valid('response', ledger.read(OWNER, NOW, 'UTC', recent=False)))
        self.assertEqual(ledger.read(OTHER, NOW, 'UTC')['month']['request_count'], 0)
        for owner, live, error in ((None, True, 'AUTHENTICATION_REQUIRED'), (OWNER, False, 'SESSION_REVOKED')):
            with self.assertRaisesRegex(ValueError, error):
                ledger.read(owner, NOW, 'UTC', live=live)
        self.assertEqual(ledger.__dict__, before)

    def test_current_profile_timezone_is_explicit_not_browser_timezone(self):
        ledger = SyntheticLedger([row(created_at='2026-04-14T16:00:00Z')])
        utc_result = ledger.read(OWNER, NOW, 'UTC')
        seoul = ledger.read(OWNER, NOW, 'Asia/Seoul')
        self.assertEqual(utc_result['day']['request_count'], 0)
        self.assertEqual(seoul['day']['request_count'], 1)
        self.assertEqual(seoul['timezone'], 'Asia/Seoul')
        self.assertEqual(seoul['quota_timezone'], 'UTC')
        self.assertEqual(ledger.rows[0]['created_at'], '2026-04-14T16:00:00Z')

    def test_calendar_day_dst_spring_and_fall_lengths(self):
        for checked, hours, fixture in (('2026-03-08T12:00:00Z', 23, 'spring-dst'), ('2026-11-01T12:00:00Z', 25, 'fall-dst')):
            result = SyntheticLedger().read(OWNER, checked, 'America/New_York')
            self.assertEqual(result, self.fixture(fixture))
            self.assertTrue(valid('response', result))
            self.assertEqual(instant(result['day']['end_at']) - instant(result['day']['start_at']), timedelta(hours=hours))

    def test_leap_month_year_rollover_and_fractional_offsets(self):
        for checked, zone, days, key in (
                ('2024-02-15T12:00:00Z', 'UTC', 29, '2024-02'),
                ('2026-12-31T12:00:00Z', 'UTC', 31, '2026-12'),
                ('2026-04-15T12:00:00Z', 'Asia/Kathmandu', 30, '2026-04')):
            result = SyntheticLedger().read(OWNER, checked, zone)
            self.assertTrue(valid('response', result))
            self.assertEqual(result['month']['local_key'], key)
            self.assertEqual(instant(result['month']['end_at']) - instant(result['month']['start_at']), timedelta(days=days))
        self.assertEqual(bounds(NOW, 'Asia/Kathmandu')['day'][1], '2026-04-14T18:15:00Z')

    def test_skipped_midnight_does_not_assume_every_day_starts_at_local_zero_hour(self):
        result = SyntheticLedger().read(OWNER, '2018-11-04T12:00:00Z', 'America/Sao_Paulo')
        self.assertTrue(valid('response', result))
        self.assertEqual(result['day']['start_at'], '2018-11-04T03:00:00Z')
        self.assertEqual(instant(result['day']['end_at']) - instant(result['day']['start_at']), timedelta(hours=23))

    def test_checked_at_exact_microseconds_not_local_machine_replacement(self):
        result = SyntheticLedger().read(OWNER, NOW, 'UTC')
        self.assertEqual(result['checked_at'], NOW)
        for value in ('2026-02-29T00:00:00Z', '2026-04-15T12:00:00+00:00', '2026-04-15t12:00:00z',
                      '2026-04-15T12:00:60Z', '0000-01-01T00:00:00Z',
                      '2026-04-15T12:00:00.1234567Z', NOW + '\n'):
            changed = copy.deepcopy(result)
            changed['checked_at'] = value
            self.assertFalse(valid('response', changed), value)

    def test_invalid_timezone_and_calendar_bounds_fail_closed(self):
        result = SyntheticLedger().read(OWNER, NOW, 'UTC')
        for zone in ('', 'GMT', '+09:00', 'Etc/Unknown', 'asia/seoul', 'SystemV/EST5', 'UTC\n'):
            changed = copy.deepcopy(result)
            changed['timezone'] = zone
            self.assertFalse(valid('response', changed), zone)
        for period, field, value in (('day', 'local_key', '2026-04-14'), ('month', 'local_key', '2026-04-15'),
                                     ('day', 'start_at', T1), ('month', 'end_at', '2026-05-02T00:00:00Z')):
            changed = copy.deepcopy(result)
            changed[period][field] = value
            self.assertFalse(valid('response', changed))

    def test_closed_projection_never_exposes_content_or_authority(self):
        result = mixed_ledger().read(OWNER, NOW, 'UTC')
        for field in ('owner_id', 'records', 'model_id', 'model_manifest_hash', 'session_id',
                      'prompt', 'reply_text', 'cost_estimate', 'currency', 'remaining_quota', 'can_execute'):
            self.assertFalse(valid('response', {**result, field: 'synthetic'}))
        for target in ((), ('day',), ('day', 'counts'), ('month', 'measured'), ('month', 'conservative_reserved')):
            changed = copy.deepcopy(result)
            node = changed
            for key in target:
                node = node[key]
            node['extra'] = True
            self.assertFalse(valid('response', changed))
        for field in result:
            changed = copy.deepcopy(result)
            del changed[field]
            self.assertFalse(valid('response', changed))

    def test_unknown_cost_never_becomes_zero_price_or_synthetic_conversion(self):
        result = mixed_ledger().read(OWNER, NOW, 'UTC')
        for value in (0, 1, '0', {'currency': 'USD', 'amount': 0}):
            changed = copy.deepcopy(result)
            changed['day']['measured']['external_cost_usd_micro'] = value
            self.assertFalse(valid('response', changed))

    def test_all_wire_numeric_leaves_reject_unsafe_or_wrong_types(self):
        result = mixed_ledger().read(OWNER, NOW, 'UTC')
        paths = [('day', 'request_count')]
        for group in ('counts', 'measured', 'conservative_reserved'):
            paths += [('day', group, field) for field, value in result['day'][group].items() if type(value) is int]
        for path in paths:
            for value in (-1, MAX_INTEGER + 1, True, 1.0, '1'):
                changed = copy.deepcopy(result)
                node = changed
                for key in path[:-1]:
                    node = node[key]
                node[path[-1]] = value
                self.assertFalse(valid('response', changed), (path, value))

    def test_count_relations_and_empty_peak_nullability(self):
        result = mixed_ledger().read(OWNER, NOW, 'UTC')
        for path, value in ((('day', 'counts', 'uncertain'), 3), (('day', 'measured', 'request_count'), 1),
                            (('day', 'measured', 'peak_memory_bytes'), None),
                            (('day', 'conservative_reserved', 'request_count'), 9),
                            (('day', 'conservative_reserved', 'max_compute_ms'), 0)):
            changed = copy.deepcopy(result)
            node = changed
            for key in path[:-1]: node = node[key]
            node[path[-1]] = value
            self.assertFalse(valid('response', changed), path)
        empty = SyntheticLedger().read(OWNER, NOW, 'UTC')
        for field in ('input_tokens', 'output_tokens', 'compute_ms', 'peak_memory_bytes'):
            changed = copy.deepcopy(empty)
            changed['day']['measured'][field] = 0 if field == 'peak_memory_bytes' else 1
            self.assertFalse(valid('response', changed))

    def test_actual_and_reservation_cap_relations(self):
        result = mixed_ledger().read(OWNER, NOW, 'UTC')
        for group, field, value in (('measured', 'input_tokens', 7169), ('measured', 'output_tokens', 1025),
                                     ('measured', 'compute_ms', 120001), ('conservative_reserved', 'max_compute_ms', 420001),
                                     ('conservative_reserved', 'max_output_tokens', 3585)):
            changed = copy.deepcopy(result)
            changed['day'][group][field] = value
            changed['month'][group][field] = value
            self.assertFalse(valid('response', changed))

    def test_day_subtotals_cannot_exceed_month(self):
        result = mixed_ledger().read(OWNER, NOW, 'UTC')
        for field in ('input_tokens', 'output_tokens', 'compute_ms', 'peak_memory_bytes'):
            changed = copy.deepcopy(result)
            changed['day']['measured'][field] += 1
            self.assertFalse(valid('response', changed), field)
        changed = copy.deepcopy(result)
        changed['month']['counts']['pending'] = 0
        changed['month']['counts']['in_progress'] = 2
        self.assertFalse(valid('response', changed))

    def test_equal_day_month_counts_require_equal_actuals_peaks_and_ceilings(self):
        result = mixed_ledger().read(OWNER, NOW, 'UTC')
        for group, fields in (('measured', METRICS), ('conservative_reserved', ('max_compute_ms', 'max_output_tokens'))):
            for field in fields:
                changed = copy.deepcopy(result)
                if group == 'measured':
                    changed['month'][group][field] += 1
                else:
                    changed['day'][group][field] -= 1
                self.assertFalse(valid('response', changed), (group, field))

    def test_month_minus_day_measured_caps_use_only_extra_request_count(self):
        rows = [row(1), row(2, created_at='2026-04-14T00:00:00Z', started_at='2026-04-14T00:01:00Z')]
        ledger = SyntheticLedger(rows)
        for reservation in rows:
            record(ledger, reservation)
        result = ledger.read(OWNER, NOW, 'UTC')
        self.assertTrue(valid('response', result))
        for field, cap in (('input_tokens', 3584), ('output_tokens', 512), ('compute_ms', 60000)):
            changed = copy.deepcopy(result)
            changed['month']['measured'][field] = changed['day']['measured'][field] + cap + 1
            self.assertFalse(valid('response', changed), field)
        for field, cap in (('max_compute_ms', 60000), ('max_output_tokens', 512)):
            changed = copy.deepcopy(result)
            changed['day']['conservative_reserved'][field] = 1
            self.assertFalse(valid('response', changed), field)
            changed = copy.deepcopy(result)
            changed['month']['conservative_reserved'][field] = changed['day']['conservative_reserved'][field]
            self.assertFalse(valid('response', changed), field)

    def test_checked_arithmetic_has_no_wrap_rounding_clamp_or_partial_total(self):
        self.assertEqual(checked_sum([MAX_INTEGER - 1, 1]), MAX_INTEGER)
        for values in ([MAX_INTEGER, 1], [1, MAX_INTEGER], [-1], [True], [1.0], [MAX_INTEGER + 1]):
            with self.subTest(values=values), self.assertRaisesRegex(ValueError, 'USAGE_AGGREGATE_OVERFLOW'):
                checked_sum(values)

    def test_json_parse_rejects_duplicate_keys_floats_nonfinite_and_surrogates(self):
        for raw in ('{"checked_at":"a","checked_at":"b"}', '{"value":1.0}', '{"value":1e0}',
                    '{"value":NaN}', '{"value":Infinity}', '{"value":9007199254740992}',
                    '{"value":"\\ud800"}'):
            with self.subTest(raw=raw), self.assertRaises((ValueError, UnicodeError)):
                loads(raw)

    def test_fixed_summary_shape_is_bounded_for_many_requests(self):
        ledger = SyntheticLedger(row(i, status='QUEUED', started_at=None) for i in range(1, 1001))
        result = ledger.read(OWNER, NOW, 'UTC')
        self.assertTrue(valid('response', result))
        self.assertEqual(result['month']['request_count'], 1000)
        self.assertLess(len(canonical_bytes(result)), 2500)


if __name__ == '__main__':
    unittest.main()

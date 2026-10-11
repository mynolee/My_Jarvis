"""Synthetic deletion contract acceptance; SQL/HTTP/browser/native NOT_RUN here."""
import copy
import unittest
from datetime import timedelta

from conversation_deletion_reference import (
    ACTIVE, DOMAINS, FIXTURES, LIMITS, MAX_INTEGER, READ_BARRIERS, ROOT, WRITE_BARRIERS,
    SyntheticDeletionClient, SyntheticDeletions, canonical_bytes, check_limits,
    dispatch_transition, eligibility, feedback_quiescent, fixture, instant, keyed, kst, loads,
    parse_mutation, parse_read, source_counts, stamp, valid, validate_outcome,
    validate_pending, validate_preparation, validate_state, validator,
)

OWNER = '11111111-1111-4111-8111-111111111111'
FOREIGN = '22222222-2222-4222-8222-222222222222'
TARGET = '81000000-0000-4000-8000-000000000001'
OTHER = '81000000-0000-4000-8000-000000000002'
RUN = '85000000-0000-4000-8000-000000000001'
CONTEXT = '86000000-0000-4000-8000-000000000001'
NOW = '2026-10-10T17:40:00Z'
LATER = '2026-10-10T17:41:00Z'
SENTINEL = 'SYNTHETIC_ONLY_SOURCE_원문_7619'


def key(number):
    return f'84000000-0000-4000-8000-{number:012d}'


def node(identifier, status, **extra):
    return {'id': identifier, 'owner_id': OWNER, 'conversation_id': TARGET,
            'run_id': RUN, 'status': status, **extra}


def target():
    return {'id': TARGET, 'owner_id': OWNER, 'project_id': None, 'created_at': NOW,
            'title': SENTINEL, 'content_version': 1, 'integrity_verified': True,
            'content': {'messages': [SENTINEL, SENTINEL], 'run_results': [SENTINEL],
                        'action_payloads': [SENTINEL], 'raw_content_hashes': ['raw-synthetic-source-hash']},
            'graph': {**{kind: [] for kind in DOMAINS},
                      'runs': [node(RUN, 'SUCCEEDED', context_history_eligible=True)],
                      'actions': [node(key(10), 'SUCCEEDED')]},
            'source_bindings': [key(60)],
            'authorizations': [{'id': key(50), 'owner_id': OWNER, 'action_id': key(10),
                'approval_id': key(51), 'security_epoch': 1, 'valid_until': LATER,
                'max_executions': 1, 'payload_hash': 'raw-synthetic-source-hash', 'revoked': False}]}


def local_target():
    result = target()
    digest = keyed(OWNER, 'synthetic-contextual-request', SENTINEL)
    result['graph']['contexts'] = [node(CONTEXT, 'SETTLED', request_digest=digest)]
    result['graph']['reservations'] = [node(key(20), 'SUCCEEDED', accounting_version=1,
        dispatch_context_id=CONTEXT, dispatch_cache_policy=LIMITS['dispatch_cache_policy'],
        started_at=NOW, body_hash=digest)]
    return result


def service(local=False):
    result = SyntheticDeletions()
    result.add_session('original', OWNER, NOW)
    result.add_session('replacement', OWNER, NOW)
    result.add_session('foreign', FOREIGN, NOW)
    result.targets[TARGET] = local_target() if local else target()
    other = target()
    other.update(id=OTHER, title='other conversation')
    other['graph'] = {kind: [] for kind in DOMAINS}
    other['content'] = {'messages': [], 'run_results': [], 'action_payloads': []}
    result.targets[OTHER] = other
    result.memories = [{'id': key(70), 'value': SENTINEL, 'source': 'EXPLICIT_OWNER_FORM'}]
    result.usage = [{'id': key(71), 'request_digest': 'c' * 64, 'tokens': 79, 'compute_ms': 901}]
    return result


def prepare(result, target_id=TARGET, request_key=None, now=NOW, label='original'):
    state = result.state(label, target_id, now)
    request = {'expected_version': state['content_version'], 'expected_digest': state['content_digest']}
    return result.prepare(label, target_id, canonical_bytes(request), request_key or key(1), now)


def confirm_body(preview):
    return {k: preview[k] for k in ('payload_hash', 'expected_version', 'expected_digest')} | {'confirm': True}


def confirm(result, preview, now=LATER, request_key=None, label='original', **kwargs):
    return result.confirm(label, preview['id'], canonical_bytes(confirm_body(preview)), request_key or key(2), now, **kwargs)


class ConversationDeletionContractsTest(unittest.TestCase):
    def test_all_eleven_closed_schemas_and_nine_wire_fixtures(self):
        paths = list((ROOT / 'schemas').glob('conversation-deletion-*.schema.json'))
        self.assertEqual(len(paths), 11)
        for path in paths:
            validator(path.name.removeprefix('conversation-deletion-').removesuffix('.schema.json'))
        for name, schema in [('state-eligible', 'state'), ('state-blocked', 'state'),
            ('state-deleted', 'state'), ('prepare-request', 'prepare-request'),
            ('preparation', 'preparation'), ('confirm-request', 'confirm-request'),
            ('receipt', 'receipt'), ('outcome-committed', 'outcome'), ('outcome-not-observed', 'outcome')]:
            self.assertTrue(valid(schema, fixture(name)), name)

    def test_every_nested_object_is_exact_closed(self):
        def walk(value):
            if isinstance(value, dict):
                if value.get('type') == 'object':
                    self.assertIs(value.get('additionalProperties'), False)
                    self.assertEqual(set(value['required']), set(value['properties']))
                for child in value.values():
                    walk(child)
            elif isinstance(value, list):
                for child in value:
                    walk(child)
        for path in (ROOT / 'schemas').glob('conversation-deletion-*.schema.json'):
            walk(loads(path.read_text()))

    def test_no_client_authority_or_scope_overrides(self):
        for schema in ('prepare-request', 'confirm-request'):
            for field in ('owner_id', 'session', 'security_epoch', 'scope_policy', 'tool_id',
                          'ttl', 'target_ids', 'approved', 'redirect_url', 'force', 'erase_backups'):
                self.assertFalse(valid(schema, fixture(schema) | {field: True}), field)

    def test_confirm_requires_exact_boolean_true(self):
        body = fixture('confirm-request')
        for value in (False, None, 'true', 1, [], {}):
            self.assertFalse(valid('confirm-request', body | {'confirm': value}))
        del body['confirm']
        self.assertFalse(valid('confirm-request', body))

    def test_strict_utf8_json_and_safe_number_corpus(self):
        for raw in ('{"expected_version":1,"expected_version":1}', '{"x":1.0}',
                    '{"x":1e0}', '{"x":NaN}', '{"x":Infinity}', '{"x":9007199254740992}',
                    '{"x":"\\ud800"}', b'\xff', b'null', b'[]', b'\xff\xfe{\x00}\x00'):
            with self.subTest(raw=raw), self.assertRaisesRegex(ValueError, 'INVALID_REQUEST'):
                parse_mutation('prepare', raw, key(1))

    def test_canonical_uuid_paths_headers_and_no_queries_bodies(self):
        for kind, identifier in [('conversation', TARGET), ('preparation', key(1)), ('request', key(2))]:
            parse_read(kind, identifier)
            for invalid in (identifier + '\n', '', 'ABCDEF01-2345-6789-ABCD-EF0123456789'):
                with self.assertRaisesRegex(ValueError, 'INVALID_REQUEST'):
                    parse_read(kind, invalid)
            for query in ([('x', '1')], [('x', '')], [('x', '1'), ('x', '2')]):
                with self.assertRaisesRegex(ValueError, 'INVALID_REQUEST'):
                    parse_read(kind, identifier, query=query)
            for body in (b'{}', b' ', b'null'):
                with self.assertRaisesRegex(ValueError, 'INVALID_REQUEST'):
                    parse_read(kind, identifier, body=body)
        for header in (None, [key(1)], key(1) + ',' + key(2), key(1) + '\n'):
            with self.assertRaisesRegex(ValueError, 'INVALID_REQUEST'):
                parse_mutation('prepare', canonical_bytes(fixture('prepare-request')), header)

    def test_http_gate_model_requires_csrf_origin_json_and_no_query(self):
        for extra in ({'csrf': False}, {'same_origin': False}, {'json_type': False}, {'query': [('x', '')]}):
            with self.assertRaisesRegex(ValueError, 'INVALID_REQUEST'):
                parse_mutation('prepare', canonical_bytes(fixture('prepare-request')), key(1), **extra)

    def test_real_utc_calendar_and_precision(self):
        for invalid in ('2026-02-30T00:00:00Z', '0000-01-01T00:00:00Z', '2026-10-10T17:40:00+00:00',
                        '2026-10-10T17:40:00.1234567Z', '2026-10-10T17:40:60Z', NOW + '\n'):
            self.assertFalse(valid('state', fixture('state-eligible') | {'checked_at': invalid}), invalid)
        self.assertTrue(valid('state', fixture('state-eligible') | {'checked_at': '0001-01-01T00:00:00.000001Z'}))

    def test_hash_case_lengths_and_safe_positive_versions(self):
        for digest in ('A' * 64, 'a' * 63, 'a' * 65, 'a' * 64 + '\n', None):
            self.assertFalse(valid('prepare-request', fixture('prepare-request') | {'expected_digest': digest}))
        for version in (0, -1, True, 1.0, MAX_INTEGER + 1, '1'):
            self.assertFalse(valid('prepare-request', fixture('prepare-request') | {'expected_version': version}))

    def test_title_unicode_scalar_limit_preserves_text_and_rejects_nul(self):
        preview = fixture('preparation')
        for title, expected in [('', False), ('😀' * 200, True), ('😀' * 201, False), ('한글\n', True),
                                ('e\u0301', True), ('\0', False), ('\udfff', False)]:
            preview['conversation']['title'] = title
            self.assertEqual(valid('preparation', preview), expected)

    def test_eligibility_discriminants_unique_sorted_blockers(self):
        state = fixture('state-blocked')
        for codes in ([], ['ACTIVE_WORK', 'ACTIVE_WORK'], ['FUTURE_STATUS']):
            self.assertFalse(valid('state', state | {'eligibility': {'status': 'BLOCKED', 'blockers': codes}}))
        unsorted = state | {'eligibility': {'status': 'BLOCKED', 'blockers': ['PENDING_APPROVAL', 'ACTIVE_WORK']}}
        self.assertTrue(valid('state', unsorted))
        self.assertFalse(validate_state(unsorted))
        self.assertFalse(valid('state', state | {'content_digest': 'a' * 64}))
        self.assertFalse(valid('state', fixture('state-eligible') | {'content_digest': None}))

    def test_deleted_state_and_receipt_reject_every_source_field(self):
        for name, schema in [('state-deleted', 'state'), ('receipt', 'receipt')]:
            for field in ('title', 'messages', 'payload_hash', 'content_digest', 'source_counts',
                          'restore_url', 'owner_id', 'request_digest'):
                self.assertFalse(valid(schema, fixture(name) | {field: 'hidden'}))
        preview = fixture('preparation')
        preview['conversation']['authority'] = {'confirmed': True}
        self.assertFalse(valid('preparation', preview))

    def test_preparation_snapshot_counts_and_expiry_cross_checks(self):
        p = fixture('preparation')
        self.assertTrue(validate_preparation(p, fixture('state-eligible')))
        self.assertFalse(validate_preparation(p | {'expires_at': LATER}))
        for field in ('run_results', 'inference_contexts'):
            self.assertFalse(validate_preparation(p | {'source_counts': p['source_counts'] | {field: 2}}))
        for field, value in [('expected_version', 8), ('expected_digest', 'c' * 64)]:
            self.assertFalse(validate_preparation(p | {field: value}, fixture('state-eligible')))
        p['conversation']['id'] = OTHER
        self.assertFalse(validate_preparation(p, fixture('state-eligible')))

    def test_receipt_target_preparation_version_and_time_cross_checks(self):
        value = fixture('outcome-committed')
        pending = {'stage': 'CONFIRM', 'key': key(2), 'conversation_id': TARGET,
                   'preparation_id': fixture('preparation')['id'], 'expected_version': 7,
                   'prepared_at': NOW, 'expires_at': fixture('preparation')['expires_at']}
        self.assertTrue(validate_outcome(value, pending, fixture('preparation')))
        for field, invalid in [('conversation_id', OTHER), ('preparation_id', key(9)),
                               ('deleted_version', 6), ('tombstone_version', 9), ('deleted_at', NOW)]:
            changed = value | {'receipt': value['receipt'] | {field: invalid}}
            if field == 'deleted_at':
                changed['receipt'][field] = '2026-10-10T17:39:59Z'
            self.assertFalse(validate_outcome(changed, pending, fixture('preparation')), field)
        self.assertFalse(validate_outcome(value | {'checked_at': NOW}))

    def test_timestamp_overflow_is_invalid_not_validator_crash(self):
        p = fixture('preparation')
        p.update(prepared_at='9999-12-31T23:59:59Z', expires_at='9999-12-31T23:59:59Z')
        self.assertFalse(validate_preparation(p))

    def test_manifest_sorts_identity_sets_but_preserves_message_ordinals(self):
        result = service()
        t = result.targets[TARGET]
        t['graph']['actions'].append(node(key(11), 'SUCCEEDED'))
        t['source_bindings'].append(key(61))
        original = result.digest(t)
        t['graph']['actions'].reverse()
        t['source_bindings'].reverse()
        self.assertEqual(result.digest(t), original)
        t['content']['messages'] = ['first', 'second']
        ordered = result.digest(t)
        t['content']['messages'].reverse()
        self.assertNotEqual(result.digest(t), ordered)

    def test_preparation_title_and_version_match_issued_backend_bounds(self):
        p, state = fixture('preparation'), fixture('state-eligible')
        p['conversation']['title'] = ''
        self.assertFalse(valid('preparation', p))
        self.assertFalse(validate_preparation(p, state))
        for version, expected in ((MAX_INTEGER - 1, True), (MAX_INTEGER, False)):
            p, state = fixture('preparation'), fixture('state-eligible')
            p['expected_version'] = state['content_version'] = version
            self.assertEqual(valid('preparation', p), expected)
            self.assertEqual(validate_preparation(p, state), expected)
        self.assertTrue(valid('state', state))
        self.assertTrue(valid('prepare-request', fixture('prepare-request') | {'expected_version': MAX_INTEGER}))
        self.assertTrue(valid('confirm-request', fixture('confirm-request') | {'expected_version': MAX_INTEGER}))

    def test_preparation_cannot_predate_observed_state_microsecond_boundary(self):
        p, state = fixture('preparation'), fixture('state-eligible')
        self.assertTrue(validate_preparation(p, state))
        state['checked_at'] = '2026-10-10T17:40:00.000001Z'
        self.assertFalse(validate_preparation(p, state))
        state['checked_at'] = '2026-10-10T17:39:59.999999Z'
        self.assertTrue(validate_preparation(p, state))

    def test_receipt_schema_reserves_one_version_for_tombstone(self):
        receipt = fixture('receipt')
        for changed in (receipt | {'deleted_version': MAX_INTEGER}, receipt | {'tombstone_version': 1}):
            self.assertFalse(valid('receipt', changed))
            self.assertFalse(valid('outcome', fixture('outcome-committed') | {'receipt': changed}))
        boundary = receipt | {'deleted_version': MAX_INTEGER - 1, 'tombstone_version': MAX_INTEGER}
        self.assertTrue(valid('receipt', boundary))
        self.assertTrue(validate_outcome(fixture('outcome-committed') | {'receipt': boundary}))

    def test_invalid_empty_title_blocks_state_without_authority(self):
        result = service()
        result.targets[TARGET]['title'] = ''
        state = result.state('original', TARGET, NOW)
        self.assertEqual(state['eligibility'], {'status': 'BLOCKED', 'blockers': ['INTEGRITY_UNVERIFIED']})
        self.assertIsNone(state['content_digest'])

    def test_active_vocabulary_matches_closed_backend_classification(self):
        self.assertEqual(ACTIVE, {'ACCEPTED', 'PLANNING', 'WAITING_APPROVAL', 'QUEUED', 'RUNNING',
            'CANCEL_REQUESTED', 'EXECUTING', 'STARTED', 'CAPTURED', 'PREPARED', 'DISPATCHED'})
        for status in ACTIVE:
            t = target()
            t['graph']['runs'][0]['status'] = status
            self.assertEqual(eligibility(t)['blockers'], ['ACTIVE_WORK'])
        for status in ('CREATED', 'VERIFYING'):
            t = target()
            t['graph']['runs'][0]['status'] = status
            self.assertEqual(eligibility(t)['blockers'], ['INTEGRITY_UNVERIFIED'])

    def test_blocker_error_precedence_before_changed_snapshot(self):
        for active in ('ACTIVE_WORK', 'UNKNOWN_WORK', 'PENDING_APPROVAL'):
            result = service(local=True)
            p = prepare(result)
            t = result.targets[TARGET]
            t['graph']['reservations'][0]['dispatch_cache_policy'] = None
            if active == 'ACTIVE_WORK':
                t['graph']['runs'][0]['status'] = 'PLANNING'
            elif active == 'UNKNOWN_WORK':
                t['graph']['runs'][0]['status'] = 'UNKNOWN'
            else:
                t['graph']['approvals'] = [node(key(48), 'PENDING')]
            t['content_version'] += 1
            before = copy.deepcopy(result.__dict__)
            with self.assertRaisesRegex(ValueError, '^CONVERSATION_NOT_QUIESCENT$'):
                confirm(result, p)
            self.assertEqual(result.__dict__, before)
            t['integrity_verified'] = False
            with self.assertRaisesRegex(ValueError, '^CONVERSATION_GRAPH_UNVERIFIED$'):
                confirm(result, p)

    def test_consumed_prepare_same_body_and_changed_body_replay_identity(self):
        result = service()
        p = prepare(result)
        original = {'expected_version': p['expected_version'], 'expected_digest': p['expected_digest']}
        issuance_hmac = result.preparations[p['id']]['request_digest']
        confirm(result, p)
        before = copy.deepcopy(result.__dict__)
        self.assertEqual(result.preparations[p['id']]['request_digest'], issuance_hmac)
        for body, code in [(original, 'APPROVAL_CONSUMED'),
                           (original | {'expected_digest': 'f' * 64}, 'IDEMPOTENCY_CONFLICT')]:
            with self.assertRaisesRegex(ValueError, '^' + code + '$'):
                result.prepare('original', TARGET, canonical_bytes(body), key(1), LATER)
        self.assertEqual(result.__dict__, before)

    def test_new_confirm_auth_and_pause_precede_consumed_and_expired_refusals(self):
        for consumed in (False, True):
            result = service()
            p = prepare(result)
            if consumed:
                confirm(result, p)
            after = '2026-10-10T17:46:00Z'
            before = copy.deepcopy(result.__dict__)
            with self.assertRaisesRegex(ValueError, '^REAUTHENTICATION_REQUIRED$'):
                confirm(result, p, request_key=key(7), now=after)
            self.assertEqual(result.__dict__, before)
            result.sessions['original']['reauth_at'] = after
            result.paused.add(OWNER)
            before = copy.deepcopy(result.__dict__)
            with self.assertRaisesRegex(ValueError, '^EXECUTION_PAUSED$'):
                confirm(result, p, request_key=key(7), now=after)
            self.assertEqual(result.__dict__, before)
            result.paused.clear()
            with self.assertRaisesRegex(ValueError, '^' + ('APPROVAL_CONSUMED' if consumed else 'APPROVAL_EXPIRED') + '$'):
                confirm(result, p, request_key=key(7), now=after)

    def test_orphan_or_duplicate_context_is_graph_unverified(self):
        for mode in ('orphan', 'duplicate'):
            t = local_target()
            if mode == 'orphan':
                t['graph']['reservations'].clear()
            else:
                t['graph']['contexts'].append(copy.deepcopy(t['graph']['contexts'][0]) | {'id': key(97)})
            self.assertIn('INTEGRITY_UNVERIFIED', eligibility(t)['blockers'])

    def test_dependent_cap_counts_messages_authorizations_and_source_bindings(self):
        t = target()
        t['graph']['jobs'] = [node(key(96), 'SUCCEEDED')] * 39994
        check_limits(t)  # 2 graph + 2 messages + 1 authorization + 1 source binding
        for kind in ('message', 'authorization', 'binding'):
            changed = copy.deepcopy(t)
            if kind == 'message':
                changed['content']['messages'].append('synthetic')
            elif kind == 'authorization':
                changed['authorizations'].append(copy.deepcopy(t['authorizations'][0]))
            else:
                changed['source_bindings'].append(key(98))
            with self.assertRaisesRegex(ValueError, '^CONVERSATION_DELETION_SCOPE_LIMIT$'):
                check_limits(changed)

    def test_future_preparation_observation_refuses_prepare_replay_and_confirm(self):
        result = service()
        p = prepare(result)
        result.sessions['original']['reauth_at'] = '2026-10-10T17:38:00Z'
        before = copy.deepcopy(result.__dict__)
        body = {'expected_version': p['expected_version'], 'expected_digest': p['expected_digest']}
        early = '2026-10-10T17:39:59.999999Z'
        with self.assertRaisesRegex(ValueError, '^AUTHORITY_NOT_VALID$'):
            result.prepare('original', TARGET, canonical_bytes(body), key(1), early)
        with self.assertRaisesRegex(ValueError, '^AUTHORITY_NOT_VALID$'):
            confirm(result, p, now=early)
        self.assertEqual(result.__dict__, before)
        self.assertEqual(confirm(result, p, now=NOW)['status'], 'COMMITTED')

    def test_pending_lifetime_is_content_free_exact_and_not_optional(self):
        client = SyntheticDeletionClient()
        client.accept_review(fixture('preparation'), fixture('state-eligible'), 0)
        client.consent = True
        client.explicit_confirm(key(2), NOW)
        pending = copy.deepcopy(client.pending)
        self.assertTrue(validate_pending(pending))
        for field in ('prepared_at', 'expires_at'):
            changed = copy.deepcopy(pending)
            del changed[field]
            self.assertFalse(validate_pending(changed))
            self.assertFalse(validate_outcome(fixture('outcome-not-observed'), changed))
        for field, value in [('expected_version', MAX_INTEGER), ('expected_version', True),
                             ('prepared_at', '2026-10-10T17:39:59Z'), ('expires_at', LATER),
                             ('prepared_at', '2026-02-30T17:40:00Z'), ('stage', 'PREPARATION')]:
            self.assertFalse(validate_pending(pending | {field: value}))
        for field in ('title', 'payload_hash', 'expected_digest'):
            self.assertFalse(validate_pending(pending | {field: 'private'}))

    def test_receipt_lifetime_bounds_survive_cleared_review_and_late_recovery(self):
        for deleted_at, accepted in [('2026-10-10T17:39:59.999999Z', False),
                                      (NOW, True), ('2026-10-10T17:44:59.999999Z', True),
                                      ('2026-10-10T17:45:00Z', False)]:
            client = SyntheticDeletionClient()
            client.accept_review(fixture('preparation'), fixture('state-eligible'), 0)
            client.consent = True
            client.explicit_confirm(key(2), NOW)
            client.uncertain()
            self.assertIsNone(client.review)
            outcome = fixture('outcome-committed')
            outcome['receipt']['deleted_at'] = deleted_at
            outcome['checked_at'] = '2026-10-11T17:40:00Z'
            self.assertEqual(client.receive(outcome, 0), accepted)
            self.assertEqual(client.state, 'COMMITTED' if accepted else 'OUTCOME_UNKNOWN')
            self.assertEqual(client.post_count, 1)

    def test_competing_deleted_target_precedes_revoked_and_expired_preview(self):
        result = service()
        p, competing = prepare(result), prepare(result, request_key=key(3))
        confirm(result, p)
        after = '2026-10-10T17:46:00Z'
        result.sessions['original']['reauth_at'] = after
        before = copy.deepcopy(result.__dict__)
        with self.assertRaisesRegex(ValueError, '^CONVERSATION_DELETED$'):
            confirm(result, competing, request_key=key(4), now=after)
        self.assertEqual(result.__dict__, before)

    def test_committed_reads_refuse_observation_before_deletion_without_mutation(self):
        result = service()
        p = prepare(result)
        committed = confirm(result, p)
        before = copy.deepcopy(result.__dict__)
        for read in (lambda: confirm(result, p, now=NOW),
                     lambda: result.reconcile('original', key(2), NOW),
                     lambda: result.state('replacement', TARGET, NOW)):
            with self.assertRaisesRegex(ValueError, '^AUTHORITY_NOT_VALID$'):
                read()
            self.assertEqual(result.__dict__, before)
        for read in (lambda: confirm(result, p, now='2026-10-11T17:40:00Z'),
                     lambda: result.reconcile('original', key(2), '2026-10-11T17:40:00Z')):
            self.assertEqual(read()['receipt'], committed['receipt'])
            self.assertEqual(result.__dict__, before)

    def test_closed_status_policy_and_counts(self):
        p = fixture('preparation')
        self.assertFalse(valid('preparation', p | {'scope_policy': 'ALL_DATA'}))
        self.assertFalse(valid('preparation', p | {'status': 'AUTHORIZED'}))
        self.assertFalse(valid('outcome', fixture('outcome-committed') | {'status': 'QUEUED'}))
        for field in p['source_counts']:
            for value in (-1, True, '1', 1.5, MAX_INTEGER + 1):
                self.assertFalse(valid('preparation', p | {'source_counts': p['source_counts'] | {field: value}}))

    def test_empty_conversation_is_eligible_without_invented_sources(self):
        result = service()
        self.assertEqual(result.state('original', OTHER, NOW)['eligibility']['status'], 'ELIGIBLE')
        p = prepare(result, OTHER)
        self.assertEqual(p['source_counts'], dict.fromkeys(p['source_counts'], 0))
        self.assertEqual(confirm(result, p)['status'], 'COMMITTED')

    def test_preparation_is_inert_and_never_persists_title(self):
        result = service()
        before = copy.deepcopy(result.targets)
        prepare(result)
        self.assertEqual(before, result.targets)
        self.assertEqual(result.audit, [])  # No deletion audit for inert preparation.
        self.assertEqual(len(result.preparation_audit), 1)
        self.assertNotIn(SENTINEL, repr(result.preparations))
        self.assertNotIn('conversation', next(iter(result.preparations.values())))

    def test_prepare_replay_fixed_id_expiry_and_no_duplicate_authority(self):
        result = service()
        p = prepare(result)
        self.assertEqual(prepare(result, now=LATER), p)
        self.assertEqual(len(result.preparations), 1)
        self.assertEqual(len(result.preparation_audit), 1)
        body = {'expected_version': p['expected_version'] + 1, 'expected_digest': p['expected_digest']}
        with self.assertRaisesRegex(ValueError, 'IDEMPOTENCY_CONFLICT'):
            result.prepare('original', TARGET, canonical_bytes(body), key(1), NOW)

    def test_exact_target_atomic_redaction_preserves_scope_and_keyed_accounting(self):
        result = service(local=True)
        before_other, before_memory, before_usage = copy.deepcopy((result.targets[OTHER], result.memories, result.usage))
        before_reservation = copy.deepcopy(result.targets[TARGET]['graph']['reservations'])
        before_epoch = copy.deepcopy(result.epochs)
        before_states = {k: [n['status'] for n in rows] for k, rows in result.targets[TARGET]['graph'].items() if k != 'contexts'}
        p = prepare(result)
        outcome = confirm(result, p)
        self.assertTrue(validate_outcome(outcome, preparation=p))
        self.assertEqual((result.targets[OTHER], result.memories, result.usage), (before_other, before_memory, before_usage))
        self.assertEqual(result.targets[TARGET]['graph']['reservations'], before_reservation)
        self.assertEqual(result.epochs, before_epoch)
        self.assertNotIn(SENTINEL, repr(result.targets[TARGET]))
        self.assertNotIn('raw-synthetic-source-hash', repr(result.targets[TARGET]))
        self.assertEqual(result.targets[TARGET]['source_bindings'], [])
        for kind, states in before_states.items():
            self.assertEqual([n['status'] for n in result.targets[TARGET]['graph'][kind]], states)
        self.assertEqual(result.targets[TARGET]['graph']['contexts'][0]['status'], 'INVALIDATED')
        self.assertEqual(len(result.audit), 1)

    def test_authorization_shell_retains_lineage_but_is_permanently_revoked(self):
        result = service()
        shell = copy.deepcopy(result.targets[TARGET]['authorizations'][0])
        confirm(result, prepare(result))
        after = result.targets[TARGET]['authorizations'][0]
        for field in ('id', 'owner_id', 'action_id', 'approval_id', 'security_epoch', 'valid_until', 'max_executions'):
            self.assertEqual(after[field], shell[field])
        self.assertTrue(after['revoked'])
        self.assertIsNone(after['payload_hash'])
        self.assertEqual(after['source_deleted_at'], LATER)

    def test_model_staged_failure_leaves_all_state_unchanged(self):
        result = service()
        p = prepare(result)
        before = copy.deepcopy(result.__dict__)
        with self.assertRaisesRegex(ValueError, 'SYNTHETIC_ROLLBACK'):
            confirm(result, p, fail_after_stage=True)
        self.assertEqual(result.__dict__, before)

    def test_same_confirm_replay_is_read_only_after_pause_and_auth_age(self):
        result = service()
        p = prepare(result)
        first = confirm(result, p)
        result.paused.add(OWNER)
        before = copy.deepcopy(result.__dict__)
        replay = confirm(result, p, now='2026-10-10T18:00:00Z')
        self.assertEqual(first['receipt'], replay['receipt'])
        self.assertEqual(result.__dict__, before)

    def test_consumed_and_competing_preparation_error_precedence(self):
        result = service()
        p, other = prepare(result), prepare(result, request_key=key(3))
        confirm(result, p)
        with self.assertRaisesRegex(ValueError, '^APPROVAL_CONSUMED$'):
            confirm(result, p, request_key=key(4))
        with self.assertRaisesRegex(ValueError, '^CONVERSATION_DELETED$'):
            confirm(result, other, request_key=key(5))
        for entry in result.preparations.values():
            self.assertIsNone(entry['request'])
            self.assertIsNone(entry['payload_hash'])
            self.assertRegex(entry['request_digest'], r'^[0-9a-f]{64}$')
        self.assertEqual((len(result.ledger), len(result.receipts), len(result.audit)), (1, 1, 1))

    def test_changed_confirm_body_or_preparation_under_used_key_conflicts(self):
        result = service()
        p = prepare(result)
        confirm(result, p)
        for field, value in [('payload_hash', 'f' * 64), ('expected_digest', 'e' * 64), ('expected_version', 2)]:
            with self.assertRaisesRegex(ValueError, 'IDEMPOTENCY_CONFLICT'):
                result.confirm('original', p['id'], canonical_bytes(confirm_body(p) | {field: value}), key(2), LATER)
        with self.assertRaisesRegex(ValueError, 'IDEMPOTENCY_CONFLICT'):
            result.confirm('original', key(88), canonical_bytes(confirm_body(p)), key(2), LATER)

    def test_prepare_and_confirm_keys_are_distinct(self):
        result = service()
        with self.assertRaisesRegex(ValueError, 'IDEMPOTENCY_CONFLICT'):
            confirm(result, prepare(result), request_key=key(1))

    def test_foreign_target_indistinguishable_and_receipt_lookup_read_only(self):
        result = service()
        p = prepare(result)
        confirm(result, p)
        before = copy.deepcopy(result.__dict__)
        for identifier in (TARGET, key(99)):
            with self.assertRaisesRegex(ValueError, '^NOT_FOUND$'):
                result.state('foreign', identifier, LATER)
        self.assertEqual(result.reconcile('foreign', key(2), LATER)['status'], 'NOT_OBSERVED')
        self.assertEqual(result.reconcile('original', key(99), LATER)['status'], 'NOT_OBSERVED')
        self.assertEqual(result.reconcile('original', key(2), LATER)['status'], 'COMMITTED')
        self.assertEqual(result.__dict__, before)

    def test_new_live_session_can_read_deleted_state_not_old_receipt(self):
        result = service()
        p = prepare(result)
        confirm(result, p)
        with self.assertRaisesRegex(ValueError, 'AUTHORITY_NOT_VALID'):
            result.reconcile('replacement', key(2), LATER)
        state = result.state('replacement', TARGET, LATER)
        self.assertTrue(validate_state(state, TARGET))
        self.assertEqual(state['status'], 'DELETED')

    def test_session_epoch_revocation_and_rotation_never_rebind_authority(self):
        for mode in ('session', 'epoch', 'rotation'):
            result = service()
            p = prepare(result)
            label = 'original'
            if mode == 'session':
                result.sessions[label]['live'] = False
            elif mode == 'epoch':
                result.epochs[OWNER] += 1
            else:
                label = 'replacement'
            with self.assertRaisesRegex(ValueError, 'SESSION_REVOKED|AUTHORITY_NOT_VALID'):
                confirm(result, p, label=label)
            self.assertEqual(result.ledger, {})

    def test_recent_auth_future_stale_exact_boundary_and_expiry(self):
        for auth_time in ('2026-10-10T17:40:01Z', '2026-10-10T17:34:59Z'):
            result = service()
            result.sessions['original']['reauth_at'] = auth_time
            with self.assertRaisesRegex(ValueError, 'REAUTHENTICATION_REQUIRED'):
                prepare(result)
        result = service()
        result.sessions['original']['reauth_at'] = '2026-10-10T17:35:00Z'
        prepare(result)
        result = service()
        p = prepare(result)
        with self.assertRaisesRegex(ValueError, 'APPROVAL_EXPIRED'):
            confirm(result, p, now=p['expires_at'])
        self.assertEqual(result.ledger, {})

    def test_expired_recent_auth_at_confirm_even_with_unexpired_preview(self):
        result = service()
        result.sessions['original']['reauth_at'] = '2026-10-10T17:36:00Z'
        p = prepare(result)
        with self.assertRaisesRegex(ValueError, 'REAUTHENTICATION_REQUIRED'):
            confirm(result, p, now='2026-10-10T17:41:01Z')

    def test_pause_blocks_new_prepare_and_confirm(self):
        result = service()
        p = prepare(result)
        result.paused.add(OWNER)
        with self.assertRaisesRegex(ValueError, 'EXECUTION_PAUSED'):
            prepare(result, request_key=key(9))
        with self.assertRaisesRegex(ValueError, 'EXECUTION_PAUSED'):
            confirm(result, p)

    def test_content_change_and_aba_reversal_invalidate_preview(self):
        for reverse in (False, True):
            result = service()
            p = prepare(result)
            result.targets[TARGET]['title'] = 'new source'
            result.targets[TARGET]['content_version'] += 1
            if reverse:
                result.targets[TARGET]['title'] = SENTINEL
                result.targets[TARGET]['content_version'] += 1
            with self.assertRaisesRegex(ValueError, 'PRECONDITION_CHANGED'):
                confirm(result, p)
            self.assertEqual(result.ledger, {})

    def test_manifest_changes_even_if_bad_writer_skips_version(self):
        result = service()
        p = prepare(result)
        result.targets[TARGET]['content']['messages'].append('later synthetic content')
        with self.assertRaisesRegex(ValueError, 'PRECONDITION_CHANGED'):
            confirm(result, p)

    def test_observation_time_does_not_change_digest_and_domains_are_separated(self):
        result = service()
        self.assertEqual(result.state('original', TARGET, NOW)['content_digest'], result.state('original', TARGET, LATER)['content_digest'])
        digests = {keyed(owner, domain, {'x': 1}) for owner in (OWNER, FOREIGN) for domain in ('manifest', 'prepare', 'confirm')}
        self.assertEqual(len(digests), 6)

    def test_all_allowlisted_terminal_states_and_unknown_blocks_every_table(self):
        for kind, states in DOMAINS.items():
            for status in states:
                t = local_target() if kind in ('contexts', 'reservations') else target()
                if kind not in ('runs', 'contexts', 'reservations'):
                    t['graph'][kind] = [node(key(11), status)]
                else:
                    t['graph'][kind][0]['status'] = status
                self.assertEqual(eligibility(t)['status'], 'ELIGIBLE', (kind, status))
            for status, blocker in [('UNKNOWN', 'UNKNOWN_WORK'), ('FUTURE_STATE', 'INTEGRITY_UNVERIFIED')]:
                t = local_target()
                t['graph'][kind] = [node(key(12), status)]
                self.assertIn(blocker, eligibility(t)['blockers'], kind)

    def test_waiting_input_with_live_dependents_and_expired_pending_approval_blocks(self):
        t = target()
        t['graph']['runs'][0]['status'] = 'WAITING_INPUT'
        self.assertEqual(eligibility(t)['status'], 'ELIGIBLE')
        t['graph']['jobs'] = [node(key(12), 'RUNNING', lease_until='2026-01-01T00:00:00Z')]
        self.assertIn('ACTIVE_WORK', eligibility(t)['blockers'])
        t['graph']['jobs'] = []
        t['graph']['approvals'] = [node(key(13), 'PENDING', expires_at='2026-01-01T00:00:00Z')]
        self.assertIn('PENDING_APPROVAL', eligibility(t)['blockers'])

    def test_local_missing_marker_and_cancelled_before_dispatch_have_no_exception(self):
        for status in ('SUCCEEDED', 'FAILED', 'CANCELLED'):
            t = local_target()
            row = t['graph']['reservations'][0]
            row.update(status=status, dispatch_context_id=None, dispatch_cache_policy=None)
            if status == 'CANCELLED':
                row['started_at'] = None
            self.assertIn('UNSUPPORTED_LEGACY_INFERENCE', eligibility(t)['blockers'])

    def test_all_local_provenance_predicates_fail_closed(self):
        mutations = [lambda t: t['graph']['reservations'][0].update(accounting_version=0),
                     lambda t: t['graph']['reservations'][0].update(started_at=None),
                     lambda t: t['graph']['reservations'][0].update(dispatch_context_id=key(9)),
                     lambda t: t['graph']['reservations'][0].update(dispatch_cache_policy='LEGACY_CACHED'),
                     lambda t: t['graph']['reservations'][0].update(body_hash='f' * 64),
                     lambda t: t['graph']['runs'][0].update(context_history_eligible=False),
                     lambda t: t['graph']['contexts'].clear(),
                     lambda t: t['graph']['contexts'][0].update(conversation_id=OTHER)]
        for index, mutate in enumerate(mutations):
            t = local_target()
            mutate(t)
            expected = 'UNSUPPORTED_LEGACY_INFERENCE' if index in (0, 1, 3) else 'INTEGRITY_UNVERIFIED'
            self.assertIn(expected, eligibility(t)['blockers'])

    def test_provenance_is_rechecked_at_confirm(self):
        result = service(local=True)
        p = prepare(result)
        result.targets[TARGET]['graph']['reservations'][0]['dispatch_cache_policy'] = None
        with self.assertRaisesRegex(ValueError, 'CONVERSATION_DELETION_UNSUPPORTED'):
            confirm(result, p)
        self.assertEqual(result.ledger, {})

    def test_dispatch_marker_only_supported_atomic_start_then_immutable(self):
        t = local_target()
        after = copy.deepcopy(t['graph']['reservations'][0])
        after['status'] = 'STARTED'
        before = after | {'status': 'QUEUED', 'started_at': None, 'dispatch_context_id': None, 'dispatch_cache_policy': None}
        context = t['graph']['contexts'][0] | {'status': 'DISPATCHED'}
        self.assertTrue(dispatch_transition(before, after, context))
        for status in ('SUCCEEDED', 'FAILED', 'CANCELLED'):
            self.assertFalse(dispatch_transition(before | {'status': status}, after, context))
        for field, value in [('body_hash', 'f' * 64), ('dispatch_context_id', key(9)), ('dispatch_cache_policy', None), ('started_at', None)]:
            self.assertFalse(dispatch_transition(after, after | {field: value}, context))
        self.assertFalse(dispatch_transition(before, after, context | {'conversation_id': OTHER}))
        self.assertFalse(dispatch_transition(before, after, context | {'status': 'PREPARED'}))

    def test_cross_owner_run_target_graph_and_duplicate_ids_refuse(self):
        for change in ('owner', 'target', 'run', 'duplicate', 'future_store'):
            t = local_target()
            if change == 'owner':
                t['graph']['contexts'][0]['owner_id'] = FOREIGN
            elif change == 'target':
                t['graph']['contexts'][0]['conversation_id'] = OTHER
            elif change == 'run':
                t['graph']['actions'][0]['run_id'] = key(44)
            elif change == 'duplicate':
                t['graph']['actions'].append(copy.deepcopy(t['graph']['actions'][0]))
            else:
                t['graph']['future_content'] = []
            self.assertIn('INTEGRITY_UNVERIFIED', eligibility(t)['blockers'])

    def test_message_run_action_row_and_byte_caps_plus_one(self):
        for kind, cap in [('messages', 2000), ('runs', 1000), ('actions', 1000)]:
            t = target()
            if kind == 'messages':
                t['content']['messages'] = [''] * cap
                check_limits(t)
                t['content']['messages'].append('')
            else:
                t['graph'][kind] = [node(key(44), 'SUCCEEDED')] * cap
                check_limits(t)
                t['graph'][kind].append(node(key(45), 'SUCCEEDED'))
            with self.assertRaisesRegex(ValueError, 'CONVERSATION_DELETION_SCOPE_LIMIT'):
                check_limits(t)
        t = target()
        t['graph']['jobs'] = [node(key(46), 'SUCCEEDED')] * (40000 - 6)
        check_limits(t)
        t['graph']['jobs'].append(node(key(47), 'SUCCEEDED'))
        with self.assertRaisesRegex(ValueError, 'CONVERSATION_DELETION_SCOPE_LIMIT'):
            check_limits(t)
        t = target()
        t['title'] = ''
        t['content'] = {'messages': ['x' * (16 * 1024 * 1024)], 'run_results': [], 'action_payloads': []}
        check_limits(t)
        t['title'] = 'a'
        with self.assertRaisesRegex(ValueError, 'CONVERSATION_DELETION_SCOPE_LIMIT'):
            check_limits(t)

    def test_deadline_and_version_exhaustion_before_changes(self):
        result = service()
        p = prepare(result)
        before = copy.deepcopy(result.__dict__)
        with self.assertRaisesRegex(ValueError, 'CONVERSATION_DELETION_TIME_LIMIT'):
            confirm(result, p, elapsed_ms=5000)
        self.assertEqual(result.__dict__, before)
        result = service()
        result.targets[TARGET]['content_version'] = MAX_INTEGER
        with self.assertRaisesRegex(ValueError, 'VERSION_EXHAUSTED'):
            prepare(result)
        self.assertEqual(result.preparations, {})

    def test_100_unconsumed_cap_and_no_prefix_or_younger_eviction(self):
        result = service()
        for i in range(100):
            prepare(result, request_key=key(100 + i))
        self.assertEqual(len(result.preparations), 100)
        before = copy.deepcopy(result.preparations)
        with self.assertRaisesRegex(ValueError, 'CONVERSATION_DELETION_PREPARATION_LIMIT'):
            prepare(result, request_key=key(999))
        self.assertEqual(result.preparations, before)

    def test_consumed_identity_excluded_cap_and_never_cleaned(self):
        result = service()
        p = prepare(result)
        confirm(result, p)
        consumed = copy.deepcopy(result.preparations[p['id']])
        # Synthetic permanent-identity population, not a production DB fixture.
        for i in range(150):
            result.preparations[key(1000 + i)] = copy.deepcopy(consumed) | {'id': key(1000 + i)}
        prepare(result, OTHER, request_key=key(3))
        self.assertEqual(sum(p['status'] == 'CONSUMED' for p in result.preparations.values()), 151)
        tomorrow = '2026-10-11T17:40:01Z'
        result.sessions['original']['reauth_at'] = tomorrow
        before_receipts, before_ledger = copy.deepcopy((result.receipts, result.ledger))
        prepare(result, OTHER, request_key=key(4), now=tomorrow)
        self.assertEqual(sum(p['status'] == 'CONSUMED' for p in result.preparations.values()), 151)
        self.assertEqual((result.receipts, result.ledger), (before_receipts, before_ledger))
        self.assertEqual(sum(p['status'] != 'CONSUMED' for p in result.preparations.values()), 1)

    def test_unconsumed_cleanup_is_24_hour_eligibility_not_expiry(self):
        result = service()
        p = prepare(result)
        after_expiry = '2026-10-10T17:46:00Z'
        result.sessions['original']['reauth_at'] = after_expiry
        prepare(result, request_key=key(3), now=after_expiry)
        self.assertIn(p['id'], result.preparations)
        tomorrow = '2026-10-11T17:40:00Z'
        result.sessions['original']['reauth_at'] = tomorrow
        prepare(result, request_key=key(4), now=tomorrow)
        self.assertIn(p['id'], result.preparations)  # exact boundary is retained
        after_boundary = '2026-10-11T17:40:00.000001Z'
        prepare(result, request_key=key(5), now=after_boundary)
        self.assertNotIn(p['id'], result.preparations)

    def test_target_tombstone_blocks_every_modeled_boundary_after_restart_copy(self):
        result = service()
        confirm(result, prepare(result))
        reopened = copy.deepcopy(result)  # logical retention, not actual process/DB restart evidence
        for boundary in READ_BARRIERS + WRITE_BARRIERS:
            with self.assertRaisesRegex(ValueError, 'CONVERSATION_DELETED'):
                reopened.content_boundary('original', TARGET, boundary, LATER)
            self.assertEqual(reopened.content_boundary('original', OTHER, boundary, LATER)['id'], OTHER)

    def test_client_unchecked_consent_cancel_and_double_submit(self):
        client = SyntheticDeletionClient()
        self.assertTrue(client.accept_review(fixture('preparation'), fixture('state-eligible'), 0))
        self.assertIsNone(client.explicit_confirm(key(1), NOW))
        self.assertTrue(client.invalidate())
        self.assertEqual(client.post_count, 0)
        self.assertFalse(client.accept_review(fixture('preparation'), fixture('state-eligible'), 0))
        self.assertTrue(client.accept_review(fixture('preparation'), fixture('state-eligible'), 1))
        client.consent = True
        self.assertIsNotNone(client.explicit_confirm(key(2), NOW))
        self.assertIsNone(client.explicit_confirm(key(3), NOW))
        self.assertFalse(client.invalidate())
        self.assertEqual(client.post_count, 1)

    def test_lost_preparation_can_be_abandoned_without_confirm_or_retry(self):
        result = service()
        prepare(result)  # response is intentionally never accepted into client review
        client = SyntheticDeletionClient()
        client.state = 'PREPARING'
        self.assertTrue(client.invalidate())
        self.assertEqual(client.post_count, 0)
        self.assertIsNone(client.pending)
        self.assertEqual(result.ledger, {})
        self.assertEqual(result.targets[TARGET]['title'], SENTINEL)

    def test_client_expiry_disables_destructive_dispatch(self):
        client = SyntheticDeletionClient()
        p = fixture('preparation')
        client.accept_review(p, fixture('state-eligible'), 0)
        client.consent = True
        self.assertIsNone(client.explicit_confirm(key(2), p['expires_at']))
        self.assertEqual(client.post_count, 0)

    def test_unknown_confirm_clears_content_keeps_only_safe_pending_no_retry(self):
        client = SyntheticDeletionClient()
        client.accept_review(fixture('preparation'), fixture('state-eligible'), 0)
        client.consent = True
        client.explicit_confirm(key(2), NOW)
        client.uncertain()
        self.assertIsNone(client.private_display)
        self.assertIsNone(client.review)
        self.assertEqual(set(client.pending), {'stage', 'key', 'conversation_id', 'preparation_id', 'expected_version', 'prepared_at', 'expires_at'})
        self.assertTrue(client.writes_blocked)
        for _ in range(4):
            self.assertFalse(client.receive(fixture('outcome-not-observed'), 0))
            self.assertIsNone(client.explicit_confirm(key(3), LATER))
        self.assertEqual(client.post_count, 1)
        self.assertEqual(client.state, 'OUTCOME_UNKNOWN')

    def test_malformed_mismatched_success_is_unknown_not_safe_failure(self):
        for value in ({}, fixture('outcome-committed') | {'title': SENTINEL},
                      fixture('outcome-committed') | {'receipt': fixture('receipt') | {'conversation_id': OTHER}}):
            client = SyntheticDeletionClient()
            client.accept_review(fixture('preparation'), fixture('state-eligible'), 0)
            client.consent = True
            client.explicit_confirm(key(2), NOW)
            self.assertFalse(client.receive(value, 0))
            self.assertEqual(client.state, 'OUTCOME_UNKNOWN')
            self.assertTrue(client.writes_blocked)

    def test_matching_committed_receipt_clears_target_and_fences_late_responses(self):
        client = SyntheticDeletionClient()
        client.accept_review(fixture('preparation'), fixture('state-eligible'), 0)
        client.consent = True
        client.explicit_confirm(key(2), NOW)
        client.uncertain()
        self.assertTrue(client.receive(fixture('outcome-committed'), 0))
        self.assertEqual(client.state, 'COMMITTED')
        self.assertIsNone(client.pending)
        self.assertFalse(client.accept_review(fixture('preparation'), fixture('state-eligible'), 0))

    def test_session_generation_fences_prior_response_without_replay(self):
        client = SyntheticDeletionClient()
        client.accept_review(fixture('preparation'), fixture('state-eligible'), 0)
        client.consent = True
        client.explicit_confirm(key(2), NOW)
        client.replace_session()
        self.assertFalse(client.receive(fixture('outcome-committed'), 0))
        self.assertEqual(client.state, 'IDLE')
        self.assertEqual(client.post_count, 1)
        self.assertIsNone(client.pending)

    def test_kst_rollover_preserves_utc_wire(self):
        self.assertEqual(kst(NOW), '2026-10-11 02:40:00 KST')
        self.assertEqual(fixture('preparation')['prepared_at'], NOW)
        self.assertEqual((LIMITS['display_timezone'], LIMITS['wire_timezone']), ('Asia/Seoul', 'UTC'))
        self.assertEqual(LIMITS['policy_version'], 'conversation-source-v1-r7')


class FeedbackDeletionMetadataOracleTest(unittest.TestCase):
    def complete_local_cancel(self):
        value = target()
        fid, run, reservation, request, context_id, study, answer, attempt, question = [key(i) for i in range(700, 709)]
        f = dict(id=fid, owner_id=OWNER, conversation_id=TARGET, study_session_id=study,
                 lesson_run_id=RUN, lesson_run_version=1, question_id=question, input_id=answer, attempt_id=attempt,
                 run_id=run, reservation_id=reservation, request_id=request, context_id=context_id,
                 project_id=None, project_version=None, initiating_session_hash='original', security_epoch=1,
                 admitted_study_version=3, admission_conversation_version=10, context_epoch=1, history_epoch=1,
                 profile_version=1, timezone='UTC', admission_xid='100', phase_xid='101', version=2, state='REVOKED',
                 output_authority='REVOKED', created_at=NOW, updated_at=LATER, closed_at=LATER,
                 source_binding_ref='x' * 142, result_ref=None, source_deleted_at=None)
        value['graph']['runs'].append(node(run, 'CANCELLED', run_id=run, version=2, history_epoch=1,
            context_history_eligible=False, result_ref=None, created_at=NOW, cancel_requested_at=LATER, control_admission_xid='100'))
        value['graph']['reservations'] = [node(reservation, 'CANCELLED', run_id=run, request_id=request,
            purpose='STUDY_FEEDBACK', accounting_version=1, control_revision=1, security_epoch=1,
            model_id='local-mlx-v1', model_manifest_hash='b' * 64, budget_revision=1, max_compute_ms=60000,
            reserved_output_tokens=512, body_hash='a' * 64, created_at=NOW, started_at=None,
            completed_at=LATER, cancel_requested_at=LATER, dispatch_context_id=None, dispatch_cache_policy=None)]
        value['graph']['contexts'] = [node(context_id, 'INVALIDATED', run_id=run, session_hash='original',
            security_epoch=1, context_epoch=1, history_epoch=1, project_id=None, project_version=None,
            profile_version=None, control_admission_xid='100', created_at=NOW, invalidated_at=LATER, history=[], memories=[])]
        value['graph']['study_sessions'] = [node(study, 'OPEN', run_id=RUN, lifecycle='OPEN', version=3,
            lesson_run_version=1, project_id=None, lesson_binding_ref='synthetic-lesson-binding')]
        value['graph']['study_answers'] = [node(answer, 'RECORDED', run_id=RUN, study_session_id=study,
            project_id=None, question_id=question, attempt_id=attempt, text_ref='synthetic-answer', source_binding_ref='synthetic-binding'),
            node(key(709), 'RECORDED', run_id=RUN, study_session_id=study, project_id=None,
            question_id=key(710), attempt_id=key(711), text_ref='synthetic-other-answer', source_binding_ref='synthetic-other-binding')]
        value['graph']['runs'][0].update(version=1, result_ref='synthetic-sealed-lesson', created_at=NOW, cancel_requested_at=None)
        lesson_q = node(key(780), 'SUCCEEDED', run_id=RUN, request_id=key(781), purpose='STUDY_LESSON',
            accounting_version=1, control_revision=0, security_epoch=1, model_id='local-mlx-v1',
            model_manifest_hash='9' * 64, budget_revision=1, max_compute_ms=60000, reserved_output_tokens=512,
            body_hash='8' * 64, created_at=NOW, started_at=NOW, completed_at=NOW, cancel_requested_at=None,
            dispatch_context_id=key(782), dispatch_cache_policy=LIMITS['dispatch_cache_policy'])
        value['graph']['reservations'].append(lesson_q)
        value['graph']['contexts'].append(node(key(782), 'SETTLED', run_id=RUN, project_id=None,
            security_epoch=1, created_at=NOW, invalidated_at=None, request_digest='8' * 64))
        lesson_usage = {k: lesson_q[k] for k in ('owner_id', 'run_id', 'request_id', 'model_id', 'model_manifest_hash', 'budget_revision', 'max_compute_ms', 'reserved_output_tokens')}
        lesson_usage.update(reservation_id=lesson_q['id'], request_digest=lesson_q['body_hash'], reserved_at=NOW,
            recorded_at=NOW, receipt_kind='MEASURED', response_kind='LESSON', error_code=None,
            input_tokens=2, output_tokens=3, compute_ms=4, peak_memory_bytes=5)
        value['graph']['study_sessions'][0]['lesson_receipt'] = lesson_usage
        value['graph']['study_sessions'][0].update(question_1_id=question, question_2_id=key(710), created_at=NOW, updated_at=NOW, source_deleted_at=None)
        value['graph']['study_mutations'] = []
        for version in (1, 2, 3):
            event = node(key(760 + version), 'COMMITTED', run_id=RUN, study_session_id=study,
                lesson_run_id=RUN, project_id=None, resulting_version=version, prior_version=None if version == 1 else version - 1,
                operation='START' if version == 1 else 'ANSWER', request_digest='f' * 64, committed_at=NOW,
                prior_conversation_version=6 + version, resulting_conversation_version=7 + version)
            if version > 1:
                original = value['graph']['study_answers'][version - 2]
                original.update(expected_study_version=version - 1, resulting_study_version=version,
                    observed_conversation_version=6 + version, session_hash='original', security_epoch=1, created_at=NOW, content_deleted_at=None)
                event.update(input_id=original['id'], question_id=original['question_id'], attempt_id=original['attempt_id'],
                    session_hash='original', security_epoch=1)
            value['graph']['study_mutations'].append(event)
        value['graph']['feedback_requests'] = [f]
        value['graph']['feedback_control_slots'] = [node(reservation, 'TERMINAL', run_id=run, reservation_id=reservation,
            request_id=request, context_id=context_id, feedback_id=fid, revision=1, purpose='STUDY_FEEDBACK',
            security_epoch=1, origin_binding='c' * 64, created_at=NOW, dispatch_commitment=None, bound_at=None,
            decision='OWNER_CANCEL', decision_at=LATER, terminal_kind='UNSTARTED_CORE', generation_id=None,
            ledger_instance_id=None, terminal_ordinal=None, recorded_at=LATER)]
        events = []
        for version, operation, state, authority, before, after, stamp_at in [
                (1, 'REQUEST', 'QUEUED', 'ACTIVE', 10, 14, NOW),
                (2, 'REVOKE', 'REVOKED', 'REVOKED', 14, 18, LATER)]:
            e = {k: f[k] for k in ('owner_id', 'conversation_id', 'study_session_id', 'lesson_run_id', 'question_id', 'input_id', 'attempt_id', 'project_id')}
            e.update(id=key(720 + version), feedback_id=fid, run_id=run, version=version,
                     operation=operation, prior_version=None if version == 1 else 1, resulting_state=state,
                     output_authority=authority, idempotency_key=key(730 + version), operation_session_hash='original',
                     operation_security_epoch=1, request_digest='d' * 64, reason_code=None if version == 1 else 'OWNER_REVOKE',
                     prior_conversation_version=before, resulting_conversation_version=after, committed_at=stamp_at,
                     phase_xid=str(99 + version), invalidated_contexts=0 if version == 1 else 1)
            events.append(e)
        value['graph']['feedback_events'] = events
        return value

    def complete_producer(self, state='ACCEPTED', kind='COMPLETED'):
        value = self.complete_local_cancel(); f = value['graph']['feedback_requests'][0]
        middle = '2026-10-10T17:40:30Z'
        f.update(version=3, state=state, output_authority='REVOKED' if state == 'REVOKED' else 'CLOSED',
                 phase_xid='102', result_ref='synthetic-sealed-feedback' if state == 'ACCEPTED' else None)
        run = value['graph']['runs'][-1]; q = value['graph']['reservations'][0]; x = value['graph']['contexts'][0]
        terminal = {'ACCEPTED': 'SUCCEEDED', 'FAILED': 'FAILED', 'REVOKED': 'UNKNOWN'}[state]
        run.update(version=3, status=terminal, cancel_requested_at=LATER if state == 'REVOKED' else None)
        q.update(status=terminal, started_at=middle, dispatch_context_id=x['id'],
                 dispatch_cache_policy=LIMITS['dispatch_cache_policy'], cancel_requested_at=run['cancel_requested_at'])
        x.update(profile_version=1, status='INVALIDATED' if state == 'REVOKED' else 'SETTLED', invalidated_at=LATER if state == 'REVOKED' else None)
        slot = value['graph']['feedback_control_slots'][0]
        slot.update(dispatch_commitment='e' * 64, bound_at=middle, terminal_kind=kind,
                    ledger_instance_id=key(740), generation_id=None if kind == 'UNSTARTED_FENCED' else key(741), terminal_ordinal=1,
                    decision='OWNER_CANCEL' if state == 'REVOKED' else None, decision_at=LATER if state == 'REVOKED' else None)
        first = value['graph']['feedback_events'][0]; last = copy.deepcopy(value['graph']['feedback_events'][1])
        dispatched = copy.deepcopy(first); dispatched.update(id=key(750), version=2, operation='DISPATCH', prior_version=1,
            resulting_state='RUNNING', output_authority='ACTIVE', idempotency_key=None, operation_session_hash=None,
            operation_security_epoch=None, request_digest=None, prior_conversation_version=14,
            resulting_conversation_version=19, committed_at=middle, phase_xid='101', invalidated_contexts=0)
        last.update(id=key(751), version=3, operation={'ACCEPTED': 'ACCEPT', 'FAILED': 'FAIL', 'REVOKED': 'REVOKE'}[state],
            prior_version=2, resulting_state=state, output_authority=f['output_authority'],
            prior_conversation_version=19, resulting_conversation_version=23, phase_xid='102',
            reason_code={'ACCEPTED': None, 'FAILED': 'MODEL_ERROR', 'REVOKED': 'OWNER_REVOKE'}[state],
            invalidated_contexts=1 if state == 'REVOKED' else 0)
        if state != 'REVOKED':
            last.update(idempotency_key=None, operation_session_hash=None, operation_security_epoch=None, request_digest=None)
            usage = {k: q[k] for k in ('owner_id', 'run_id', 'request_id', 'model_id', 'model_manifest_hash', 'budget_revision', 'max_compute_ms', 'reserved_output_tokens')}
            usage.update(reservation_id=q['id'], request_digest=q['body_hash'], reserved_at=q['created_at'], recorded_at=LATER,
                receipt_kind='MEASURED' if state == 'ACCEPTED' else 'ERROR', response_kind='FEEDBACK' if state == 'ACCEPTED' else 'ERROR',
                error_code=None if state == 'ACCEPTED' else 'MODEL_UNAVAILABLE', input_tokens=2, output_tokens=3, compute_ms=4, peak_memory_bytes=5)
            f['usage_receipt'] = usage
        value['graph']['feedback_events'] = [first, dispatched, last]
        return value

    def test_all_dispatched_terminal_branches_require_version_three_exact_producer_and_usage(self):
        for state in ('ACCEPTED', 'FAILED', 'REVOKED'):
            for kind in ('COMPLETED', 'WORKER_STOPPED', 'UNSTARTED_FENCED'):
                value = self.complete_producer(state, kind); f = value['graph']['feedback_requests'][0]
                self.assertEqual(feedback_quiescent(value, f), not (state == 'ACCEPTED' and kind == 'UNSTARTED_FENCED'), (state, kind))
                for field, wrong in (('ledger_instance_id', 'not-a-uuid'), ('terminal_ordinal', True), ('recorded_at', 'infinity')):
                    changed = copy.deepcopy(value); changed['graph']['feedback_control_slots'][0][field] = wrong
                    self.assertFalse(feedback_quiescent(changed, changed['graph']['feedback_requests'][0]), field)
        for field in ('reservation_id', 'run_id', 'request_id', 'request_digest', 'model_manifest_hash', 'reserved_at'):
            value = self.complete_producer(); value['graph']['feedback_requests'][0]['usage_receipt'][field] = None
            self.assertFalse(feedback_quiescent(value, value['graph']['feedback_requests'][0]), field)

    def test_accepted_and_failed_deleted_overlays_preserve_metadata_proof(self):
        for state in ('ACCEPTED', 'FAILED'):
            value = self.complete_producer(state); f = value['graph']['feedback_requests'][0]
            f.update(source_deleted_at=LATER, source_binding_ref=None, result_ref=None)
            value.update(deleted_at=LATER, deletion_ledger_at=LATER)
            for run in value['graph']['runs']:
                run.update(result_ref=None, context_history_eligible=False, content_deleted_at=LATER)
            for source_context in value['graph']['contexts']:
                source_context.update(status='INVALIDATED', invalidated_at=LATER)
            value['graph']['study_sessions'][0].update(lesson_binding_ref=None, source_deleted_at=LATER)
            for original in value['graph']['study_answers']:
                original.update(text_ref=None, source_binding_ref=None, content_deleted_at=LATER)
            self.assertTrue(feedback_quiescent(value, f), state)
            f['source_deleted_at'] = NOW
            self.assertFalse(feedback_quiescent(value, f))

    def test_feedback_run_source_overlay_must_match_live_or_exact_deletion(self):
        value = self.complete_producer(); f = value['graph']['feedback_requests'][0]
        run = next(row for row in value['graph']['runs'] if row['id'] == f['run_id'])
        run['content_deleted_at'] = LATER
        self.assertFalse(feedback_quiescent(value, f))
        f.update(source_binding_ref=None, result_ref=None, source_deleted_at=LATER)
        value.update(deleted_at=LATER, deletion_ledger_at=LATER)
        for source_run in value['graph']['runs']:
            source_run.update(result_ref=None, context_history_eligible=False, content_deleted_at=LATER)
        for source_context in value['graph']['contexts']:
            source_context.update(status='INVALIDATED', invalidated_at=LATER)
        value['graph']['study_sessions'][0].update(lesson_binding_ref=None, source_deleted_at=LATER)
        for original in value['graph']['study_answers']:
            original.update(text_ref=None, source_binding_ref=None, content_deleted_at=LATER)
        self.assertTrue(feedback_quiescent(value, f))
        run['content_deleted_at'] = NOW
        self.assertFalse(feedback_quiescent(value, f))

    def test_missing_or_substituted_original_lesson_and_answer_receipts_fail(self):
        value = self.complete_local_cancel(); value['graph']['reservations'][1]['purpose'] = 'CONVERSATION'
        self.assertFalse(feedback_quiescent(value, value['graph']['feedback_requests'][0]))
        value = self.complete_local_cancel(); value['graph']['study_sessions'][0]['lesson_receipt']['response_kind'] = 'ANSWER'
        self.assertFalse(feedback_quiescent(value, value['graph']['feedback_requests'][0]))
        value = self.complete_local_cancel(); value['graph']['study_mutations'].pop()
        self.assertFalse(feedback_quiescent(value, value['graph']['feedback_requests'][0]))
        value = self.complete_local_cancel(); value['graph']['study_mutations'][1]['input_id'] = key(799)
        self.assertFalse(feedback_quiescent(value, value['graph']['feedback_requests'][0]))

    def test_boolean_integer_aliases_and_final_phase_substitution_are_rejected(self):
        for kind, field in (('runs', 'version'), ('reservations', 'accounting_version'), ('reservations', 'control_revision'),
                            ('reservations', 'budget_revision'), ('contexts', 'security_epoch'), ('feedback_control_slots', 'revision')):
            value = self.complete_local_cancel(); f = value['graph']['feedback_requests'][0]
            identity = {'runs': 'run_id', 'reservations': 'reservation_id', 'contexts': 'context_id', 'feedback_control_slots': 'reservation_id'}[kind]
            row = next(row for row in value['graph'][kind] if row['id'] == f[identity]); row[field] = True
            self.assertFalse(feedback_quiescent(value, f), (kind, field))
        value = self.complete_local_cancel(); value['graph']['feedback_requests'][0]['phase_xid'] = '100'
        self.assertFalse(feedback_quiescent(value, value['graph']['feedback_requests'][0]))

    def test_exact_local_cancel_proof_is_required_for_feedback_only(self):
        value = self.complete_local_cancel(); f = value['graph']['feedback_requests'][0]
        self.assertTrue(feedback_quiescent(value, f))
        self.assertEqual(eligibility(value)['status'], 'ELIGIBLE')
        self.assertEqual(source_counts(value)['feedback_requests'], 1)
        self.assertEqual(source_counts(value)['feedback_results'], 0)
        for field in ('decision', 'decision_at', 'recorded_at', 'terminal_kind', 'origin_binding'):
            changed = copy.deepcopy(value); changed['graph']['feedback_control_slots'][0][field] = None
            self.assertFalse(feedback_quiescent(changed, changed['graph']['feedback_requests'][0]), field)
        value['graph']['runs'][0]['status'] = 'UNKNOWN'
        self.assertIn('UNKNOWN_WORK', eligibility(value)['blockers'])

    def test_v2_cannot_acquire_a_dispatched_producer_proof(self):
        for kind in ('COMPLETED', 'WORKER_STOPPED', 'UNSTARTED_FENCED'):
            value = self.complete_local_cancel(); f = value['graph']['feedback_requests'][0]
            q = value['graph']['reservations'][0]; slot = value['graph']['feedback_control_slots'][0]
            q.update(started_at=NOW, dispatch_context_id=f['context_id'], dispatch_cache_policy=LIMITS['dispatch_cache_policy'])
            slot.update(terminal_kind=kind, dispatch_commitment='e' * 64, bound_at=NOW,
                        ledger_instance_id=key(740), terminal_ordinal=1, generation_id=None if kind == 'UNSTARTED_FENCED' else key(741))
            self.assertFalse(feedback_quiescent(value, f), kind)

    def test_every_event_identity_state_counter_and_time_mutation_fails(self):
        mutations = {'resulting_state': 'ACCEPTED', 'output_authority': 'CLOSED', 'phase_xid': 'untrusted',
                     'committed_at': 'infinity', 'input_id': key(799), 'version': True,
                     'resulting_conversation_version': MAX_INTEGER + 1, 'invalidated_contexts': -1,
                     'request_digest': None, 'operation_session_hash': None}
        for field, replacement in mutations.items():
            changed = self.complete_local_cancel(); changed['graph']['feedback_events'][0][field] = replacement
            self.assertFalse(feedback_quiescent(changed, changed['graph']['feedback_requests'][0]), field)
        for field in ('owner_id', 'run_id', 'request_id', 'purpose', 'security_epoch', 'revision', 'context_id'):
            changed = self.complete_local_cancel(); changed['graph']['feedback_control_slots'][0][field] = None
            self.assertFalse(feedback_quiescent(changed, changed['graph']['feedback_requests'][0]), field)

    def test_missing_and_duplicate_lineages_fail_closed(self):
        for kind in ('runs', 'reservations', 'contexts', 'study_sessions', 'study_answers', 'feedback_events', 'feedback_control_slots'):
            changed = self.complete_local_cancel(); changed['graph'][kind].pop()
            self.assertFalse(feedback_quiescent(changed, changed['graph']['feedback_requests'][0]), kind)
        value = self.complete_local_cancel(); value['graph']['feedback_control_slots'].append(copy.deepcopy(value['graph']['feedback_control_slots'][0]))
        self.assertFalse(feedback_quiescent(value, value['graph']['feedback_requests'][0]))

    def test_each_inventory_count_equality_and_one_beyond_remains_conjunctive_with_physical_limit(self):
        # Count-only projections intentionally lack lineage. They must never be treated as
        # eligible feedback graphs; actual SQL tests cover the attainable joint equality.
        domains = [('runs', 1000), ('messages', 2000), ('actions', 1000), ('contexts', 1000),
                   ('study_sessions', 1000), ('study_answers', 2000), ('feedback_control_slots', 1000),
                   ('feedback_requests', 2000), ('feedback_events', 6000), ('feedback_results', 2000)]
        def projection(kind, count):
            value = target()
            value['graph'] = {name: [] for name in DOMAINS}
            value['content'] = {name: [] for name in value['content']}
            value['authorizations'] = []; value['source_bindings'] = []
            if kind == 'messages':
                value['content']['messages'] = ['synthetic'] * count
            elif kind == 'feedback_results':
                value['graph']['feedback_requests'] = [node(key(10000+i), 'ACCEPTED', state='ACCEPTED', result_ref='synthetic') for i in range(count)]
            else:
                value['graph'][kind] = [node(key(10000+i), 'SUCCEEDED') for i in range(count)]
            return value
        for kind, cap in domains:
            with self.subTest(kind=kind):
                equality = projection(kind, cap); check_limits(equality)
                if kind.startswith('feedback'):
                    self.assertEqual(eligibility(equality)['status'], 'BLOCKED')
                with self.assertRaisesRegex(ValueError, 'CONVERSATION_DELETION_SCOPE_LIMIT'):
                    check_limits(projection(kind, cap+1))
                rows = sum(len(part) for part in equality['graph'].values()) + len(equality['content']['messages'])
                # Physical identities remain synthetic and unique; source-free bindings count as rows.
                equality['source_bindings'] = [key(100000+i) for i in range(40000-rows)]
                check_limits(equality)
                equality['source_bindings'].append(key(200000))
                with self.assertRaisesRegex(ValueError, 'CONVERSATION_DELETION_SCOPE_LIMIT'):
                    check_limits(equality)

    def test_feedback_count_ceiling_never_overrides_distinct_lesson_and_output_run_requirements(self):
        preparation = fixture('preparation')
        counts = preparation['source_counts']
        counts.update(runs=1000, run_results=1000, inference_contexts=1000, study_sessions=334,
                      study_answers=668, feedback_requests=666, feedback_results=666)
        self.assertTrue(validate_preparation(preparation))
        counts.update(feedback_requests=667, feedback_results=667)
        self.assertFalse(validate_preparation(preparation))  #334 distinct lessons+667 distinct outputs>1000.
        counts.update(feedback_requests=666, feedback_results=667)
        self.assertFalse(validate_preparation(preparation))
        counts.update(runs=1000, study_sessions=1000, study_answers=2000, feedback_requests=2000, feedback_results=2000)
        self.assertFalse(validate_preparation(preparation))

    def test_capacity_metadata_refusal_precedes_source_byte_access(self):
        class UnreadableSource(str):
            def encode(self, *args, **kwargs):
                raise AssertionError('source byte access reached before cap refusal')
        value = target(); value['graph']['runs'] = [node(key(10000+i), 'SUCCEEDED') for i in range(1001)]
        value['content']['run_results'] = [UnreadableSource('synthetic-unreadable')]
        with self.assertRaisesRegex(ValueError, 'CONVERSATION_DELETION_SCOPE_LIMIT'):
            check_limits(value)


if __name__ == '__main__':
    unittest.main()

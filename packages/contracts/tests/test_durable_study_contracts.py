"""Closed synthetic durable-study contracts, not Java/PostgreSQL execution evidence."""
import copy
import json
import unittest
from durable_study_reference import ROOT, valid, parse_request, outcome_ok, view_ok, index_ok, envelope_ok, validator


class DurableStudyContractsTest(unittest.TestCase):
    def setUp(self):
        self.f = json.loads((ROOT / 'fixtures/durable-study/synthetic-lifecycle.json').read_text())

    def test_synthetic_shapes_are_closed_and_valid(self):
        self.assertTrue(self.f['synthetic'])
        for operation in ('start', 'answer', 'close'):
            self.assertTrue(valid(operation + '-request', self.f[operation + '_request']))
            self.assertTrue(outcome_ok(self.f[operation]))
        self.assertTrue(outcome_ok(self.f['not_observed']))
        self.assertTrue(view_ok(self.f['view']))
        self.assertTrue(index_ok(self.f['index']))
        for path, field in [('session', 'session_id'), ('conversation', 'conversation_id'), ('mutation', 'idempotency_key')]:
            self.assertTrue(valid(path + '-path', {field: self.f['view']['id']}))

    def test_source_authority_feedback_and_extra_fields_are_rejected(self):
        for name in ('start', 'answer', 'close'):
            for field in ('source_id', 'source_kind', 'attempt_id', 'input_id', 'owner_id', 'session_hash', 'role', 'trust', 'feedback', 'lesson', 'verified'):
                value = self.f[name + '_request'] | {field: 'untrusted'}
                self.assertFalse(valid(name + '-request', value), (name, field))
        for field in ('text', 'lesson', 'request_digest', 'source_binding_ref', 'feedback'):
            value = copy.deepcopy(self.f['answer']); value['receipt'][field] = 'untrusted'
            self.assertFalse(outcome_ok(value), field)

    def test_operation_receipt_identity_version_and_clock_bindings(self):
        for operation in ('start', 'answer', 'close'):
            baseline = self.f[operation]
            self.assertTrue(outcome_ok(baseline, baseline['receipt']))
            for field in ('resulting_version', 'resulting_conversation_version'):
                value = copy.deepcopy(baseline); value['receipt'][field] += 1
                self.assertFalse(outcome_ok(value))
            value = copy.deepcopy(baseline); value['checked_at'] = '2025-01-01T00:00:00Z'
            self.assertFalse(outcome_ok(value))
            self.assertFalse(outcome_ok(baseline, {'mutation_id': self.f['view']['id']}))
        for operation in ('start', 'close'):
            value = copy.deepcopy(self.f[operation]); value['receipt']['input_id'] = self.f['view']['id']
            self.assertFalse(outcome_ok(value))

    def test_unsafe_integer_noncanonical_uuid_and_duplicate_json_fail(self):
        key = self.f['start']['receipt']['mutation_id']
        body = self.f['answer_request']
        for bad in (0, -1, 5, 9007199254740992, 1.0, True):
            value = body | {'expected_version': bad}
            with self.assertRaises((ValueError, TypeError)):
                parse_request('ANSWER', json.dumps(value), key)
        for raw in ('{"lesson_run_id":"x","lesson_run_id":"y","expected_conversation_version":1}', '{"lesson_run_id":"x","expected_conversation_version":1e0}'):
            with self.assertRaises(ValueError): parse_request('START', raw, key)
        self.assertFalse(valid('headers', {'Idempotency-Key': 'AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA'}))

    def test_exact_unicode_answer_and_nonblank_bounds(self):
        key = self.f['answer']['receipt']['mutation_id']; body = self.f['answer_request']
        text = '😀' * 8000
        self.assertEqual(parse_request('ANSWER', json.dumps(body | {'text': text}), key)['text'], text)
        self.assertEqual(parse_request('ANSWER', json.dumps(body), key)['text'], body['text'])
        for text in ('', ' \t\n\u00a0\ufeff', 'a\0b', '\ud800', '😀' * 8001):
            with self.assertRaises((ValueError, UnicodeError)):
                parse_request('ANSWER', json.dumps(body | {'text': text}), key)

    def test_view_stage_question_attempt_and_feedback_relations(self):
        v = self.f['view']; self.assertTrue(view_ok(v))
        for field, value in [('stage', 'FEEDBACK_PENDING'), ('version', 4), ('feedback_availability', 'QUEUED'), ('lifecycle', 'SOURCE_UNAVAILABLE')]:
            self.assertFalse(view_ok(v | {field: value}))
        for field in ('input_id', 'attempt_id', 'question_id'):
            value = copy.deepcopy(v); other = copy.deepcopy(value['attempts'][0]); other['question_id'] = value['lesson']['questions'][1]['id']
            other['input_id'] = self.f['close']['receipt']['mutation_id']; other['attempt_id'] = self.f['start']['receipt']['mutation_id']; other[field] = value['attempts'][0][field]
            value['attempts'].append(other); value['version'] = 3; value['stage'] = 'FEEDBACK_PENDING'
            self.assertFalse(view_ok(value), field)
        value = copy.deepcopy(v); value['lesson']['questions'][1]['id'] = value['lesson']['questions'][0]['id']
        self.assertFalse(view_ok(value))
        self.assertTrue(view_ok(v | {'lifecycle': 'CLOSED_BY_OWNER', 'stage': 'CLOSED_BY_OWNER', 'version': 3}))

    def test_index_partial_identity_future_time_and_unknown_content_fail(self):
        for field in ('study_session_id', 'study_version', 'lifecycle'):
            value = copy.deepcopy(self.f['index']); value['items'][0][field] = None
            self.assertFalse(index_ok(value))
        value = copy.deepcopy(self.f['index']); value['items'][0]['text'] = 'private'
        self.assertFalse(index_ok(value))
        value = copy.deepcopy(self.f['index']); value['items'][0]['created_at'] = '2027-01-01T00:00:00Z'
        self.assertFalse(index_ok(value))
        self.assertFalse(valid('index-query', {'limit': '51'}))
        self.assertFalse(valid('index-query', {'owner_id': 'untrusted'}))

    def test_response_envelope_and_all_seven_deletion_counts_are_bounded(self):
        self.assertTrue(envelope_ok('view', self.f['view']))
        self.assertFalse(envelope_ok('view', {'text': 'x' * 262144}))
        deletion = json.loads((ROOT / 'schemas/conversation-deletion-preparation.schema.json').read_text())
        counts = deletion['properties']['source_counts']
        self.assertEqual(set(counts['required']), {'messages', 'runs', 'run_results', 'action_payloads', 'inference_contexts', 'study_sessions', 'study_answers'})
        self.assertEqual(counts['properties']['study_sessions']['maximum'], 1000)
        self.assertEqual(counts['properties']['study_answers']['maximum'], 2000)


class DurableStudyDeletionReferenceTest(unittest.TestCase):
    def seed(self):
        from test_conversation_deletion_contracts import service, TARGET, node, key
        result = service(); target = result.targets[TARGET]
        target['graph']['study_sessions'] = [node(key(81), 'OPEN', lifecycle='OPEN', lesson_binding_ref='synthetic-encrypted-lesson')]
        target['graph']['study_answers'] = [node(key(82), 'RECORDED', study_session_id=key(81), text_ref='synthetic-encrypted-answer', source_binding_ref='synthetic-encrypted-binding')]
        target['graph']['study_mutations'] = [node(key(83), 'COMMITTED', study_session_id=key(81), operation='START', request_digest='d' * 64), node(key(84), 'COMMITTED', study_session_id=key(81), operation='ANSWER', request_digest='e' * 64)]
        return result, target

    def test_open_passive_study_is_deletable_and_redaction_preserves_receipts(self):
        from test_conversation_deletion_contracts import TARGET, prepare, confirm
        from conversation_deletion_reference import eligibility
        result, target = self.seed(); receipts = copy.deepcopy(target['graph']['study_mutations'])
        self.assertEqual(eligibility(target)['status'], 'ELIGIBLE')
        preview = prepare(result); self.assertEqual(preview['source_counts']['study_sessions'], 1); self.assertEqual(preview['source_counts']['study_answers'], 1)
        confirm(result, preview); after = result.targets[TARGET]
        self.assertIsNone(after['graph']['study_sessions'][0]['lesson_binding_ref'])
        self.assertIsNone(after['graph']['study_answers'][0]['text_ref'])
        self.assertIsNone(after['graph']['study_answers'][0]['source_binding_ref'])
        self.assertEqual(after['graph']['study_mutations'], receipts)

    def test_answer_commitments_and_receipt_hmacs_contribute_to_byte_cap(self):
        from conversation_deletion_reference import check_limits, LIMITS
        result, target = self.seed(); check_limits(target)
        for kind, field in [('study_sessions', 'lesson_binding_ref'), ('study_answers', 'text_ref'), ('study_answers', 'source_binding_ref'), ('study_mutations', 'request_digest')]:
            changed = copy.deepcopy(target); changed['graph'][kind][0][field] = 'x' * LIMITS['ciphertext_and_title_utf8_bytes']
            with self.assertRaisesRegex(ValueError, 'CONVERSATION_DELETION_SCOPE_LIMIT'): check_limits(changed)

    def test_metadata_receipts_count_toward_aggregate_rows_and_study_caps(self):
        from conversation_deletion_reference import check_limits, LIMITS
        result, target = self.seed()
        changed = copy.deepcopy(target); changed['graph']['study_mutations'] = [target['graph']['study_mutations'][0]] * LIMITS['dependent_rows']
        with self.assertRaisesRegex(ValueError, 'CONVERSATION_DELETION_SCOPE_LIMIT'): check_limits(changed)
        for kind in ('study_sessions', 'study_answers'):
            changed = copy.deepcopy(target); changed['graph'][kind] = [target['graph'][kind][0]] * (LIMITS[kind] + 1)
            with self.assertRaisesRegex(ValueError, 'CONVERSATION_DELETION_SCOPE_LIMIT'): check_limits(changed)

"""Pure synthetic contracts/source evidence only; no provider, Java or database execution."""
import copy
import hashlib
import json
from pathlib import Path
import unittest

from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT.parents[1]


def schema(name):
    return json.loads((ROOT / 'schemas' / f'{name}.schema.json').read_text())


class StudyLessonAdmissionContractsTest(unittest.TestCase):
    def setUp(self):
        self.fixture = json.loads((ROOT / 'fixtures/study-lesson/synthetic-lesson.json').read_text())
        self.wire = Draft202012Validator(schema('turn-response'), format_checker=FormatChecker())
        self.result = Draft202012Validator(schema('study-lesson-result'), format_checker=FormatChecker())

    def test_synthetic_wire_and_read_shapes_are_closed_valid_schemas(self):
        self.assertIs(self.fixture['synthetic'], True)
        for name in ('turn-request', 'turn-response', 'study-lesson-result', 'model-artifact-manifest'):
            Draft202012Validator.check_schema(schema(name))
        self.wire.validate(self.fixture['wire'])
        self.result.validate(self.fixture['read'])

    def test_wire_never_accepts_model_ids_authority_or_unknown_fields(self):
        for field in ('id', 'question_ids', 'owner_id', 'verified', 'approved', 'session_hash', 'lesson'):
            value = copy.deepcopy(self.fixture['wire']); value[field] = True
            self.assertFalse(self.wire.is_valid(value), field)
        value = copy.deepcopy(self.fixture['wire']); value['questions'][0] = {'id': 'untrusted', 'text': 'Synthetic'}
        self.assertFalse(self.wire.is_valid(value))

    def test_exact_counts_and_text_limits_match_kernel(self):
        for field, count, limit in (('concepts', 3, 1000), ('questions', 2, 2000)):
            for bad in ([], ['Synthetic'] * (count - 1), ['Synthetic'] * (count + 1), ['x' * (limit + 1)] * count):
                value = copy.deepcopy(self.fixture['wire']); value[field] = bad
                self.assertFalse(self.wire.is_valid(value), (field, len(bad)))
            value = copy.deepcopy(self.fixture['wire']); value[field] = ['😀' * limit] * count
            self.assertTrue(self.wire.is_valid(value))

    def test_all_kernel_blank_nul_and_nonscalar_values_fail(self):
        blanks = [chr(n) for n in (*range(9, 14), 32, 133, 160, 5760, *range(8192, 8203), 8232, 8233, 8239, 8287, 12288, 65279)]
        for bad in blanks + ['', '\0', 'x\0', '\ud800', 1, True, None, {}]:
            value = copy.deepcopy(self.fixture['wire']); value['concepts'][0] = bad
            self.assertFalse(self.wire.is_valid(value), repr(bad))

    def test_typed_read_has_content_only_for_success(self):
        for status in ('QUEUED', 'RUNNING', 'WAITING_INPUT', 'FAILED', 'UNKNOWN', 'CANCELLED', 'BLOCKED', 'EXPIRED'):
            value = copy.deepcopy(self.fixture['read']); value['status'] = status
            self.assertFalse(self.result.is_valid(value)); value['lesson'] = None
            self.assertTrue(self.result.is_valid(value))
        value = copy.deepcopy(self.fixture['read']); value['lesson'] = None
        self.assertFalse(self.result.is_valid(value))

    def test_typed_read_rejects_extra_identity_and_noncanonical_question_ids(self):
        for path in ((), ('lesson',), ('lesson', 'questions', 0)):
            value = copy.deepcopy(self.fixture['read']); item = value
            for part in path: item = item[part]
            item['verified'] = True; self.assertFalse(self.result.is_valid(value))
        value = copy.deepcopy(self.fixture['read']); value['lesson']['questions'][0]['id'] = 'not-a-uuid'
        self.assertFalse(self.result.is_valid(value))

    def test_request_purpose_extension_is_optional_and_study_requires_context(self):
        request = schema('turn-request')
        self.assertNotIn('purpose', request['required'])
        self.assertEqual(request['properties']['purpose']['enum'], ['CONVERSATION', 'STUDY_LESSON'])
        condition = next(item for item in request['allOf'] if item.get('if', {}).get('properties', {}).get('purpose'))
        self.assertEqual(condition['if']['required'], ['purpose'])
        self.assertEqual(condition['then']['required'], ['context'])
        self.assertEqual(condition['then']['properties']['allowed_tool_ids'], {'maxItems': 0})

    def test_private_manifest_addition_contains_only_an_optional_digest(self):
        manifest = schema('model-artifact-manifest')
        self.assertNotIn('study_lesson_prompt_sha256', manifest['required'])
        self.assertEqual(manifest['properties']['study_lesson_prompt_sha256']['pattern'], '^[a-f0-9]{64}$')
        self.assertNotIn('study_lesson_prompt_path', manifest['properties'])

    def test_pre_v15_migrations_remain_exactly_unchanged(self):
        expected = json.loads((ROOT / 'fixtures/study-lesson/pre-v15-migration-hashes.json').read_text())
        migrations = REPO / 'apps/core/src/main/resources/db/migration'
        self.assertEqual(len(expected), 14)
        for name, digest in expected.items():
            self.assertEqual(hashlib.sha256((migrations / name).read_bytes()).hexdigest(), digest, name)

    def test_v15_adds_no_content_store_and_retains_exact_deletion_gate(self):
        migration = (REPO / 'apps/core/src/main/resources/db/migration/V15__typed_study_lesson_admission.sql').read_text()
        self.assertNotIn('CREATE TABLE', migration)
        self.assertIn('ADD COLUMN purpose', migration)
        self.assertIn("NEW.response_kind='LESSON' AND expected<>'STUDY_LESSON'", migration)
        self.assertIn("NEW.response_kind='ANSWER' AND expected<>'CONVERSATION'", migration)
        self.assertIn('expected_digest_ref=NULL,payload_hash_ref=NULL WHERE consumed_at IS NULL', migration)
        core = (REPO / 'apps/core/src/main/java/dev/myjarvis/core/ConversationDeletionService.java').read_text()
        self.assertIn('private boolean exactSchema(){return "16".equals', core)
        self.assertIn('if(!exactSchema())', core)
        self.assertIn('conversation-source-v1-r5', core)
        self.assertIn('SELECT x.* FROM ', core)
        self.assertIn('SET result_ref=NULL', core)
        self.assertIn('SET content_ref=NULL,body_hash=NULL', core)

    def test_all_existing_result_readers_use_the_reviewed_plain_projection(self):
        for name in ('WorkflowService', 'ConversationHistoryService', 'ConversationReplyWriter'):
            text = (REPO / f'apps/core/src/main/java/dev/myjarvis/core/{name}.java').read_text()
            self.assertIn('StudyLessonResult.text(', text, name)
        text = (REPO / 'apps/core/src/main/java/dev/myjarvis/core/StudyLessonResult.java').read_text()
        self.assertIn('"revision", 1', text)
        self.assertIn('if (!encode(lesson).equals(encoded))', text)

    def test_settlement_locks_descendants_before_id_mint_and_rechecks_after_writes(self):
        text = (REPO / 'apps/core/src/main/java/dev/myjarvis/core/LocalInferenceService.java').read_text()
        settlement = text[text.index('private void settle(Dispatch d,String status,String text,JsonNode response)'):]
        self.assertLess(settlement.index('flow.access.requireRun'), settlement.index('StudyLessonResult.admit'))
        self.assertLess(settlement.index('SELECT session_hash,security_epoch'), settlement.index('StudyLessonResult.admit'))
        self.assertGreater(settlement.index('flow.access.lockSession'), settlement.index('flow.setRun'))
        self.assertLess(text.index('usage.record(dispatch,response)'), text.index('settle(dispatch,switch'))


if __name__ == '__main__':
    unittest.main()

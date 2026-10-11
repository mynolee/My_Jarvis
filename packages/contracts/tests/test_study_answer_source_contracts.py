"""Synthetic source relationships only; no Java, authenticated adapter or model execution."""
import copy
import json
from pathlib import Path
import unittest

from jsonschema import Draft202012Validator
from referencing import Registry, Resource

ROOT = Path(__file__).resolve().parents[1]
NAMES = ('study-session', 'study-session-event', 'study-answer-source-case')
SCHEMAS = {name: json.loads((ROOT / 'schemas' / f'{name}.schema.json').read_text()) for name in NAMES}
REGISTRY = Registry().with_resources((value['$id'], Resource.from_contents(value)) for value in SCHEMAS.values())
VALIDATOR = Draft202012Validator(SCHEMAS['study-answer-source-case'], registry=REGISTRY)


def source_matches(value):
    """Fixture oracle, not authentication or an implementation of the Java transition."""
    state, actor, selected, source = (value[key] for key in ('state', 'actor_owner_id', 'selection', 'current_source'))
    return (source is not None and state['owner_id'] == actor == source['owner_id']
            and state['conversation_id'] == source['conversation_id']
            and state['project_id'] == source['project_id']
            and selected['message_id'] == source['message_id']
            and selected['conversation_content_version'] == source['conversation_content_version']
            and selected['target'] == {key: value['event'][key] for key in
                                       ('session_id', 'expected_version', 'question_id', 'attempt_id')}
            and source['role'] == 'USER' and source['trust'] == 'USER_DIRECT'
            and value['event']['answer'] == source['text'])


class StudyAnswerSourceContractsTest(unittest.TestCase):
    def setUp(self):
        self.fixture = json.loads((ROOT / 'fixtures/study-answer-source/synthetic-source-cases.json').read_text())
        self.value = self.fixture['cases'][0]['input']

    def test_schemas_and_shared_source_relationship_cases(self):
        for schema in SCHEMAS.values():
            Draft202012Validator.check_schema(schema)
        self.assertIs(self.fixture['synthetic'], True)
        self.assertEqual(len(self.fixture['cases']), 25)
        self.assertEqual(len({case['name'] for case in self.fixture['cases']}), 25)
        for case in self.fixture['cases']:
            with self.subTest(case=case['name']):
                VALIDATOR.validate(case['input'])
                self.assertEqual(source_matches(case['input']), 'state' in case['expected'])
                if 'error' in case['expected']:
                    self.assertEqual(case['expected'], {'error': 'STUDY_SOURCE_UNAVAILABLE'})

    def test_success_reuses_existing_kernel_snapshot_without_new_fields(self):
        shared = json.loads((ROOT / 'fixtures/study-session/synthetic-dijkstra.json').read_text())
        case = self.fixture['cases'][0]
        self.assertEqual(case['input']['state'], shared['initial'])
        self.assertEqual(case['input']['event'], shared['steps'][0]['event'])
        self.assertEqual(case['expected']['state'], shared['steps'][0]['expected'])
        for case in self.fixture['cases'][:2]:
            result = case['expected']['state']
            Draft202012Validator(SCHEMAS['study-session'], registry=REGISTRY).validate(result)
            self.assertEqual(result['attempts'][0]['answer'], case['input']['current_source']['text'])
            self.assertEqual(result['version'], case['input']['state']['version'] + 1)

    def test_every_object_rejects_unknown_authority_fields(self):
        paths = [(), ('selection',), ('selection', 'target'), ('current_source',), ('event',), ('state',), ('state', 'lesson'),
                 ('state', 'lesson', 'questions', 0)]
        for path in paths:
            for key in ('approved', 'verified', 'authenticated', 'authorization', 'session_hash', 'security_epoch'):
                value = copy.deepcopy(self.value)
                node = value
                for part in path:
                    node = node[part]
                node[key] = True
                self.assertFalse(VALIDATOR.is_valid(value), (path, key))

    def test_every_required_envelope_source_and_selection_field_is_required(self):
        for path in [(), ('selection',), ('selection', 'target'), ('current_source',), ('event',)]:
            node = self.value
            for part in path:
                node = node[part]
            for key in node:
                value = copy.deepcopy(self.value)
                target = value
                for part in path:
                    target = target[part]
                del target[key]
                self.assertFalse(VALIDATOR.is_valid(value), (path, key))

    def test_unavailable_source_is_content_and_identity_free(self):
        self.assertTrue(VALIDATOR.is_valid({**self.value, 'current_source': None}))
        for source in ({}, {'available': False}, {'message_id': self.value['selection']['message_id']},
                       {'state': 'UNAVAILABLE', 'text': 'Synthetic content'}):
            self.assertFalse(VALIDATOR.is_valid({**self.value, 'current_source': source}))
        for source_field in ('text', 'message_id', 'owner_id', 'conversation_id'):
            value = copy.deepcopy(self.value)
            value['current_source'][source_field] = None
            self.assertFalse(VALIDATOR.is_valid(value))

    def test_only_existing_answer_event_can_be_bound(self):
        shared = json.loads((ROOT / 'fixtures/study-session/synthetic-dijkstra.json').read_text())
        for step in shared['steps']:
            value = {**self.value, 'event': step['event']}
            self.assertEqual(VALIDATOR.is_valid(value), step['event']['kind'] == 'ANSWER')

    def test_uuid_shapes_are_strict_at_every_identity_path(self):
        paths = [('actor_owner_id',), ('selection', 'message_id')]
        paths += [('current_source', key) for key in ('message_id', 'owner_id', 'conversation_id', 'project_id')]
        paths += [('event', key) for key in ('session_id', 'question_id', 'attempt_id')]
        paths += [('selection', 'target', key) for key in ('session_id', 'question_id', 'attempt_id')]
        for path in paths:
            for identifier in ('', 'unknown', '00000000-0000-4000-8000-000000000001\n',
                               'A0000000-0000-4000-8000-000000000001', ' 00000000-0000-4000-8000-000000000001'):
                value = copy.deepcopy(self.value)
                node = value
                for key in path[:-1]:
                    node = node[key]
                node[path[-1]] = identifier
                self.assertFalse(VALIDATOR.is_valid(value), (path, identifier))

    def test_conversation_revision_bounds_are_existing_safe_integer_domain(self):
        for parent in ('selection', 'current_source'):
            for version in (0, -1, 9007199254740992, True, '7', 1.5):
                value = copy.deepcopy(self.value)
                value[parent]['conversation_content_version'] = version
                self.assertFalse(VALIDATOR.is_valid(value), (parent, version))
            for version in (1, 9007199254740991):
                value = copy.deepcopy(self.value)
                value[parent]['conversation_content_version'] = version
                self.assertTrue(VALIDATOR.is_valid(value))

    def test_source_text_reuses_exact_existing_answer_domain(self):
        for text, valid in [('😀' * 8000, True), ('😀' * 8001, False), ('', False), (' \t\n', False),
                            ('\u0085\u00a0\ufeff', False), ('a\0b', False), ('a\ud800b', False)]:
            value = copy.deepcopy(self.value)
            value['event']['answer'] = text
            value['current_source']['text'] = text
            self.assertEqual(VALIDATOR.is_valid(value), valid)

    def test_structurally_valid_provenance_is_not_authority(self):
        value = copy.deepcopy(self.value)
        value['current_source'].update(role='ASSISTANT', trust='MODEL_PROPOSAL')
        self.assertTrue(VALIDATOR.is_valid(value))
        self.assertFalse(source_matches(value))
        for field in ('role', 'trust'):
            value = copy.deepcopy(self.value)
            value['current_source'][field] = 'VERIFIED_OWNER'
            self.assertFalse(VALIDATOR.is_valid(value))

    def test_exact_text_has_no_normalization_or_whitespace_fallback(self):
        for source, answer in [('a', 'a '), ('é', 'e\u0301'), ('Ａ', 'A'), ('x\ny', 'x\r\ny')]:
            value = copy.deepcopy(self.value)
            value['current_source']['text'] = source
            value['event']['answer'] = answer
            self.assertTrue(VALIDATOR.is_valid(value))
            self.assertFalse(source_matches(value))

    def test_unavailability_errors_have_no_content_or_identity_details(self):
        errors = [case['expected'] for case in self.fixture['cases'] if 'error' in case['expected']]
        self.assertEqual(len(errors), 23)
        self.assertEqual({json.dumps(error, sort_keys=True) for error in errors},
                         {'{"error": "STUDY_SOURCE_UNAVAILABLE"}'})


if __name__ == '__main__':
    unittest.main()

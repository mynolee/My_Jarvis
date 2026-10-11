"""Synthetic wire/fixture checks only; Java policy and runtime behavior are separate evidence."""
import copy
import json
from pathlib import Path
import unittest

from jsonschema import Draft202012Validator
from referencing import Registry, Resource

ROOT = Path(__file__).resolve().parents[1]
NAMES = ('study-session', 'study-session-event', 'study-feedback')
SCHEMAS = {name: json.loads((ROOT / 'schemas' / f'{name}.schema.json').read_text()) for name in NAMES}
# All resources are bundled. No remote schema retrieval or dependency fetching.
REGISTRY = Registry().with_resources((schema['$id'], Resource.from_contents(schema)) for schema in SCHEMAS.values())


def validator(name):
    return Draft202012Validator(SCHEMAS[name], registry=REGISTRY)


def coherent_state(state):
    """Cross-reference fixture validation, not authentication or a transition reference implementation."""
    validator('study-session').validate(state)
    if state['lifecycle'] == 'SOURCE_UNAVAILABLE':
        return
    questions = [q['id'] for q in state['lesson']['questions']]
    if len(set(questions)) != 2:
        raise ValueError('duplicate question')
    seen_questions, seen_attempts = set(), set()
    for attempt in state['attempts']:
        question, identifier = attempt['question_id'], attempt['id']
        if question not in questions or question in seen_questions or identifier in seen_attempts:
            raise ValueError('invalid attempt relationship')
        seen_questions.add(question)
        seen_attempts.add(identifier)
        feedback = attempt['feedback']
        if feedback and (feedback['question_id'] != question or feedback['attempt_id'] != identifier):
            raise ValueError('invalid feedback relationship')


class StudySessionContractsTest(unittest.TestCase):
    def setUp(self):
        self.fixture = json.loads((ROOT / 'fixtures/study-session/synthetic-dijkstra.json').read_text())
        self.initial = self.fixture['initial']
        self.answer = self.fixture['steps'][0]['event']
        self.feedback = self.fixture['steps'][1]['event']['feedback']

    def test_all_three_schemas_are_valid(self):
        for schema in SCHEMAS.values():
            Draft202012Validator.check_schema(schema)

    def test_shared_synthetic_snapshots_and_event_shapes(self):
        self.assertIs(self.fixture['synthetic'], True)
        coherent_state(self.initial)
        prior = self.initial
        for step in self.fixture['steps']:
            event, current = step['event'], step['expected']
            validator('study-session-event').validate(event)
            coherent_state(current)
            self.assertEqual(event['session_id'], prior['id'])
            self.assertEqual(event['expected_version'], prior['version'])
            self.assertEqual(current['version'], prior['version'] + 1)
            for key in ('id', 'owner_id', 'conversation_id', 'project_id'):
                self.assertEqual(current[key], prior[key])
            prior = current
        self.assertEqual(prior['lifecycle'], 'SOURCE_UNAVAILABLE')
        self.assertIsNone(prior['lesson'])
        self.assertEqual(prior['attempts'], [])

    def test_unknown_authority_fields_rejected_everywhere(self):
        for key in ('approved', 'verified', 'owner_verified', 'authorization', 'user_intent_id', 'tool_id', 'security_epoch'):
            for schema, value in [('study-session', self.initial), ('study-session-event', self.answer), ('study-feedback', self.feedback)]:
                body = copy.deepcopy(value)
                body[key] = True
                self.assertFalse(validator(schema).is_valid(body), (schema, key))
        state = copy.deepcopy(self.initial)
        state['lesson']['questions'][0]['approved'] = True
        self.assertFalse(validator('study-session').is_valid(state))

    def test_strict_event_discriminators_and_no_hidden_fields(self):
        for kind in ('COMPLETE_TASK', 'SAVE_MEMORY', 'RETRY', 'GENERATE', 'DELETE', 'answer'):
            self.assertFalse(validator('study-session-event').is_valid({**self.answer, 'kind': kind}))
        for event in [step['event'] for step in self.fixture['steps']]:
            for field in event:
                body = {k: v for k, v in event.items() if k != field}
                self.assertFalse(validator('study-session-event').is_valid(body))
        close = copy.deepcopy(self.fixture['steps'][4]['event'])
        close['answer'] = 'Unrequested text'
        self.assertFalse(validator('study-session-event').is_valid(close))

    def test_answer_unicode_bounds(self):
        for size, expected in [(1, True), (8000, True), (8001, False)]:
            self.assertEqual(validator('study-session-event').is_valid({**self.answer, 'answer': '😀' * size}), expected)
        for text in ('', ' \t\n', '\u0085\u00a0\u2000\ufeff', 'a\0b', 'a\ud800b', 'a\udc00b'):
            self.assertFalse(validator('study-session-event').is_valid({**self.answer, 'answer': text}))

    def test_version_and_uuid_bounds(self):
        for version in (0, -1, 9007199254740992, 1.5, True, '1'):
            self.assertFalse(validator('study-session-event').is_valid({**self.answer, 'expected_version': version}))
        self.assertTrue(validator('study-session-event').is_valid({**self.answer, 'expected_version': 9007199254740991}))
        for identifier in (None, '', 'not-a-uuid', '00000000-0000-4000-8000-00000000000G'):
            self.assertFalse(validator('study-session-event').is_valid({**self.answer, 'session_id': identifier}))

    def test_uuid_fields_reject_trailing_newline_and_other_noncanonical_forms(self):
        examples = [('study-session', self.initial),
                    ('study-session', self.fixture['steps'][1]['expected']),
                    ('study-feedback', self.feedback)]
        examples += [('study-session-event', step['event']) for step in self.fixture['steps']]

        def uuid_paths(value, path=()):
            if isinstance(value, dict):
                for key, item in value.items():
                    if key == 'id' or key.endswith('_id'):
                        if item is not None:
                            yield path + (key,)
                    else:
                        yield from uuid_paths(item, path + (key,))
            elif isinstance(value, list):
                for index, item in enumerate(value):
                    yield from uuid_paths(item, path + (index,))

        checked = 0
        for schema, original in examples:
            for path in uuid_paths(original):
                for transform in (lambda x: x + '\n', lambda x: x + '\r\n',
                                  lambda x: ' ' + x, lambda x: x[:-1], lambda x: x + '0',
                                  lambda x: 'A' + x[1:]):
                    value = copy.deepcopy(original)
                    target = value
                    for key in path[:-1]:
                        target = target[key]
                    target[path[-1]] = transform(target[path[-1]])
                    with self.subTest(schema=schema, path=path, value=repr(target[path[-1]])):
                        self.assertFalse(validator(schema).is_valid(value))
                    checked += 1
        self.assertGreater(checked, 100)
        project = {**self.initial, 'project_id': self.initial['id'] + '\n'}
        self.assertFalse(validator('study-session').is_valid(project))

    def test_every_uuid_schema_has_fixed_length(self):
        count = 0

        def visit(value):
            nonlocal count
            if isinstance(value, dict):
                if value.get('pattern', '').startswith('^[0-9a-f]{8}-'):
                    self.assertEqual(value.get('minLength'), 36)
                    self.assertEqual(value.get('maxLength'), 36)
                    count += 1
                for child in value.values():
                    visit(child)
            elif isinstance(value, list):
                for child in value:
                    visit(child)

        for schema in SCHEMAS.values():
            visit(schema)
        self.assertEqual(count, 15)

    def test_default_lesson_requires_three_concepts_and_two_questions(self):
        for key, counts in [('concepts', (0, 2, 4)), ('questions', (0, 1, 3))]:
            for count in counts:
                state = copy.deepcopy(self.initial)
                state['lesson'][key] = [state['lesson'][key][0]] * count
                self.assertFalse(validator('study-session').is_valid(state))
        state = copy.deepcopy(self.initial)
        state['lesson']['questions'][0]['text'] = 'x' * 2001
        self.assertFalse(validator('study-session').is_valid(state))

    def test_feedback_requires_explanation_example_assumptions_and_checklist(self):
        for field in ('explanation', 'example', 'assumptions', 'checklist'):
            body = copy.deepcopy(self.feedback)
            del body[field]
            self.assertFalse(validator('study-feedback').is_valid(body))
        for field in ('assumptions', 'checklist'):
            for value in ([], ['x'] * 6, ['x' * 1001], [' ']):
                self.assertFalse(validator('study-feedback').is_valid({**self.feedback, field: value}))
        for outcome in ('CORRECT', 'NEEDS_WORK', 'NEEDS_CLARIFICATION'):
            self.assertTrue(validator('study-feedback').is_valid({**self.feedback, 'outcome': outcome}))
        for outcome in ('MASTERED', 'TASK_DONE', 'COMPILED', 'SUCCEEDED'):
            self.assertFalse(validator('study-feedback').is_valid({**self.feedback, 'outcome': outcome}))

    def test_unavailable_state_cannot_carry_content(self):
        unavailable = copy.deepcopy(self.fixture['steps'][-1]['expected'])
        for field, value in [('lesson', self.initial['lesson']), ('attempts', self.fixture['steps'][0]['expected']['attempts'])]:
            self.assertFalse(validator('study-session').is_valid({**unavailable, field: value}))
        self.assertFalse(validator('study-session').is_valid({**self.initial, 'lesson': None}))

    def test_semantic_fixture_checks_reject_cross_question_binding(self):
        state = copy.deepcopy(self.fixture['steps'][1]['expected'])
        state['attempts'][0]['feedback']['question_id'] = self.initial['lesson']['questions'][1]['id']
        # Shape alone is not enough; the Java policy also checks these relationships.
        self.assertTrue(validator('study-session').is_valid(state))
        with self.assertRaises(ValueError):
            coherent_state(state)

    def test_semantic_fixture_checks_reject_duplicate_or_unknown_identities(self):
        for change in ('question', 'attempt', 'unknown'):
            state = copy.deepcopy(self.fixture['steps'][2]['expected'])
            if change == 'question':
                state['lesson']['questions'][1]['id'] = state['lesson']['questions'][0]['id']
            elif change == 'attempt':
                state['attempts'][1]['id'] = state['attempts'][0]['id']
            else:
                state['attempts'][0]['question_id'] = state['id']
            with self.assertRaises(ValueError):
                coherent_state(state)

    def test_fixture_has_no_compilation_or_mastery_claim(self):
        self.assertIn('nonnegative', self.initial['lesson']['concepts'][2].lower())
        self.assertEqual(self.feedback['outcome'], 'NEEDS_WORK')
        self.assertEqual(self.fixture['steps'][4]['expected']['lifecycle'], 'CLOSED_BY_OWNER')
        self.assertNotIn('completed', self.fixture['steps'][4]['expected'])
        self.assertNotIn('compilation_status', self.initial['lesson'])


if __name__ == '__main__':
    unittest.main()

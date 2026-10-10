"""Synthetic task wire-shape regressions, not authorization/runtime evidence."""
import copy
from datetime import datetime, timezone
import re
import hashlib
import json
from pathlib import Path
import sys
import unittest

from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from canonical import canonical_bytes, loads


CHECKER = FormatChecker()


@CHECKER.checks('date-time', raises=(ValueError, TypeError, OverflowError))
def valid_datetime(value):
    # jsonschema's optional RFC3339 dependency is not installed by this lock.
    # Never silently skip semantic timestamp validation in the task fixtures.
    if not isinstance(value, str):
        return True
    match = re.fullmatch(r'([0-9]{4})-([0-9]{2})-([0-9]{2})[Tt]([0-9]{2}):([0-9]{2}):([0-9]{2})(?:\.[0-9]{1,6})?(?:[Zz]|([+-])([0-9]{2}):([0-9]{2}))', value)
    if not match:
        return False
    # fromisoformat may normalize an invalid minute offset; reject it first.
    if match[7] and (int(match[8]) > 23 or int(match[9]) > 59):
        return False
    if int(match[4]) > 23 or int(match[5]) > 59 or int(match[6]) > 59:
        return False
    parsed = datetime.fromisoformat(value.replace('t', 'T').replace('z', 'Z'))
    normalized = parsed.astimezone(timezone.utc)
    return 1 <= normalized.year <= 9999



class TaskContractsTest(unittest.TestCase):
    def validator(self, name):
        schema = json.loads((ROOT / 'schemas' / f'{name}.schema.json').read_text())
        Draft202012Validator.check_schema(schema)
        return Draft202012Validator(schema, format_checker=CHECKER)

    def setUp(self):
        self.cases = json.loads((ROOT / 'fixtures/tasks/cases.json').read_text())
        self.samples = {case['name']: case['instance'] for case in self.cases}

    def test_all_task_schemas_are_valid(self):
        for path in (ROOT / 'schemas').glob('task*.schema.json'):
            with self.subTest(schema=path.name):
                Draft202012Validator.check_schema(json.loads(path.read_text()))

    def test_synthetic_task_cases(self):
        for case in self.cases:
            with self.subTest(case=case['name']):
                self.assertEqual(self.validator(case['schema']).is_valid(case['instance']), case['valid'])

    def test_authority_fields_rejected_at_top_level_and_inside_arguments(self):
        for name, schema in [('create-task', 'task-create-request'), ('update-task', 'task-update-request'),
                             ('create-intent', 'task-intent-request'), ('update-intent', 'task-intent-request')]:
            for field in ['owner_id', 'session_id', 'security_epoch', 'risk', 'approved', 'verified', 'grant_id', 'authorization']:
                with self.subTest(sample=name, field=field):
                    body = copy.deepcopy(self.samples[name])
                    body[field] = True
                    self.assertFalse(self.validator(schema).is_valid(body))
                    if 'intent' in name:
                        body = copy.deepcopy(self.samples[name])
                        body['arguments'][field] = True
                        self.assertFalse(self.validator(schema).is_valid(body))

    def test_title_and_description_unicode_bounds(self):
        v = self.validator('task-create-request')
        for title, expected in [('', False), ('x' * 200, True), ('x' * 201, False), ('😀' * 200, True), ('😀' * 201, False)]:
            self.assertEqual(v.is_valid({**self.samples['create-task'], 'title': title}), expected)
        for length, expected in [(8000, True), (8001, False)]:
            self.assertEqual(v.is_valid({**self.samples['create-task'], 'description': '가' * length}), expected)

    def test_explicit_unicode_nonblank_set_preserves_other_codepoints(self):
        whitespace = list(range(0x09, 0x0E)) + [0x20, 0x85, 0xA0, 0x1680] + list(range(0x2000, 0x200B)) + [0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF]
        v = self.validator('task-create-request')
        for point in whitespace:
            with self.subTest(codepoint=hex(point)):
                self.assertFalse(v.is_valid({**self.samples['create-task'], 'title': chr(point) * 3}))
        self.assertFalse(v.is_valid({**self.samples['create-task'], 'title': ''.join(map(chr, whitespace))}))
        for title in ['\u200b', '\u180e', '\u001c', ' \u0085Synthetic\ufeff ', '  이름  ']:
            body = {**self.samples['create-task'], 'title': title}
            self.assertTrue(v.is_valid(body))
            self.assertEqual(body['title'], title)
        issued = copy.deepcopy(self.samples['issued-intent'])
        issued['arguments']['title'] = '\ufeff'
        self.assertFalse(self.validator('task-intent-response').is_valid(issued))

    def test_utc_year_and_offset_semantics(self):
        invalid = ['0000-01-01T00:00:00Z', '0001-01-01T00:00:00+00:01',
                   '9999-12-31T23:59:59-00:01', '2030-01-01T10:00:00+24:00',
                   '2030-01-01T10:00:00+00:60', '2030-01-01T24:00:00Z',
                   '2030-01-01T10:00:60Z', '2030-02-30T10:00:00Z']
        valid = ['0001-01-01T00:00:00Z', '9999-12-31T23:59:59.999999Z',
                 '0001-01-01T23:59:00+23:59', '9999-12-31T00:00:00-23:59',
                 '2030-01-01t10:00:00.123456z']
        v = self.validator('task-create-request')
        for value in invalid:
            with self.subTest(value=value):
                self.assertFalse(v.is_valid({**self.samples['create-task'], 'due_at': value}))
        for value in valid:
            with self.subTest(value=value):
                self.assertTrue(v.is_valid({**self.samples['create-task'], 'due_at': value}))

    def test_fraction_precision_limit_for_task_and_query(self):
        task = self.validator('task-create-request')
        query = self.validator('task-list-query')
        for precision in range(1, 9):
            value = '2030-01-01T10:00:00.' + '1' * precision + 'Z'
            expected = precision <= 6
            self.assertEqual(task.is_valid({**self.samples['create-task'], 'due_at': value}), expected)
            for field in ['from', 'to']:
                self.assertEqual(query.is_valid({field: value}), expected)
        self.assertFalse(task.is_valid({**self.samples['create-task'], 'due_at': '2030-01-01T10:00:00.Z'}))

    def test_task_uuid_canonical_lowercase_only(self):
        lower = 'abcdef01-2345-6789-abcd-ef0123456789'
        upper = lower.upper()
        create = self.validator('task-create-request')
        for field in ['user_intent_id', 'project_id']:
            self.assertTrue(create.is_valid({**self.samples['create-task'], field: lower}))
            self.assertFalse(create.is_valid({**self.samples['create-task'], field: upper}))
        record = self.validator('task')
        self.assertFalse(record.is_valid({**self.samples['task-record'], 'id': upper}))
        intent = self.validator('task-intent-request')
        self.assertFalse(intent.is_valid({**self.samples['update-intent'], 'target_id': upper}))

    def test_title_nul_rejected_without_changing_encrypted_description(self):
        validator = self.validator('task-create-request')
        for title in ['\x00', 'Synthetic\x00title', '\x00title']:
            self.assertFalse(validator.is_valid({**self.samples['create-task'], 'title': title}))
        self.assertTrue(validator.is_valid({**self.samples['create-task'], 'description': 'Encrypted\x00description'}))
        patch = self.validator('task-update-request')
        self.assertFalse(patch.is_valid({**self.samples['update-task'], 'title': 'x\x00y'}))

    def test_nullable_fields_and_required_create_fields(self):
        v = self.validator('task-create-request')
        self.assertTrue(v.is_valid({**self.samples['create-task'], 'project_id': None, 'due_at': None, 'estimated_minutes': None}))
        for field in self.samples['create-task']:
            body = dict(self.samples['create-task'])
            del body[field]
            self.assertFalse(v.is_valid(body), field)
        self.assertFalse(v.is_valid({**self.samples['create-task'], 'description': None}))

    def test_version_and_estimate_integer_bounds(self):
        update = self.validator('task-update-request')
        for value in [0, -1, True, '1', 1.5, 9007199254740992]:
            self.assertFalse(update.is_valid({**self.samples['update-task'], 'expected_version': value}))
        self.assertTrue(update.is_valid({**self.samples['update-task'], 'expected_version': 9007199254740991}))
        create = self.validator('task-create-request')
        for value, expected in [(None, True), (1, True), (525600, True), (0, False), (-1, False), (525601, False), (True, False), ('30', False)]:
            self.assertEqual(create.is_valid({**self.samples['create-task'], 'estimated_minutes': value}), expected)

    def test_task_status_is_explicit_editable_but_not_create_authority(self):
        for status in ['TODO', 'IN_PROGRESS', 'DONE', 'CANCELLED']:
            self.assertTrue(self.validator('task-update-request').is_valid({**self.samples['update-task'], 'status': status}))
            self.assertFalse(self.validator('task-create-request').is_valid({**self.samples['create-task'], 'status': status}))
        for status in ['SUCCEEDED', 'UNKNOWN', 'RUNNING', 'APPROVED']:
            self.assertFalse(self.validator('task-update-request').is_valid({**self.samples['update-task'], 'status': status}))

    def test_timestamp_uuid_and_priority_validation(self):
        v = self.validator('task-create-request')
        for field, value in [('due_at', '2030-01-01'), ('due_at', '2030-01-01T10:00:00'),
                             ('due_at', 'not-a-time'), ('due_at', '2030-02-30T10:00:00Z'), ('due_at', '2030-01-01T10:00:00+25:00'), ('project_id', 'foreign'), ('user_intent_id', 'missing'), ('priority', 'URGENT')]:
            self.assertFalse(v.is_valid({**self.samples['create-task'], field: value}))
        self.assertTrue(v.is_valid({**self.samples['create-task'], 'due_at': '2030-01-01T19:00:00+09:00'}))

    def test_create_and_update_intent_target_types(self):
        v = self.validator('task-intent-request')
        self.assertFalse(v.is_valid({**self.samples['create-intent'], 'target_id': self.samples['task-record']['id']}))
        self.assertFalse(v.is_valid({**self.samples['update-intent'], 'target_id': None}))
        self.assertFalse(v.is_valid({**self.samples['update-intent'], 'arguments': {'expected_version': 1}}))

    def test_query_and_list_bounds(self):
        v = self.validator('task-list-query')
        self.assertTrue(v.is_valid({}))
        for limit in [0, 101, True, '20']:
            self.assertFalse(v.is_valid({'limit': limit}))
        self.assertTrue(v.is_valid({'limit': 100, 'cursor': 'x' * 512}))
        self.assertFalse(v.is_valid({'cursor': 'x' * 513}))
        self.assertFalse(v.is_valid({'owner_id': 'any'}))
        listing = self.validator('task-list')
        self.assertTrue(listing.is_valid({'items': [self.samples['task-record']] * 100, 'next_cursor': None}))
        self.assertFalse(listing.is_valid({'items': [self.samples['task-record']] * 101, 'next_cursor': None}))

    def test_strict_parser_precedes_task_schema(self):
        for raw in ['{"title":"first","title":"second"}', '{"expected_version":1.0}',
                    '{"estimated_minutes":NaN}', '{"title":"\\ud800"}']:
            with self.subTest(raw=raw), self.assertRaises((ValueError, UnicodeError)):
                loads(raw)

    def test_intent_binding_hash_vector(self):
        vector = json.loads((ROOT / 'fixtures/tasks/intent-hash-vector.json').read_text())
        encoded = canonical_bytes(vector['payload'])
        self.assertEqual(encoded.decode(), vector['canonical'])
        self.assertEqual(hashlib.sha256(encoded).hexdigest(), vector['sha256'])
        for key, value in [('tool_id', 'task.update'), ('target_id', '10000000-0000-4000-8000-000000000004'),
                           ('arguments', {**vector['payload']['arguments'], 'title': 'Changed'})]:
            self.assertNotEqual(hashlib.sha256(canonical_bytes({**vector['payload'], key: value})).hexdigest(), vector['sha256'])


if __name__ == '__main__':
    unittest.main()

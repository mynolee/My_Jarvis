"""Synthetic project shape tests; not runtime or authorization evidence."""
import copy
from datetime import datetime
import json
from pathlib import Path
import re
import sys
import unittest

from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from canonical import loads

CHECKER = FormatChecker()


@CHECKER.checks('date-time', raises=(ValueError, TypeError, OverflowError))
def valid_utc_timestamp(value):
    # The optional jsonschema date-time dependency is absent from the lock.
    if not isinstance(value, str):
        return True
    if not re.fullmatch(r'[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]{1,6})?Z', value):
        return False
    datetime.fromisoformat(value)
    return True


class ProjectContractsTest(unittest.TestCase):
    def setUp(self):
        self.cases = loads((ROOT / 'fixtures/projects/cases.json').read_text())
        self.samples = {c['name']: c['instance'] for c in self.cases}

    def validator(self, name):
        schema = loads((ROOT / 'schemas' / f'{name}.schema.json').read_text())
        Draft202012Validator.check_schema(schema)
        return Draft202012Validator(schema, format_checker=CHECKER)

    def test_all_project_schemas_and_synthetic_cases(self):
        for path in (ROOT / 'schemas').glob('project-*.schema.json'):
            Draft202012Validator.check_schema(loads(path.read_text()))
        for case in self.cases:
            with self.subTest(case=case['name']):
                self.assertEqual(self.validator(case['schema']).is_valid(case['instance']), case['valid'])

    def test_all_objects_are_closed_including_nested_records(self):
        pairs = [('create-project', 'project-create-request'), ('project-record', 'project-record'),
                 ('project-list', 'project-list'), ('project-list-query', 'project-list-query'),
                 ('create-headers', 'project-create-headers'), ('committed-receipt', 'project-create-receipt'),
                 ('not-observed-receipt', 'project-create-receipt')]
        for sample, schema in pairs:
            for field in ['owner_id', 'session_id', 'security_epoch', 'approved', 'verified', 'risk',
                          'grant_id', 'authorization', 'user_intent_id', 'memory_context', 'delete']:
                with self.subTest(sample=sample, field=field):
                    self.assertFalse(self.validator(schema).is_valid({**self.samples[sample], field: True}))
        for sample, schema, nested in [('project-list', 'project-list', 'items'),
                                        ('committed-receipt', 'project-create-receipt', 'project')]:
            data = copy.deepcopy(self.samples[sample])
            target = data[nested][0] if nested == 'items' else data[nested]
            target['owner_id'] = self.samples['project-record']['id']
            self.assertFalse(self.validator(schema).is_valid(data))

    def test_required_and_nullable_fields(self):
        for sample, schema in [('create-project', 'project-create-request'), ('project-record', 'project-record'),
                              ('project-list', 'project-list'), ('create-headers', 'project-create-headers'),
                              ('committed-receipt', 'project-create-receipt')]:
            for field in self.samples[sample]:
                with self.subTest(sample=sample, field=field):
                    data = dict(self.samples[sample]); del data[field]
                    self.assertFalse(self.validator(schema).is_valid(data))
                    data = {**self.samples[sample], field: None}
                    self.assertEqual(self.validator(schema).is_valid(data), field == 'next_cursor')

    def test_codepoint_length_bounds_and_nul(self):
        v = self.validator('project-create-request')
        base = self.samples['create-project']
        for text, expected in [('', False), ('x' * 200, True), ('x' * 201, False),
                               ('😀' * 200, True), ('😀' * 201, False)]:
            self.assertEqual(v.is_valid({**base, 'name': text}), expected)
        for length, expected in [(0, True), (4000, True), (4001, False)]:
            self.assertEqual(v.is_valid({**base, 'description': '😀' * length}), expected)
        for field in ['name', 'description']:
            self.assertFalse(v.is_valid({**base, field: 'a\x00b'}))
        # Only name excludes all ISO controls; description's extra ban is NUL.
        self.assertTrue(v.is_valid({**base, 'description': '\n\t\x85'}))

    def test_java_isblank_and_iso_controls_exact_parity(self):
        v = self.validator('project-create-request')
        base = self.samples['create-project']
        java_whitespace = list(range(9, 14)) + list(range(28, 33)) + [0x1680] + list(range(0x2000, 0x2007)) + list(range(0x2008, 0x200B)) + [0x2028, 0x2029, 0x205F, 0x3000]
        for point in java_whitespace:
            with self.subTest(whitespace=hex(point)):
                self.assertFalse(v.is_valid({**base, 'name': chr(point) * 2}))
        for point in list(range(32)) + list(range(127, 160)):
            with self.subTest(control=hex(point)):
                self.assertFalse(v.is_valid({**base, 'name': 'a' + chr(point) + 'b'}))
        for text in ['\u00a0', '\u2007', '\u202f', '\ufeff', '\u200b', '\u180e', '  이름  ']:
            self.assertTrue(v.is_valid({**base, 'name': text}), repr(text))

    def test_canonical_uuid_in_record_and_required_header(self):
        value = self.samples['project-record']['id']
        for bad in [value.upper(), value.replace('-', ''), '1-1-1-1-1', 'not-a-uuid', value + '\n']:
            self.assertFalse(self.validator('project-record').is_valid({**self.samples['project-record'], 'id': bad}))
            self.assertFalse(self.validator('project-create-headers').is_valid({'Idempotency-Key': bad}))
        self.assertTrue(self.validator('project-create-headers').is_valid({'Idempotency-Key': value}))

    def test_timestamp_calendar_utc_precision_and_null(self):
        v = self.validator('project-record')
        base = self.samples['project-record']
        invalid = ['0000-01-01T00:00:00Z', '2030-02-29T00:00:00Z', '2030-01-01T24:00:00Z',
                   '2030-01-01T00:60:00Z', '2030-01-01T00:00:60Z', '2030-01-01T00:00:00+00:00',
                   '2030-01-01t00:00:00z', '2030-01-01T00:00:00.1234567Z', '2030-01-01', None]
        valid = ['0001-01-01T00:00:00Z', '9999-12-31T23:59:59.999999Z', '2028-02-29T00:00:00Z']
        for value in invalid + valid:
            with self.subTest(value=value):
                self.assertEqual(v.is_valid({**base, 'created_at': value}), value in valid)

    def test_read_only_constants_do_not_become_create_authority(self):
        for field, values in [('active', [False, 1, 'true', None]), ('version', [0, 2, True, '1', None])]:
            for value in values:
                self.assertFalse(self.validator('project-record').is_valid({**self.samples['project-record'], field: value}))
        for field, value in self.samples['project-record'].items():
            if field not in ['name', 'description']:
                self.assertFalse(self.validator('project-create-request').is_valid({**self.samples['create-project'], field: value}))

    def test_paging_bounds_types_and_no_owner_filter_from_client(self):
        v = self.validator('project-list-query')
        for value, expected in [(1, True), (100, True), (0, False), (101, False), (True, False), ('20', False), (1.5, False), (None, False)]:
            self.assertEqual(v.is_valid({'limit': value}), expected)
        for size, expected in [(0, False), (1, True), (1024, True), (1025, False)]:
            self.assertEqual(v.is_valid({'cursor': 'x' * size}), expected)
            self.assertEqual(self.validator('project-list').is_valid({'items': [], 'next_cursor': 'x' * size}), expected)
        self.assertTrue(self.validator('project-list').is_valid({'items': [self.samples['project-record']] * 100, 'next_cursor': None}))
        self.assertFalse(self.validator('project-list').is_valid({'items': [self.samples['project-record']] * 101, 'next_cursor': None}))
        for field in ['owner_id', 'active', 'from', 'to', 'sort', 'name']:
            self.assertFalse(v.is_valid({field: 'arbitrary'}))

    def test_unknown_receipt_never_encodes_retry_or_noncommit(self):
        v = self.validator('project-create-receipt')
        base = self.samples['not-observed-receipt']
        for status in ['UNKNOWN', 'FAILED', 'NOT_COMMITTED', 'CANCELLED', 'RETRY']:
            self.assertFalse(v.is_valid({**base, 'status': status}))
        for field in ['retryable', 'safe_to_retry', 'not_committed', 'resubmit']:
            self.assertFalse(v.is_valid({**base, field: True}))
        self.assertFalse(v.is_valid({**base, 'project': self.samples['project-record']}))
        self.assertFalse(v.is_valid({'status': 'COMMITTED', 'project': None}))

    def test_lone_surrogates_rejected_but_astral_codepoints_allowed(self):
        for schema, sample in [('project-create-request', 'create-project'), ('project-record', 'project-record')]:
            v = self.validator(schema)
            for field in ['name', 'description']:
                for value in ['\ud800', '\udfff', 'a\ud800b', '\udfff\ud800']:
                    with self.subTest(schema=schema, field=field, value=repr(value)):
                        self.assertFalse(v.is_valid({**self.samples[sample], field: value}))
                self.assertTrue(v.is_valid({**self.samples[sample], field: '😀'}))
        for case in loads((ROOT / 'fixtures/projects/unicode-parse-cases.json').read_text()):
            with self.subTest(case=case['name']):
                if case['valid']:
                    parsed = loads(case['raw'])
                    self.assertTrue(self.validator('project-create-request').is_valid(parsed))
                    self.assertEqual(len(parsed['name']), 1)
                else:
                    with self.assertRaises((ValueError, UnicodeError)):
                        loads(case['raw'])

    def test_strict_parser_precedes_project_schema(self):
        for raw in ['{"name":"first","name":"second"}', '{"limit":1.0}', '{"limit":NaN}',
                    '{"description":"\\ud800"}', '{"limit":9007199254740992}']:
            with self.subTest(raw=raw), self.assertRaises((ValueError, UnicodeError)):
                loads(raw)


if __name__ == '__main__':
    unittest.main()

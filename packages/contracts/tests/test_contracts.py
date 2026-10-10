import hashlib
import json
import pathlib
import sys
import unittest
from jsonschema import Draft202012Validator, FormatChecker

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from canonical import canonical_bytes, loads

class ContractsTest(unittest.TestCase):
    def validator(self, name):
        schema = json.loads((ROOT / 'schemas' / (name + '.schema.json')).read_text())
        Draft202012Validator.check_schema(schema)
        return Draft202012Validator(schema, format_checker=FormatChecker())

    def test_valid_fixtures(self):
        for path in (ROOT / 'fixtures/valid').glob('*.json'):
            with self.subTest(path=path.name):
                self.validator('turn-response' if path.stem.startswith('turn-response-') else path.stem).validate(loads(path.read_text()))

    def test_invalid_fixtures(self):
        for path in (ROOT / 'fixtures/invalid').glob('*.json'):
            with self.subTest(path=path.name):
                case = json.loads(path.read_text())
                self.assertFalse(self.validator(case['schema']).is_valid(case['instance']))

    def test_jcs_vectors(self):
        for vector in json.loads((ROOT / 'fixtures/jcs-vectors.json').read_text()):
            result = canonical_bytes(vector['input'])
            self.assertEqual(result.decode(), vector['canonical'])
            self.assertEqual(result.hex(), vector['utf8_hex'])
            self.assertEqual(hashlib.sha256(result).hexdigest(), vector['sha256'])

    def test_reject_ambiguous_json(self):
        for raw in ['{"a":1,"a":2}', '{"x":NaN}', '{"x":Infinity}',
                    '{"cost":1.0}', '{"x":9007199254740992}', '"\\ud800"']:
            with self.subTest(raw=raw), self.assertRaises((ValueError, UnicodeError)):
                loads(raw)

    def test_strict_unknown_fields_on_every_input(self):
        for name in ['message-request','user-intent-request','approval-decision','turn-request','turn-response','tool-proposal']:
            baseline = json.loads((ROOT / 'fixtures/valid' / (name+'.json')).read_text())
            for field in ['owner_id','risk','approved','verified','authorization','security_epoch']:
                self.assertFalse(self.validator(name).is_valid({**baseline, field: True}))

    def test_unknown_only_reconciles_and_terminal_states_immutable(self):
        transitions = json.loads((ROOT / 'state-machines.json').read_text())['action_transitions']
        self.assertEqual(transitions['UNKNOWN'], ['VERIFYING'])
        for status in ['SUCCEEDED','FAILED','CANCELLED','EXPIRED','BLOCKED']:
            self.assertEqual(transitions[status], [])

if __name__ == '__main__':
    unittest.main()

import copy
import json
from pathlib import Path
import unittest
from jsonschema import Draft202012Validator, FormatChecker, ValidationError
from canonical import canonical_bytes, sha256
from context_reference import LIMITS, validate_request, validate_raw

ROOT = Path(__file__).resolve().parents[1]


def uid(n):
    return f'00000000-0000-4000-8000-{n:012d}'


class ContextContractTest(unittest.TestCase):
    def base(self):
        return json.loads((ROOT / 'fixtures/context-v1/request.json').read_text())

    def reject(self, value):
        with self.assertRaises((ValueError, ValidationError)):
            validate_request(value)

    def test_shared_parity_cases(self):
        cases = json.loads((ROOT / 'fixtures/context-v1/cases.json').read_text())
        for case in cases:
            with self.subTest(case=case['name']):
                if case['valid']:
                    validate_request(case['request'])
                else:
                    self.reject(case['request'])

    def test_valid_and_schemas(self):
        value = self.base()
        validate_request(value)
        self.assertEqual(value, validate_raw(canonical_bytes(value)))
        schema = json.loads((ROOT / 'schemas/context-v1.schema.json').read_text())
        Draft202012Validator.check_schema(schema)
        Draft202012Validator(schema, format_checker=FormatChecker()).validate(
            {key: value[key] for key in ('context', 'project_context', 'memories', 'history')})

    def test_legacy_unchanged_context_requires_versioned_local_branch(self):
        value = json.loads((ROOT / 'fixtures/valid/turn-request.json').read_text())
        validate_request(value)
        self.reject({**value, 'history': []})
        self.reject({**value, 'memories': self.base()['memories']})
        for change in ({'model_id': 'mock-v1'}, {'prompt_revision': 'context-v1'},
                       {'allowed_tool_ids': ['calendar.event.create']}, {'context': None}):
            self.reject({**self.base(), **change})
        for field in ('context', 'history'):
            value = self.base(); del value[field]; self.reject(value)

    def test_strict_objects_and_server_fields(self):
        for path in ((), ('context',), ('project_context',), ('memories', 0), ('history', 0), ('budget',)):
            for extra in ('approved', 'security_epoch', 'owner_id', 'session_token', 'authority'):
                value = self.base(); value['project_context'] = {'id': uid(99), 'version': 1}
                node = value
                for key in path: node = node[key]
                node[extra] = True
                self.reject(value)
        for field in self.base()['context']:
            value = self.base(); del value['context'][field]; self.reject(value)

    def test_identity_and_generation_domains(self):
        for field in ('id', 'conversation_id'):
            for invalid in ('', 'not-an-id', uid(1) + '\n', uid(1).upper().replace('00000000', 'AAAAAAAA')):
                value = self.base(); value['context'][field] = invalid; self.reject(value)
        for path in (('context', 'context_epoch'), ('context', 'history_epoch'),
                     ('context', 'profile_version'), ('memories', 0, 'version'),
                     ('memories', 0, 'generation')):
            for invalid in (0, -1, True, 1.0, 9007199254740992):
                value = self.base(); node = value
                for key in path[:-1]: node = node[key]
                node[path[-1]] = invalid; self.reject(value)
        value = self.base(); value['context']['revision'] = 2; self.reject(value)

    def test_project_binding(self):
        value = self.base(); memory = value['memories'][0]
        memory.update(scope='PROJECT', project_id=uid(99)); self.reject(value)
        value['project_context'] = {'id': uid(98), 'version': 1}; self.reject(value)
        value['project_context']['id'] = uid(99); validate_request(value)
        memory['scope'] = 'GLOBAL'; self.reject(value)

    def test_complete_pairs_and_roles(self):
        for field, bad in [('role', 'SYSTEM'), ('trust', 'SYSTEM'), ('trust', 'MODEL_PROPOSAL')]:
            value = self.base(); value['history'][0][field] = bad; self.reject(value)
        value = self.base(); value['history'].pop(); self.reject(value)
        value = self.base(); value['history'].reverse(); self.reject(value)
        value = self.base(); value['history'][1]['run_id'] = uid(999); self.reject(value)
        value = self.base()
        for row in value['history']: row['run_id'] = value['run_id']
        self.reject(value)

    def test_source_duplicates(self):
        value = self.base(); value['history'][0]['source_id'] = value['memories'][0]['source_id']; self.reject(value)
        value = self.base(); value['history'][1]['id'] = value['history'][0]['id']; self.reject(value)
        value = self.base(); value['memories'].append(copy.deepcopy(value['memories'][0])); self.reject(value)

    def test_collection_boundaries(self):
        value = self.base(); value['memories'] = []
        for n in range(20):
            row = copy.deepcopy(self.base()['memories'][0]); row.update(id=uid(100+n), source_id=uid(200+n)); value['memories'].append(row)
        value['history'] = []
        for n in range(8):
            pair = copy.deepcopy(self.base()['history'])
            for i, row in enumerate(pair): row.update(id=uid(300+n*2+i), source_id=uid(400+n*2+i), run_id=uid(500+n))
            value['history'].extend(pair)
        validate_request(value)
        extra = copy.deepcopy(value); extra['memories'].append(copy.deepcopy(extra['memories'][0])); self.reject(extra)
        extra = copy.deepcopy(value); extra['history'].extend(copy.deepcopy(extra['history'][:2])); self.reject(extra)

    def test_scalar_and_byte_bounds(self):
        for text in ('\x00', '\ud800'):
            value = self.base(); value['history'][0]['text'] = text; self.reject(value)
        value = self.base(); value['memories'][0]['value'] = 'x' * 2001; self.reject(value)
        value = self.base(); value['history'][0]['text'] = 'x' * 16001; self.reject(value)
        value = self.base()
        for row in value['history']: row['text'] = '🌐' * 16000
        self.reject(value)  # history aggregate exceeds 65536 despite per-field validity
        value = self.base(); value['memories'] = []
        for n in range(20):
            row = copy.deepcopy(self.base()['memories'][0]); row.update(id=uid(100+n), source_id=uid(200+n), value='🌐'*2000); value['memories'].append(row)
        self.reject(value)  # memory aggregate exceeds 131072
        with self.assertRaises(ValueError): validate_raw(' ' * (LIMITS['max_request_canonical_bytes']+1))

    def test_exact_canonical_array_byte_boundaries(self):
        def text_bytes(size):
            return '🌐' * (size // 4) + ('', 'x', 'é', '한')[size % 4]
        value = self.base()
        history = value['history']
        for row in history: row['text'] = 'x'
        remaining = LIMITS['max_history_canonical_bytes'] - len(canonical_bytes(history))
        for row in history:
            added = min(63999, remaining)
            row['text'] = text_bytes(1 + added)
            remaining -= added
        self.assertEqual(0, remaining)
        self.assertEqual(65536, len(canonical_bytes(history)))
        validate_request(value)
        history[-1]['text'] += 'x'
        self.reject(value)
        value = self.base(); value['memories'] = []
        for n in range(20):
            row = copy.deepcopy(self.base()['memories'][0])
            row.update(id=uid(100+n), source_id=uid(200+n), value='x')
            value['memories'].append(row)
        remaining = LIMITS['max_memories_canonical_bytes'] - len(canonical_bytes(value['memories']))
        for row in value['memories']:
            added = min(7999, remaining)
            row['value'] = text_bytes(1 + added)
            remaining -= added
        self.assertEqual(0, remaining)
        self.assertEqual(131072, len(canonical_bytes(value['memories'])))
        validate_request(value)
        value['memories'][-1]['value'] += 'x'
        self.reject(value)

    def test_canonical_binding_and_strict_parse(self):
        value = self.base(); original = sha256(value)
        for field in ('version', 'generation', 'value'):
            changed = copy.deepcopy(value); changed['memories'][0][field] = 'green' if field == 'value' else 2
            self.assertNotEqual(original, sha256(changed))
        changed = copy.deepcopy(value); changed['context']['context_epoch'] = 2
        self.assertNotEqual(original, sha256(changed))
        with self.assertRaises(ValueError): validate_raw('{"context":1,"context":2}')

    def test_token_limits_remain_independent_runtime_gate(self):
        self.assertEqual((4096, 512, 3584), (LIMITS['max_total_tokens'], LIMITS['reserved_output_tokens'], LIMITS['max_admitted_input_tokens']))
        value = self.base(); value['budget']['max_output_tokens'] = 513; self.reject(value)
        value = self.base(); value['budget']['max_input_tokens'] = 4097; self.reject(value)
        # A valid wire request is not a claim of native token admission or authorization.
        validate_request(self.base())


if __name__ == '__main__': unittest.main()

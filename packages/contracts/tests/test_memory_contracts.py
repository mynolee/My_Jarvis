"""Synthetic memory wire regression tests; no runtime authorization claims."""
import copy
import hashlib
from datetime import datetime
import json
from pathlib import Path
import re
import sys
import unittest

from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from canonical import canonical_bytes, loads

CHECKER = FormatChecker()


@CHECKER.checks('date-time', raises=(ValueError, TypeError, OverflowError))
def server_timestamp(value):
    if not isinstance(value, str):
        return True
    if not re.fullmatch(r'[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]{1,6})?Z', value):
        return False
    datetime.fromisoformat(value)
    return True


def strict_request(schema, value):
    """Executable limit oracle, not a replacement for server validation."""
    encoded = canonical_bytes(value)  # rejects floats, unsafe integers, surrogates
    if len(encoded) > 16384:
        raise ValueError('canonical request too large')
    schema.validate(value)
    return encoded


class MemoryContractsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.cases = loads((ROOT / 'fixtures/memories/cases.json').read_text())
        cls.samples = {c['name']: c['instance'] for c in cls.cases}
        cls.validators = {}
        for path in (ROOT / 'schemas').glob('memory-*.schema.json'):
            schema = loads(path.read_text())
            Draft202012Validator.check_schema(schema)
            cls.validators[path.name.removesuffix('.schema.json')] = Draft202012Validator(schema, format_checker=CHECKER)

    def validator(self, name):
        return self.validators[name]

    def test_schemas_and_synthetic_cases(self):
        self.assertEqual(len(self.validators), 17)
        for case in self.cases:
            with self.subTest(case=case['name']):
                self.assertEqual(self.validator(case['schema']).is_valid(case['instance']), case['valid'])

    def test_all_objects_are_closed_recursively(self):
        def walk(node):
            if isinstance(node, dict):
                if node.get('type') == 'object':
                    self.assertIs(node.get('additionalProperties'), False)
                for value in node.values():
                    walk(value)
            elif isinstance(node, list):
                for value in node:
                    walk(value)
        for validator in self.validators.values():
            walk(validator.schema)

    def test_no_user_supplied_authority_or_provenance(self):
        for name in ['create-request', 'revise-request', 'create-intent', 'revise-intent', 'prepare-request', 'confirm-request']:
            case = next(c for c in self.cases if c['name'] == name)
            for field in ['owner_id','session_id','security_epoch','trust','status','source','confirmed_at','approved','recent_auth','risk','grant_id','authorization','generation']:
                with self.subTest(name=name, field=field):
                    body = copy.deepcopy(case['instance']); body[field] = True
                    self.assertFalse(self.validator(case['schema']).is_valid(body))
                    if 'arguments' in body:
                        body = copy.deepcopy(case['instance']); body['arguments'][field] = True
                        self.assertFalse(self.validator(case['schema']).is_valid(body))

    def test_required_fields(self):
        for case in self.cases:
            if not case['valid'] or case['schema'].endswith('-query'):
                continue
            for field in case['instance']:
                body = copy.deepcopy(case['instance']); del body[field]
                self.assertFalse(self.validator(case['schema']).is_valid(body), (case['name'], field))

    def test_exact_intent_operation_target_and_argument_types(self):
        v = self.validator('memory-intent-request')
        uid = self.samples['record']['id']
        for name, wrong in [('create-intent',uid),('revise-intent',None)]:
            self.assertFalse(v.is_valid({**self.samples[name], 'target_id':wrong}))
        for operation in ['UPDATE','DELETE','CREATE_OR_REVISE','create']:
            self.assertFalse(v.is_valid({**self.samples['create-intent'],'operation':operation}))
        for field in ['scope','project_id','key','kind']:
            body = copy.deepcopy(self.samples['revise-intent']); body['arguments'][field] = self.samples['create-intent']['arguments'][field]
            self.assertFalse(v.is_valid(body))
        body = copy.deepcopy(self.samples['create-intent']); body['arguments']['revive_deleted'] = 1
        self.assertFalse(v.is_valid(body))
        self.assertFalse(v.is_valid({**self.samples['create-intent'],'tool_id':'task.create'}))

    def test_scope_shape_is_not_project_ownership(self):
        v = self.validator('memory-key-state-request'); body = self.samples['key-state-request']; uid = self.samples['record']['id']
        for scope, project, valid in [('GLOBAL',None,True),('GLOBAL',uid,False),('PROJECT',None,False),('PROJECT',uid,True),('SHARED',uid,False)]:
            self.assertEqual(v.is_valid({**body,'scope':scope,'project_id':project}),valid)
        for key in ['', 'Upper', 'a/b', 'a'*97, 'a\n', 'a\x00', 'a😀']:
            self.assertFalse(v.is_valid({**body,'key':key}),repr(key))
        self.assertTrue(v.is_valid({**body,'key':'a' * 96}))

    def test_unicode_nonblank_scalar_and_utf8_limits(self):
        v = self.validator('memory-create-request')
        whites = list(range(9,14)) + [32,133,160,5760] + list(range(8192,8203)) + [8232,8233,8239,8287,12288,65279]
        for field,limit,byte_limit in [('value',2000,8000),('change_reason',300,1200)]:
            for value,valid in [('',False),('😀'*limit,True),('😀'*(limit+1),False),('\ud800',False),('\udfff',False),('x\x00y',False),('\u200b',True),(' exact untrimmed ',True)]+[(chr(p)*2,False) for p in whites]:
                body = copy.deepcopy(self.samples['create-request']); body['arguments'][field] = value
                self.assertEqual(v.is_valid(body), valid, (field,repr(value[:20])))
                if valid:
                    self.assertLessEqual(len(value.encode('utf-8')),byte_limit)
                    self.assertIn(value.encode('utf-8'),strict_request(v,body))

    def test_safe_integer_and_boolean_boundaries(self):
        for name,schema,minimum in [('prepare-request','memory-delete-prepare-request',1),('confirm-request','memory-delete-confirm-request',1)]:
            for field in ['expected_version','expected_generation']:
                for value in [-1,0,True,'1',1.5,9007199254740992]:
                    self.assertFalse(self.validator(schema).is_valid({**self.samples[name],field:value}))
                self.assertTrue(self.validator(schema).is_valid({**self.samples[name],field:9007199254740991}))
        for value in [False,1,'true',None,{},[]]:
            self.assertFalse(self.validator('memory-delete-confirm-request').is_valid({**self.samples['confirm-request'],'confirm':value}))
        body = copy.deepcopy(self.samples['create-request']); body['arguments']['expected_generation'] = 0
        self.assertTrue(self.validator('memory-create-request').is_valid(body))
        body['arguments']['expected_generation'] = -1
        self.assertFalse(self.validator('memory-create-request').is_valid(body))

    def test_receipts_and_intent_responses_are_content_free(self):
        for case in self.cases:
            if case['valid'] and case['schema'] in ['memory-mutation-receipt','memory-intent-response','memory-key-state']:
                for field in ['value','source','change_reason','key','arguments','memory','previous_value','old_source_hash']:
                    self.assertFalse(self.validator(case['schema']).is_valid({**case['instance'],field:'Synthetic forbidden content'}))
        v = self.validator('memory-mutation-receipt')
        self.assertFalse(v.is_valid({**self.samples['committed-receipt'],'operation':'DELETE'}))
        for field in ['memory_id','version','generation','operation']:
            self.assertFalse(v.is_valid({**self.samples['not-observed-receipt'],field:self.samples['committed-receipt'][field]}))
        for field in ['memory_id','version','generation']:
            self.assertFalse(v.is_valid({**self.samples['redacted-delete'],field:None}))

    def test_tombstone_has_no_deleted_identity_or_version(self):
        v = self.validator('memory-key-state')
        for field in ['memory_id','version']:
            self.assertFalse(v.is_valid({**self.samples['key-deleted'],field:self.samples['key-active'][field]}))
        self.assertFalse(v.is_valid({**self.samples['key-absent'],'generation':1}))
        self.assertFalse(v.is_valid({**self.samples['key-deleted'],'generation':0}))

    def test_list_and_revision_query_isolation_and_limits(self):
        v = self.validator('memory-list-query')
        for query in [{},{'scope':'GLOBAL','project_id':None},{'scope':'PROJECT'},{'scope':'GLOBAL','include_global':True}]:
            self.assertFalse(v.is_valid(query))
        for limit,valid in [(0,False),(1,True),(50,True),(51,False),(True,False),('20',False)]:
            self.assertEqual(v.is_valid({'scope':'GLOBAL','limit':limit}),valid)
        for length,valid in [(0,False),(1024,True),(1025,False)]:
            self.assertEqual(v.is_valid({'scope':'GLOBAL','cursor':'x'*length}),valid)
        for schema,sample in [('memory-list','record'),('memory-revisions','revision')]:
            for count,valid in [(0,True),(50,True),(51,False)]:
                self.assertEqual(self.validator(schema).is_valid({'items':[self.samples[sample]]*count,'next_cursor':None}),valid)
        self.assertFalse(self.validator('memory-revisions-query').is_valid({'memory_id':self.samples['record']['id']}))

    def test_uuid_hash_and_server_timestamp_lexical_semantics(self):
        for field, values in [('id',['ABCDEF01-2345-6789-ABCD-EF0123456789','bad','10000000-0000-4000-8000-000000000001\n']),('payload_hash',['A'*64,'a'*63,'a'*65,'a'*64+'\n']),('expires_at',['2030-02-30T00:00:00Z','0000-01-01T00:00:00Z','2030-01-01T00:00:00.1234567Z','2030-01-01T24:00:00Z','2030-01-01T00:00:00+00:00'])]:
            for value in values:
                self.assertFalse(self.validator('memory-intent-response').is_valid({**self.samples['issued-intent'],field:value}),repr(value))

    def test_strict_parser_rejects_noncanonical_numeric_and_unicode_inputs(self):
        for raw in ['{"value":"one","value":"two"}','{"expected_version":1.0}','{"expected_version":1e0}','{"expected_version":NaN}','{"value":"\\ud800"}','{"expected_version":9007199254740992}']:
            with self.assertRaises((ValueError,UnicodeError)):
                loads(raw)
        # Schema integer alone accepts 1.0; the mandatory parser closes that gap.
        body = copy.deepcopy(self.samples['prepare-request']); body['expected_version'] = 1.0
        with self.assertRaises(ValueError):
            strict_request(self.validator('memory-delete-prepare-request'),body)

    def test_canonical_body_bound_is_separate_from_string_bounds(self):
        body = copy.deepcopy(self.samples['create-request'])
        # Escaped controls fit per-field limits; canonical size counts their escape bytes.
        body['arguments']['value'] = '\x01'*2000
        body['arguments']['change_reason'] = '\x01'*300
        self.assertTrue(self.validator('memory-create-request').is_valid(body))
        self.assertLessEqual(len(strict_request(self.validator('memory-create-request'),body)),16384)
        # The transport-sized arbitrary body is still rejected by the canonical cap.
        with self.assertRaises(ValueError):
            strict_request(self.validator('memory-create-request'),{'padding':'x'*16385})
        limits = loads((ROOT / 'fixtures/memories/implementation-limits.json').read_text())
        self.assertEqual(limits['request_canonical_utf8_bytes'],16384)
        self.assertEqual(limits['transport_raw_body_bytes'],262144)
        self.assertEqual(limits['cursor_codepoints'],1024)

    def test_canonical_binding_vectors_and_sensitive_changes(self):
        vectors = loads((ROOT / 'fixtures/memories/hash-vectors.json').read_text())['vectors']
        for vector in vectors:
            encoded = canonical_bytes(vector['payload'])
            self.assertEqual(encoded.decode(), vector['canonical'])
            self.assertEqual(hashlib.sha256(encoded).hexdigest(), vector['sha256'])
            payload = vector['payload']
            for field in payload:
                changed = copy.deepcopy(payload)
                if field == 'arguments':
                    changed[field]['value'] += ' changed'
                elif isinstance(changed[field], int):
                    changed[field] += 1
                else:
                    changed[field] += 'x'
                self.assertNotEqual(hashlib.sha256(canonical_bytes(changed)).hexdigest(), vector['sha256'], field)
            if vector['name'] == 'write':
                self.assertIsNotNone(payload['target_id'])
                self.assertIsNone(self.samples['create-intent']['target_id'])
            else:
                self.assertEqual(hashlib.sha256(canonical_bytes(vector['snapshot'])).hexdigest(), payload['snapshot_hash'])
                self.assertTrue(self.validator('memory-record').is_valid(vector['snapshot']))
                for field in ['value', 'change_reason', 'source']:
                    changed = copy.deepcopy(vector['snapshot'])
                    if field == 'source':
                        changed[field]['source_hash'] = 'c' * 64
                    else:
                        changed[field] += ' changed'
                    self.assertNotEqual(hashlib.sha256(canonical_bytes(changed)).hexdigest(), payload['snapshot_hash'])


if __name__ == '__main__':
    unittest.main()

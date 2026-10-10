"""Synthetic owner-profile wire tests. These do not establish backend authorization."""
import copy
from datetime import datetime
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


def strict_patch(validator, body):
    encoded = canonical_bytes(body)
    if len(encoded) > 4096:
        raise ValueError('canonical request too large')
    validator.validate(body)
    return encoded


class OwnerProfileContractsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.cases = loads((ROOT / 'fixtures/owner-profile/cases.json').read_text())
        cls.samples = {c['name']: c['instance'] for c in cls.cases}
        cls.validators = {}
        for path in (ROOT / 'schemas').glob('owner-profile*.schema.json'):
            schema = loads(path.read_text())
            Draft202012Validator.check_schema(schema)
            cls.validators[path.name.removesuffix('.schema.json')] = Draft202012Validator(schema, format_checker=CHECKER)

    def validator(self, suffix=''):
        return self.validators['owner-profile' + suffix]

    def test_schemas_and_synthetic_fixtures(self):
        self.assertEqual(len(self.validators), 8)
        for case in self.cases:
            with self.subTest(case=case['name']):
                self.assertEqual(self.validators[case['schema']].is_valid(case['instance']), case['valid'])

    def test_closed_objects_recursively(self):
        def walk(value):
            if isinstance(value, dict):
                if value.get('type') == 'object':
                    self.assertIs(value.get('additionalProperties'), False)
                for child in value.values():
                    walk(child)
            elif isinstance(value, list):
                for child in value:
                    walk(child)
        for validator in self.validators.values():
            walk(validator.schema)

    def test_strict_partial_patch_and_no_authority_fields(self):
        v = self.validator('-patch-request')
        self.assertFalse(v.is_valid({'expected_version':1}))
        self.assertFalse(v.is_valid({'display_name':None}))
        for field in ['owner_id','login_name','password','mode','execution_paused','security_epoch','permissions','answer_length','profile_effects','session_id','approved','version']:
            self.assertFalse(v.is_valid({**self.samples['patch-all'],field:True}), field)
        for field in ['locale','timezone']:
            self.assertFalse(v.is_valid({'expected_version':1,field:None}))
        self.assertTrue(v.is_valid({'expected_version':1,'display_name':None}))

    def test_unicode_scalar_controls_blank_and_limits(self):
        v = self.validator('-patch-request')
        whites = list(range(9,14)) + [32,133,160,5760] + list(range(8192,8203)) + [8232,8233,8239,8287,12288,65279]
        examples = [('',False),('😀'*80,True),('😀'*81,False),('\ud800',False),('\udfff',False),(' exact untrimmed ',True),('\u200b',True)]
        examples += [(chr(p)*2,False) for p in whites]
        examples += [('x'+chr(p)+'y',False) for p in list(range(32))+list(range(127,160))]
        for value, valid in examples:
            body = {'expected_version':1,'display_name':value}
            self.assertEqual(v.is_valid(body),valid,repr(value[:20]))
            if valid:
                self.assertLessEqual(len(value.encode('utf-8')),320)
                self.assertEqual(loads(strict_patch(v,body).decode())['display_name'],value)
        # No Unicode normalization: decomposed and composed forms remain distinct.
        self.assertNotEqual(strict_patch(v,{'expected_version':1,'display_name':'e\u0301'}),strict_patch(v,{'expected_version':1,'display_name':'é'}))

    def test_exact_locales(self):
        v = self.validator('-patch-request')
        for value in ['ko','en','KO-kr','en-us',' en-US','en-US ',False,[],{}]:
            self.assertFalse(v.is_valid({'expected_version':1,'locale':value}))
        for value in ['ko-KR','en-US']:
            self.assertTrue(v.is_valid({'expected_version':1,'locale':value}))

    def test_timezone_lexical_shape_is_not_tzdb_membership(self):
        v = self.validator('-patch-request')
        for value in ['UTC','Etc/UTC','Etc/GMT+5','America/New_York','Europe/Paris']:
            self.assertTrue(v.is_valid({'expected_version':1,'timezone':value}))
        for value in ['', 'utc','CST','EST','+09:00','UTC+09:00','GMT+09:00','SystemV/EST5',' UTC','UTC\n','Europe/Paris ', 'A/'+ 'b'*79,'A//B','A/é']:
            self.assertFalse(v.is_valid({'expected_version':1,'timezone':value}),repr(value))
        # Explicit boundary: syntactically plausible unknown/case-mismatched IDs
        # require the backend's exact supported-set lookup, not this portable schema.
        for value in ['Synthetic/Unknown','europe/paris']:
            self.assertTrue(v.is_valid({'expected_version':1,'timezone':value}))

    def test_safe_version_and_strict_json(self):
        v = self.validator('-patch-request')
        for value in [-1,0,True,'1',1.5,9007199254740992]:
            self.assertFalse(v.is_valid({'expected_version':value,'display_name':None}))
        self.assertTrue(v.is_valid({'expected_version':9007199254740991,'display_name':None}))
        for raw in ['{"expected_version":1,"expected_version":2}', '{"expected_version":1.0}', '{"expected_version":1e0}', '{"expected_version":NaN}', '{"display_name":"\\ud800"}']:
            with self.assertRaises((ValueError,UnicodeError)):
                loads(raw)
        with self.assertRaises(ValueError):
            strict_patch(v,{'expected_version':1.0,'display_name':None})
        with self.assertRaises(ValueError):
            strict_patch(v,{'padding':'x'*4097})
        limits = loads((ROOT / 'fixtures/owner-profile/implementation-limits.json').read_text())
        self.assertEqual(limits['request_canonical_utf8_bytes'],4096)
        self.assertEqual(limits['display_name_utf8_bytes'],320)

    def test_full_profile_preserves_existing_fields_and_truthful_effects(self):
        v = self.validator()
        for field in self.samples['default-profile']:
            body = copy.deepcopy(self.samples['default-profile']); del body[field]
            self.assertFalse(v.is_valid(body),field)
        for field,value in self.samples['default-profile']['profile_effects'].items():
            body = copy.deepcopy(self.samples['default-profile'])
            body['profile_effects'][field] = 'Europe/Paris' if isinstance(value,str) else not value
            self.assertFalse(v.is_valid(body),field)
        for field in ['security_epoch','password_hash','profile_ref']:
            self.assertFalse(v.is_valid({**self.samples['default-profile'],field:'forbidden'}))

    def test_receipts_are_content_free_and_status_disjoint(self):
        v = self.validator('-mutation-receipt')
        for sample in ['committed-receipt','not-observed-receipt']:
            for field in ['display_name','locale','timezone','request_body','profile','previous_profile','session_hash','request_digest']:
                self.assertFalse(v.is_valid({**self.samples[sample],field:'Synthetic forbidden data'}))
        for field in ['owner_id','version','updated_fields']:
            self.assertFalse(v.is_valid({**self.samples['not-observed-receipt'],field:self.samples['committed-receipt'][field]}))
            self.assertFalse(v.is_valid({**self.samples['committed-receipt'],field:None}))
        for value in [[],['timezone','locale'],['locale','locale'],['login_name'],['display_name','timezone','locale']]:
            self.assertFalse(v.is_valid({**self.samples['committed-receipt'],'updated_fields':value}))

    def test_replay_current_profile_can_differ_from_original_receipt_version(self):
        result = self.samples['replay-current-profile']
        self.assertTrue(self.validator('-patch-result').is_valid(result))
        self.assertGreater(result['profile']['version'], result['mutation']['version'])
        self.assertFalse(self.validator('-patch-result').is_valid({'profile':result['profile'],'mutation':self.samples['not-observed-receipt']}))

    def test_options_shape_and_runtime_sorting_boundary(self):
        v = self.validator('-options'); body = self.samples['options']
        for patch in [{'locales':['en-US','ko-KR']},{'timezones':[]},{'timezones':['UTC','UTC']},{'timezones':['Etc/UTC']},{'tzdb_version':''},{'tzdb_version':'2030a\n'}]:
            self.assertFalse(v.is_valid({**body,**patch}))
        self.assertEqual(body['timezones'], sorted(body['timezones']))
        # JSON Schema checks uniqueness, shape, UTC presence; server sorts actual list.
        self.assertTrue(v.is_valid({**body,'timezones':list(reversed(body['timezones']))}))

    def test_query_header_path_and_timestamp_boundaries(self):
        self.assertTrue(self.validator('-query').is_valid({}))
        for field in ['owner_id','timezone','key','limit']:
            self.assertFalse(self.validator('-query').is_valid({field:'x'}))
        for value in ['bad','ABCDEF01-2345-6789-ABCD-EF0123456789',self.samples['headers']['Idempotency-Key']+'\n',[]]:
            self.assertFalse(self.validator('-mutation-headers').is_valid({'Idempotency-Key':value}))
            self.assertFalse(self.validator('-mutation-path').is_valid({'key':value}))
        self.assertFalse(self.validator('-mutation-headers').is_valid({}))
        for value in ['2030-02-30T00:00:00Z','0000-01-01T00:00:00Z','2030-01-01T24:00:00Z','2030-01-01T00:00:00+00:00','2030-01-01T00:00:00.1234567Z']:
            self.assertFalse(self.validator().is_valid({**self.samples['default-profile'],'server_time':value}))


if __name__ == '__main__':
    unittest.main()

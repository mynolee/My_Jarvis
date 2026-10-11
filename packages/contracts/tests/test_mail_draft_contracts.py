"""Synthetic contract checks. No PostgreSQL/HTTP/browser execution is claimed."""
import copy
import json
import unittest
from mail_draft_reference import ROOT, valid, validator, loads, request_digest, cursor_token, valid_merged_patch

class MailDraftContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.cases=loads((ROOT/'fixtures/mail-drafts/cases.json').read_text())
        cls.samples={c['name']:c['instance'] for c in cls.cases}
    def base(self):return copy.deepcopy(self.samples['empty-draft'])
    def test_schema_and_fixture_shapes(self):
        for path in (ROOT/'schemas').glob('mail-draft-*.schema.json'): validator(path.stem.removeprefix('mail-draft-').removesuffix('.schema'))
        for c in self.cases:
            with self.subTest(case=c['name']): self.assertEqual(valid(c['schema'].removeprefix('mail-draft-'),c['instance']),c['valid'])
    def test_all_request_fields_required(self):
        for field in self.base():
            b=self.base();del b[field];self.assertFalse(valid('create-request',b))
    def test_incomplete_draft_is_valid(self):
        self.assertTrue(valid('create-request',self.base()))
        self.assertTrue(valid('create-request',{**self.base(),'to':['unfinished address']}))
    def test_single_line_controls_rejected(self):
        for code in list(range(32))+list(range(127,160)):
            for field in ('sender_candidate','subject','to','cc','bcc'):
                text='a'+chr(code)+'b';value=[text] if field in ('to','cc','bcc') else text
                self.assertFalse(valid('create-request',{**self.base(),field:value}),(field,code))
    def test_body_allowed_controls_only(self):
        for code in list(range(32))+list(range(127,160)):
            self.assertEqual(valid('create-request',{**self.base(),'body':'a'+chr(code)+'b'}),code in (9,10,13))
    def test_unicode_blank_sender_and_recipient_rejected(self):
        whites='\t\n\v\f\r \x85\u00a0\u1680'+''.join(map(chr,range(0x2000,0x200b)))+'\u2028\u2029\u202f\u205f\u3000\ufeff'
        for w in whites:
            self.assertFalse(valid('create-request',{**self.base(),'sender_candidate':w}))
            self.assertFalse(valid('create-request',{**self.base(),'to':[w]}))
    def test_codepoint_limits(self):
        for f,n in [('subject',200),('sender_candidate',320),('body',16000)]:
            self.assertTrue(valid('create-request',{**self.base(),f:'😀'*n}))
            self.assertFalse(valid('create-request',{**self.base(),f:'😀'*(n+1)}))
    def test_recipient_combined_bound(self):
        b={**self.base(),'to':['a']*10,'cc':['b']*10,'bcc':['c']*10}
        self.assertTrue(valid('create-request',b));b['bcc'].append('c');self.assertFalse(valid('create-request',b))
    def test_canonical_total_byte_bound(self):
        b={**self.base(),'body':'😀'*16000,'to':['😀'*320]*2}
        self.assertFalse(valid('create-request',b))
        self.assertTrue(valid('create-request',{**b,'to':[]}))
    def test_integer_domain_rejects_float_boolean_and_unsafe(self):
        for v in [True,False,1.0,0,-1,9007199254740992]:self.assertFalse(valid('patch-request',{'subject':'x','expected_version':v}))
        self.assertTrue(valid('patch-request',{'subject':'x','expected_version':9007199254740991}))
    def test_lone_surrogates_rejected(self):
        for field in ('subject','body','sender_candidate'):
            self.assertFalse(valid('create-request',{**self.base(),field:'\ud800'}))
    def test_duplicate_json_keys_rejected(self):
        with self.assertRaises(ValueError):loads('{"subject":"a","subject":"b"}')
    def test_private_and_authority_fields_closed(self):
        for c in self.cases:
            if c['valid'] and isinstance(c['instance'],dict):
                for field in ('owner_id','security_epoch','approved','send','grant_id'):
                    self.assertFalse(valid(c['schema'].removeprefix('mail-draft-'),{**c['instance'],field:True}))
    def test_metadata_contains_no_private_fields(self):
        for field in self.base():
            if field!='project_id':self.assertFalse(valid('metadata',{**self.samples['metadata'],field:self.base()[field]}))
    def test_receipt_rejects_private_content(self):
        for field in self.base():self.assertFalse(valid('mutation-receipt',{**self.samples['committed'],field:self.base()[field]}))
    def test_mutation_result_relations(self):
        b=copy.deepcopy(self.samples['mutation-result']);b['mutation']['version']=2;self.assertFalse(valid('mutation-result',b))
        b['draft']['version']=3;self.assertTrue(valid('mutation-result',b))
        b['mutation']['draft_id']='20000000-0000-4000-8000-000000000002';self.assertFalse(valid('mutation-result',b))
        b['mutation']=self.samples['not-observed'];self.assertFalse(valid('mutation-result',b))
    def test_timestamp_order_and_calendar(self):
        b=copy.deepcopy(self.samples['record']);b['updated_at']='2029-12-31T23:59:59Z';self.assertFalse(valid('record',b))
        for t in ['2030-02-29T00:00:00Z','0000-01-01T00:00:00Z','2030-01-01T00:00:00+00:00','2030-01-01T00:00:00.1234567Z']:
            b['created_at']=t;self.assertFalse(valid('record',b))
    def test_list_order_uniqueness_and_empty_cursor(self):
        m=copy.deepcopy(self.samples['metadata']);self.assertFalse(valid('list',{'items':[m,m],'next_cursor':None}))
        self.assertFalse(valid('list',{'items':[],'next_cursor':'abc'}))
        second={**m,'id':'20000000-0000-4000-8000-000000000002'}
        self.assertFalse(valid('list',{'items':[m,second],'next_cursor':None}))
        self.assertTrue(valid('list',{'items':[second,m],'next_cursor':None}))
    def test_strict_query_and_header_lexemes(self):
        for limit in ['0','01','101','1\n','1.0',1]:self.assertFalse(valid('list-query',{'limit':limit}))
        for token in ['', 'a'*1025, 'abc=', 'abc\n', 'é']:self.assertFalse(valid('list-query',{'cursor':token}))
        ident=self.samples['path']['id']
        for ident in [ident+'\n',ident.replace('-',''),'ABCDEF00-0000-4000-8000-000000000001']:
            self.assertFalse(valid('mutation-headers',{'Idempotency-Key':ident}))
    def test_merged_patch_bounds(self):
        b={**self.base(),'to':['a']*20,'cc':['b']*10}
        patch={'bcc':['c'],'expected_version':1}
        self.assertTrue(valid('patch-request',patch));self.assertFalse(valid_merged_patch(b,patch))
        b={**self.base(),'body':'😀'*16000}
        patch={'to':['😀'*320]*2,'expected_version':1}
        self.assertTrue(valid('patch-request',patch));self.assertFalse(valid_merged_patch(b,patch))
        self.assertTrue(valid_merged_patch(self.base(),{'subject':'','expected_version':1}))
    def test_hmac_and_cursor_vectors(self):
        v=loads((ROOT/'fixtures/mail-drafts/commitment-vector.json').read_text());raw=bytes.fromhex(v['synthetic_key_hex'])
        self.assertEqual(request_digest(raw,v['owner_id'],v['method'],v['target'],v['body']),v['request_digest'])
        c=v['cursor'];self.assertEqual(cursor_token(raw,v['owner_id'],c['project_id'],c['created_at'],c['id']),c['token'])
        self.assertNotEqual(request_digest(raw,v['owner_id'],'PATCH',v['target'],v['body']),v['request_digest'])
        self.assertNotEqual(cursor_token(raw,v['owner_id'],c['id'],c['created_at'],c['id']),c['token'])

if __name__=='__main__':unittest.main()

"""Synthetic local Today contracts. No HTTP/DB/provider/browser execution."""
import copy
import unittest
from datetime import datetime
from today_reference import ROOT, loads, valid, validator, summarize, bounds
class TodayContractsTest(unittest.TestCase):
    def setUp(self):
        self.empty=loads((ROOT/'fixtures/today/empty.json').read_text())
        self.case=loads((ROOT/'fixtures/today/ranking-input.json').read_text())
    def result(self):return summarize(self.case['tasks'],self.case['checked_at'],self.case['timezone'])
    def test_schemas(self):
        for name in ['query','response','task-summary']:validator(name)
    def test_empty_available(self):self.assertTrue(valid('response',self.empty))
    def test_no_query_fields(self):
        self.assertTrue(valid('query',{}))
        for k in ['owner_id','timezone','project_id','limit','cursor']:self.assertFalse(valid('query',{k:'x'}))
    def test_fixture_rankings(self):
        r=self.result();self.assertTrue(valid('response',r))
        self.assertEqual([x['id'] for x in r['tasks']['top_tasks']],self.case['expected_top_ids'])
        self.assertEqual([x['id'] for x in r['tasks']['deadlines']],self.case['expected_deadline_ids'])
    def test_completed_and_cancelled_excluded(self):
        self.case['tasks'][0]['status']='CANCELLED'
        ids=[t['id'] for t in self.result()['tasks']['top_tasks']]
        self.assertNotIn(self.case['tasks'][0]['id'],ids);self.assertNotIn(self.case['tasks'][3]['id'],ids)
    def test_deadline_boundary_excluded(self):self.assertEqual(self.result()['tasks']['deadline_total'],2)
    def test_null_deadline_only_top(self):self.assertIsNone(self.result()['tasks']['top_tasks'][1]['due_at'])
    def test_strict_overdue_boundary(self):
        self.case['tasks'][0]['due_at']=self.case['checked_at']
        self.assertEqual(self.result()['tasks']['top_tasks'][0]['priority'],'HIGH')
    def test_all_rows_not_first_page(self):
        t=self.case['tasks'][0];self.case['tasks']=[{**t,'id':f'00000000-0000-4000-8000-{i:012d}','due_at':None} for i in range(100,220)]+[t]
        self.assertEqual(self.result()['tasks']['top_tasks'][0]['id'],t['id'])
    def test_truncation(self):
        t=self.case['tasks'][0];self.case['tasks']=[{**t,'id':f'00000000-0000-4000-8000-{i:012d}'} for i in range(30)]
        r=self.result();self.assertTrue(valid('response',r));self.assertEqual(len(r['tasks']['deadlines']),20);self.assertEqual(r['tasks']['deadline_total'],30);self.assertTrue(r['tasks']['deadlines_truncated'])
    def test_dst_short_day(self):
        _,s,e=bounds('2030-03-10T12:00:00Z','America/New_York');self.assertEqual((datetime.fromisoformat(e)-datetime.fromisoformat(s)).total_seconds(),23*3600)
    def test_dst_long_day(self):
        _,s,e=bounds('2030-11-03T12:00:00Z','America/New_York');self.assertEqual((datetime.fromisoformat(e)-datetime.fromisoformat(s)).total_seconds(),25*3600)
    def test_local_date_not_utc_date(self):self.assertEqual(bounds('2030-01-15T23:00:00Z','Asia/Seoul')[0],'2030-01-16')
    def test_bad_zone_and_false_boundary(self):
        for patch in [{'timezone':'Missing/Zone'},{'local_date':'2030-01-14'},{'deadline_window_end_at':'2030-01-17T00:00:00Z'}]:self.assertFalse(valid('response',{**self.empty,**patch}))
    def test_unavailable_not_empty_or_available(self):
        for key in ['calendar','briefing','device']:
            for item in [{'status':'AVAILABLE'},{'status':'UNAVAILABLE','reason':'NOT_IMPLEMENTED','items':[]}]:self.assertFalse(valid('response',{**self.empty,key:item}))
    def test_private_extra_fields(self):
        for key in ['owner_id','token','description','body','source_ref']:self.assertFalse(valid('task-summary',{**self.case['tasks'][0],key:'x'}))
    def test_response_extra_fields(self):self.assertFalse(valid('response',{**self.empty,'send':True}))
    def test_duplicate_ids(self):
        r=self.result();r['tasks']['top_tasks']=[r['tasks']['top_tasks'][0]]*2;self.assertFalse(valid('response',r))
    def test_mismatched_cross_list_summary(self):
        r=self.result();r['tasks']['deadlines'][0]=dict(r['tasks']['deadlines'][0],version=2);self.assertFalse(valid('response',r))
    def test_wrong_order(self):
        r=self.result();r['tasks']['top_tasks'].reverse();self.assertFalse(valid('response',r))
    def test_false_total_or_truncation(self):
        for key,value in [('deadline_total',4),('deadlines_truncated',True)]:
            r=self.result();r['tasks'][key]=value;self.assertFalse(valid('response',r))
    def test_summary_limits_and_strict_numbers(self):
        t=self.case['tasks'][0]
        for patch in [{'title':'x'*201},{'title':'\ufeff'},{'title':'\ud800'},{'version':True},{'version':1.0},{'version':9007199254740992},{'status':'DONE'},{'due_at':'2030-02-30T00:00:00Z'}]:self.assertFalse(valid('task-summary',{**t,**patch}))
        self.assertTrue(valid('task-summary',{**t,'title':'😀'*200}))
    def test_duplicate_json_rejected(self):
        with self.assertRaises(ValueError):loads('{"status":"AVAILABLE","status":"UNAVAILABLE"}')
    def test_required_fields(self):
        for k in self.empty:
            v=copy.deepcopy(self.empty);del v[k];self.assertFalse(valid('response',v))

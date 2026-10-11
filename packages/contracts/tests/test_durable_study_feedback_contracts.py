"""Public feedback shape and relation gates using independently authored data."""
import copy
import json
import re
import unittest
from datetime import datetime
from pathlib import Path
from jsonschema import Draft202012Validator, FormatChecker

ROOT=Path(__file__).resolve().parents[1]
FIXTURES=ROOT/'fixtures/study-feedback/valid'
FORMAT=FormatChecker()
MAX_SAFE=9007199254740991


def fixture(name):return json.loads((FIXTURES/(name+'.json')).read_text())

def schema(name):return json.loads((ROOT/'schemas'/(name+'.schema.json')).read_text())

def valid(name,value):return Draft202012Validator(schema(name),format_checker=FORMAT).is_valid(value)

def encoded(value):return json.dumps(value,ensure_ascii=False,separators=(',',':'),allow_nan=False).encode('utf-8')

def clock(value):return datetime.fromisoformat(value.replace('Z','+00:00'))

def mutation_ok(value):
    if not valid('durable-study-feedback-mutation',value) or len(encoded(value))>16384:return False
    receipt=value['receipt']
    if receipt is None:return True
    if clock(value['checked_at'])<clock(receipt['committed_at']):return False
    before,after=receipt['prior_conversation_version'],receipt['resulting_conversation_version']
    if not 1<=before<after<=MAX_SAFE-1:return False
    return after-before==4 if receipt['operation']=='REQUEST_FEEDBACK' else 4<=after-before<=1003


def projection_ok(value):
    if not valid('durable-study-feedback-projection',value) or len(encoded(value))>262144:return False
    targets=value['targets'];seen={name:set() for name in ('question_id','input_id','attempt_id')};active=0
    for target in targets:
        for name in seen:
            identity=target[name]
            if identity is not None:
                if identity in seen[name]:return False
                seen[name].add(identity)
        request=target['request']
        if request:
            if not clock(request['created_at'])<=clock(request['updated_at'])<=clock(value['checked_at']):return False
            active+=request['output_authority']=='ACTIVE'
            if request['status']=='QUEUED' and (request['accounting']!='PENDING' or request['execution_evidence']!='UNCONFIRMED'):return False
        if target['availability']=='AVAILABLE' and (value['lifecycle']!='OPEN' or value['study_version']!=3 or target['input_id'] is None or request is not None):return False
    return active<=1


class DurableStudyFeedbackContractsTests(unittest.TestCase):
    def test_independent_synthetic_contracts_are_valid_and_closed(self):
        for name in ('request','revoke','source','result','projection','mutation'):
            self.assertTrue(valid('durable-study-feedback-'+name,fixture(name)),name)
            self.assertFalse(valid('durable-study-feedback-'+name,{**fixture(name),'owner_id':'forbidden'}),name)
        self.assertTrue(valid('study-feedback-readiness',fixture('readiness')))
        self.assertTrue(mutation_ok(fixture('mutation')));self.assertTrue(projection_ok(fixture('projection')))

    def test_request_cannot_supply_answer_prose_prompt_or_authority(self):
        for name in ('source','text','feedback_source','answer','lesson','session_hash','security_epoch','owner_id','model_id','profile'):
            self.assertFalse(valid('durable-study-feedback-request',{**fixture('request'),name:'synthetic'}))
        for name,value in (('expected_study_version',2),('expected_conversation_version',True),('expected_conversation_version',MAX_SAFE+1),('question_id','AAAAAAAA-0000-4000-8000-000000000001')):
            self.assertFalse(valid('durable-study-feedback-request',{**fixture('request'),name:value}))

    def test_receipt_phase_versions_and_observation_time_are_exact(self):
        for name,value in (('resulting_feedback_version',2),('prior_feedback_version',1),('study_version',4),('resulting_conversation_version',15)):
            body=fixture('mutation');body['receipt'][name]=value;self.assertFalse(mutation_ok(body),name)
        body=fixture('mutation');body['checked_at']='2029-01-01T00:00:00Z';self.assertFalse(mutation_ok(body))
        body=fixture('mutation');body.update(status='NOT_OBSERVED');self.assertFalse(mutation_ok(body))
        self.assertTrue(mutation_ok({'status':'NOT_OBSERVED','receipt':None,'checked_at':'2030-01-01T00:00:00Z'}))

    def test_projection_requires_original_distinct_targets_and_separate_state_axes(self):
        for name in ('question_id','input_id','attempt_id'):
            body=fixture('projection');body['targets'][1][name]=body['targets'][0][name];self.assertFalse(projection_ok(body))
        body=fixture('projection');body['targets'][0]['request']['output_authority']='CLOSED';self.assertFalse(projection_ok(body))
        body=fixture('projection');body['targets'][0]['request']['result']=fixture('result');self.assertFalse(projection_ok(body))
        body=fixture('projection');body['targets'][0]['request']['updated_at']='2031-01-01T00:00:00Z';self.assertFalse(projection_ok(body))
        body=fixture('projection');body['targets'][1]['request']=copy.deepcopy(body['targets'][0]['request']);body['targets'][1]['availability']='ALREADY_REQUESTED';self.assertFalse(projection_ok(body))

    def test_accepted_result_remains_readable_for_closed_study(self):
        body=fixture('projection');body.update(study_version=4,lifecycle='CLOSED_BY_OWNER')
        request=body['targets'][0]['request'];request.update(status='ACCEPTED',feedback_version=3,output_authority='CLOSED',accounting='MEASURED',execution_evidence='COMPLETED',result=fixture('result'))
        body['targets'][1]['availability']='STUDY_CLOSED';self.assertTrue(projection_ok(body))
        request['result']['code_execution']='VERIFIED';self.assertFalse(projection_ok(body))

    def test_old_schema_surfaces_have_no_feedback_availability_extension(self):
        view=schema('durable-study-view');text=json.dumps(view)
        self.assertIn('UNAVAILABLE',text);self.assertNotIn('ALREADY_REQUESTED',text)
        self.assertNotIn('STUDY_FEEDBACK',json.dumps(schema('study-feedback')))
        ready=schema('runtime-readiness');self.assertEqual(len(ready['properties']),10)

    def test_turn_discriminator_preserves_old_shape_and_excludes_generic_input(self):
        request=schema('turn-request');old,new=request['oneOf']
        self.assertIn('user_input',old['required']);self.assertNotIn('feedback_source',old['properties'])
        self.assertEqual(old['properties']['purpose']['enum'],['CONVERSATION','STUDY_LESSON'])
        self.assertNotIn('user_input',new['properties']);self.assertIn('feedback_source',new['required'])
        self.assertTrue(valid('turn-request',fixture('turn-request')))
        self.assertFalse(valid('turn-request',{**fixture('turn-request'),'user_input':''}))
        response=fixture('turn-response');self.assertTrue(valid('turn-response',response))
        response['completed']=True;self.assertFalse(valid('turn-response',response))

    def test_feedback_readiness_is_closed_and_true_requires_manifest(self):
        for changes in ({'model_manifest_hash':None},{'prompt_path':'synthetic'},{'control_revision':2},{'model_id':'mock-v1'}):
            self.assertFalse(valid('study-feedback-readiness',{**fixture('readiness'),**changes}))
        self.assertTrue(valid('study-feedback-readiness',{**fixture('readiness'),'model_manifest_hash':None,'ready':False}))

    def test_optional_manifest_pin_preserves_old_manifest_required_fields(self):
        manifest=schema('model-artifact-manifest');pin=manifest['properties']['study_feedback_prompt_sha256']
        self.assertNotIn('study_feedback_prompt_sha256',manifest['required'])
        validator=Draft202012Validator(pin)
        for value in ('a'*64,'0'*64):self.assertTrue(validator.is_valid(value))
        for value in ('a'*63,'a'*65,'A'*64,'a'*64+'\n',None):self.assertFalse(validator.is_valid(value))

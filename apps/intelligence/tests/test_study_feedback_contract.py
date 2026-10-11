"""Pure synthetic feedback wire tests; no network, database or generation."""
import copy
import json
import unittest
from pathlib import Path
from jarvis_intelligence.app import _error, _validate_output
from jarvis_intelligence.contracts import (CONTRACT_ROOT, ContractViolation, FeedbackTurnRequest,
    TurnRequest, body_hash, canonical, control_identity, parse_json, validate)
from jarvis_intelligence.mlx_adapter import output_schema

FIXTURES = CONTRACT_ROOT / 'fixtures/study-feedback/valid'
MEASURED = {'input_tokens': 120, 'output_tokens': 65, 'compute_ms': 18, 'peak_memory_bytes': 1024}


def fixture(name='turn-request'):
    return json.loads((FIXTURES / (name + '.json')).read_text())


class FeedbackContractTests(unittest.TestCase):
    def test_dedicated_model_has_no_generic_user_input(self):
        body = fixture(); turn = TurnRequest.from_wire(body)
        self.assertIsInstance(turn, FeedbackTurnRequest)
        self.assertFalse(hasattr(turn, 'user_input'))
        self.assertEqual(canonical(turn.model_dump(exclude_unset=True)), canonical(body))
        self.assertEqual(control_identity(body, {'owner_id':'aaaaaaaa-0000-4000-8000-000000000001'})['purpose'], 'STUDY_FEEDBACK')

    def test_old_omitted_and_explicit_purposes_preserve_canonical_bytes(self):
        body = json.loads((CONTRACT_ROOT/'fixtures/context-v1/request.json').read_text())
        for purpose in (None,'CONVERSATION','STUDY_LESSON'):
            value = copy.deepcopy(body)
            if purpose is not None: value['purpose'] = purpose
            self.assertEqual(canonical(TurnRequest.from_wire(value).model_dump(exclude_unset=True)), canonical(value))
            value['feedback_source'] = fixture('source')
            with self.assertRaises(ValueError): TurnRequest.from_wire(value)

    def test_missing_source_control_context_and_fabricated_input_refused(self):
        for field in ('purpose','feedback_source','context','control','history','memories'):
            body = fixture(); body.pop(field)
            with self.subTest(field=field), self.assertRaises(ValueError): TurnRequest.from_wire(body)
        for input_value in ('','synthetic',None):
            with self.assertRaises(ValueError): TurnRequest.from_wire({**fixture(),'user_input':input_value})

    def test_cross_field_question_and_duplicate_target_identity_refused(self):
        changes = [lambda s:s.update(question_id='ffffffff-0000-4000-8000-000000000001'),
                   lambda s:s['lesson']['questions'][1].update(id=s['question_id']),
                   lambda s:s.update(input_id=s['attempt_id'])]
        for change in changes:
            body=fixture(); change(body['feedback_source'])
            with self.assertRaises(ValueError): TurnRequest.from_wire(body)

    def test_execution_and_source_identifiers_must_not_alias(self):
        changes = [lambda b:b.update(request_id=b['feedback_source']['feedback_id']),
                   lambda b:b.update(run_id=b['feedback_source']['lesson_run_id']),
                   lambda b:b['context'].update(id=b['budget']['reservation_id']),
                   lambda b:b['context'].update(conversation_id=b['feedback_source']['study_session_id'])]
        for change in changes:
            body=fixture();change(body)
            with self.assertRaises(ValueError):TurnRequest.from_wire(body)

    def test_exact_budget_empty_sources_and_local_provider_required(self):
        changes = [lambda b:b.update(model_id='mock-v1',prompt_revision='mock-v1'),
                   lambda b:b.update(timezone='Asia/Seoul'),lambda b:b['control'].update(security_epoch=0)]
        for name in ('history','memories','evidence','tool_results','allowed_tool_ids'):
            changes.append(lambda b,name=name:b[name].append({}))
        for name in ('max_compute_ms','max_input_tokens','max_output_tokens','max_model_calls'):
            changes.append(lambda b,name=name:b['budget'].__setitem__(name,b['budget'][name]-1))
            changes.append(lambda b,name=name:b['budget'].__setitem__(name,True))
        for change in changes:
            body=fixture(); change(body)
            with self.assertRaises(ValueError): TurnRequest.from_wire(body)

    def test_answer_exact_unicode_and_limits(self):
        body=fixture(); text=' e\u0301\t😀 '; body['feedback_source']['answer']['text']=text
        self.assertEqual(TurnRequest.from_wire(body).feedback_source['answer']['text'],text)
        body['feedback_source']['answer']['text']='😀'*8000
        self.assertEqual(TurnRequest.from_wire(body).feedback_source['answer']['text'],'😀'*8000)
        for text in ('',' \t\ufeff','a\0b','\ud800','😀'*8001):
            body=fixture(); body['feedback_source']['answer']['text']=text
            with self.assertRaises((ValueError,UnicodeError)): TurnRequest.from_wire(body)

    def test_body_cap_duplicate_keys_and_unsafe_numbers_rejected(self):
        for raw in (b'{"purpose":"STUDY_FEEDBACK","purpose":"CONVERSATION"}',b'{"x":1.0}',b'\xff',b'x'*262145):
            with self.assertRaises(ContractViolation): parse_json(raw)
        for field in ('owner_id','session_hash','source_binding','prompt','approved'):
            body=fixture(); body['feedback_source'][field]='synthetic forbidden'
            with self.assertRaises(ValueError): TurnRequest.from_wire(body)

    def test_source_substitution_changes_complete_capability_body_hash(self):
        original=fixture()
        for change in (lambda b:b['feedback_source']['answer'].update(text='different synthetic text'),
                       lambda b:b['feedback_source'].update(lesson_run_version=4),
                       lambda b:b['control'].update(dispatch_commitment='d'*64)):
            body=copy.deepcopy(original); change(body)
            self.assertNotEqual(body_hash(original),body_hash(body))


class FeedbackOutputTests(unittest.TestCase):
    def setUp(self): self.turn=TurnRequest.from_wire(fixture())

    def test_exact_feedback_with_service_measured_usage(self):
        output=fixture('model-feedback'); response=_validate_output(output,self.turn,measured=MEASURED)
        self.assertEqual(response,fixture('turn-response'))
        self.assertNotIn('usage',output)
        self.assertEqual([v['properties']['kind']['const'] for v in output_schema([], 'STUDY_FEEDBACK')['oneOf']],['FEEDBACK'])

    def test_native_model_cannot_emit_error_usage_other_mode_or_authority(self):
        for field in ('usage','owner_id','verified','completed','mastery','score','cited_source_ids','task'):
            with self.subTest(field=field), self.assertRaises(ValueError):
                _validate_output({**fixture('model-feedback'),field:[]},self.turn,measured=MEASURED)
        for output in ({'kind':'ERROR','error_code':'MODEL_UNAVAILABLE','retryable':False},
                       {'kind':'NEED_INPUT','question':'More?','missing_fields':[]},
                       {'kind':'ANSWER','reply_text':'synthetic','cited_source_ids':[]}):
            with self.assertRaises(ValueError): _validate_output(output,self.turn,measured=MEASURED)
        self.assertEqual(_error(self.turn,'MODEL_UNAVAILABLE')['retryable'],False)

    def test_no_unmeasured_feedback_or_other_purpose_feedback(self):
        with self.assertRaises(ValueError): _validate_output(fixture('model-feedback'),self.turn)
        old=json.loads((CONTRACT_ROOT/'fixtures/context-v1/request.json').read_text())
        with self.assertRaises(ValueError): _validate_output(fixture('model-feedback'),TurnRequest.from_wire(old),measured=MEASURED)

    def test_feedback_scalar_cardinality_closed_shape_and_budget_bounds(self):
        changes=[lambda o:o.update(assessment='PASSED'),lambda o:o.update(explanation=' '*20),
                 lambda o:o.update(example='😀'*2001),lambda o:o.update(assumptions=[]),
                 lambda o:o.update(review_checklist=['one']*4),lambda o:o['assumptions'].__setitem__(0,'x\0y')]
        for change in changes:
            output=fixture('model-feedback'); change(output)
            with self.assertRaises(ValueError): _validate_output(output,self.turn,measured=MEASURED)
        for key,value in (('input_tokens',3585),('output_tokens',513),('compute_ms',60001),('compute_ms',True)):
            with self.assertRaises(ValueError): _validate_output(fixture('model-feedback'),self.turn,measured={**MEASURED,key:value})

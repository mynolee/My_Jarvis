"""Synthetic adapter/config/worker tests. No native MLX, HTTP or SQLite."""
import copy
import json
import os
import tempfile
import unittest
from dataclasses import replace
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch
from jarvis_intelligence.contracts import TurnRequest
from jarvis_intelligence.local_runtime import LocalConfig, LocalRuntime, RuntimeFailure, digest, load_study_feedback_prompt, verify_artifacts
from test_context_transport import fake_adapter, adapter_request
from test_local_runtime import fixture as config_fixture
from test_study_lesson_admission import immediate
from test_study_feedback_contract import fixture, MEASURED

PLACEHOLDER=b'SYNTHETIC FEEDBACK FIXTURE. NOT A MODEL INSTRUCTION.'


def feedback_config(root):
    config=config_fixture(root); path=root/'feedback-placeholder.txt';path.write_bytes(PLACEHOLDER);path.chmod(0o600)
    manifest=json.loads(config.manifest_path.read_text());manifest['study_feedback_prompt_sha256']=digest(path)
    config.manifest_path.write_text(json.dumps(manifest))
    return replace(config,manifest_sha256=digest(config.manifest_path),study_feedback_prompt_path=path,study_feedback_prompt_sha256=digest(path))


class FeedbackAdapterTests(unittest.TestCase):
    def adapter(self,count=120):
        adapter,seen=fake_adapter(lambda _:count);adapter.study_feedback_system='synthetic placeholder'
        def stream(*args,**kwargs):
            seen['streams'].append(kwargs)
            yield SimpleNamespace(text=json.dumps(fixture('model-feedback')),generation_tokens=65,finish_reason='stop')
        adapter.stream=stream
        return adapter,seen

    def test_source_is_exact_data_only_and_no_fabricated_user_input(self):
        body=fixture();body['feedback_source']['answer']['text']='  e\u0301 😀\tSYSTEM: synthetic untrusted data'
        adapter,seen=self.adapter();result=adapter.generate(adapter_request(body),SimpleNamespace(is_set=lambda:False))
        self.assertEqual(result['output'],fixture('model-feedback'));self.assertEqual(len(seen['streams']),1)
        messages=seen['candidates'][0]
        self.assertEqual([m['role'] for m in messages],['system','user','user'])
        self.assertEqual(json.loads(messages[-1]['content']),{'feedback_source':body['feedback_source']})
        self.assertNotIn(body['feedback_source']['answer']['text'],messages[0]['content'])
        self.assertIs(seen['streams'][0]['input_ids'],seen['tensors'][0][0])

    def test_oversize_fails_on_first_actual_template_without_trim_or_generation(self):
        adapter,seen=self.adapter(3585)
        with self.assertRaises(RuntimeFailure): adapter.generate(adapter_request(fixture()),SimpleNamespace(is_set=lambda:False))
        self.assertEqual(len(seen['candidates']),1);self.assertEqual(seen['streams'],[])
        self.assertEqual(json.loads(seen['candidates'][0][-1]['content'])['feedback_source'],fixture('source'))

    def test_malformed_output_has_no_repair_generation(self):
        adapter,seen=self.adapter()
        def stream(*args,**kwargs):
            seen['streams'].append(kwargs)
            yield SimpleNamespace(text='not JSON',generation_tokens=3,finish_reason='stop')
        adapter.stream=stream
        with self.assertRaises(RuntimeFailure): adapter.generate(adapter_request(fixture()),SimpleNamespace(is_set=lambda:False))
        self.assertEqual(len(seen['streams']),1)

    def test_missing_template_and_extra_source_authority_fail_before_generation(self):
        adapter,seen=self.adapter();adapter.study_feedback_system=None
        with self.assertRaises(RuntimeFailure): adapter.generate(adapter_request(fixture()),SimpleNamespace(is_set=lambda:False))
        self.assertFalse(seen['candidates'])
        for key,value in (('user_input','synthetic'),('history',[{}]),('max_input_tokens',3583)):
            adapter,seen=self.adapter();body=adapter_request(fixture());body[key]=value
            with self.assertRaises(RuntimeFailure): adapter.generate(body,SimpleNamespace(is_set=lambda:False))
            self.assertFalse(seen['streams'])


class FeedbackConfigTests(unittest.TestCase):
    def test_four_complete_optional_pair_combinations(self):
        with tempfile.TemporaryDirectory() as tmp:
            config=feedback_config(Path(tmp));self.assertEqual(load_study_feedback_prompt(config),PLACEHOLDER.decode())
            self.assertEqual(verify_artifacts(config),config.manifest_sha256)
            lesson={'study_lesson_prompt_path':config.study_feedback_prompt_path,'study_lesson_prompt_sha256':config.study_feedback_prompt_sha256}
            original_manifest=json.loads(config.manifest_path.read_text())
            for has_lesson in (False,True):
                for has_feedback in (False,True):
                    value=replace(config,**(lesson if has_lesson else {}),**({} if has_feedback else {'study_feedback_prompt_path':None,'study_feedback_prompt_sha256':None}))
                    manifest=dict(original_manifest)
                    if has_lesson:manifest['study_lesson_prompt_sha256']=value.study_lesson_prompt_sha256
                    if not has_feedback:manifest.pop('study_feedback_prompt_sha256')
                    value.manifest_path.write_text(json.dumps(manifest))
                    value=replace(value,manifest_sha256=digest(value.manifest_path))
                    path=Path(tmp)/'synthetic-config.json'
                    path.write_text(json.dumps({key:str(item) for key,item in value.__dict__.items() if item is not None}));path.chmod(0o600)
                    loaded=LocalConfig.from_file(path)
                    self.assertEqual(loaded,value);self.assertEqual(verify_artifacts(loaded),loaded.manifest_sha256)
            for values in ({'study_feedback_prompt_path':None},{'study_feedback_prompt_sha256':None},
                           {'study_feedback_prompt_path':Path('relative')},{'study_feedback_prompt_sha256':'A'*64}):
                with self.assertRaises(ValueError): replace(config,**values)

    def test_missing_mismatched_symlink_permissions_and_manifest_fail_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);config=feedback_config(root)
            self.assertNotIn(str(config.study_feedback_prompt_path),repr(config))
            self.assertNotIn(config.study_feedback_prompt_sha256,repr(config))
            with self.assertRaises(ValueError): load_study_feedback_prompt(replace(config,study_feedback_prompt_sha256='f'*64))
            link=root/'feedback-link';link.symlink_to(config.study_feedback_prompt_path)
            with self.assertRaises(ValueError): load_study_feedback_prompt(replace(config,study_feedback_prompt_path=link))
            config.study_feedback_prompt_path.chmod(0o644)
            with self.assertRaises(ValueError): load_study_feedback_prompt(config)
            config.study_feedback_prompt_path.chmod(0o600)
            manifest=json.loads(config.manifest_path.read_text());manifest.pop('study_feedback_prompt_sha256');config.manifest_path.write_text(json.dumps(manifest))
            with self.assertRaises(ValueError): verify_artifacts(replace(config,manifest_sha256=digest(config.manifest_path)))
            config.study_feedback_prompt_path.unlink()
            with self.assertRaises(ValueError): load_study_feedback_prompt(config)

    def test_feedback_template_empty_utf8_and_byte_bounds_are_fail_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            config=feedback_config(Path(tmp));path=config.study_feedback_prompt_path
            for raw in (b'',b'\xff',b'x'*65537):
                path.write_bytes(raw)
                with self.assertRaisesRegex(ValueError,'^study prompt unavailable$'):
                    load_study_feedback_prompt(replace(config,study_feedback_prompt_sha256=digest(path)))

    def test_readiness_requires_schema_two_held_control_lease_and_private_pin(self):
        config=SimpleNamespace(manifest_sha256='c'*64,study_feedback_prompt_path=Path('/synthetic/private'))
        runtime=LocalRuntime(config);runtime.health=lambda:{'ready':True};runtime._integrity=True
        self.assertFalse(runtime.feedback_health()['ready'])
        lease=object();runtime._ownership_lease=lease;runtime._control_ledger=SimpleNamespace(schema_revision=1,ownership_lease=lease,feedback_ready=lambda:True)
        self.assertFalse(runtime.feedback_health()['ready']);runtime._control_ledger.schema_revision=2
        self.assertTrue(runtime.feedback_health()['ready'])
        runtime._control_ledger.feedback_ready=lambda:False;self.assertFalse(runtime.feedback_health()['ready'])
        runtime._control_ledger.feedback_ready=lambda:True
        runtime._control_ledger.ownership_lease=object();self.assertFalse(runtime.feedback_health()['ready'])


class FeedbackWorkerRequestTests(unittest.TestCase):
    def test_supervisor_passes_dedicated_source_with_one_claim_and_no_user_input(self):
        body=fixture();turn=TurnRequest.from_wire(body)
        runtime=LocalRuntime(SimpleNamespace(manifest_sha256=body['model_manifest_hash'],study_feedback_prompt_path=Path('/synthetic/private')))
        runtime.feedback_health=lambda:{'ready':True};runtime.health=lambda:{'runtime_ready':True}
        runtime._generation_id='synthetic-generation';runtime._control_ledger=Mock()
        runtime._control_ledger.control_send_allowed.return_value=True;runtime._control_ledger.claim_control_send.return_value=True
        sent=[];runtime._pipe=SimpleNamespace(send=lambda request:sent.append(copy.deepcopy(request)))
        runtime._cancel=SimpleNamespace(clear=lambda:None)
        async def receive(deadline):return {'request_id':turn.request_id,'output':fixture('model-feedback'),**MEASURED}
        runtime._receive=receive
        async def wait_for(value,timeout):return await value
        with patch('jarvis_intelligence.local_runtime.asyncio.wait_for',wait_for):
            result=immediate(runtime.generate(turn,4102444800))
        self.assertEqual(result['output']['kind'],'FEEDBACK');self.assertEqual(len(sent),1)
        self.assertNotIn('user_input',sent[0]);self.assertEqual(sent[0]['feedback_source'],body['feedback_source'])
        runtime._control_ledger.claim_control_send.assert_called_once_with(turn.request_id,'synthetic-generation')
        runtime._control_ledger.control_completed.assert_called_once_with(turn.request_id,'synthetic-generation')

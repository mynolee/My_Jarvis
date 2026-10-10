"""Synthetic CPU/process tests; none is evidence of actual MLX inference."""
import asyncio
import hashlib
import json
import os
import tempfile
import time
import unittest
from dataclasses import replace
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from jarvis_intelligence.contracts import ContractViolation, parse_json
from jarvis_intelligence.local_runtime import LocalConfig, LocalRuntime, RuntimeFailure, digest, verify_artifacts
from jarvis_intelligence.mlx_adapter import admit


def fake_worker(pipe, config, cancel, owner_handle):
    fd = owner_handle
    try:
        pipe.send({'ready': True})
        while True:
            request = pipe.recv()
            if request['user_input'] == 'hang':
                time.sleep(30)
            elif request['user_input'] == 'cooperative':
                while not cancel.is_set():
                    time.sleep(.01)
                pipe.send({'request_id': request['request_id'], 'error': 'CANCELLED'})
                continue
            else:
                time.sleep(.15)
            pipe.send({'request_id': request['request_id'], 'output': {'kind': 'ANSWER', 'reply_text': 'synthetic', 'cited_source_ids': []},
                       'input_tokens': 10, 'output_tokens': 2, 'compute_ms': 150, 'peak_memory_bytes': 1, 'finish_reason': 'stop'})
    finally:
        os.close(fd)
        pipe.close()


def fixture(root):
    model = root / 'model'
    model.mkdir()
    for name, content in {'config.json': '{}', 'tokenizer_config.json': '{}', 'processor_config.json': '{}',
                          'chat_template.jinja': 'synthetic', 'model.safetensors.index.json': '{"weight_map":{"x":"weights.safetensors"}}',
                          'weights.safetensors': 'synthetic, not actual tensors'}.items():
        (model / name).write_text(content)
    lock, prompt = root / 'runtime.lock', root / 'prompt.txt'
    lock.write_text('synthetic==0')
    prompt.write_text('synthetic')
    manifest = root / 'manifest.json'
    data = {'complete': True, 'revision': 'a' * 40, 'repo': 'fixture/model', 'upstream_repo': 'fixture/upstream',
            'upstream_revision': 'b' * 40, 'upstream_license_sha256': 'c' * 64, 'conversion_license_sha256': 'd' * 64,
            'licenses_reviewed': True, 'runtime_lock_sha256': digest(lock), 'prompt_sha256': digest(prompt),
            'template_files': ['chat_template.jinja'], 'tokenizer_files': ['tokenizer_config.json'],
            'processor_files': ['processor_config.json'],
            'files': [{'path': f.name, 'size': f.stat().st_size, 'sha256': digest(f)} for f in sorted(model.iterdir())]}
    manifest.write_text(json.dumps(data))
    return LocalConfig(model, manifest, lock, prompt, root / 'worker.lock', digest(lock), digest(prompt), digest(manifest))


def request(config, rid='one', text='normal', compute=60000):
    return SimpleNamespace(request_id=rid, model_manifest_hash=config.manifest_sha256, user_input=text,
                           allowed_tool_ids=[], current_time='2030-01-01T00:00:00Z',timezone='UTC', budget=SimpleNamespace(max_input_tokens=3584, max_output_tokens=512, max_compute_ms=compute))


class AdmissionTests(unittest.TestCase):
    def test_exact_tensor_total_budget_and_fixed_reserve(self):
        self.assertEqual(admit(SimpleNamespace(shape=(1, 3584)), 4096, 512), 3584)
        for count, max_input, output in ((3585,4096,512),(3585,4096,1),(200,199,512),(1,4096,513)):
            with self.assertRaises(RuntimeFailure):
                admit(SimpleNamespace(shape=(1,count)), max_input, output)

    def test_generic_array_ceiling(self):
        with self.assertRaises(ContractViolation):
            parse_json(json.dumps([0]*1001).encode())


class ArtifactTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.config = fixture(Path(self.tmp.name))
    def tearDown(self):
        self.tmp.cleanup()
    def test_intact_inventory(self):
        self.assertEqual(verify_artifacts(self.config), self.config.manifest_sha256)
    def test_hash_tamper(self):
        (self.config.model_path/'weights.safetensors').write_text('tampered')
        with self.assertRaises(ValueError): verify_artifacts(self.config)
    def test_unlisted_python_rejected(self):
        (self.config.model_path/'custom.py').write_text('raise RuntimeError')
        with self.assertRaises(ValueError): verify_artifacts(self.config)
    def test_symlink_rejected(self):
        (self.config.model_path/'extra.txt').symlink_to(self.config.prompt_path)
        with self.assertRaises(ValueError): verify_artifacts(self.config)
    def test_unpinned_manifest_rejected(self):
        with self.assertRaises(ValueError): verify_artifacts(replace(self.config, manifest_sha256='0'*64))
    def test_manifest_missing_license_evidence(self):
        obj=json.loads(self.config.manifest_path.read_text()); obj.pop('licenses_reviewed')
        self.config.manifest_path.write_text(json.dumps(obj))
        with self.assertRaises(ValueError): verify_artifacts(replace(self.config, manifest_sha256=digest(self.config.manifest_path)))
    def test_private_configuration_permissions(self):
        path=Path(self.tmp.name)/'config.json'
        path.write_text(json.dumps({key:str(value) for key,value in self.config.__dict__.items()})); path.chmod(0o600)
        self.assertEqual(LocalConfig.from_file(path), self.config)
        path.chmod(0o644)
        with self.assertRaises(ValueError): LocalConfig.from_file(path)


class SupervisorTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.tmp=tempfile.TemporaryDirectory(); self.config=fixture(Path(self.tmp.name))
        self.runtime=LocalRuntime(self.config, worker_target=fake_worker)
        await self.runtime.start()
    async def asyncTearDown(self):
        await self.runtime.close(); self.tmp.cleanup()
    async def test_reload_rejects_tamper_and_clears_stale_integrity(self):
        await self.runtime._stop_worker()
        self.runtime._inference_verified=True
        (self.config.model_path/'weights.safetensors').write_text('tampered')
        with self.assertRaises(RuntimeFailure): await self.runtime.start()
        self.assertFalse(self.runtime.health()['integrity_verified'])
        self.assertFalse(self.runtime.health()['inference_verified'])
        self.assertFalse(self.runtime.health()['ready'])
    async def test_health_never_generates_or_claims_real_inference(self):
        pid=self.runtime._process.pid
        for _ in range(10): self.assertTrue(self.runtime.health()['ready'])
        result=await self.runtime.generate(request(self.config),time.time()+5)
        self.assertEqual(result['request_id'],'one')
        self.assertFalse(self.runtime.health()['inference_verified'])
        self.assertEqual(pid,self.runtime._process.pid)
    async def test_single_generation_two_waiters_fourth_backpressure(self):
        tasks=[asyncio.create_task(self.runtime.generate(request(self.config,str(i)),time.time()+5)) for i in range(4)]
        results=await asyncio.gather(*tasks,return_exceptions=True)
        self.assertEqual(sum(isinstance(x,dict) for x in results),3)
        failures=[x for x in results if isinstance(x,RuntimeFailure)]
        self.assertEqual([x.code for x in failures],['RESOURCE_LIMIT'])
    async def test_duplicate_owner_two_attempts_never_bypass_lock(self):
        second=LocalRuntime(self.config,worker_target=fake_worker)
        for _ in range(2):
            with self.assertRaises(RuntimeFailure): await second.start()
            self.assertIsNone(second._owner_fd)
            self.assertIsNone(second._process)
        await second.close()
    async def test_timeout_verifies_death_before_slot_release_and_reload(self):
        previous=self.runtime._process
        with self.assertRaises(RuntimeFailure) as caught:
            await self.runtime.generate(request(self.config,text='hang',compute=100),time.time()+5)
        self.assertEqual(caught.exception.code,'GENERATION_TIMEOUT')
        self.assertFalse(previous.is_alive()); self.assertIsNotNone(previous.exitcode)
        self.assertFalse(self.runtime._serial.locked()); self.assertFalse(self.runtime.health()['ready'])
        await self.runtime.start()
        self.assertNotEqual(previous.pid,self.runtime._process.pid)
    async def test_cancellation_waits_for_actual_worker_death(self):
        previous=self.runtime._process
        task=asyncio.create_task(self.runtime.generate(request(self.config,text='hang'),time.time()+5))
        await asyncio.sleep(.1); task.cancel()
        with self.assertRaises(asyncio.CancelledError): await task
        self.assertFalse(previous.is_alive()); self.assertIsNotNone(previous.exitcode)
        self.assertFalse(self.runtime._serial.locked())
    async def test_cooperative_cancel_acknowledged(self):
        task=asyncio.create_task(self.runtime.generate(request(self.config,text='cooperative'),time.time()+5))
        await asyncio.sleep(.1); await self.runtime.cancel('one')
        with self.assertRaises(RuntimeFailure) as caught: await task
        self.assertEqual(caught.exception.code,'CANCELLED')
        self.assertFalse(self.runtime._serial.locked())
    async def test_worker_keeps_kernel_owner_lock_after_parent_fd_closes(self):
        os.close(self.runtime._owner_fd); self.runtime._owner_fd=None
        second=LocalRuntime(self.config,worker_target=fake_worker)
        with self.assertRaises(RuntimeFailure): await second.start()
        self.assertIsNone(second._owner_fd)
        await second.close()
    async def test_cancelling_waiter_does_not_stop_active_generation(self):
        process=self.runtime._process
        active=asyncio.create_task(self.runtime.generate(request(self.config,'active'),time.time()+5))
        await asyncio.sleep(.02)
        waiting=asyncio.create_task(self.runtime.generate(request(self.config,'waiting'),time.time()+5))
        await asyncio.sleep(.02); waiting.cancel()
        with self.assertRaises(asyncio.CancelledError): await waiting
        self.assertEqual((await active)['request_id'],'active')
        self.assertTrue(process.is_alive())
    async def test_unresponsive_cancel_hard_stops(self):
        previous=self.runtime._process
        task=asyncio.create_task(self.runtime.generate(request(self.config,text='hang'),time.time()+5))
        await asyncio.sleep(.1); await self.runtime.cancel('one')
        with self.assertRaises(RuntimeFailure) as caught: await task
        self.assertEqual(caught.exception.code,'CANCELLED'); self.assertFalse(previous.is_alive())

class AdapterTensorTests(unittest.TestCase):
    def test_admitted_tensors_passed_unchanged_and_schema_counted(self):
        from jarvis_intelligence.mlx_adapter import MLXAdapter
        adapter=object.__new__(MLXAdapter)
        adapter.system='synthetic'
        adapter.processor=object()
        adapter.config={}
        adapter.model=SimpleNamespace(config=SimpleNamespace(model_type='qwen3_5'))
        ids=SimpleNamespace(shape=(1,3584)); mask=object()
        seen={}
        def render(processor,config,messages,**kwargs):
            seen['messages']=messages; return 'rendered'
        def prepare(processor,**kwargs):
            seen['prepare']=kwargs; return {'input_ids':ids,'attention_mask':mask}
        def stream(model,processor,prompt,**kwargs):
            seen['stream']=kwargs
            yield SimpleNamespace(text='{"kind":"ANSWER","reply_text":"synthetic","cited_source_ids":[]}',generation_tokens=20,finish_reason='stop')
        adapter.render=render;adapter.prepare=prepare;adapter.stream=stream;adapter.special=lambda *args:True
        adapter.mx=SimpleNamespace(reset_peak_memory=lambda:None,get_peak_memory=lambda:100)
        result=adapter.generate({'user_input':'synthetic','allowed_tool_ids':[],'current_time':'2030-01-01T00:00:00Z','timezone':'UTC', 'max_input_tokens':3584,'max_output_tokens':512},SimpleNamespace(is_set=lambda:False))
        self.assertIs(seen['stream']['input_ids'],ids)
        self.assertIs(seen['stream']['mask'],mask)
        self.assertTrue(seen['prepare']['add_special_tokens'])
        self.assertIn('oneOf',seen['messages'][0]['content'])
        self.assertEqual(result['input_tokens'],3584)
        self.assertEqual(result['output_tokens'],20)
        self.assertEqual(result['peak_memory_bytes'],100)
    def test_model_json_not_repaired(self):
        from jarvis_intelligence.app import _validate_output
        turn=SimpleNamespace(request_id='10000000-0000-4000-8000-000000000001',model_manifest_hash='a'*64,
              allowed_tool_ids=[],budget=SimpleNamespace(reservation_id='10000000-0000-4000-8000-000000000001',max_input_tokens=3584,max_output_tokens=512))
        measured={'input_tokens':10,'output_tokens':10,'compute_ms':1,'peak_memory_bytes':1}
        with self.assertRaises(ContractViolation):
            _validate_output({'kind':'ANSWER','reply_text':'x','cited_source_ids':[],'approved':True},turn,measured=measured)
        with self.assertRaises(ContractViolation):
            _validate_output({'kind':'ANSWER','reply_text':'x','cited_source_ids':[],'usage':{}},turn,measured=measured)


class UnconfirmedDeathTests(unittest.IsolatedAsyncioTestCase):
    async def test_unconfirmed_death_poison_prevents_restart(self):
        with tempfile.TemporaryDirectory() as temp:
            runtime=LocalRuntime(fixture(Path(temp)),worker_target=fake_worker)
            runtime._process=SimpleNamespace(is_alive=lambda:True,terminate=lambda:None,kill=lambda:None,join=lambda timeout:None,exitcode=None)
            with self.assertRaises(RuntimeFailure) as caught: await runtime._stop_worker()
            self.assertEqual(caught.exception.code,'WORKER_DEATH_UNCONFIRMED')
            self.assertTrue(runtime._poisoned)
            with self.assertRaises(RuntimeFailure): await runtime.start()
            self.assertFalse(runtime.health()['ready'])

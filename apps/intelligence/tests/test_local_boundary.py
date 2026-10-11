"""Authenticated ASGI local-mode tests using only a synthetic runtime."""
import json
from dataclasses import replace
from unittest.mock import patch

from fastapi.testclient import TestClient
from jarvis_intelligence.app import create_app
import test_boundary as boundary
from test_local_runtime import fixture


class FakeRuntime:
    def __init__(self, config):
        self.manifest_hash=config.manifest_sha256
        self.calls=0
        self.output={'kind':'ANSWER','reply_text':'Synthetic local-boundary test','cited_source_ids':[]}
    async def start(self): pass
    async def close(self): pass
    def health(self):
        return {'ready':True,'status':'READY','model_id':'local-mlx-v1','model_manifest_hash':self.manifest_hash,
                'download_complete':True,'integrity_verified':True,'runtime_ready':True,
                'inference_verified':False,'integration_verified':False,'control_revision':1}
    async def generate(self, turn, expiry):
        self.calls+=1
        return {'output':self.output,'input_tokens':100,'output_tokens':15,'compute_ms':1,'peak_memory_bytes':64}


# Reuse request signing helpers without inheriting the mock-specific test suite.
class LocalBoundaryTests(__import__('unittest').TestCase):
    claims=boundary.BoundaryTests.claims
    headers=boundary.BoundaryTests.headers
    send=boundary.BoundaryTests.send
    def setUp(self):
        boundary.BoundaryTests.setUp(self)
        self.client.close()
        self.path = self.path.with_name("local-replay.sqlite")
        self.settings = replace(self.settings, ledger_path=self.path)
        config=fixture(self.path.parent)
        self.runtime=FakeRuntime(config)
        self.settings=replace(self.settings,mode='local_mlx',allow_loopback_mock_without_mtls=False,local_config=config)
        self.app=create_app(self.settings,runtime=self.runtime)
        self.client=TestClient(self.app,client=('127.0.0.1',45000))
        self.body.update(model_id='local-mlx-v1',prompt_revision='local-v1',model_manifest_hash=self.runtime.manifest_hash)
        self.body['budget']['max_input_tokens']=3584
    def tearDown(self):
        self.client.close()
        self.app.state.ledger.ownership_lease.close()
        self.temp.cleanup()
    def test_missing_mtls_never_calls_runtime(self):
        self.assertEqual(self.send().status_code,401)
        self.assertEqual(self.runtime.calls,0)
    def test_bad_capability_never_calls_runtime(self):
        with patch('jarvis_intelligence.app._peer_allowed',return_value=True):
            headers=self.headers();headers['x-jarvis-capability']='invalid'
            self.assertEqual(self.send(headers=headers).status_code,401)
        self.assertEqual(self.runtime.calls,0)
    def test_valid_capability_exact_usage_and_replay_no_generation(self):
        with patch('jarvis_intelligence.app._peer_allowed',return_value=True):
            first=self.send();second=self.send()
        self.assertEqual(first.status_code,200)
        self.assertEqual(first.json()['usage']['input_tokens'],100)
        self.assertEqual(first.json(),second.json());self.assertEqual(self.runtime.calls,1)
    def test_authority_output_unknown_without_repair(self):
        self.runtime.output['approved']=True
        with patch('jarvis_intelligence.app._peer_allowed',return_value=True):
            result=self.send();replay=self.send()
        self.assertEqual(result.status_code,409)
        self.assertEqual(result.json()['error_code'],'MODEL_OUTPUT_INVALID')
        self.assertEqual(replay.json()['error_code'],'INFERENCE_UNKNOWN')
        self.assertEqual(self.runtime.calls,1)
    def test_readiness_no_inference(self):
        from jarvis_intelligence.contracts import validate
        for _ in range(4):
            readiness=validate('runtime-readiness',self.client.get('/health/ready').json())
            self.assertEqual(readiness['model_manifest_hash'],self.runtime.manifest_hash)
            self.assertEqual(readiness['control_revision'],1)
        self.assertEqual(self.runtime.calls,0)
    def test_wrong_manifest_never_generates(self):
        self.body['model_manifest_hash']='f'*64
        with patch('jarvis_intelligence.app._peer_allowed',return_value=True):
            self.assertEqual(self.send().status_code,400)
        self.assertEqual(self.runtime.calls,0)

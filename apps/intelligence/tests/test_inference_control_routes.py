"""ASGI/SQLite integration tests authored for approved CI, not guarded local runs."""
import copy
import json
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import Mock, patch

from fastapi.testclient import TestClient
from jarvis_intelligence.app import Settings, create_app
from jarvis_intelligence.contracts import MAX_CONTROL_BYTES, body_hash, canonical
from test_inference_control_contract import fixture, token
from test_inference_control_ledger import claims, descriptor, request


class ControlRouteTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(); self.path=Path(self.tmp.name)/'ledger.sqlite'
        self.key=Mock(); self.now=datetime(2026,1,1,tzinfo=timezone.utc).timestamp()
        self.app=create_app(Settings(self.path,b's'*32,{'synthetic-core':self.key},
                                    allow_loopback_mock_without_mtls=True,mode='unavailable'),clock=lambda:self.now)
        self.client=TestClient(self.app,client=('127.0.0.1',43000))
        self.body=fixture()['request']

    def tearDown(self):
        self.client.close(); self.tmp.cleanup()

    def headers(self,body=None,changes=None):
        body=body or self.body
        claims={**fixture()['capability'],**body,'body_hash':body_hash(body),**(changes or {})}
        return {'content-type':'application/json','x-jarvis-capability':token(claims)}

    def send(self,body=None,headers=None,path=None):
        body=body or self.body
        path=path or ('/internal/v1/inference-control/observe' if body['operation']=='OBSERVE' else '/internal/v1/inference-control')
        return self.client.post(path,content=canonical(body),headers=headers or self.headers(body))

    def test_model_unready_still_fences_and_observes_without_generation(self):
        self.assertFalse(self.client.get('/health/ready').json()['ready'])
        response=self.send()
        self.assertEqual(response.status_code,200)
        self.assertEqual(response.json()['terminal']['kind'],'UNSTARTED_FENCED')
        self.assertEqual(self.send({**self.body,'operation':'OBSERVE'}).json(),response.json())
        self.assertEqual(self.app.state.mock_invocations,0)
        self.assertEqual(response.headers['cache-control'],'no-store')

    def test_observe_unknown_has_no_fence_or_slot_allocation(self):
        response=self.send({**self.body,'operation':'OBSERVE'})
        self.assertEqual(response.status_code,200); self.assertEqual(response.json()['state'],'NOT_OBSERVED')
        with self.app.state.ledger.connect() as db:
            self.assertEqual(db.execute('SELECT COUNT(*) FROM control_identity').fetchone()[0],0)

    def test_mtls_requirement_precedes_capability_and_storage(self):
        with patch('jarvis_intelligence.app._peer_allowed',return_value=False): result=self.send()
        self.assertEqual(result.status_code,401)
        self.key.verify.assert_not_called()
        self.assertEqual(self.send({**self.body,'operation':'OBSERVE'}).json()['state'],'NOT_OBSERVED')

    def test_expiry_equality_future_issue_and_wrong_audience_refuse_without_fence(self):
        for changes in ({'expires_at':'2026-01-01T00:00:00Z'},
                        {'issued_at':'2026-01-01T00:00:00.000001Z'},
                        {'audience':'jarvis-orchestrator'}, {'max_model_calls':1}):
            self.assertEqual(self.send(headers=self.headers(changes=changes)).status_code,401)
        self.assertEqual(self.send({**self.body,'operation':'OBSERVE'}).json()['state'],'NOT_OBSERVED')

    def test_route_operation_must_match_and_query_identity_is_rejected(self):
        self.assertEqual(self.send(path='/internal/v1/inference-control/observe').status_code,400)
        self.assertEqual(self.send(path='/internal/v1/inference-control?request_id=synthetic').status_code,400)
        self.assertEqual(self.client.get('/internal/v1/inference-control').status_code,405)

    def test_bounded_strict_streamed_envelope_and_headers(self):
        for raw in (b'x'*(MAX_CONTROL_BYTES+1),b'{"revision":1,"revision":1}',b'\xff',b'{"x":1.1}'):
            self.assertEqual(self.client.post('/internal/v1/inference-control',content=raw,headers=self.headers()).status_code,400)
        headers={**self.headers(),'x-padding':'x'*8192}
        self.assertEqual(self.send(headers=headers).status_code,400)
        duplicate=[*self.headers().items(),('x-jarvis-capability',self.headers()['x-jarvis-capability'])]
        self.assertEqual(self.send(headers=duplicate).status_code,401)
        self.assertEqual(self.send(headers={**self.headers(),'content-encoding':'gzip'}).status_code,400)

    def test_identity_conflict_and_capacity_errors_are_generic(self):
        self.app.state.ledger.max_rows=1
        self.assertEqual(self.send().status_code,200)
        changed={**self.body,'origin_binding':'f'*64}
        self.assertEqual(self.send(changed).status_code,409)
        changed=request(77)
        self.assertEqual(self.send(changed).status_code,429)
        self.assertEqual(self.send({**self.body,'operation':'OBSERVE'}).status_code,200)

    def test_ledger_failure_never_echoes_path_traceback_or_capability(self):
        with patch.object(self.app.state.ledger,'control',side_effect=RuntimeError('/private/sql/path synthetic-secret')):
            result=self.send()
        self.assertEqual(result.status_code,503)
        self.assertEqual(result.json(),{'error_code':'CONTROL_UNAVAILABLE'})
        self.assertNotIn('synthetic-secret',result.text)

    def test_historical_replay_cannot_be_upgraded_by_safety_route(self):
        self.app.state.ledger.begin(claims(self.body),self.now,60,cache_response=False)
        self.assertEqual(self.send().status_code,409)
        self.assertEqual(self.send({**self.body,'operation':'OBSERVE'}).status_code,409)

    def test_fresh_safety_token_observes_fact_after_original_expiry(self):
        fact=self.send().json(); self.now+=86400
        body={**self.body,'operation':'OBSERVE'}
        headers=self.headers(body,{'issued_at':'2026-01-02T00:00:00Z','expires_at':'2026-01-02T00:00:30Z'})
        result=self.send(body,headers)
        self.assertEqual(result.status_code,200); self.assertEqual(result.json(),fact)

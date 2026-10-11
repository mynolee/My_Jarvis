"""Actual ASGI/SQLite tests for authorized CI; never in guarded local runs."""
import base64
import asyncio
import copy
import json
import tempfile
import time
import unittest
from datetime import datetime, timezone
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch
from fastapi.testclient import TestClient
from jarvis_intelligence.app import Settings, create_app
from jarvis_intelligence.contracts import TurnRequest, body_hash, canonical, control_identity
from jarvis_intelligence.ledger import Ledger, ReplayConflict
from jarvis_intelligence.local_runtime import LocalRuntime, OwnershipLease, RuntimeFailure
from test_study_feedback_contract import fixture
from test_study_feedback_runtime import feedback_config
from test_context_transport import fake_adapter


def synthetic_feedback_control_worker(pipe, config, cancel, inherited_lock):
    """Actual CPU fixture child, deliberately independent of every model runtime."""
    inherited_fd = inherited_lock  # _InheritedLock reduction yields the original inherited FD.
    try:
        pipe.send({'ready': True})
        while True:
            request = pipe.recv()
            if request.get('purpose') != 'STUDY_FEEDBACK' or 'user_input' in request:
                pipe.send({'request_id': request['request_id'], 'error': 'CONTRACT_VIOLATION'})
                continue
            marker = request['feedback_source']['answer']['text']
            if marker == 'SYNTHETIC UNCOOPERATIVE FEEDBACK':
                while True:
                    time.sleep(.01)  # Only confirmed process exit can close this request.
            if marker == 'SYNTHETIC COOPERATIVE FEEDBACK':
                while not cancel.is_set():
                    time.sleep(.005)
                pipe.send({'request_id': request['request_id'], 'error': 'CANCELLED'})
            else:
                pipe.send({'request_id': request['request_id'], 'output': fixture('model-feedback'),
                           'input_tokens': 120, 'output_tokens': 65, 'compute_ms': 1, 'peak_memory_bytes': 1024})
    except EOFError:
        pass
    finally:
        import os
        os.close(inherited_fd)
        pipe.close()


class RecordingFeedbackPipe:
    def __init__(self, pipe, ledger, generation):
        self.pipe, self.ledger, self.generation = pipe, ledger, generation
        self.sent = []

    def send(self, request):
        # This reads the committed real SQLite identity before the actual send.
        if self.ledger.control_generation_for(request['request_id']) != self.generation:
            raise AssertionError('feedback send preceded durable generation claim')
        if request.get('purpose') != 'STUDY_FEEDBACK' or 'user_input' in request:
            raise AssertionError('feedback crossed generic source path')
        self.sent.append(copy.deepcopy(request))
        self.pipe.send(request)

    def __getattr__(self, name):
        return getattr(self.pipe, name)


class SyntheticFeedbackRuntime(LocalRuntime):
    """Exercise the actual supervisor/claim/adapter path without spawning MLX."""
    def __init__(self,config):
        super().__init__(config,worker_target=lambda:None)
        self.calls=0;self.available=True;self.last_request=None;self.result=None
        self.adapter,self.seen=fake_adapter(lambda _:120)
        self.adapter.study_feedback_system='SYNTHETIC PLACEHOLDER, NOT A MODEL INSTRUCTION'
        def stream(*args,**kwargs):
            self.calls+=1;self.seen['streams'].append(kwargs)
            yield SimpleNamespace(text=json.dumps(fixture('model-feedback')),generation_tokens=65,finish_reason='stop')
        self.adapter.stream=stream

    async def start(self):
        identity=self._verified_owner_lock()
        if self._recover_ownership:
            self._control_ledger.recover_control_generations(*identity);self._recover_ownership=False
        self._generation_id=self._control_ledger.register_control_generation(*identity)
        self._control_ledger.control_child_starting(self._generation_id)
        self._cancel=SimpleNamespace(clear=lambda:None,is_set=lambda:False,set=lambda:None)
        self._pipe=SimpleNamespace(send=self.send)
        self._integrity=True;self._ready=True

    def send(self,request):
        self.last_request=copy.deepcopy(request)
        self.result={'request_id':request['request_id'],**self.adapter.generate(request,self._cancel)}

    async def _receive(self,deadline):return self.result

    def health(self):
        ready=bool(self._ready and self.available)
        return {'ready':ready,'status':'READY' if ready else 'MODEL_UNAVAILABLE','model_id':'local-mlx-v1',
                'model_manifest_hash':self.manifest_hash,'download_complete':self._integrity,
                'integrity_verified':self._integrity,'runtime_ready':ready,'inference_verified':False,
                'integration_verified':False,'control_revision':1}

    async def close(self):
        if self._generation_id is not None:
            self._control_ledger.control_worker_stopped(self._generation_id);self._generation_id=None
        self._ready=False
        if self._ownership_lease:self._ownership_lease.close()


class StudyFeedbackBoundaryTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        root=Path(self.temp.name);config=feedback_config(root)
        self.runtime=SyntheticFeedbackRuntime(config);self.key=Mock()
        self.app=create_app(Settings(root/'ledger.sqlite',b's'*32,{'synthetic-core':self.key},mode='local_mlx',local_config=config),runtime=self.runtime)
        self.client=TestClient(self.app,client=('127.0.0.1',45000));self.client.__enter__();self.addCleanup(lambda:self.client.__exit__(None,None,None))
        self.body=fixture();self.body['model_manifest_hash']=self.runtime.manifest_hash

    def headers(self,body):
        stamp=lambda value:datetime.fromtimestamp(value,timezone.utc).isoformat()
        now=time.time();claims={'issuer':'jarvis-core','audience':'jarvis-orchestrator','key_id':'synthetic-core',
            'owner_id':'aaaaaaaa-0000-4000-8000-000000000001','request_id':body['request_id'],'run_id':body['run_id'],
            'body_hash':body_hash(body),'reservation_id':body['budget']['reservation_id'],'model_id':body['model_id'],
            'max_model_calls':1,'max_output_tokens':512,'issued_at':stamp(now-1),'expires_at':stamp(now+50),
            'nonce':'aaaaaaaa-0000-4000-8000-000000000002'}
        encode=lambda raw:base64.urlsafe_b64encode(raw).decode().rstrip('=')
        return {'content-type':'application/json','x-jarvis-capability':encode(canonical(claims))+'.'+encode(b'synthetic-signature')}

    def send(self,body=None):
        body=self.body if body is None else body
        return self.client.post('/internal/v1/turn',content=canonical(body),headers=self.headers(body))

    def test_one_authenticated_request_one_actual_synthetic_adapter_stream_no_generic_source(self):
        with patch('jarvis_intelligence.app._peer_allowed',return_value=True):
            result=self.send();replay=self.send()
        self.assertEqual(result.status_code,200,result.text);self.assertEqual(result.json()['kind'],'FEEDBACK')
        self.assertEqual(result.json()['usage']['input_tokens'],120);self.assertEqual(self.runtime.calls,1)
        self.assertEqual(replay.status_code,409);self.assertNotIn('user_input',self.runtime.last_request)
        self.assertEqual(self.runtime.last_request['feedback_source'],self.body['feedback_source'])
        with self.app.state.ledger.connect() as db:
            self.assertEqual(db.execute('SELECT response,nonce,cache_until,cache_response,control_revision FROM replay').fetchone(),(None,None,None,0,1))
            self.assertEqual(db.execute('SELECT terminal_kind FROM control_identity').fetchone(),('COMPLETED',))

    def test_feedback_and_base_readiness_are_nongenerating_closed_and_authenticated(self):
        self.assertEqual(self.client.get('/health/study-feedback').status_code,401)
        with patch('jarvis_intelligence.app._peer_allowed',return_value=True):
            for _ in range(3):
                response=self.client.get('/health/study-feedback')
                self.assertEqual(set(response.json()),{'revision','control_revision','model_id','model_manifest_hash','ready'})
                self.assertTrue(response.json()['ready']);self.assertEqual(response.headers['cache-control'],'no-store')
                self.assertEqual(len(self.client.get('/health/ready').json()),10)
        self.assertEqual(self.runtime.calls,0)
        with self.app.state.ledger.connect() as db:self.assertEqual(db.execute('SELECT COUNT(*) FROM replay').fetchone()[0],0)

    def test_unready_rejects_before_replay_allocation_without_mock_fallback(self):
        self.runtime.available=False
        with patch('jarvis_intelligence.app._peer_allowed',return_value=True):response=self.send()
        self.assertEqual(response.status_code,503);self.assertEqual(self.runtime.calls,0);self.assertEqual(self.app.state.mock_invocations,0)
        with self.app.state.ledger.connect() as db:self.assertEqual(db.execute('SELECT COUNT(*) FROM replay').fetchone()[0],0)

    def test_invalid_capability_and_injected_generic_input_never_generate(self):
        with patch('jarvis_intelligence.app._peer_allowed',return_value=True):
            response=self.client.post('/internal/v1/turn',content=canonical(self.body),headers={'content-type':'application/json','x-jarvis-capability':'invalid'})
            self.assertEqual(response.status_code,401)
            self.assertEqual(self.send({**self.body,'user_input':'synthetic injected source'}).status_code,400)
        self.assertEqual(self.runtime.calls,0)

    def test_original_lease_is_attached_before_start_and_same_descriptor_survives(self):
        ledger=self.app.state.ledger
        self.assertIs(ledger.ownership_lease,self.runtime._ownership_lease)
        self.assertEqual(self.runtime._owner_fd,ledger.ownership_lease.fd)
        self.assertEqual(ledger.schema_revision,2)
        with ledger.connect() as db:
            row=db.execute('SELECT lock_device,lock_inode FROM control_generation').fetchone()
            self.assertEqual(row,ledger.ownership_lease.verify())


class StudyFeedbackControlProcessTests(unittest.IsolatedAsyncioTestCase):
    """Real SQLite/spawn/lease feedback cases, authored for CI and NOT_RUN locally.

    Stage A ControlLedgerTests and MockedControlRuntimeTests retain the generic
    concurrent serialization, deadline and ordinal cases. These cases exercise
    the dedicated STUDY_FEEDBACK discriminator through the actual supervisor.
    """
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.config = feedback_config(self.root)
        self.lease = OwnershipLease.acquire(self.config.lock_path)
        self.addCleanup(self.lease.close)
        self.ledger = Ledger(self.root/'ledger.sqlite', b's'*32, max_concurrency=3,
                             ownership_lease=self.lease)
        self.runtime = LocalRuntime(self.config, worker_target=synthetic_feedback_control_worker)
        self.runtime.attach_control_ledger(self.ledger, ownership_lease=self.lease)
        self.addAsyncCleanup(self.runtime.close)
        await self.runtime.start()
        self.assertTrue(self.runtime.feedback_health()['ready'])
        self.runtime._closing = True  # Do not launch recovery tasks after a fixture stop.
        self.pipe = RecordingFeedbackPipe(self.runtime._pipe, self.ledger, self.runtime._generation_id)
        self.runtime._pipe = self.pipe

    def request(self, number=1, marker='SYNTHETIC NORMAL FEEDBACK'):
        body = fixture()
        identifier = lambda index: f'{index:08x}-0000-4000-8000-{index:012x}'
        body.update(request_id=identifier(1000+number), run_id=identifier(2000+number),
                    model_manifest_hash=self.config.manifest_sha256)
        body['budget']['reservation_id'] = identifier(3000+number)
        body['context']['id'] = identifier(4000+number)
        body['feedback_source']['feedback_id'] = identifier(5000+number)
        body['feedback_source']['answer']['text'] = marker
        identity = control_identity(body, {'owner_id': 'aaaaaaaa-0000-4000-8000-000000000001'})
        claims = {key: identity[key] for key in ('owner_id', 'request_id', 'reservation_id', 'run_id')}
        claims['body_hash'] = body_hash(body)
        return TurnRequest.from_wire(body), identity, claims

    def begin(self, request):
        turn, identity, claims = request
        self.ledger.begin(claims, time.time(), 60, cache_response=False, control=identity)
        return turn

    def command(self, request, operation='FENCE_OR_STOP'):
        return {**request[1], 'operation': operation}

    def observe(self, request):
        return self.ledger.control(self.command(request, 'OBSERVE'))

    async def wait_active(self, request, task):
        for _ in range(300):
            if self.runtime._active == request[0].request_id:
                return
            if task.done():
                await task
            await asyncio.sleep(.01)
        self.fail('synthetic feedback never became active')

    def assert_no_cached_source(self):
        with self.ledger.connect() as db:
            self.assertFalse(db.execute('SELECT 1 FROM replay WHERE response IS NOT NULL OR nonce IS NOT NULL OR cache_until IS NOT NULL OR cache_response<>0 OR control_revision<>1').fetchone())
            self.assertFalse(db.execute("SELECT 1 FROM control_identity WHERE purpose<>'STUDY_FEEDBACK'").fetchone())

    async def test_feedback_fence_before_and_after_begin_prevents_every_send(self):
        for number, begin_first in enumerate((False, True), 1):
            with self.subTest(begin_first=begin_first):
                request = self.request(number)
                if begin_first:
                    self.begin(request)
                fact = await self.runtime.control(self.command(request))
                self.assertEqual(fact['terminal']['kind'], 'UNSTARTED_FENCED')
                if begin_first:
                    with self.assertRaises(RuntimeFailure) as caught:
                        await self.runtime.generate(request[0], time.time()+5)
                    self.assertEqual(caught.exception.code, 'CANCELLED')
                else:
                    with self.assertRaises(ReplayConflict):
                        self.begin(request)
                self.assertEqual(self.observe(request), fact)
                self.assertIsNone(self.ledger.control_generation_for(request[0].request_id))
        self.assertEqual(self.pipe.sent, [])
        self.assertFalse(self.runtime._cancel.is_set())
        self.assertTrue(self.runtime._process.is_alive())
        self.assert_no_cached_source()

    async def test_feedback_fence_between_queue_check_and_claim_prevents_send(self):
        request = self.request(); self.begin(request)
        original = self.ledger.control_send_allowed
        def fence_after_check(request_id):
            permitted = original(request_id)
            self.ledger.control(self.command(request))
            return permitted
        with patch.object(self.ledger, 'control_send_allowed', side_effect=fence_after_check):
            with self.assertRaises(RuntimeFailure) as caught:
                await self.runtime.generate(request[0], time.time()+5)
        self.assertEqual(caught.exception.code, 'CANCELLED')
        self.assertEqual(self.observe(request)['terminal']['kind'], 'UNSTARTED_FENCED')
        self.assertEqual(self.pipe.sent, [])
        self.assertTrue(self.runtime._process.is_alive())

    async def test_feedback_queued_fence_does_not_signal_active_feedback_or_send_waiter(self):
        active = self.request(1, 'SYNTHETIC COOPERATIVE FEEDBACK'); self.begin(active)
        waiting = self.request(2); self.begin(waiting)
        task = asyncio.create_task(self.runtime.generate(active[0], time.time()+10))
        waiter = None
        try:
            await self.wait_active(active, task)
            waiter = asyncio.create_task(self.runtime.generate(waiting[0], time.time()+10))
            for _ in range(100):
                if self.runtime._waiting:
                    break
                await asyncio.sleep(0)
            self.assertEqual(self.runtime._waiting, 1)
            fact = await self.runtime.control(self.command(waiting))
            self.assertEqual(fact['terminal']['kind'], 'UNSTARTED_FENCED')
            self.assertFalse(self.runtime._cancel.is_set())
            await self.runtime.control(self.command(active))
            with self.assertRaises(RuntimeFailure):
                await task
            with self.assertRaises(RuntimeFailure) as caught:
                await waiter
            self.assertEqual(caught.exception.code, 'CANCELLED')
            self.assertEqual(self.observe(waiting), fact)
            self.assertEqual(len(self.pipe.sent), 1)
            self.assertEqual(self.pipe.sent[0]['request_id'], active[0].request_id)
            self.assertEqual(self.observe(active)['terminal']['kind'], 'COMPLETED')
        finally:
            tasks = [item for item in (task, waiter) if item is not None]
            for item in tasks:
                if not item.done():
                    item.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)

    async def test_feedback_claimed_send_failure_stays_pending_until_actual_exit(self):
        request = self.request(); self.begin(request)
        process, generation = self.runtime._process, self.runtime._generation_id
        def failed_send(body):
            self.assertNotIn('user_input', body)
            self.assertEqual(body['purpose'], 'STUDY_FEEDBACK')
            self.assertEqual(self.ledger.control_generation_for(body['request_id']), generation)
            self.assertTrue(process.is_alive())
            self.assertEqual(self.ledger.control(self.command(request))['state'], 'PENDING')
            raise OSError('synthetic pre-send pipe failure')
        with patch.object(self.pipe, 'send', side_effect=failed_send):
            with self.assertRaises(OSError):
                await self.runtime.generate(request[0], time.time()+5)
        self.assertFalse(process.is_alive()); self.assertIsNotNone(process.exitcode)
        fact = self.observe(request)
        self.assertEqual(fact['terminal']['kind'], 'WORKER_STOPPED')
        self.assertEqual(fact['terminal']['generation_id'], generation)
        self.assertEqual(self.pipe.sent, [])
        self.assert_no_cached_source()

    async def test_feedback_completed_stop_does_not_cancel_later_warm_feedback(self):
        old = self.request(); self.begin(old)
        generation = self.runtime._generation_id
        result = await self.runtime.generate(old[0], time.time()+5)
        self.assertEqual(result['output']['kind'], 'FEEDBACK')
        old_fact = self.observe(old)
        self.assertEqual(old_fact['terminal']['kind'], 'COMPLETED')
        current = self.request(2, 'SYNTHETIC COOPERATIVE FEEDBACK'); self.begin(current)
        task = asyncio.create_task(self.runtime.generate(current[0], time.time()+10))
        try:
            await self.wait_active(current, task)
            self.assertEqual(await self.runtime.control(self.command(old)), old_fact)
            self.assertFalse(self.runtime._cancel.is_set())
            self.assertEqual(self.runtime._generation_id, generation)
            self.assertEqual((await self.runtime.control(self.command(current)))['state'], 'PENDING')
            with self.assertRaises(RuntimeFailure) as caught:
                await task
            self.assertEqual(caught.exception.code, 'CANCELLED')
        finally:
            if not task.done():
                task.cancel()
                await asyncio.gather(task, return_exceptions=True)
        self.assertEqual(self.observe(current)['terminal']['kind'], 'COMPLETED')
        self.assertEqual(self.observe(current)['terminal']['generation_id'], generation)
        self.assertEqual(self.observe(old), old_fact)
        self.assertEqual(len(self.pipe.sent), 2)
        self.assertTrue(self.runtime._process.is_alive())
        self.assertFalse(self.runtime._serial.locked())
        self.assert_no_cached_source()

    async def test_feedback_unconfirmed_live_child_never_gets_terminal_proof_then_exact_exit_does(self):
        request = self.request(1, 'SYNTHETIC UNCOOPERATIVE FEEDBACK'); self.begin(request)
        process, generation = self.runtime._process, self.runtime._generation_id
        self.assertTrue(self.ledger.claim_control_send(request[0].request_id, generation))
        self.pipe.send({'request_id': request[0].request_id, 'purpose': 'STUDY_FEEDBACK',
                        'feedback_source': request[0].feedback_source})
        self.assertEqual((await self.runtime.control(self.command(request)))['state'], 'PENDING')
        # Mask only this fixture child's stop methods. The actual child remains
        # alive, so an elapsed join/kill attempt must not become exit evidence.
        with patch.object(process, 'terminate'), patch.object(process, 'kill'), patch.object(process, 'join'):
            with self.assertRaises(RuntimeFailure) as caught:
                await self.runtime._stop_worker()
        self.assertEqual(caught.exception.code, 'WORKER_DEATH_UNCONFIRMED')
        self.assertTrue(process.is_alive()); self.assertTrue(self.runtime._poisoned)
        self.assertEqual(self.observe(request)['state'], 'PENDING')
        self.assertEqual(self.runtime._generation_id, generation)
        with self.ledger.connect() as db:
            self.assertEqual(db.execute('SELECT state,exit_observed FROM control_generation WHERE id=?', (generation,)).fetchone(), ('OWNED', 0))
        with self.assertRaises((ValueError, BlockingIOError)):
            OwnershipLease.acquire(self.config.lock_path)
        await self.runtime._stop_worker()
        self.assertFalse(process.is_alive()); self.assertIsNotNone(process.exitcode)
        fact = self.observe(request)
        self.assertEqual(fact['terminal']['kind'], 'WORKER_STOPPED')
        self.assertEqual(fact['terminal']['generation_id'], generation)
        self.assertIsNone(self.runtime._generation_id)
        self.assert_no_cached_source()

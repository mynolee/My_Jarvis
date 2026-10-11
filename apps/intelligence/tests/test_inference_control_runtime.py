"""Pure mocked supervisor races. No worker process, SQLite, HTTP or provider."""
import asyncio
import time
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock, patch

from jarvis_intelligence.contracts import TurnRequest
from jarvis_intelligence.ledger import ReplayConflict
from jarvis_intelligence.local_runtime import LocalRuntime, RuntimeFailure
from test_inference_control_contract import control_turn, fixture


class FakeLedger:
    def __init__(self):
        self.rows={}; self.calls=[]; self.fail_completed=False
    def add(self, request_id):
        self.rows[request_id]={'fence':False,'generation':None,'terminal':None}
    def control_send_allowed(self, request_id):
        self.calls.append(('post_queue_check',request_id))
        row=self.rows[request_id]
        return not row['fence'] and row['generation'] is None
    def claim_control_send(self, request_id, generation):
        self.calls.append(('send_claim',request_id,generation))
        row=self.rows[request_id]
        if row['fence']: return False
        row['generation']=generation
        return True
    def control(self, identity):
        row=self.rows[identity['request_id']]
        if identity['operation']=='FENCE_OR_STOP' and row['terminal'] is None:
            row['fence']=True
            if row['generation'] is None: row['terminal']='UNSTARTED_FENCED'
        return {'state':'TERMINAL' if row['terminal'] else 'PENDING'}
    def control_generation_for(self, request_id):
        row=self.rows[request_id]
        return row['generation'] if row['terminal'] is None else None
    def control_completed(self, request_id, generation):
        self.calls.append(('completed',request_id,generation))
        if self.fail_completed: raise ReplayConflict('synthetic proof failure')
        assert self.rows[request_id]['generation']==generation
        self.rows[request_id]['terminal']='COMPLETED'
    def control_worker_stopped(self,generation):
        self.calls.append(('stopped',generation))
        for row in self.rows.values():
            if row['generation']==generation and row['terminal'] is None: row['terminal']='WORKER_STOPPED'


class MockedControlRuntimeTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.body=control_turn(); self.turn=TurnRequest.from_wire(self.body)
        self.runtime=LocalRuntime(SimpleNamespace(manifest_sha256=self.turn.model_manifest_hash))
        self.ledger=FakeLedger(); self.ledger.add(self.turn.request_id)
        self.runtime.attach_control_ledger(self.ledger)
        self.runtime._generation_id=fixture()['completed']['terminal']['generation_id']
        self.runtime._ready=True
        self.runtime._process=SimpleNamespace(is_alive=lambda:True)
        self.runtime._cancel=SimpleNamespace(clear=Mock(),set=Mock())
        self.runtime._pipe=SimpleNamespace(send=Mock(),close=Mock())
        self.runtime._closing=True  # No restart task can escape a mocked test.
        self.stop=AsyncMock()
        self.runtime._stop_worker=self.stop

    def command(self, turn=None, operation='FENCE_OR_STOP'):
        return {'request_id':(turn or self.turn).request_id,'operation':operation}

    async def wait_for_send(self, count=1):
        for _ in range(100):
            if self.runtime._pipe.send.call_count>=count: return
            await asyncio.sleep(0)
        self.fail('synthetic send was not reached')

    def other_turn(self):
        body={**self.body,'request_id':'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'}
        other=TurnRequest.from_wire(body); self.ledger.add(other.request_id)
        return other

    async def test_queued_fence_is_checked_after_serial_wait_without_stopping_active(self):
        done=asyncio.Event()
        async def receive(deadline):
            await done.wait(); return {'request_id':self.turn.request_id}
        self.runtime._receive=receive
        active=asyncio.create_task(self.runtime.generate(self.turn,time.time()+10))
        await self.wait_for_send()
        other=self.other_turn()
        waiting=asyncio.create_task(self.runtime.generate(other,time.time()+10))
        await asyncio.sleep(0)
        self.assertEqual((await self.runtime.control(self.command(other)))['state'],'TERMINAL')
        self.runtime._cancel.set.assert_not_called()
        done.set(); await active
        with self.assertRaises(RuntimeFailure) as caught: await waiting
        self.assertEqual(caught.exception.code,'CANCELLED')
        self.assertEqual(self.runtime._pipe.send.call_count,1)
        self.stop.assert_not_awaited()

    async def test_final_claim_rechecks_fence_after_initial_queue_check(self):
        def initial_check(request_id):
            self.ledger.rows[request_id]['fence']=True
            return True
        self.ledger.control_send_allowed=initial_check
        with self.assertRaises(RuntimeFailure): await self.runtime.generate(self.turn,time.time()+10)
        self.runtime._pipe.send.assert_not_called(); self.stop.assert_not_awaited()
        self.assertIsNone(self.ledger.rows[self.turn.request_id]['generation'])

    async def test_send_claim_precedes_pipe_send_and_claimed_send_failure_requires_death(self):
        def send(request):
            self.assertEqual(self.ledger.rows[request['request_id']]['generation'],self.runtime._generation_id)
            raise OSError('synthetic pipe failure')
        self.runtime._pipe.send=Mock(side_effect=send)
        with self.assertRaises(OSError): await self.runtime.generate(self.turn,time.time()+10)
        self.stop.assert_awaited_once()
        self.assertIsNotNone(self.ledger.rows[self.turn.request_id]['generation'])

    async def test_send_before_cancel_stays_pending_until_exact_response(self):
        done=asyncio.Event()
        async def receive(deadline):
            await done.wait(); return {'request_id':self.turn.request_id,'error':'CANCELLED'}
        self.runtime._receive=receive
        task=asyncio.create_task(self.runtime.generate(self.turn,time.time()+10)); await self.wait_for_send()
        self.assertEqual((await self.runtime.control(self.command()))['state'],'PENDING')
        self.runtime._cancel.set.assert_called_once()
        self.assertIsNone(self.ledger.rows[self.turn.request_id]['terminal'])
        done.set()
        with self.assertRaises(RuntimeFailure): await task
        self.assertEqual(self.ledger.rows[self.turn.request_id]['terminal'],'COMPLETED')
        self.stop.assert_not_awaited()

    async def test_old_completed_stop_never_cancels_new_request_on_same_generation(self):
        self.runtime._receive=AsyncMock(return_value={'request_id':self.turn.request_id})
        await self.runtime.generate(self.turn,time.time()+10)
        done=asyncio.Event(); other=self.other_turn()
        async def receive(deadline):
            await done.wait(); return {'request_id':other.request_id}
        self.runtime._receive=receive
        task=asyncio.create_task(self.runtime.generate(other,time.time()+10)); await self.wait_for_send(2)
        self.assertEqual((await self.runtime.control(self.command()))['state'],'TERMINAL')
        self.runtime._cancel.set.assert_not_called()
        done.set(); await task

    async def test_old_pending_identity_never_cancels_different_active_request(self):
        other=self.other_turn()
        self.ledger.rows[self.turn.request_id]['generation']=self.runtime._generation_id
        self.runtime._active=other.request_id
        self.assertEqual((await self.runtime.control(self.command()))['state'],'PENDING')
        self.runtime._cancel.set.assert_not_called()

    async def test_observe_never_sets_fence_or_signals_or_claims(self):
        before=list(self.ledger.calls)
        await self.runtime.control(self.command(operation='OBSERVE'))
        self.assertFalse(self.ledger.rows[self.turn.request_id]['fence'])
        self.assertEqual(self.ledger.calls,before); self.runtime._cancel.set.assert_not_called()

    async def test_completion_write_failure_poison_retains_serial_ownership(self):
        self.ledger.fail_completed=True
        self.runtime._receive=AsyncMock(return_value={'request_id':self.turn.request_id})
        with self.assertRaises(RuntimeFailure): await self.runtime.generate(self.turn,time.time()+10)
        self.assertTrue(self.runtime._poisoned); self.assertTrue(self.runtime._serial.locked())
        self.assertIsNone(self.runtime._active)
        self.assertIsNone(self.ledger.rows[self.turn.request_id]['terminal'])
        await self.runtime.control(self.command())
        self.runtime._cancel.set.assert_not_called()
        with self.assertRaises(RuntimeFailure): await self.runtime.generate(self.other_turn(),time.time()+10)

    async def test_active_closed_before_durable_completion_and_serial_release(self):
        original=self.ledger.control_completed
        def complete(request_id,generation):
            self.assertIsNone(self.runtime._active)
            self.assertTrue(self.runtime._serial.locked())
            original(request_id,generation)
        self.ledger.control_completed=complete
        self.runtime._receive=AsyncMock(return_value={'request_id':self.turn.request_id})
        await self.runtime.generate(self.turn,time.time()+10)
        self.assertFalse(self.runtime._serial.locked())

    async def test_cancelled_waiter_does_not_touch_active_process(self):
        await self.runtime._serial.acquire(); self.runtime._active='another-active-request'
        task=asyncio.create_task(self.runtime.generate(self.turn,time.time()+10))
        await asyncio.sleep(0); task.cancel()
        with self.assertRaises(asyncio.CancelledError): await task
        self.assertEqual(self.runtime._active,'another-active-request')
        self.assertTrue(self.runtime._serial.locked()); self.stop.assert_not_awaited()
        self.assertEqual((await self.runtime.control(self.command()))['state'],'TERMINAL')
        self.runtime._cancel.set.assert_not_called()
        self.runtime._serial.release()

    async def test_mismatched_worker_response_never_completes_control_identity(self):
        self.runtime._receive=AsyncMock(return_value={'request_id':'wrong'})
        with self.assertRaises(RuntimeFailure): await self.runtime.generate(self.turn,time.time()+10)
        self.stop.assert_awaited_once()
        self.assertFalse(any(call[0]=='completed' for call in self.ledger.calls))

    async def test_partial_spawn_pid_none_is_uncertain_not_death(self):
        self.runtime._stop_worker=LocalRuntime._stop_worker.__get__(self.runtime)
        self.runtime._spawn_attempted=True
        self.runtime._process=SimpleNamespace(pid=None,is_alive=lambda:False,exitcode=None)
        with self.assertRaises(RuntimeFailure) as caught: await self.runtime._stop_worker()
        self.assertEqual(caught.exception.code,'WORKER_DEATH_UNCONFIRMED')
        self.assertTrue(self.runtime._poisoned)
        self.assertFalse(any(call[0]=='stopped' for call in self.ledger.calls))

    async def test_unconfirmed_death_never_retires_generation(self):
        self.runtime._stop_worker=LocalRuntime._stop_worker.__get__(self.runtime)
        self.runtime._process=SimpleNamespace(pid=123,is_alive=lambda:True,exitcode=None,
                                             terminate=Mock(),kill=Mock(),join=Mock())
        async def inline(function,*args): return function(*args)
        with patch('jarvis_intelligence.local_runtime.asyncio.to_thread',side_effect=inline):
            with self.assertRaises(RuntimeFailure): await self.runtime._stop_worker()
        self.assertTrue(self.runtime._poisoned)
        self.assertFalse(any(call[0]=='stopped' for call in self.ledger.calls))

    async def test_confirmed_exact_death_precedes_generation_retirement(self):
        self.runtime._stop_worker=LocalRuntime._stop_worker.__get__(self.runtime)
        self.runtime._process=SimpleNamespace(pid=123,is_alive=lambda:False,exitcode=0,join=Mock())
        self.ledger.rows[self.turn.request_id]['generation']=self.runtime._generation_id
        async def inline(function,*args): return function(*args)
        with patch('jarvis_intelligence.local_runtime.asyncio.to_thread',side_effect=inline):
            await self.runtime._stop_worker()
        self.assertEqual(self.ledger.rows[self.turn.request_id]['terminal'],'WORKER_STOPPED')
        self.assertIsNone(self.runtime._generation_id)

    async def test_expiry_during_control_lock_wait_prevents_claim_and_send(self):
        now=[100.0]
        await self.runtime._control_lock.acquire()
        with patch('jarvis_intelligence.local_runtime.time.time',side_effect=lambda:now[0]):
            task=asyncio.create_task(self.runtime.generate(self.turn,101.0))
            for _ in range(30):
                if any(call[0]=='post_queue_check' for call in self.ledger.calls): break
                await asyncio.sleep(0)
            now[0]=101.0
            self.runtime._control_lock.release()
            with self.assertRaises(RuntimeFailure) as caught: await task
        self.assertEqual(caught.exception.code,'GENERATION_TIMEOUT')
        self.runtime._pipe.send.assert_not_called()
        self.assertFalse(any(call[0]=='send_claim' for call in self.ledger.calls))
        self.stop.assert_not_awaited()

    async def test_expiry_during_committed_claim_has_no_send_but_requires_death(self):
        now=[100.0]; claim=self.ledger.claim_control_send
        def slow_claim(request_id,generation):
            result=claim(request_id,generation); now[0]=101.0
            return result
        self.ledger.claim_control_send=slow_claim
        with patch('jarvis_intelligence.local_runtime.time.time',side_effect=lambda:now[0]):
            with self.assertRaises(RuntimeFailure): await self.runtime.generate(self.turn,101.0)
        self.runtime._pipe.send.assert_not_called(); self.stop.assert_awaited_once()
        self.assertIsNotNone(self.ledger.rows[self.turn.request_id]['generation'])

    async def test_clock_advancement_between_samples_never_extends_fixed_deadline(self):
        clock=SimpleNamespace(time=lambda:100.0,monotonic=Mock(side_effect=[10.0,11.0,12.0,13.0]))
        observed=[]
        async def receive(deadline):
            observed.append(deadline); return {'request_id':self.turn.request_id}
        self.runtime._receive=receive
        with patch('jarvis_intelligence.local_runtime.time',clock):
            await self.runtime.generate(self.turn,105.0)
        self.assertEqual(self.runtime._pipe.send.call_args.args[0]['deadline_monotonic'],15.0)
        self.assertEqual(observed,[15.0])

    async def test_control_metadata_is_not_forwarded_to_worker_source(self):
        self.runtime._receive=AsyncMock(return_value={'request_id':self.turn.request_id})
        await self.runtime.generate(self.turn,time.time()+10)
        sent=self.runtime._pipe.send.call_args.args[0]
        for name in ('control','origin_binding','dispatch_commitment','owner_id','security_epoch'):
            self.assertNotIn(name,sent)
        for name in ('context','project_context','memories','history','evidence','tool_results'):
            self.assertEqual(sent[name],self.body[name])

    async def test_current_readiness_reports_control_revision_without_inference(self):
        from jarvis_intelligence.contracts import validate
        before=list(self.ledger.calls)
        self.runtime._integrity=True
        self.assertEqual(validate('runtime-readiness',self.runtime.health())['control_revision'],1)
        self.assertEqual(self.ledger.calls,before)
        self.runtime._pipe.send.assert_not_called()

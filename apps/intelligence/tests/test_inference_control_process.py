"""Real synthetic child/inherited-lock + SQLite CI tests. Never run locally without approval.

These tests exercise CPU fixture processes only and make no native/model-quality claim.
"""
import asyncio
import os
import tempfile
import time
import unittest
from pathlib import Path

from jarvis_intelligence.contracts import TurnRequest, body_hash, control_identity
from jarvis_intelligence.ledger import Ledger, ReplayConflict
from jarvis_intelligence.local_runtime import LocalRuntime, RuntimeFailure
from test_inference_control_contract import control_turn, fixture as control_fixture
from test_local_runtime import fixture, fake_worker


class ControlProcessTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.tmp=tempfile.TemporaryDirectory(); self.root=Path(self.tmp.name)
        self.config=fixture(self.root)
        self.ledger=Ledger(self.root/'ledger.sqlite',b's'*32,max_concurrency=3)
        self.runtime=LocalRuntime(self.config,worker_target=fake_worker)
        self.runtime.attach_control_ledger(self.ledger)
        await self.runtime.start()

    async def asyncTearDown(self):
        await self.runtime.close(); self.tmp.cleanup()

    def admitted(self,number=2,text='normal',compute=60000):
        body=control_turn(); body['model_manifest_hash']=self.config.manifest_sha256
        body['request_id']=f'{number:08x}-0000-4000-8000-{number:012x}'
        body['budget']['reservation_id']=f'{number+100:08x}-0000-4000-8000-{number+100:012x}'
        body['budget']['max_compute_ms']=compute; body['user_input']=text
        identity=control_identity(body,{'owner_id':control_fixture()['request']['owner_id']})
        claims={key:identity[key] for key in ('owner_id','request_id','reservation_id','run_id')} | {'body_hash':body_hash(body)}
        self.ledger.begin(claims,time.time(),60,cache_response=False,control=identity)
        return TurnRequest.from_wire(body),{**identity,'operation':'FENCE_OR_STOP'}

    async def wait_active(self,request_id):
        for _ in range(300):
            if self.runtime._active==request_id: return
            await asyncio.sleep(.01)
        self.fail('synthetic process did not acquire request')

    async def test_real_cooperative_completion_persists_generation_fact(self):
        turn,identity=self.admitted()
        result=await self.runtime.generate(turn,time.time()+5)
        fact=self.ledger.control({**identity,'operation':'OBSERVE'})
        self.assertEqual(result['request_id'],turn.request_id)
        self.assertEqual(fact['terminal']['kind'],'COMPLETED')
        self.assertEqual(fact['terminal']['generation_id'],self.runtime._generation_id)
        self.assertIsNone(self.runtime._active); self.assertFalse(self.runtime._serial.locked())

    async def test_real_queue_fence_prevents_waiter_dispatch(self):
        active,active_identity=self.admitted(text='hang')
        task=asyncio.create_task(self.runtime.generate(active,time.time()+10)); await self.wait_active(active.request_id)
        waiting,identity=self.admitted(3)
        waiter=asyncio.create_task(self.runtime.generate(waiting,time.time()+10)); await asyncio.sleep(.03)
        fact=await self.runtime.control(identity)
        self.assertEqual(fact['terminal']['kind'],'UNSTARTED_FENCED')
        self.assertFalse(self.runtime._cancel.is_set())
        await self.runtime.control(active_identity)
        with self.assertRaises(RuntimeFailure): await task
        with self.assertRaises(RuntimeFailure): await waiter
        self.assertEqual(self.ledger.control({**identity,'operation':'OBSERVE'}),fact)

    async def test_real_stop_requires_exit_before_worker_stopped_fact(self):
        turn,identity=self.admitted(text='hang'); previous=self.runtime._process
        task=asyncio.create_task(self.runtime.generate(turn,time.time()+10)); await self.wait_active(turn.request_id)
        self.assertEqual((await self.runtime.control(identity))['state'],'PENDING')
        with self.assertRaises(RuntimeFailure): await task
        self.assertFalse(previous.is_alive()); self.assertIsNotNone(previous.exitcode)
        self.assertEqual(self.ledger.control({**identity,'operation':'OBSERVE'})['terminal']['kind'],'WORKER_STOPPED')

    async def test_real_completed_stop_does_not_signal_later_warm_request(self):
        old,old_identity=self.admitted(); await self.runtime.generate(old,time.time()+5)
        current,current_identity=self.admitted(3,text='hang')
        task=asyncio.create_task(self.runtime.generate(current,time.time()+10)); await self.wait_active(current.request_id)
        self.assertEqual((await self.runtime.control(old_identity))['terminal']['kind'],'COMPLETED')
        self.assertFalse(self.runtime._cancel.is_set())
        await self.runtime.control(current_identity)
        with self.assertRaises(RuntimeFailure): await task

    async def test_alive_child_retains_exact_lock_after_parent_descriptor_closes(self):
        turn,identity=self.admitted(text='hang')
        task=asyncio.create_task(self.runtime.generate(turn,time.time()+10)); await self.wait_active(turn.request_id)
        os.close(self.runtime._owner_fd); self.runtime._owner_fd=None
        second=LocalRuntime(self.config,worker_target=fake_worker)
        second.attach_control_ledger(Ledger(self.root/'ledger.sqlite',b's'*32))
        try:
            with self.assertRaises(RuntimeFailure): await second.start()
            self.assertEqual(self.ledger.control({**identity,'operation':'OBSERVE'})['state'],'PENDING')
            self.assertIsNone(second._process)
        finally:
            await second.close()
            self.runtime._closing=True
            await self.runtime.control(identity)
            with self.assertRaises(RuntimeFailure): await task

    async def test_replaced_lock_object_refuses_new_spawn_and_recovery(self):
        # Persist an owned pre-send generation while no child holds the lock;
        # replacement must still fail rather than manufacture its exit proof.
        await self.runtime.close()
        generation=self.ledger.register_control_generation(self.config.lock_path.stat().st_dev,self.config.lock_path.stat().st_ino)
        self.ledger.control_child_starting(generation)
        self.config.lock_path.rename(self.root/'original-worker.lock')
        self.config.lock_path.touch(mode=0o600)
        second=LocalRuntime(self.config,worker_target=fake_worker); second.attach_control_ledger(self.ledger)
        try:
            with self.assertRaises(RuntimeFailure): await second.start()
            self.assertIsNone(second._process)
            with self.ledger.connect() as db:
                self.assertEqual(db.execute('SELECT state FROM control_generation WHERE id=?',(generation,)).fetchone()[0],'OWNED')
        finally: await second.close()

    async def test_actual_parent_crash_cannot_retire_alive_inherited_lock_child(self):
        import multiprocessing
        import signal
        await self.runtime.close()
        context=multiprocessing.get_context('spawn')
        receiver,sender=context.Pipe(duplex=False)
        parent=context.Process(target=_crash_parent_with_live_child,args=(self.config,self.root/'ledger.sqlite',sender))
        parent.start(); sender.close()
        child_pid=None; second=None
        try:
            deadline=time.monotonic()+15
            while not receiver.poll() and time.monotonic()<deadline: await asyncio.sleep(.02)
            self.assertTrue(receiver.poll(),'fixture parent did not publish its admitted identity')
            info=receiver.recv(); child_pid=info['child_pid']; identity=info['identity']
            await asyncio.to_thread(parent.join,5)
            self.assertEqual(parent.exitcode,73)
            second=LocalRuntime(self.config,worker_target=fake_worker)
            recovered_ledger=Ledger(self.root/'ledger.sqlite',b's'*32)
            second.attach_control_ledger(recovered_ledger)
            with self.assertRaises(RuntimeFailure): await second.start()
            self.assertEqual(recovered_ledger.control({**identity,'operation':'OBSERVE'})['state'],'PENDING')
            # PID is used only to clean up this fixture child. It is never the
            # authority for proof: subsequent same-object flock acquisition is.
            os.kill(child_pid,signal.SIGTERM)
            deadline=time.monotonic()+10
            while time.monotonic()<deadline:
                try:
                    await second.start()
                    break
                except RuntimeFailure:
                    await asyncio.sleep(.02)
            self.assertTrue(second.health()['ready'])
            child_pid=None  # Same-lock recovery proved the fixture child gone; never signal a reused PID.
            fact=recovered_ledger.control({**identity,'operation':'OBSERVE'})
            self.assertEqual(fact['terminal']['kind'],'WORKER_STOPPED')
            self.assertEqual(fact['terminal']['generation_id'],info['generation_id'])
        finally:
            if parent.is_alive(): parent.terminate(); await asyncio.to_thread(parent.join,5)
            if child_pid is not None:
                try: os.kill(child_pid,signal.SIGTERM)
                except ProcessLookupError: pass
            if second is not None: await second.close()
            receiver.close()


def _crash_parent_with_live_child(config,ledger_path,pipe):
    """CI fixture deliberately exits without multiprocessing's daemon cleanup."""
    async def run():
        ledger=Ledger(ledger_path,b's'*32)
        runtime=LocalRuntime(config,worker_target=fake_worker); runtime.attach_control_ledger(ledger)
        await runtime.start()
        body=control_turn(); body['model_manifest_hash']=config.manifest_sha256; body['user_input']='hang'
        identity=control_identity(body,{'owner_id':control_fixture()['request']['owner_id']})
        claims={key:identity[key] for key in ('owner_id','request_id','reservation_id','run_id')} | {'body_hash':body_hash(body)}
        ledger.begin(claims,time.time(),60,cache_response=False,control=identity)
        turn=TurnRequest.from_wire(body)
        task=asyncio.create_task(runtime.generate(turn,time.time()+60))
        while runtime._active!=turn.request_id:
            if task.done(): await task
            await asyncio.sleep(.01)
        pipe.send({'child_pid':runtime._process.pid,'generation_id':runtime._generation_id,'identity':identity})
        os._exit(73)
    asyncio.run(run())

"""Actual SQLite CI tests. Do not include in guarded source-only local runs."""
import copy
import sqlite3
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from jarvis_intelligence.contracts import control_identity
from jarvis_intelligence.ledger import Ledger, LedgerFull, MAX_SAFE_INTEGER, ReplayConflict
from jarvis_intelligence.local_runtime import OwnershipLease
from test_inference_control_contract import fixture, control_turn


def request(number=2, operation='FENCE_OR_STOP'):
    value=fixture()['request']
    value['request_id']=f'{number:08x}-0000-4000-8000-{number:012x}'
    value['reservation_id']=f'{number+100:08x}-0000-4000-8000-{number+100:012x}'
    value['operation']=operation
    return value


def claims(identity):
    return {key:identity[key] for key in ('owner_id','request_id','reservation_id','run_id')} | {'body_hash':'c'*64}


def descriptor(identity):
    return {key:value for key,value in identity.items() if key!='operation'}


class ControlLedgerTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(); self.path=Path(self.tmp.name)/'ledger.sqlite'
        self.ledger=Ledger(self.path,b's'*32)
        self.identity=request()

    def tearDown(self): self.tmp.cleanup()

    def begin(self, identity=None):
        identity=identity or self.identity
        return self.ledger.begin(claims(identity),100,60,cache_response=False,control=descriptor(identity))

    def observe(self, identity=None):
        return self.ledger.control({**(identity or self.identity),'operation':'OBSERVE'})

    def generation(self):
        generation=self.ledger.register_control_generation(11,22)
        self.ledger.control_child_starting(generation)
        return generation

    def test_observe_absent_does_not_allocate_or_advance_ordinal(self):
        with self.ledger.connect() as db:
            before=db.execute('SELECT next_ordinal FROM control_meta').fetchone()
        self.assertEqual(self.observe()['state'],'NOT_OBSERVED')
        with self.ledger.connect() as db:
            self.assertEqual(db.execute('SELECT COUNT(*) FROM control_identity').fetchone()[0],0)
            self.assertEqual(db.execute('SELECT next_ordinal FROM control_meta').fetchone(),before)

    def test_fence_before_begin_survives_reopen_without_replay_or_source(self):
        fact=self.ledger.control(self.identity)
        self.assertEqual(fact['terminal']['kind'],'UNSTARTED_FENCED')
        self.ledger=Ledger(self.path,b's'*32)
        with self.assertRaises(ReplayConflict): self.begin()
        self.assertEqual(self.observe(),fact)
        with self.ledger.connect() as db:
            self.assertEqual(db.execute('SELECT COUNT(*) FROM replay').fetchone()[0],0)
            columns={row[1] for row in db.execute('PRAGMA table_info(control_identity)')}
            self.assertFalse(columns & {'user_input','response','body_hash','output','prompt'})

    def test_begin_before_fence_never_claims_send(self):
        self.begin(); generation=self.generation()
        self.assertTrue(self.ledger.control_send_allowed(self.identity['request_id']))
        fact=self.ledger.control(self.identity)
        self.assertEqual(fact['terminal']['kind'],'UNSTARTED_FENCED')
        self.assertFalse(self.ledger.claim_control_send(self.identity['request_id'],generation))

    def test_claim_before_fence_stays_pending_even_without_pipe_send(self):
        self.begin(); generation=self.generation()
        self.assertTrue(self.ledger.claim_control_send(self.identity['request_id'],generation))
        self.assertEqual(self.ledger.control(self.identity)['state'],'PENDING')
        self.ledger=Ledger(self.path,b's'*32)
        self.assertEqual(self.observe()['state'],'PENDING')
        self.ledger.recover_control_generations(11,22)
        fact=self.observe()
        self.assertEqual(fact['terminal']['kind'],'WORKER_STOPPED')
        self.assertEqual(fact['terminal']['generation_id'],generation)

    def test_completion_is_permanent_replay_stop_does_not_rewrite(self):
        self.begin(); generation=self.generation()
        self.ledger.claim_control_send(self.identity['request_id'],generation)
        self.ledger.control_completed(self.identity['request_id'],generation)
        fact=self.observe()
        self.ledger.control_completed(self.identity['request_id'],generation)
        self.assertEqual(self.ledger.control(self.identity),fact)
        self.ledger.control_worker_stopped(generation)
        self.assertEqual(self.observe(),fact)

    def test_distinct_generation_and_fact_ordinals_never_reused(self):
        self.begin(); generation=self.generation()
        self.ledger.claim_control_send(self.identity['request_id'],generation)
        self.ledger.control_completed(self.identity['request_id'],generation)
        ordinal=self.observe()['terminal']['ordinal']
        self.ledger.control_worker_stopped(generation)
        next_generation=self.generation()
        self.assertNotEqual(generation,next_generation)
        with self.ledger.connect() as db:
            self.assertGreater(db.execute('SELECT ordinal FROM control_generation WHERE id=?',(next_generation,)).fetchone()[0],ordinal)

    def test_all_identity_changes_and_reservation_reuse_fail_closed(self):
        self.begin()
        for key in ('owner_id','request_id','reservation_id','run_id','context_id','purpose','security_epoch','origin_binding','dispatch_commitment'):
            identity=copy.deepcopy(self.identity)
            if key.endswith('_id'): identity[key]='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
            elif key=='security_epoch': identity[key]+=1
            elif key=='purpose': identity[key]='CONVERSATION'
            else: identity[key]='f'*64
            with self.subTest(key=key), self.assertRaises(ReplayConflict): self.ledger.control(identity)

    def test_legacy_context_and_cacheable_requests_cannot_upgrade_or_downgrade(self):
        self.ledger.begin(claims(self.identity),100,60,cache_response=False)
        with self.assertRaises(ReplayConflict): self.ledger.control(self.identity)
        with self.assertRaises(ReplayConflict): self.begin()
        other=request(3); self.begin(other)
        with self.assertRaises(ReplayConflict): self.ledger.begin(claims(other),100,60,cache_response=False)
        with self.assertRaises(ReplayConflict): self.ledger.begin(claims(request(4)),100,60,control=descriptor(request(4)))

    def test_union_capacity_does_not_double_count_controlled_replay(self):
        self.ledger.max_rows=2
        self.begin(); self.ledger.control(request(3))
        with self.assertRaises(LedgerFull): self.ledger.control(request(4))
        self.assertEqual(self.observe(request(4))['state'],'NOT_OBSERVED')
        self.assertEqual(self.ledger.control(self.identity)['terminal']['kind'],'UNSTARTED_FENCED')

    def test_generation_capacity_preserves_existing_fence_and_observe(self):
        with patch('jarvis_intelligence.ledger.MAX_CONTROL_GENERATIONS',1):
            generation=self.generation(); self.ledger.control_worker_stopped(generation)
            with self.assertRaises(LedgerFull): self.generation()
            self.assertEqual(self.ledger.control(self.identity)['state'],'TERMINAL')
            self.assertEqual(self.observe()['state'],'TERMINAL')

    def test_ordinal_overflow_rolls_back_new_fence(self):
        with self.ledger.connect() as db:
            db.execute('UPDATE control_meta SET next_ordinal=?',(MAX_SAFE_INTEGER,))
        with self.assertRaises(LedgerFull): self.ledger.control(self.identity)
        self.assertEqual(self.observe()['state'],'NOT_OBSERVED')

    def test_wrong_lock_or_changed_ledger_refuses_recovery(self):
        self.begin(); generation=self.generation()
        self.ledger.claim_control_send(self.identity['request_id'],generation)
        for device,inode in ((12,22),(11,23)):
            with self.assertRaises(ReplayConflict): self.ledger.recover_control_generations(device,inode)
        self.assertEqual(self.observe()['state'],'PENDING')
        self.ledger.ledger_instance_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
        with self.assertRaises(ReplayConflict): self.ledger.recover_control_generations(11,22)

    def test_proof_failure_rolls_back_retirement_without_false_terminal(self):
        self.begin(); generation=self.generation()
        self.ledger.claim_control_send(self.identity['request_id'],generation)
        with patch.object(self.ledger,'_next_control_ordinal',side_effect=LedgerFull()):
            with self.assertRaises(LedgerFull): self.ledger.control_worker_stopped(generation)
        self.assertEqual(self.observe()['state'],'PENDING')
        with self.ledger.connect() as db:
            self.assertEqual(db.execute('SELECT state,exit_observed FROM control_generation').fetchone(),('OWNED',0))

    def test_terminal_and_revision_sql_mutations_are_rejected(self):
        self.begin(); self.ledger.control(self.identity)
        statements=["UPDATE control_identity SET fence=0", "UPDATE control_identity SET terminal_kind=NULL,terminal_ordinal=NULL",
                    "UPDATE control_identity SET origin_binding='"+'d'*64+"'", "DELETE FROM control_identity",
                    "UPDATE replay SET control_revision=0", "UPDATE control_meta SET ledger_instance_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'",
                    'DELETE FROM control_meta']
        for sql in statements:
            with self.subTest(sql=sql), self.ledger.connect() as db, self.assertRaises(sqlite3.IntegrityError): db.execute(sql)

    def test_missing_control_table_or_singleton_or_trigger_is_not_recreated(self):
        # Each subcase starts with a fence-only identity, the dangerous no-replay case.
        for damage in ('identity','meta','generation','singleton','trigger'):
            path=Path(self.tmp.name)/f'{damage}.sqlite'
            ledger=Ledger(path,b's'*32); ledger.control(self.identity)
            with ledger.connect() as db:
                if damage=='singleton':
                    db.execute('DROP TRIGGER control_meta_no_delete'); db.execute('DELETE FROM control_meta')
                elif damage=='trigger': db.execute('DROP TRIGGER control_identity_update_guard')
                else: db.execute('DROP TABLE control_'+damage)
            with self.subTest(damage=damage), self.assertRaises((ReplayConflict,sqlite3.Error)):
                Ledger(path,b's'*32)

    def test_legacy_upgrade_preserves_tombstones_and_creates_no_control_identity(self):
        # Model the baseline ledger by dropping A-only tables/column on a fresh
        # unused fixture, then introduce a legacy replay using its unchanged SQL.
        path=Path(self.tmp.name)/'legacy.sqlite'
        lease=OwnershipLease.acquire(path.with_suffix('.lock'))
        self.addCleanup(lease.close)
        with sqlite3.connect(path) as db:
            db.execute("CREATE TABLE replay (request_id TEXT PRIMARY KEY,owner_id TEXT NOT NULL,reservation_id TEXT NOT NULL UNIQUE,run_id TEXT NOT NULL,body_hash TEXT NOT NULL,state TEXT NOT NULL,deadline REAL NOT NULL,cache_until REAL,nonce BLOB UNIQUE,response BLOB,CHECK(state IN ('STARTED','COMPLETED','UNKNOWN')))")
            db.execute("INSERT INTO replay(request_id,owner_id,reservation_id,run_id,body_hash,state,deadline) VALUES ('old','owner','reservation','run','hash','UNKNOWN',1)")
        path.chmod(0o600)
        migrated=Ledger(path,b's'*32,ownership_lease=lease)
        with migrated.connect() as db:
            self.assertEqual(db.execute("SELECT request_id,state,control_revision FROM replay").fetchall(),[('old','UNKNOWN',0)])
            self.assertEqual(db.execute('SELECT COUNT(*) FROM control_identity').fetchone()[0],0)

    def test_null_primary_keys_and_malformed_generation_are_rejected(self):
        with self.ledger.connect() as db, self.assertRaises(sqlite3.IntegrityError):
            db.execute("INSERT INTO control_generation VALUES (NULL,1,?,'OWNED',11,22,0,0)",(self.ledger.ledger_instance_id,))
        columns=('request_id','reservation_id','owner_id','run_id','context_id','purpose','security_epoch','origin_binding','dispatch_commitment')
        values=tuple(None if key=='request_id' else self.identity[key] for key in columns)
        with self.ledger.connect() as db, self.assertRaises(sqlite3.IntegrityError):
            db.execute("INSERT INTO control_identity("+','.join(columns)+",cache_policy) VALUES ("+','.join('?' for _ in columns)+",'CONTEXT_V1_NO_RESPONSE_CACHE')",values)

    def test_no_response_ciphertext_or_usage_nonce_for_controlled_context(self):
        self.begin(); self.ledger.complete(claims(self.identity),{'source':'must-not-be-persisted'},101,cache_response=False)
        with self.ledger.connect() as db:
            self.assertEqual(db.execute('SELECT response,nonce,cache_until,cache_response,control_revision FROM replay').fetchone(),(None,None,None,0,1))
            self.assertEqual(db.execute('SELECT SUM(used) FROM cache_key_usage').fetchone()[0],0)

    def test_actual_concurrent_begin_and_fence_never_admits_after_fence(self):
        from concurrent.futures import ThreadPoolExecutor
        from threading import Barrier
        barrier=Barrier(2)
        def begin():
            barrier.wait()
            try: return self.begin()
            except ReplayConflict: return 'FENCED'
        def fence():
            barrier.wait(); return self.ledger.control(self.identity)
        with ThreadPoolExecutor(max_workers=2) as pool:
            admitted=pool.submit(begin); fenced=pool.submit(fence)
            self.assertIn(admitted.result(),(None,'FENCED'))
            self.assertEqual(fenced.result()['terminal']['kind'],'UNSTARTED_FENCED')
        generation=self.generation()
        self.assertFalse(self.ledger.claim_control_send(self.identity['request_id'],generation))
        with self.assertRaises(ReplayConflict): self.begin()

    def test_actual_concurrent_claim_and_fence_has_one_durable_winner(self):
        from concurrent.futures import ThreadPoolExecutor
        from threading import Barrier
        self.begin(); generation=self.generation(); barrier=Barrier(2)
        def claim():
            barrier.wait(); return self.ledger.claim_control_send(self.identity['request_id'],generation)
        def fence():
            barrier.wait(); return self.ledger.control(self.identity)
        with ThreadPoolExecutor(max_workers=2) as pool:
            claimed=pool.submit(claim); fenced=pool.submit(fence)
            won=claimed.result(); fact=fenced.result()
        if won:
            self.assertEqual(fact['state'],'PENDING')
            self.ledger.control_worker_stopped(generation)
            self.assertEqual(self.observe()['terminal']['kind'],'WORKER_STOPPED')
        else:
            self.assertEqual(fact['terminal']['kind'],'UNSTARTED_FENCED')
            self.assertIsNone(fact['terminal']['generation_id'])

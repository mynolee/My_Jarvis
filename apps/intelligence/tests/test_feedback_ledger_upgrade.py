"""Actual populated SQLite/reopen/fault tests for separately authorized CI only.

The Stage A DDL below is frozen from the reviewed original, not obtained from
Ledger._control_schema under test. No model, service or native worker is used.
"""
import hashlib
import os
import sqlite3
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from jarvis_intelligence.ledger import Ledger, LedgerFull, ReplayConflict, CONTROL_COLUMNS, MAX_SAFE_INTEGER
from jarvis_intelligence.local_runtime import OwnershipLease


def stage_a_uuid_check(name):
    return (f"length({name})=36 AND {name}=lower({name}) AND "
            f"substr({name},9,1)='-' AND substr({name},14,1)='-' AND "
            f"substr({name},19,1)='-' AND substr({name},24,1)='-' AND "
            f"length(replace({name},'-',''))=32 AND replace({name},'-','') NOT GLOB '*[^0-9a-f]*'")


def stage_a_schema():
    uuid = stage_a_uuid_check
    identities = ','.join(f"{name} TEXT {'PRIMARY KEY NOT NULL' if name == 'request_id' else 'NOT NULL'} CHECK({uuid(name)})" for name in ('request_id','reservation_id','owner_id','run_id','context_id'))
    immutable = ' OR '.join(f'NEW.{name} IS NOT OLD.{name}' for name in (*CONTROL_COLUMNS, 'cache_policy'))
    statements = [
        f"""CREATE TABLE IF NOT EXISTS control_meta (
            singleton INTEGER PRIMARY KEY CHECK(singleton=1),
            schema_revision INTEGER NOT NULL CHECK(schema_revision=1),
            ledger_instance_id TEXT NOT NULL CHECK({uuid('ledger_instance_id')}),
            next_ordinal INTEGER NOT NULL CHECK(typeof(next_ordinal)='integer' AND next_ordinal BETWEEN 1 AND {MAX_SAFE_INTEGER})
        )""",
        f"""CREATE TABLE IF NOT EXISTS control_generation (
            id TEXT PRIMARY KEY NOT NULL CHECK({uuid('id')}),
            ordinal INTEGER NOT NULL UNIQUE CHECK(typeof(ordinal)='integer' AND ordinal BETWEEN 1 AND {MAX_SAFE_INTEGER}),
            ledger_instance_id TEXT NOT NULL CHECK({uuid('ledger_instance_id')}),
            state TEXT NOT NULL CHECK(state IN ('OWNED','RETIRED')),
            lock_device INTEGER NOT NULL CHECK(typeof(lock_device)='integer' AND lock_device>=0),
            lock_inode INTEGER NOT NULL CHECK(typeof(lock_inode)='integer' AND lock_inode>=0),
            child_started INTEGER NOT NULL CHECK(child_started IN (0,1)),
            exit_observed INTEGER NOT NULL CHECK(exit_observed IN (0,1)),
            CHECK((state='OWNED' AND exit_observed=0) OR (state='RETIRED' AND exit_observed=1))
        )""",
        f"""CREATE TABLE IF NOT EXISTS control_identity (
            {identities},
            purpose TEXT NOT NULL CHECK(purpose IN ('CONVERSATION','STUDY_LESSON')),
            security_epoch INTEGER NOT NULL CHECK(typeof(security_epoch)='integer' AND security_epoch BETWEEN 0 AND {MAX_SAFE_INTEGER}),
            origin_binding TEXT NOT NULL CHECK(length(origin_binding)=64 AND origin_binding NOT GLOB '*[^0-9a-f]*'),
            dispatch_commitment TEXT NOT NULL CHECK(length(dispatch_commitment)=64 AND dispatch_commitment NOT GLOB '*[^0-9a-f]*'),
            cache_policy TEXT NOT NULL CHECK(cache_policy='CONTEXT_V1_NO_RESPONSE_CACHE'),
            fence INTEGER NOT NULL DEFAULT 0 CHECK(fence IN (0,1)),
            generation_id TEXT REFERENCES control_generation(id),
            terminal_kind TEXT CHECK(terminal_kind IN ('UNSTARTED_FENCED','COMPLETED','WORKER_STOPPED')),
            terminal_ordinal INTEGER UNIQUE CHECK(terminal_ordinal IS NULL OR (typeof(terminal_ordinal)='integer' AND terminal_ordinal BETWEEN 1 AND {MAX_SAFE_INTEGER})),
            UNIQUE(reservation_id),
            CHECK((terminal_kind IS NULL AND terminal_ordinal IS NULL) OR
                  (terminal_kind IS NOT NULL AND terminal_ordinal IS NOT NULL AND
                   ((terminal_kind='UNSTARTED_FENCED' AND generation_id IS NULL AND fence=1) OR
                    (terminal_kind IN ('COMPLETED','WORKER_STOPPED') AND generation_id IS NOT NULL))))
        )""",
        f"""CREATE TRIGGER IF NOT EXISTS control_identity_update_guard BEFORE UPDATE ON control_identity
            WHEN {immutable} OR NEW.fence<OLD.fence OR
                 (OLD.generation_id IS NOT NULL AND NEW.generation_id IS NOT OLD.generation_id) OR
                 (OLD.terminal_kind IS NOT NULL AND
                  (NEW.terminal_kind IS NOT OLD.terminal_kind OR NEW.terminal_ordinal IS NOT OLD.terminal_ordinal OR NEW.generation_id IS NOT OLD.generation_id))
            BEGIN SELECT RAISE(ABORT,'immutable control identity'); END""",
        """CREATE TRIGGER IF NOT EXISTS control_generation_update_guard BEFORE UPDATE ON control_generation
            WHEN NEW.id IS NOT OLD.id OR NEW.ordinal IS NOT OLD.ordinal OR
                 NEW.ledger_instance_id IS NOT OLD.ledger_instance_id OR NEW.lock_device IS NOT OLD.lock_device OR
                 NEW.lock_inode IS NOT OLD.lock_inode OR NEW.child_started<OLD.child_started OR
                 NEW.exit_observed<OLD.exit_observed OR (OLD.state='RETIRED' AND NEW.state IS NOT OLD.state)
            BEGIN SELECT RAISE(ABORT,'immutable control generation'); END""",
        """CREATE TRIGGER IF NOT EXISTS control_meta_update_guard BEFORE UPDATE ON control_meta
            WHEN NEW.singleton IS NOT OLD.singleton OR NEW.schema_revision IS NOT OLD.schema_revision OR
                 NEW.ledger_instance_id IS NOT OLD.ledger_instance_id OR NEW.next_ordinal<=OLD.next_ordinal
            BEGIN SELECT RAISE(ABORT,'immutable control authority'); END""",
        """CREATE TRIGGER IF NOT EXISTS replay_control_revision_guard BEFORE UPDATE OF control_revision ON replay
            WHEN NEW.control_revision IS NOT OLD.control_revision
            BEGIN SELECT RAISE(ABORT,'immutable control revision'); END""",
        """CREATE TRIGGER IF NOT EXISTS replay_control_insert_guard BEFORE INSERT ON replay
            WHEN (NEW.control_revision=1 AND (NEW.cache_response<>0 OR NOT EXISTS (
                SELECT 1 FROM control_identity c WHERE c.request_id=NEW.request_id AND c.reservation_id=NEW.reservation_id
                  AND c.owner_id=NEW.owner_id AND c.run_id=NEW.run_id AND c.fence=0 AND c.terminal_kind IS NULL))) OR
                 (NEW.control_revision=0 AND EXISTS (SELECT 1 FROM control_identity c
                    WHERE c.request_id=NEW.request_id OR c.reservation_id=NEW.reservation_id))
            BEGIN SELECT RAISE(ABORT,'invalid control replay'); END""",
        """CREATE TRIGGER IF NOT EXISTS control_identity_insert_guard BEFORE INSERT ON control_identity
            WHEN EXISTS (SELECT 1 FROM replay r WHERE r.request_id=NEW.request_id OR r.reservation_id=NEW.reservation_id)
                 OR NEW.generation_id IS NOT NULL OR NEW.terminal_kind IS NOT NULL OR NEW.terminal_ordinal IS NOT NULL
            BEGIN SELECT RAISE(ABORT,'invalid initial control identity'); END""",
    ]
    for table in ('control_identity', 'control_generation', 'control_meta'):
        statements.append(f"CREATE TRIGGER IF NOT EXISTS {table}_no_delete BEFORE DELETE ON {table} BEGIN SELECT RAISE(ABORT,'control evidence cannot be deleted'); END")
    return statements

TABLES=('replay','cache_domain','cache_key_usage','cache_nonces','control_generation','control_identity','control_meta')
def uid(n):return f'{n:08x}-0000-4000-8000-{n:012x}'


def seed_stage_a(root):
    path=root/'ledger.sqlite';lease=OwnershipLease.acquire(root/'worker.lock')
    db=sqlite3.connect(path);db.execute('PRAGMA foreign_keys=ON')
    # Exact approved cache schema and column order, including legacy replay rows.
    db.execute("CREATE TABLE replay (request_id TEXT PRIMARY KEY,owner_id TEXT NOT NULL,reservation_id TEXT NOT NULL UNIQUE,run_id TEXT NOT NULL,body_hash TEXT NOT NULL,state TEXT NOT NULL,deadline REAL NOT NULL,cache_until REAL,nonce BLOB UNIQUE,response BLOB,CHECK(state IN ('STARTED','COMPLETED','UNKNOWN')))")
    for name,declaration in (("cache_key_id","TEXT"),("aad_version","INTEGER NOT NULL DEFAULT 0"),("account_id","TEXT"),("cache_response","INTEGER NOT NULL DEFAULT 1"),("control_revision","INTEGER NOT NULL DEFAULT 0 CHECK(control_revision IN (0,1))")):
        db.execute(f'ALTER TABLE replay ADD COLUMN {name} {declaration}')
    db.execute("CREATE TABLE cache_domain (singleton INTEGER PRIMARY KEY CHECK(singleton=1),salt BLOB NOT NULL CHECK(length(salt)=32))")
    db.execute("CREATE TABLE cache_key_usage (key_id TEXT PRIMARY KEY,used INTEGER NOT NULL CHECK (used >= 0 AND used <= 16777216))")
    db.execute("CREATE TABLE cache_nonces (key_id TEXT NOT NULL,nonce BLOB NOT NULL CHECK(length(nonce)=12),PRIMARY KEY(key_id,nonce))")
    salt=b'd'*32;db.execute('INSERT INTO cache_domain VALUES (1,?)',(salt,))
    key=HKDF(algorithm=hashes.SHA256(),length=32,salt=salt,info=b'jarvis-inference-response-cache/v1\x00'+os.fsencode(path.resolve())).derive(b's'*32)
    key_id=hashlib.sha256(b'jarvis-response-cache-key\x00'+key).hexdigest()
    db.execute('INSERT INTO cache_key_usage VALUES (?,2)',(key_id,))
    for nonce in (b'n'*12,b'm'*12):db.execute('INSERT INTO cache_nonces VALUES (?,?)',(key_id,nonce))
    for statement in stage_a_schema():
        if statement.startswith('CREATE TABLE'):db.execute(statement)
    ledger=uid(901);device,inode=lease.verify()
    db.execute('INSERT INTO control_meta VALUES (1,1,?,5)',(ledger,))
    db.execute("INSERT INTO control_generation VALUES (?,1,?,'RETIRED',?,?,1,1)",(uid(902),ledger,device,inode))
    db.execute("INSERT INTO control_generation VALUES (?,2,?,'OWNED',?,?,1,0)",(uid(903),ledger,device,inode))
    identity_columns=(*CONTROL_COLUMNS,'cache_policy','fence','generation_id','terminal_kind','terminal_ordinal')
    for i,purpose,generation,terminal,ordinal,fence in ((1,'STUDY_LESSON',uid(902),'COMPLETED',3,0),(2,'CONVERSATION',uid(903),None,None,0),(3,'STUDY_LESSON',None,'UNSTARTED_FENCED',4,1)):
        identity={'request_id':uid(i),'reservation_id':uid(10+i),'owner_id':uid(100),'run_id':uid(20+i),'context_id':uid(30+i),'purpose':purpose,'security_epoch':2,'origin_binding':'a'*64,'dispatch_commitment':'b'*64,'cache_policy':'CONTEXT_V1_NO_RESPONSE_CACHE','fence':fence,'generation_id':generation,'terminal_kind':terminal,'terminal_ordinal':ordinal}
        db.execute('INSERT INTO control_identity('+','.join(identity_columns)+') VALUES ('+','.join('?' for _ in identity_columns)+')',tuple(identity[name] for name in identity_columns))
        if i<3:
            db.execute("INSERT INTO replay(request_id,owner_id,reservation_id,run_id,body_hash,state,deadline,cache_response,control_revision) VALUES (?,?,?,?,?,?,?,0,1)",(uid(i),uid(100),uid(10+i),uid(20+i),'c'*64,'COMPLETED' if i==1 else 'UNKNOWN',100.5))
    db.execute("INSERT INTO replay(request_id,owner_id,reservation_id,run_id,body_hash,state,deadline,cache_until,nonce,response,cache_key_id,aad_version) VALUES ('legacy','owner','legacy-reservation','run','old-digest','COMPLETED',20.5,40.5,?,?,?,1)",(b'n'*12,b'synthetic encrypted cache bytes',key_id))
    for statement in stage_a_schema():
        if statement.startswith('CREATE TRIGGER'):db.execute(statement)
    db.commit();db.close();path.chmod(0o600)
    return path,lease


def snapshot(path):
    with sqlite3.connect(path) as db:
        rows={name:sorted(db.execute('SELECT * FROM '+name).fetchall(),key=repr) for name in TABLES}
        definitions=db.execute("SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY type,name").fetchall()
    return rows,definitions


class FeedbackLedgerLimitsTests(unittest.TestCase):
    """Feedback-specific capacity/overflow rollback, actual SQLite; NOT_RUN here."""
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.lease = OwnershipLease.acquire(self.root/'worker.lock'); self.addCleanup(self.lease.close)
        self.path = self.root/'ledger.sqlite'
        self.ledger = Ledger(self.path, b's'*32, ownership_lease=self.lease)

    def identity(self, number=1, operation='FENCE_OR_STOP'):
        return {'revision': 1, 'operation': operation, 'owner_id': uid(100),
                'request_id': uid(1000+number), 'reservation_id': uid(2000+number),
                'run_id': uid(3000+number), 'context_id': uid(4000+number),
                'purpose': 'STUDY_FEEDBACK', 'security_epoch': 2,
                'origin_binding': 'a'*64, 'dispatch_commitment': 'b'*64}

    def begin(self, identity):
        descriptor = {k: v for k, v in identity.items() if k != 'operation'}
        claims = {k: identity[k] for k in ('owner_id', 'request_id', 'reservation_id', 'run_id')}
        return self.ledger.begin({**claims, 'body_hash': 'c'*64}, 100, 60,
                                 cache_response=False, control=descriptor)

    def observe(self, identity):
        return self.ledger.control({**identity, 'operation': 'OBSERVE'})

    def test_feedback_logical_cap_counts_replay_identity_union_and_preserves_existing_facts(self):
        self.ledger.max_rows = 2
        first, second, extra = self.identity(1), self.identity(2), self.identity(3)
        self.begin(first)
        fact = self.ledger.control(second)
        before = snapshot(self.path)
        with self.assertRaises(LedgerFull): self.begin(extra)
        with self.assertRaises(LedgerFull): self.ledger.control(extra)
        self.assertEqual(snapshot(self.path), before)
        self.assertEqual(self.observe(extra)['state'], 'NOT_OBSERVED')
        self.assertEqual(self.observe(second), fact)
        self.assertEqual(self.ledger.control(first)['terminal']['kind'], 'UNSTARTED_FENCED')
        with self.ledger.connect() as db:
            self.assertEqual(db.execute('SELECT COUNT(*) FROM replay').fetchone()[0], 1)
            self.assertEqual(db.execute('SELECT COUNT(*) FROM control_identity').fetchone()[0], 2)

    def test_feedback_generation_cap_never_blocks_observe_or_existing_unstarted_fence(self):
        first = self.identity(); self.begin(first)
        with patch('jarvis_intelligence.ledger.MAX_CONTROL_GENERATIONS', 1):
            generation = self.ledger.register_control_generation(*self.lease.verify())
            # No worker was constructed or started for this generation.
            self.ledger.control_worker_stopped(generation)
            before = snapshot(self.path)
            with self.assertRaises(LedgerFull): self.ledger.register_control_generation(*self.lease.verify())
            self.assertEqual(snapshot(self.path), before)
            self.assertEqual(self.observe(first)['state'], 'PENDING')
            fact = self.ledger.control(first)
            self.assertEqual(fact['terminal']['kind'], 'UNSTARTED_FENCED')
            self.assertEqual(self.observe(first), fact)

    def test_feedback_terminal_ordinal_exhaustion_rolls_back_new_and_existing_fences(self):
        first, extra = self.identity(), self.identity(2); self.begin(first)
        with self.ledger.connect() as db:
            db.execute('UPDATE control_meta SET next_ordinal=?', (MAX_SAFE_INTEGER,))
        before = snapshot(self.path)
        for identity in (first, extra):
            with self.subTest(request_id=identity['request_id']), self.assertRaises(LedgerFull):
                self.ledger.control(identity)
            self.assertEqual(snapshot(self.path), before)
        self.assertEqual(self.observe(first)['state'], 'PENDING')
        self.assertEqual(self.observe(extra)['state'], 'NOT_OBSERVED')

    def test_feedback_terminal_ordinal_exhaustion_preserves_claim_and_generation_authority(self):
        identity = self.identity(); self.begin(identity)
        generation = self.ledger.register_control_generation(*self.lease.verify())
        self.ledger.control_child_starting(generation)
        self.assertTrue(self.ledger.claim_control_send(identity['request_id'], generation))
        with self.ledger.connect() as db:
            db.execute('UPDATE control_meta SET next_ordinal=?', (MAX_SAFE_INTEGER,))
        before = snapshot(self.path)
        for operation in (lambda: self.ledger.control_completed(identity['request_id'], generation),
                          lambda: self.ledger.control_worker_stopped(generation)):
            with self.assertRaises(LedgerFull): operation()
            self.assertEqual(snapshot(self.path), before)
        self.assertEqual(self.observe(identity)['state'], 'PENDING')
        self.assertEqual(self.ledger.control_generation_for(identity['request_id']), generation)
        with self.ledger.connect() as db:
            self.assertEqual(db.execute('SELECT state,exit_observed FROM control_generation').fetchone(), ('OWNED', 0))


class FeedbackLedgerUpgradeTests(unittest.TestCase):
    def test_original_stage_a_ledger_constructor_refuses_populated_schema_two_without_mutation(self):
        # Execute only in the separately authorized actual SQLite suite. This
        # is the unchanged original Stage A Ledger constructor, not the full
        # old application, and neither its guards nor constants are patched.
        from types import ModuleType
        frozen = Path(__file__).parent/'fixtures/stage_a_ledger_7a2fee8.py.txt'
        raw = frozen.read_bytes()
        self.assertEqual(hashlib.sha256(raw).hexdigest(),
                         '36946cf64e72b041e9456cf2c50cf1a0fe52fe58a415642110c4af32a92f8485')
        original = ModuleType('jarvis_intelligence.stage_a_ledger_7a2fee8')
        original.__package__ = 'jarvis_intelligence'
        exec(compile(raw, str(frozen), 'exec'), original.__dict__)
        with tempfile.TemporaryDirectory() as tmp:
            path, lease = seed_stage_a(Path(tmp))
            try:
                upgraded = Ledger(path, b's'*32, ownership_lease=lease)
                before = snapshot(path)
                lock_identity = lease.verify()
                with self.assertRaisesRegex(original.ReplayConflict, '^unsupported control ledger$'):
                    original.Ledger(path, b's'*32)
                # Every table row and exact schema definition is retained,
                # including UUID, ordinals, salt, key counters and burn nonces.
                self.assertEqual(snapshot(path), before)
                self.assertEqual(lease.verify(), lock_identity)
                reopened = Ledger(path, b's'*32, ownership_lease=lease)
                self.assertEqual(reopened.ledger_instance_id, upgraded.ledger_instance_id)
                self.assertEqual(reopened.key_id, upgraded.key_id)
                self.assertEqual(reopened.schema_revision, 2)
            finally:
                lease.close()

    def test_populated_stage_a_upgrade_preserves_every_original_row_except_revision(self):
        with tempfile.TemporaryDirectory() as tmp:
            path,lease=seed_stage_a(Path(tmp));before,_=snapshot(path)
            try:
                ledger=Ledger(path,b's'*32,ownership_lease=lease)
                after,_=snapshot(path)
                expected={**before,'control_meta':[(1,2,*before['control_meta'][0][2:])]}
                self.assertEqual(after,expected);self.assertEqual(ledger.schema_revision,2)
                self.assertIs(ledger.ownership_lease,lease)
                self.assertEqual(after['control_generation'],before['control_generation'])
                reopened=Ledger(path,b's'*32,ownership_lease=lease)
                self.assertEqual(reopened.ledger_instance_id,ledger.ledger_instance_id)
                with reopened.connect() as db:self.assertEqual(db.execute('PRAGMA foreign_key_check').fetchall(),[])
            finally:lease.close()

    def test_fault_at_each_rebuild_boundary_is_exact_before_or_exact_after(self):
        steps=['before_create','after_create_control_identity','after_create_control_meta','after_copy_identity','after_copy_meta','after_copy_validation']
        triggers=('control_identity_update_guard','control_identity_insert_guard','control_identity_no_delete','control_meta_update_guard','control_meta_no_delete','replay_control_insert_guard')
        steps += ['after_drop_trigger_'+name for name in triggers]
        steps += [prefix+name for name in ('control_identity','control_meta') for prefix in ('after_drop_','after_rename_')]
        steps += ['after_create_trigger_'+name for name in triggers]
        steps += ['after_final_validation','before_commit','after_commit']
        for step in steps:
            with self.subTest(step=step),tempfile.TemporaryDirectory() as tmp:
                path,lease=seed_stage_a(Path(tmp));before=snapshot(path)
                def fault(actual):
                    if actual==step:raise RuntimeError('synthetic interrupted upgrade')
                try:
                    with patch.object(Ledger,'_upgrade_checkpoint',side_effect=fault),self.assertRaises(RuntimeError):Ledger(path,b's'*32,ownership_lease=lease)
                    after=snapshot(path)
                    if step!='after_commit':self.assertEqual(after,before)
                    else:
                        expected={**before[0],'control_meta':[(1,2,*before[0]['control_meta'][0][2:])]}
                        self.assertEqual(after[0],expected)
                    Ledger(path,b's'*32,ownership_lease=lease)
                finally:lease.close()

    def test_existing_stage_a_without_original_lease_refuses_without_repair(self):
        with tempfile.TemporaryDirectory() as tmp:
            path,lease=seed_stage_a(Path(tmp));before=snapshot(path)
            try:
                with self.assertRaises(ReplayConflict):Ledger(path,b's'*32)
                self.assertEqual(snapshot(path),before)
            finally:lease.close()

    def test_unknown_missing_partial_temp_view_and_replaced_trigger_refused(self):
        damage=("DROP TRIGGER control_identity_update_guard","CREATE TABLE control_identity_upgrade_v2(x)",
                "CREATE VIEW unwanted AS SELECT * FROM control_meta","CREATE INDEX unexpected ON replay(owner_id)",
                "DROP TRIGGER replay_control_insert_guard","DROP TABLE control_identity")
        for statement in damage:
            with self.subTest(statement=statement),tempfile.TemporaryDirectory() as tmp:
                path,lease=seed_stage_a(Path(tmp))
                try:
                    with sqlite3.connect(path) as db:db.execute(statement)
                    before=snapshot(path) if not statement.startswith('DROP TABLE') else None
                    with self.assertRaises((ReplayConflict,sqlite3.Error)):Ledger(path,b's'*32,ownership_lease=lease)
                    if before is not None:self.assertEqual(snapshot(path),before)
                finally:lease.close()

    def test_populated_a_missing_salt_counter_or_nonce_authority_never_upgrades(self):
        statements=("DELETE FROM cache_domain","DELETE FROM cache_key_usage","UPDATE cache_key_usage SET used=0",
                    "INSERT INTO cache_nonces VALUES ('orphan',X'010101010101010101010101')",
                    "UPDATE cache_domain SET salt=X'0000000000000000000000000000000000000000000000000000000000000000'")
        for statement in statements:
            with self.subTest(statement=statement),tempfile.TemporaryDirectory() as tmp:
                path,lease=seed_stage_a(Path(tmp))
                try:
                    with sqlite3.connect(path) as db:db.execute(statement)
                    before=snapshot(path)
                    with self.assertRaises(ReplayConflict):Ledger(path,b's'*32,ownership_lease=lease)
                    self.assertEqual(snapshot(path),before)
                finally:lease.close()

    def test_original_opening_key_is_required_for_upgrade_but_b_rotation_is_retained(self):
        with tempfile.TemporaryDirectory() as tmp:
            path,lease=seed_stage_a(Path(tmp));before=snapshot(path)
            try:
                with self.assertRaises(ReplayConflict):Ledger(path,b't'*32,ownership_lease=lease)
                self.assertEqual(snapshot(path),before)
                first=Ledger(path,b's'*32,ownership_lease=lease)
                second=Ledger(path,b't'*32,ownership_lease=lease)
                self.assertNotEqual(first.key_id,second.key_id)
                with second.connect() as db:self.assertEqual(db.execute('SELECT COUNT(*) FROM cache_key_usage').fetchone()[0],2)
            finally:lease.close()

    def test_original_lock_inode_and_ledger_inode_are_retained_and_replacement_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);path,lease=seed_stage_a(root)
            try:
                with self.assertRaises((BlockingIOError,OSError)):OwnershipLease.acquire(root/'worker.lock',path)
                (root/'worker.lock').rename(root/'old-worker.lock');(root/'worker.lock').touch(mode=0o600)
                with self.assertRaises(ValueError):Ledger(path,b's'*32,ownership_lease=lease)
            finally:lease.close()

    def test_schema_two_admits_feedback_and_rejects_future_or_downgrade_marker(self):
        from test_study_feedback_contract import fixture
        from jarvis_intelligence.contracts import control_identity
        with tempfile.TemporaryDirectory() as tmp:
            path,lease=seed_stage_a(Path(tmp))
            try:
                ledger=Ledger(path,b's'*32,ownership_lease=lease)
                identity=control_identity(fixture(),{'owner_id':uid(100)})
                fact=ledger.control({**identity,'operation':'FENCE_OR_STOP'})
                self.assertEqual(fact['purpose'],'STUDY_FEEDBACK');self.assertEqual(fact['revision'],1)
                with ledger.connect() as db:
                    self.assertNotEqual(db.execute('SELECT schema_revision FROM control_meta').fetchone()[0],1)
                    with self.assertRaises(sqlite3.IntegrityError):db.execute('UPDATE control_meta SET schema_revision=1')
            finally:lease.close()


class FeedbackLegacyLedgerUpgradeTests(unittest.TestCase):
    HISTORICAL = ("CREATE TABLE replay (request_id TEXT PRIMARY KEY,owner_id TEXT NOT NULL,"
                  "reservation_id TEXT UNIQUE NOT NULL,run_id TEXT NOT NULL,body_hash TEXT NOT NULL,"
                  "state TEXT NOT NULL,deadline REAL NOT NULL,cache_until REAL,nonce BLOB UNIQUE,response BLOB)")

    def seed(self,root,*,through_a=False,cache_only=False,state='UNKNOWN',ddl=None):
        path=root/'legacy.sqlite';lease=OwnershipLease.acquire(root/'worker.lock')
        self.addCleanup(lease.close)
        with sqlite3.connect(path) as db:
            db.execute(ddl or self.HISTORICAL)
            db.execute("INSERT INTO replay(request_id,owner_id,reservation_id,run_id,body_hash,state,deadline,nonce,response) VALUES ('historical','owner','reservation','run','digest',?,10.5,?,?)",(state,b'n'*12,b'original opaque bytes'))
            if through_a or cache_only:
                # The original cache/control upgrade preserved this exact old
                # table while adding columns. It did not recreate its CHECK.
                for name,declaration in (("cache_key_id","TEXT"),("aad_version","INTEGER NOT NULL DEFAULT 0"),("account_id","TEXT"),("cache_response","INTEGER NOT NULL DEFAULT 1"),("control_revision","INTEGER NOT NULL DEFAULT 0 CHECK(control_revision IN (0,1))")):
                    if not cache_only or name != 'control_revision':db.execute(f'ALTER TABLE replay ADD COLUMN {name} {declaration}')
                db.execute("CREATE TABLE cache_domain (singleton INTEGER PRIMARY KEY CHECK(singleton=1),salt BLOB NOT NULL CHECK(length(salt)=32))")
                db.execute("CREATE TABLE cache_key_usage (key_id TEXT PRIMARY KEY,used INTEGER NOT NULL CHECK (used >= 0 AND used <= 16777216))")
                db.execute("CREATE TABLE cache_nonces (key_id TEXT NOT NULL,nonce BLOB NOT NULL CHECK(length(nonce)=12),PRIMARY KEY(key_id,nonce))")
                salt=b'z'*32;db.execute('INSERT INTO cache_domain VALUES (1,?)',(salt,))
                key=HKDF(algorithm=hashes.SHA256(),length=32,salt=salt,info=b'jarvis-inference-response-cache/v1\x00'+os.fsencode(path.resolve())).derive(b's'*32)
                key_id=hashlib.sha256(b'jarvis-response-cache-key\x00'+key).hexdigest()
                db.execute('INSERT INTO cache_key_usage VALUES (?,1)',(key_id,));db.execute('INSERT INTO cache_nonces VALUES (?,?)',(key_id,b'n'*12))
                if through_a:
                    for statement in stage_a_schema():db.execute(statement)
                    db.execute('INSERT INTO control_meta VALUES (1,1,?,1)',(uid(990),))
        path.chmod(0o600)
        return path,lease

    def test_populated_original_legacy_to_b_preserves_tombstone_and_reserves_nonce(self):
        with tempfile.TemporaryDirectory() as tmp:
            path,lease=self.seed(Path(tmp));ledger=Ledger(path,b's'*32,ownership_lease=lease)
            with ledger.connect() as db:
                self.assertEqual(db.execute('SELECT request_id,state,body_hash,nonce,response,control_revision FROM replay').fetchone(),('historical','UNKNOWN','digest',b'n'*12,b'original opaque bytes',0))
                self.assertEqual(db.execute('SELECT COUNT(*) FROM control_identity').fetchone()[0],0)
                self.assertEqual(db.execute('SELECT COUNT(*) FROM cache_nonces').fetchone()[0],1)
                self.assertEqual(db.execute('SELECT schema_revision,next_ordinal FROM control_meta').fetchone(),(2,1))

    def test_populated_original_legacy_to_a_to_b_preserves_uuid_salt_nonce_and_rows(self):
        with tempfile.TemporaryDirectory() as tmp:
            path,lease=self.seed(Path(tmp),through_a=True);before,_=snapshot(path)
            ledger=Ledger(path,b's'*32,ownership_lease=lease);after,_=snapshot(path)
            self.assertEqual(after,{**before,'control_meta':[(1,2,uid(990),1)]})
            self.assertEqual(ledger.ledger_instance_id,uid(990))

    def test_invalid_no_check_state_null_identity_and_third_schema_are_refused(self):
        cases=[{'state':'UNTRUSTED'}, {'ddl':self.HISTORICAL.replace('body_hash TEXT NOT NULL','body_hash TEXT')},
               {'ddl':self.HISTORICAL.replace('reservation_id TEXT UNIQUE NOT NULL','reservation_id TEXT NOT NULL UNIQUE')}]
        for case in cases:
            with self.subTest(case=case),tempfile.TemporaryDirectory() as tmp:
                path,lease=self.seed(Path(tmp),**case)
                with self.assertRaises(ReplayConflict):Ledger(path,b's'*32,ownership_lease=lease)
        with tempfile.TemporaryDirectory() as tmp:
            path,lease=self.seed(Path(tmp))
            with sqlite3.connect(path) as db:db.execute('UPDATE replay SET request_id=NULL')
            with self.assertRaises(ReplayConflict):Ledger(path,b's'*32,ownership_lease=lease)

    def test_established_legacy_cache_is_preserved_and_partial_authority_is_never_recreated(self):
        def authority_snapshot(path):
            with sqlite3.connect(path) as db:
                definitions=db.execute('SELECT type,name,sql FROM sqlite_master ORDER BY type,name').fetchall()
                tables=[row[1] for row in definitions if row[0]=='table']
                return definitions,{name:db.execute('SELECT * FROM '+name+' ORDER BY rowid').fetchall() for name in tables}
        for damage in (None,'DROP TABLE cache_domain','DROP TABLE cache_key_usage','DROP TABLE cache_nonces',
                       'DELETE FROM cache_domain','DELETE FROM cache_key_usage'):
            with self.subTest(damage=damage),tempfile.TemporaryDirectory() as tmp:
                path,lease=self.seed(Path(tmp),cache_only=True)
                if damage:
                    with sqlite3.connect(path) as db:db.execute(damage)
                before=authority_snapshot(path)
                if damage:
                    with self.assertRaises(ReplayConflict):Ledger(path,b's'*32,ownership_lease=lease)
                    self.assertEqual(authority_snapshot(path),before)
                else:
                    ledger=Ledger(path,b's'*32,ownership_lease=lease)
                    with ledger.connect() as db:
                        for name in ('cache_domain','cache_key_usage','cache_nonces'):
                            self.assertEqual(db.execute('SELECT * FROM '+name+' ORDER BY rowid').fetchall(),before[1][name])

    def test_interrupted_legacy_upgrade_restores_exact_original_schema_and_row(self):
        with tempfile.TemporaryDirectory() as tmp:
            path,lease=self.seed(Path(tmp))
            with sqlite3.connect(path) as db:
                before_schema=db.execute('SELECT type,name,sql FROM sqlite_master ORDER BY type,name').fetchall()
                before_rows=db.execute('SELECT * FROM replay').fetchall()
            def fault(stage):
                if stage=='after_drop_control_identity':raise RuntimeError('synthetic crash')
            with patch.object(Ledger,'_upgrade_checkpoint',side_effect=fault),self.assertRaises(RuntimeError):Ledger(path,b's'*32,ownership_lease=lease)
            with sqlite3.connect(path) as db:
                self.assertEqual(db.execute('SELECT type,name,sql FROM sqlite_master ORDER BY type,name').fetchall(),before_schema)
                self.assertEqual(db.execute('SELECT * FROM replay').fetchall(),before_rows)


def hold_inherited_lease(pipe, owner_handle):
    fd=owner_handle
    try:
        pipe.send('holding-original-lock')
        pipe.recv()
    finally:
        pipe.close();os.close(fd)


class FeedbackOwnershipStartupTests(unittest.TestCase):
    def test_live_inherited_child_blocks_upgrade_until_actual_exit(self):
        import multiprocessing
        from jarvis_intelligence.local_runtime import _InheritedLock
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);path,lease=seed_stage_a(root)
            context=multiprocessing.get_context('spawn');parent,child=context.Pipe()
            process=context.Process(target=hold_inherited_lease,args=(child,_InheritedLock(lease.fd)))
            process.start();child.close()
            try:
                self.assertTrue(parent.poll(5));self.assertEqual(parent.recv(),'holding-original-lock')
                lease.close()
                with self.assertRaises((BlockingIOError,OSError)):OwnershipLease.acquire(root/'worker.lock',path)
                with sqlite3.connect(path) as db:self.assertEqual(db.execute('SELECT schema_revision FROM control_meta').fetchone(),(1,))
                parent.send('exit');process.join(5)
                self.assertFalse(process.is_alive());self.assertEqual(process.exitcode,0)
                recovered=OwnershipLease.acquire(root/'worker.lock',path)
                try:
                    ledger=Ledger(path,b's'*32,ownership_lease=recovered)
                    with ledger.connect() as db:
                        self.assertEqual(db.execute("SELECT state FROM control_generation WHERE id=?",(uid(903),)).fetchone(),('OWNED',))
                        self.assertIsNone(db.execute('SELECT terminal_kind FROM control_identity WHERE request_id=?',(uid(2),)).fetchone()[0])
                finally:recovered.close()
            finally:
                lease.close();parent.close()
                if process.is_alive():process.terminate();process.join(5)

    def test_actual_app_lease_precedes_populated_upgrade_and_remains_attached(self):
        from jarvis_intelligence.app import Settings, create_app
        from test_local_runtime import fixture
        from unittest.mock import Mock
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);path,lease=seed_stage_a(root);lease.close();config=fixture(root)
            app=create_app(Settings(path,b's'*32,{'synthetic-core':Mock()},mode='local_mlx',local_config=config))
            original=app.state.ledger.ownership_lease
            try:
                self.assertIsNotNone(original);self.assertEqual(app.state.ledger.schema_revision,2)
                self.assertEqual(original.ledger_path,path);self.assertEqual(original.verify(),(os.stat(config.lock_path).st_dev,os.stat(config.lock_path).st_ino))
                with self.assertRaises((BlockingIOError,OSError)):OwnershipLease.acquire(config.lock_path,path)
            finally:original.close()

    def test_actual_unavailable_app_refuses_existing_stage_a_without_original_lock(self):
        from jarvis_intelligence.app import Settings, create_app
        from unittest.mock import Mock
        with tempfile.TemporaryDirectory() as tmp:
            path,lease=seed_stage_a(Path(tmp));before=snapshot(path)
            try:
                with self.assertRaises(ReplayConflict):create_app(Settings(path,b's'*32,{'synthetic-core':Mock()},mode='unavailable'))
                self.assertEqual(snapshot(path),before)
            finally:lease.close()

    def test_missing_or_replaced_ledger_and_missing_lock_cannot_create_new_authority(self):
        for target in ('ledger','lock'):
            with self.subTest(target=target),tempfile.TemporaryDirectory() as tmp:
                root=Path(tmp);path,lease=seed_stage_a(root)
                lock_path=root/'worker.lock'
                lock_identity=lease.verify()
                ledger_info=os.stat(path,follow_symlinks=False)
                ledger_identity=(ledger_info.st_dev,ledger_info.st_ino)
                before=snapshot(path)
                # Raw Stage A fixture creation held the original lock but had
                # no Ledger constructor to bind its newly created file. With
                # no child running, reacquire those same existing objects via
                # the actual startup API before any missing/replaced probe.
                lease.close()
                lease=OwnershipLease.acquire(lock_path,path)
                current=path if target=='ledger' else root/'worker.lock'
                try:
                    self.assertEqual(lease.verify(),lock_identity)
                    self.assertEqual(lease.ledger_path,path)
                    self.assertEqual(lease.ledger_identity,ledger_identity)
                    self.assertEqual(snapshot(path),before)
                    saved=current.with_suffix('.saved')
                    current.rename(saved)
                    saved_bytes=saved.read_bytes(); saved_mode=saved.stat().st_mode
                    with self.assertRaises((ValueError,OSError)):OwnershipLease.acquire(lock_path,path)
                    self.assertFalse(current.exists())
                    with self.assertRaises((ValueError,OSError)):lease.verify()
                    self.assertEqual(saved.read_bytes(),saved_bytes)
                    self.assertEqual(saved.stat().st_mode,saved_mode)
                    current.touch(mode=0o600)
                    replacement=current.read_bytes(); replacement_mode=current.stat().st_mode
                    with self.assertRaises((ValueError,OSError)):lease.verify()
                    self.assertEqual(current.read_bytes(),replacement)
                    self.assertEqual(current.stat().st_mode,replacement_mode)
                    self.assertEqual(saved.read_bytes(),saved_bytes)
                    self.assertEqual(saved.stat().st_mode,saved_mode)
                    original_ledger=saved if target=='ledger' else path
                    original_lock=lock_path if target=='ledger' else saved
                    info=os.stat(original_ledger,follow_symlinks=False)
                    self.assertEqual((info.st_dev,info.st_ino),ledger_identity)
                    info=os.stat(original_lock,follow_symlinks=False)
                    self.assertEqual((info.st_dev,info.st_ino),lock_identity)
                    self.assertEqual(snapshot(original_ledger),before)
                finally:lease.close()

"""Private inference-only response cache and durable replay tombstones.

Key domain is this canonical ledger path plus its persistent random salt. All
instances of a ledger must use the same canonical path. Moving/copying a ledger
changes its key domain and fails closed for cached ciphertext. Legacy raw-key
caches fail closed; replay identities and STARTED/UNKNOWN tombstones survive.
Restoring stale storage cannot restore the latest replay/nonce authority safely.
"""
from __future__ import annotations

import hashlib
import hmac
import os
import sqlite3
from contextlib import contextmanager
from pathlib import Path
from uuid import uuid4
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from .contracts import CONTROL_IDENTITY_FIELDS, canonical, parse_json, validate


class ReplayConflict(ValueError):
    pass


class LedgerFull(ValueError):
    pass


MAX_KEY_ENCRYPTIONS = 1 << 24
MAX_NONCE_ATTEMPTS = 8
AAD_VERSION = 1
MAX_SAFE_INTEGER = 9007199254740991
MAX_CONTROL_GENERATIONS = 10000
CONTROL_COLUMNS = tuple(name for name in CONTROL_IDENTITY_FIELDS if name != "revision")


class Ledger:
    def __init__(self, path: Path, cache_key: bytes, *, max_rows=10000, max_concurrency=4):
        if len(cache_key) != 32 or not path.is_absolute() or str(path) == ":memory:":
            raise ValueError("persistent ledger and AES-256 key required")
        self.path = path
        self.max_rows, self.max_concurrency = max_rows, max_concurrency
        path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        # Refuse symlink-based storage. Deployment must own and protect parent.
        fd = os.open(path, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
        os.close(fd)
        os.chmod(path, 0o600)
        with self.connect() as db:
            db.execute("PRAGMA journal_mode=WAL")
            db.execute("""CREATE TABLE IF NOT EXISTS replay (
                request_id TEXT PRIMARY KEY, owner_id TEXT NOT NULL, reservation_id TEXT NOT NULL UNIQUE,
                run_id TEXT NOT NULL, body_hash TEXT NOT NULL, state TEXT NOT NULL,
                deadline REAL NOT NULL, cache_until REAL, nonce BLOB UNIQUE, response BLOB,
                CHECK (state IN ('STARTED','COMPLETED','UNKNOWN'))
            )""")
            db.execute("BEGIN IMMEDIATE")
            try:
                db.execute("CREATE TABLE IF NOT EXISTS cache_domain (singleton INTEGER PRIMARY KEY CHECK(singleton=1), salt BLOB NOT NULL CHECK(length(salt)=32))")
                domain = db.execute("SELECT salt FROM cache_domain WHERE singleton=1").fetchone()
                if domain is None:
                    salt = os.urandom(32)
                    db.execute("INSERT INTO cache_domain VALUES (1,?)", (salt,))
                else:
                    salt = domain[0]
                key = HKDF(algorithm=hashes.SHA256(), length=32, salt=salt,
                           info=b"jarvis-inference-response-cache/v1\x00" + os.fsencode(path.resolve())).derive(cache_key)
                self.binding_key = HKDF(algorithm=hashes.SHA256(), length=32, salt=salt,
                                        info=b"jarvis-context-binding/v1\x00" + os.fsencode(path.resolve())).derive(cache_key)
                self.aes = AESGCM(key)
                self.key_id = hashlib.sha256(b"jarvis-response-cache-key\x00" + key).hexdigest()
                columns = {row[1] for row in db.execute("PRAGMA table_info(replay)")}
                for name, declaration in (("cache_key_id", "TEXT"), ("aad_version", "INTEGER NOT NULL DEFAULT 0"),
                                          ("account_id", "TEXT"), ("cache_response", "INTEGER NOT NULL DEFAULT 1"),
                                          ("control_revision", "INTEGER NOT NULL DEFAULT 0 CHECK(control_revision IN (0,1))")):
                    if name not in columns:
                        db.execute(f"ALTER TABLE replay ADD COLUMN {name} {declaration}")
                db.execute("""CREATE TABLE IF NOT EXISTS cache_key_usage (
                    key_id TEXT PRIMARY KEY, used INTEGER NOT NULL CHECK (used >= 0 AND used <= 16777216)
                )""")
                db.execute("""CREATE TABLE IF NOT EXISTS cache_nonces (
                    key_id TEXT NOT NULL, nonce BLOB NOT NULL CHECK(length(nonce)=12),
                    PRIMARY KEY(key_id, nonce)
                )""")
                db.execute("INSERT OR IGNORE INTO cache_key_usage VALUES (?,0)", (self.key_id,))
                # Old replay rows retained their nonce even after cache expiry. Reserve
                # those for every opening key conservatively; never delete reservations.
                db.execute("INSERT OR IGNORE INTO cache_nonces SELECT ?,nonce FROM replay WHERE nonce IS NOT NULL AND cache_key_id IS NULL",
                           (self.key_id,))
                db.execute("UPDATE cache_key_usage SET used=MAX(used,(SELECT COUNT(*) FROM cache_nonces WHERE key_id=?)) WHERE key_id=?",
                           (self.key_id, self.key_id))
                self._migrate_control(db, first_migration="control_revision" not in columns)
                db.commit()
            except BaseException:
                db.rollback()
                raise

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.path, timeout=1, isolation_level=None)
        db.execute("PRAGMA synchronous=FULL")
        db.execute("PRAGMA foreign_keys=ON")
        try:
            yield db
        finally:
            db.close()

    @staticmethod
    def _aad(claims, version=AAD_VERSION):
        identity = {key: claims[key] for key in ("owner_id", "request_id", "reservation_id", "run_id", "body_hash")}
        if version != AAD_VERSION:
            raise ReplayConflict("unsupported cache format")
        # The current inference contract has no external account; null explicitly
        # binds that scope. If supplied by a future caller, bind its account too.
        return canonical({**identity, "account_id": claims.get("account_id"),
                          "purpose": "jarvis-inference-response-cache", "format_version": version})

    def _bound_claims(self, claims, cache_response):
        if cache_response:
            return claims
        return {**claims, 'body_hash': hmac.new(self.binding_key, claims['body_hash'].encode('ascii'), hashlib.sha256).hexdigest()}

    def begin(self, claims, now, duration, *, cache_response=True, control=None):
        if control is not None:
            validate("inference-control-request", {**control, "operation": "OBSERVE"})
            if cache_response or any(control[k] != claims[k] for k in ("owner_id", "request_id", "reservation_id", "run_id")):
                raise ReplayConflict("control identity changed")
        claims = self._bound_claims(claims, cache_response)
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            try:
                self._check_control_meta(db)
                existing_control = self._control_row(db, claims["request_id"])
                if existing_control is not None:
                    if control is None:
                        raise ReplayConflict("control downgrade")
                    self._match_control(existing_control, control)
                    if existing_control["fence"] or existing_control["terminal_kind"] is not None:
                        raise ReplayConflict("request fenced")
                elif db.execute("SELECT 1 FROM control_identity WHERE reservation_id=?", (claims["reservation_id"],)).fetchone():
                    raise ReplayConflict("reservation already bound")
                row = db.execute("SELECT owner_id,reservation_id,run_id,body_hash,state,cache_until,nonce,response,account_id,cache_key_id,aad_version,cache_response,control_revision FROM replay WHERE request_id=?", (claims["request_id"],)).fetchone()
                if row:
                    if tuple(row[:4]) != tuple(claims[k] for k in ("owner_id", "reservation_id", "run_id", "body_hash")) or row[8] != claims.get("account_id"):
                        raise ReplayConflict("request identity changed")
                    if row[12] != int(control is not None):
                        raise ReplayConflict("control revision changed")
                    if bool(row[11]) != cache_response:
                        raise ReplayConflict("cache policy changed")
                    if row[4] == "COMPLETED" and row[5] is not None and row[5] > now and row[7] is not None and cache_response:
                        if row[9] != self.key_id:
                            raise ReplayConflict("cache key mismatch")
                        result = parse_json(self.aes.decrypt(row[6], row[7], self._aad(claims, row[10])))
                        db.commit()
                        return result
                    if row[5] is not None and row[5] <= now:
                        db.execute("UPDATE replay SET response=NULL WHERE request_id=?", (claims["request_id"],))
                    db.commit()
                    return "UNKNOWN"
                if db.execute("SELECT 1 FROM replay WHERE reservation_id=?", (claims["reservation_id"],)).fetchone():
                    raise ReplayConflict("reservation already consumed")
                # Clear ciphertext after one hour, retain replay tombstones indefinitely.
                db.execute("UPDATE replay SET response=NULL WHERE cache_until<=?", (now,))
                db.execute("UPDATE replay SET state='UNKNOWN' WHERE state='STARTED' AND deadline<=?", (now,))
                total = self._logical_count(db)
                active = db.execute("SELECT COUNT(*) FROM replay WHERE state='STARTED'").fetchone()[0]
                if (existing_control is None and total >= self.max_rows) or active >= self.max_concurrency:
                    raise LedgerFull("ledger capacity exceeded")
                if control is not None and existing_control is None:
                    self._insert_control(db, control)
                db.execute("INSERT INTO replay(owner_id,request_id,reservation_id,run_id,body_hash,state,deadline,account_id,cache_response,control_revision) VALUES (?,?,?,?,?,'STARTED',?,?,?,?)",
                           tuple(claims[k] for k in ("owner_id", "request_id", "reservation_id", "run_id", "body_hash")) + (now + duration, claims.get("account_id"), int(cache_response), int(control is not None)))
                db.commit()
                return None
            except BaseException:
                db.rollback()
                raise

    @staticmethod
    def _identity(claims):
        return tuple(claims[k] for k in ("request_id", "owner_id", "reservation_id", "run_id", "body_hash")) + (claims.get("account_id"),)

    def _reserve_nonce(self, claims, now):
        # All instances using this key must share this durable ledger. Commit on
        # a separate connection before encrypting: response rollback or a crash
        # cannot release a nonce already used by AES-GCM.
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            try:
                if not db.execute("SELECT 1 FROM replay WHERE request_id=? AND owner_id=? AND reservation_id=? AND run_id=? AND body_hash=? AND account_id IS ? AND state='STARTED' AND deadline>? AND cache_response=1",
                                  self._identity(claims) + (now,)).fetchone():
                    raise ReplayConflict("inference result uncertain")
                used = db.execute("SELECT used FROM cache_key_usage WHERE key_id=?", (self.key_id,)).fetchone()[0]
                if used >= MAX_KEY_ENCRYPTIONS:
                    raise LedgerFull("cache key encryption limit reached")
                for _ in range(MAX_NONCE_ATTEMPTS):
                    nonce = os.urandom(12)
                    inserted = db.execute("INSERT INTO cache_nonces(key_id,nonce) VALUES (?,?) ON CONFLICT(key_id,nonce) DO NOTHING",
                                          (self.key_id, nonce)).rowcount
                    if inserted:
                        db.execute("UPDATE cache_key_usage SET used=used+1 WHERE key_id=?", (self.key_id,))
                        db.commit()
                        return nonce
                raise LedgerFull("cache nonce reservation exhausted")
            except BaseException:
                db.rollback()
                raise

    def complete(self, claims, response, now, *, cache_response=True):
        claims = self._bound_claims(claims, cache_response)
        if not cache_response:
            with self.connect() as db:
                updated = db.execute("UPDATE replay SET state='COMPLETED',cache_until=NULL,nonce=NULL,response=NULL WHERE request_id=? AND owner_id=? AND reservation_id=? AND run_id=? AND body_hash=? AND account_id IS ? AND state='STARTED' AND deadline>? AND cache_response=0",
                                     self._identity(claims) + (now,)).rowcount
                if updated != 1:
                    raise ReplayConflict("inference result uncertain")
            return
        plaintext, aad = canonical(response), self._aad(claims)
        nonce = self._reserve_nonce(claims, now)
        encrypted = self.aes.encrypt(nonce, plaintext, aad)
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            try:
                updated = db.execute("UPDATE replay SET state='COMPLETED',cache_until=?,nonce=?,response=?,cache_key_id=?,aad_version=? WHERE request_id=? AND owner_id=? AND reservation_id=? AND run_id=? AND body_hash=? AND account_id IS ? AND state='STARTED' AND deadline>?",
                                     (now + 3600, nonce, encrypted, self.key_id, AAD_VERSION) + self._identity(claims) + (now,)).rowcount
                if updated != 1:
                    raise ReplayConflict("inference result uncertain")
                db.commit()
            except BaseException:
                db.rollback()
                raise

    def unknown(self, claims, *, cache_response=True):
        claims = self._bound_claims(claims, cache_response)
        with self.connect() as db:
            db.execute("UPDATE replay SET state='UNKNOWN' WHERE request_id=? AND owner_id=? AND reservation_id=? AND run_id=? AND body_hash=? AND account_id IS ? AND state='STARTED'",
                       self._identity(claims))

    @staticmethod
    def _uuid_check(name):
        return (f"length({name})=36 AND {name}=lower({name}) AND "
                f"substr({name},9,1)='-' AND substr({name},14,1)='-' AND "
                f"substr({name},19,1)='-' AND substr({name},24,1)='-' AND "
                f"length(replace({name},'-',''))=32 AND replace({name},'-','') NOT GLOB '*[^0-9a-f]*'")

    def _migrate_control(self, db, *, first_migration):
        """Upgrade once atomically; a damaged upgraded ledger must never reset."""
        expected_tables = {'control_meta', 'control_identity', 'control_generation'}
        expected_triggers = {f'{table}_no_delete' for table in expected_tables} | {
            'control_identity_update_guard', 'control_generation_update_guard', 'control_meta_update_guard',
            'replay_control_revision_guard', 'replay_control_insert_guard', 'control_identity_insert_guard'}
        tables = {row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'control_%'")}
        triggers = {row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='trigger'")}
        if first_migration:
            if tables or expected_triggers & triggers:
                raise ReplayConflict('partial control migration')
        elif not expected_tables <= tables or not expected_triggers <= triggers:
            raise ReplayConflict('incomplete control storage')
        elif db.execute('SELECT COUNT(*) FROM control_meta WHERE singleton=1').fetchone()[0] != 1:
            raise ReplayConflict('missing control authority')
        uuid = self._uuid_check
        db.execute(f"""CREATE TABLE IF NOT EXISTS control_meta (
            singleton INTEGER PRIMARY KEY CHECK(singleton=1),
            schema_revision INTEGER NOT NULL CHECK(schema_revision=1),
            ledger_instance_id TEXT NOT NULL CHECK({uuid('ledger_instance_id')}),
            next_ordinal INTEGER NOT NULL CHECK(typeof(next_ordinal)='integer' AND next_ordinal BETWEEN 1 AND {MAX_SAFE_INTEGER})
        )""")
        db.execute("INSERT OR IGNORE INTO control_meta VALUES (1,1,?,1)", (str(uuid4()),))
        meta = db.execute("SELECT schema_revision,ledger_instance_id FROM control_meta WHERE singleton=1").fetchone()
        if meta is None or meta[0] != 1:
            raise ReplayConflict('unsupported control ledger')
        self.ledger_instance_id = meta[1]
        db.execute(f"""CREATE TABLE IF NOT EXISTS control_generation (
            id TEXT PRIMARY KEY NOT NULL CHECK({uuid('id')}),
            ordinal INTEGER NOT NULL UNIQUE CHECK(typeof(ordinal)='integer' AND ordinal BETWEEN 1 AND {MAX_SAFE_INTEGER}),
            ledger_instance_id TEXT NOT NULL CHECK({uuid('ledger_instance_id')}),
            state TEXT NOT NULL CHECK(state IN ('OWNED','RETIRED')),
            lock_device INTEGER NOT NULL CHECK(typeof(lock_device)='integer' AND lock_device>=0),
            lock_inode INTEGER NOT NULL CHECK(typeof(lock_inode)='integer' AND lock_inode>=0),
            child_started INTEGER NOT NULL CHECK(child_started IN (0,1)),
            exit_observed INTEGER NOT NULL CHECK(exit_observed IN (0,1)),
            CHECK((state='OWNED' AND exit_observed=0) OR (state='RETIRED' AND exit_observed=1))
        )""")
        identities = ','.join(f"{name} TEXT {'PRIMARY KEY NOT NULL' if name == 'request_id' else 'NOT NULL'} CHECK({uuid(name)})" for name in
                              ('request_id','reservation_id','owner_id','run_id','context_id'))
        db.execute(f"""CREATE TABLE IF NOT EXISTS control_identity (
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
        )""")
        immutable = ' OR '.join(f'NEW.{name} IS NOT OLD.{name}' for name in (*CONTROL_COLUMNS, 'cache_policy'))
        db.execute(f"""CREATE TRIGGER IF NOT EXISTS control_identity_update_guard BEFORE UPDATE ON control_identity
            WHEN {immutable} OR NEW.fence<OLD.fence OR
                 (OLD.generation_id IS NOT NULL AND NEW.generation_id IS NOT OLD.generation_id) OR
                 (OLD.terminal_kind IS NOT NULL AND
                  (NEW.terminal_kind IS NOT OLD.terminal_kind OR NEW.terminal_ordinal IS NOT OLD.terminal_ordinal OR NEW.generation_id IS NOT OLD.generation_id))
            BEGIN SELECT RAISE(ABORT,'immutable control identity'); END""")
        db.execute("""CREATE TRIGGER IF NOT EXISTS control_generation_update_guard BEFORE UPDATE ON control_generation
            WHEN NEW.id IS NOT OLD.id OR NEW.ordinal IS NOT OLD.ordinal OR
                 NEW.ledger_instance_id IS NOT OLD.ledger_instance_id OR NEW.lock_device IS NOT OLD.lock_device OR
                 NEW.lock_inode IS NOT OLD.lock_inode OR NEW.child_started<OLD.child_started OR
                 NEW.exit_observed<OLD.exit_observed OR (OLD.state='RETIRED' AND NEW.state IS NOT OLD.state)
            BEGIN SELECT RAISE(ABORT,'immutable control generation'); END""")
        db.execute("""CREATE TRIGGER IF NOT EXISTS control_meta_update_guard BEFORE UPDATE ON control_meta
            WHEN NEW.singleton IS NOT OLD.singleton OR NEW.schema_revision IS NOT OLD.schema_revision OR
                 NEW.ledger_instance_id IS NOT OLD.ledger_instance_id OR NEW.next_ordinal<=OLD.next_ordinal
            BEGIN SELECT RAISE(ABORT,'immutable control authority'); END""")
        db.execute("""CREATE TRIGGER IF NOT EXISTS replay_control_revision_guard BEFORE UPDATE OF control_revision ON replay
            WHEN NEW.control_revision IS NOT OLD.control_revision
            BEGIN SELECT RAISE(ABORT,'immutable control revision'); END""")
        db.execute("""CREATE TRIGGER IF NOT EXISTS replay_control_insert_guard BEFORE INSERT ON replay
            WHEN (NEW.control_revision=1 AND (NEW.cache_response<>0 OR NOT EXISTS (
                SELECT 1 FROM control_identity c WHERE c.request_id=NEW.request_id AND c.reservation_id=NEW.reservation_id
                  AND c.owner_id=NEW.owner_id AND c.run_id=NEW.run_id AND c.fence=0 AND c.terminal_kind IS NULL))) OR
                 (NEW.control_revision=0 AND EXISTS (SELECT 1 FROM control_identity c
                    WHERE c.request_id=NEW.request_id OR c.reservation_id=NEW.reservation_id))
            BEGIN SELECT RAISE(ABORT,'invalid control replay'); END""")
        db.execute("""CREATE TRIGGER IF NOT EXISTS control_identity_insert_guard BEFORE INSERT ON control_identity
            WHEN EXISTS (SELECT 1 FROM replay r WHERE r.request_id=NEW.request_id OR r.reservation_id=NEW.reservation_id)
                 OR NEW.generation_id IS NOT NULL OR NEW.terminal_kind IS NOT NULL OR NEW.terminal_ordinal IS NOT NULL
            BEGIN SELECT RAISE(ABORT,'invalid initial control identity'); END""")
        for table in ('control_identity', 'control_generation', 'control_meta'):
            db.execute(f"""CREATE TRIGGER IF NOT EXISTS {table}_no_delete BEFORE DELETE ON {table}
                BEGIN SELECT RAISE(ABORT,'control evidence cannot be deleted'); END""")

    def _check_control_meta(self, db):
        row = db.execute('SELECT schema_revision,ledger_instance_id FROM control_meta WHERE singleton=1').fetchone()
        if row != (1, self.ledger_instance_id):
            raise ReplayConflict('control ledger changed')

    @staticmethod
    def _logical_count(db):
        return db.execute('SELECT COUNT(*) FROM (SELECT request_id FROM replay UNION SELECT request_id FROM control_identity)').fetchone()[0]

    @staticmethod
    def _control_row(db, request_id):
        columns = (*CONTROL_COLUMNS, 'fence', 'generation_id', 'terminal_kind', 'terminal_ordinal')
        row = db.execute(f"SELECT {','.join(columns)} FROM control_identity WHERE request_id=?", (request_id,)).fetchone()
        return dict(zip(columns, row)) if row is not None else None

    @staticmethod
    def _match_control(row, identity):
        if identity.get('revision') != 1 or any(row[name] != identity[name] for name in CONTROL_COLUMNS):
            raise ReplayConflict('control identity changed')

    @staticmethod
    def _insert_control(db, identity):
        columns = ','.join(CONTROL_COLUMNS)
        values = ','.join('?' for _ in CONTROL_COLUMNS)
        db.execute(f"INSERT INTO control_identity({columns},cache_policy) VALUES ({values},'CONTEXT_V1_NO_RESPONSE_CACHE')",
                   tuple(identity[name] for name in CONTROL_COLUMNS))

    def _next_control_ordinal(self, db):
        self._check_control_meta(db)
        ordinal = db.execute('SELECT next_ordinal FROM control_meta WHERE singleton=1').fetchone()[0]
        # Keep next_ordinal in the safe integer domain; no wrap or reset.
        if ordinal >= MAX_SAFE_INTEGER:
            raise LedgerFull('control ordinal exhausted')
        db.execute('UPDATE control_meta SET next_ordinal=? WHERE singleton=1', (ordinal + 1,))
        return ordinal

    def _terminal_control(self, db, request_id, kind, generation_id):
        row = self._control_row(db, request_id)
        if row is None or row['generation_id'] != generation_id:
            raise ReplayConflict('control generation changed')
        if row['terminal_kind'] is not None:
            if row['terminal_kind'] != kind:
                raise ReplayConflict('control terminal conflict')
            return
        ordinal = self._next_control_ordinal(db)
        db.execute('UPDATE control_identity SET terminal_kind=?,terminal_ordinal=? WHERE request_id=?',
                   (kind, ordinal, request_id))

    def _control_response(self, identity, row):
        terminal = None
        if row is not None and row['terminal_kind'] is not None:
            terminal = {'kind': row['terminal_kind'], 'generation_id': row['generation_id'], 'ordinal': row['terminal_ordinal']}
        result = {name: identity[name] for name in CONTROL_IDENTITY_FIELDS}
        result.update(state='NOT_OBSERVED' if row is None else 'TERMINAL' if terminal else 'PENDING',
                      ledger_instance_id=self.ledger_instance_id, terminal=terminal)
        return validate('inference-control-response', result)

    def control(self, identity):
        """OBSERVE is read-only; FENCE serializes durably with the send claim."""
        validate('inference-control-request', identity)
        fence = identity['operation'] == 'FENCE_OR_STOP'
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE' if fence else 'BEGIN')
            try:
                self._check_control_meta(db)
                row = self._control_row(db, identity['request_id'])
                if row is not None:
                    self._match_control(row, identity)
                else:
                    if (db.execute('SELECT 1 FROM replay WHERE request_id=? OR reservation_id=?',
                                   (identity['request_id'], identity['reservation_id'])).fetchone() or
                            db.execute('SELECT 1 FROM control_identity WHERE reservation_id=?', (identity['reservation_id'],)).fetchone()):
                        raise ReplayConflict('legacy or conflicting control identity')
                    if fence:
                        if self._logical_count(db) >= self.max_rows:
                            raise LedgerFull('ledger capacity exceeded')
                        self._insert_control(db, identity)
                        row = self._control_row(db, identity['request_id'])
                if fence and row['terminal_kind'] is None:
                    db.execute('UPDATE control_identity SET fence=1 WHERE request_id=?', (identity['request_id'],))
                    if row['generation_id'] is None:
                        self._terminal_control(db, identity['request_id'], 'UNSTARTED_FENCED', None)
                    row = self._control_row(db, identity['request_id'])
                result = self._control_response(identity, row)
                db.commit()
                return result
            except BaseException:
                db.rollback()
                raise

    def control_send_allowed(self, request_id):
        with self.connect() as db:
            self._check_control_meta(db)
            row = self._control_row(db, request_id)
            if row is None:
                raise ReplayConflict('missing control identity')
            return not row['fence'] and row['terminal_kind'] is None and row['generation_id'] is None

    def claim_control_send(self, request_id, generation_id):
        """A committed claim means possibly sent, including a crash before send."""
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            try:
                self._check_control_meta(db)
                row = self._control_row(db, request_id)
                if row is None:
                    raise ReplayConflict('missing control identity')
                if row['fence'] or row['terminal_kind'] is not None:
                    db.commit()
                    return False
                if row['generation_id'] is not None:
                    raise ReplayConflict('generation already claimed')
                if not db.execute("SELECT 1 FROM replay WHERE request_id=? AND control_revision=1 AND cache_response=0 AND state='STARTED'", (request_id,)).fetchone():
                    raise ReplayConflict('request not admitted')
                if not db.execute("SELECT 1 FROM control_generation WHERE id=? AND ledger_instance_id=? AND state='OWNED' AND child_started=1 AND exit_observed=0", (generation_id,self.ledger_instance_id)).fetchone():
                    raise ReplayConflict('generation not owned')
                db.execute('UPDATE control_identity SET generation_id=? WHERE request_id=?', (generation_id,request_id))
                db.commit()
                return True
            except BaseException:
                db.rollback()
                raise

    def register_control_generation(self, lock_device, lock_inode):
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            try:
                self._check_control_meta(db)
                if db.execute("SELECT 1 FROM control_generation WHERE state='OWNED'").fetchone():
                    raise ReplayConflict('prior generation unresolved')
                if db.execute('SELECT COUNT(*) FROM control_generation').fetchone()[0] >= MAX_CONTROL_GENERATIONS:
                    raise LedgerFull('generation capacity exceeded')
                generation_id = str(uuid4())
                ordinal = self._next_control_ordinal(db)
                db.execute("INSERT INTO control_generation VALUES (?,?,?,'OWNED',?,?,0,0)",
                           (generation_id,ordinal,self.ledger_instance_id,lock_device,lock_inode))
                db.commit()
                return generation_id
            except BaseException:
                db.rollback()
                raise

    def control_child_starting(self, generation_id):
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            try:
                self._check_control_meta(db)
                if db.execute("UPDATE control_generation SET child_started=1 WHERE id=? AND ledger_instance_id=? AND state='OWNED' AND child_started=0", (generation_id,self.ledger_instance_id)).rowcount != 1:
                    raise ReplayConflict('generation not owned')
                db.commit()
            except BaseException:
                db.rollback()
                raise

    def control_completed(self, request_id, generation_id):
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            try:
                self._check_control_meta(db)
                self._terminal_control(db, request_id, 'COMPLETED', generation_id)
                db.commit()
            except BaseException:
                db.rollback()
                raise

    def _retire_control_generation(self, db, generation_id):
        row = db.execute('SELECT ledger_instance_id,state FROM control_generation WHERE id=?', (generation_id,)).fetchone()
        if row is None or row[0] != self.ledger_instance_id:
            raise ReplayConflict('generation authority changed')
        db.execute("UPDATE control_generation SET state='RETIRED',exit_observed=1 WHERE id=?", (generation_id,))
        # Bounded by the logical request cap, including any old uncertain call.
        for request_id, in db.execute('SELECT request_id FROM control_identity WHERE generation_id=? AND terminal_kind IS NULL', (generation_id,)).fetchall():
            self._terminal_control(db, request_id, 'WORKER_STOPPED', generation_id)

    def control_worker_stopped(self, generation_id):
        """Caller must have confirmed this exact process's exit, never timeout."""
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            try:
                self._check_control_meta(db)
                self._retire_control_generation(db, generation_id)
                db.commit()
            except BaseException:
                db.rollback()
                raise

    def recover_control_generations(self, lock_device, lock_inode):
        """Only called after fresh exclusive acquisition of the inherited lock.

        A live orphan retains that kernel lock. Replacement of the path/object
        or ledger cannot prove anything about its lifetime and fails closed.
        """
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            try:
                self._check_control_meta(db)
                rows = db.execute("SELECT id,ledger_instance_id,lock_device,lock_inode FROM control_generation WHERE state='OWNED'").fetchall()
                if any(row[1:] != (self.ledger_instance_id,lock_device,lock_inode) for row in rows):
                    raise ReplayConflict('inherited lock changed')
                for generation_id, *_ in rows:
                    self._retire_control_generation(db, generation_id)
                db.commit()
            except BaseException:
                db.rollback()
                raise

    def control_generation_for(self, request_id):
        with self.connect() as db:
            self._check_control_meta(db)
            row = self._control_row(db, request_id)
            if row is None or row['terminal_kind'] is not None:
                return None
            return row['generation_id']

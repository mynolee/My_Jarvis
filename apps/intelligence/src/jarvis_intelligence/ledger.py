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
import re
import stat
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
    def __init__(self, path: Path, cache_key: bytes, *, max_rows=10000, max_concurrency=4, ownership_lease=None):
        if len(cache_key) != 32 or not path.is_absolute() or path.resolve() != path or str(path) == ":memory:":
            raise ValueError("persistent ledger and AES-256 key required")
        self.path = path
        self.max_rows, self.max_concurrency = max_rows, max_concurrency
        self.ownership_lease = ownership_lease
        self._initialized = False
        self._new_cache_authority = False
        self.schema_revision = None
        path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        existed = path.exists()
        if ownership_lease is not None:
            ownership_lease.verify()
        flags = os.O_RDWR | os.O_NOFOLLOW | os.O_NONBLOCK
        fd = os.open(path, flags if existed else flags | os.O_CREAT | os.O_EXCL, 0o600)
        try:
            info = os.fstat(fd)
            if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or
                    info.st_mode & 0o077 or info.st_nlink != 1):
                raise ReplayConflict('private ledger required')
            self._file_identity = (info.st_dev, info.st_ino)
        finally:
            os.close(fd)
        if ownership_lease is not None:
            ownership_lease.bind_ledger(path, self._file_identity)
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            try:
                tables = {row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table' LIMIT 9")}
                fresh = not tables
                upgrading_existing_control = False
                if fresh and existed:
                    raise ReplayConflict('missing ledger authority')
                if fresh:
                    self._new_cache_authority = True
                    self._create_cache_storage(db)
                    for statement in self._control_schema(2): db.execute(statement)
                    db.execute('INSERT INTO control_meta VALUES (1,2,?,1)', (str(uuid4()),))
                else:
                    if 'replay' not in tables:
                        raise ReplayConflict('missing replay authority')
                    columns = {row[1] for row in db.execute('PRAGMA table_info(replay)')}
                    if 'control_revision' not in columns:
                        if ownership_lease is None:
                            raise ReplayConflict('original ownership lease required for upgrade')
                        self._upgrade_legacy_cache(db)
                        for statement in self._control_schema(1): db.execute(statement)
                        db.execute('INSERT INTO control_meta VALUES (1,1,?,1)', (str(uuid4()),))
                    # No CREATE IF NOT EXISTS may reconstruct missing authority.
                    self._validate_storage(db)
                    revision = db.execute('SELECT schema_revision FROM control_meta WHERE singleton=1').fetchone()[0]
                    if revision == 1:
                        upgrading_existing_control = "control_revision" in columns
                        self._upgrade_feedback(db, cache_key if upgrading_existing_control else None)
                self._validate_storage(db, expected_revision=2)
                salt_row = db.execute('SELECT salt FROM cache_domain WHERE singleton=1').fetchone()
                if salt_row is None or len(salt_row[0]) != 32:
                    raise ReplayConflict('missing cache domain')
                salt = salt_row[0]
                key = HKDF(algorithm=hashes.SHA256(), length=32, salt=salt,
                           info=b"jarvis-inference-response-cache/v1\x00" + os.fsencode(path.resolve())).derive(cache_key)
                self.binding_key = HKDF(algorithm=hashes.SHA256(), length=32, salt=salt,
                                        info=b"jarvis-context-binding/v1\x00" + os.fsencode(path.resolve())).derive(cache_key)
                self.aes = AESGCM(key)
                self.key_id = hashlib.sha256(b"jarvis-response-cache-key\x00" + key).hexdigest()
                if upgrading_existing_control:
                    if not db.execute('SELECT 1 FROM cache_key_usage WHERE key_id=?', (self.key_id,)).fetchone():
                        raise ReplayConflict('original cache key domain required for upgrade')
                else:
                    db.execute('INSERT OR IGNORE INTO cache_key_usage VALUES (?,0)', (self.key_id,))
                if fresh or 'control_revision' not in columns:
                    db.execute('INSERT OR IGNORE INTO cache_nonces SELECT ?,nonce FROM replay WHERE nonce IS NOT NULL AND cache_key_id IS NULL', (self.key_id,))
                    db.execute('UPDATE cache_key_usage SET used=MAX(used,(SELECT COUNT(*) FROM cache_nonces WHERE key_id=?)) WHERE key_id=?', (self.key_id,self.key_id))
                self._new_cache_authority = False
                self._validate_cache_rows(db)
                self.ledger_instance_id = db.execute('SELECT ledger_instance_id FROM control_meta WHERE singleton=1').fetchone()[0]
                if ownership_lease is not None: ownership_lease.verify()
                self._upgrade_checkpoint('before_commit')
                db.commit()
                self.schema_revision = 2
                self._initialized = True
                self._upgrade_checkpoint('after_commit')
            except BaseException:
                db.rollback()
                raise
        # WAL is a storage policy, not a migration authority or proof.
        with self.connect() as db: db.execute('PRAGMA journal_mode=WAL')

    @staticmethod
    def _cache_schema():
        return [
            """CREATE TABLE replay (request_id TEXT PRIMARY KEY, owner_id TEXT NOT NULL, reservation_id TEXT NOT NULL UNIQUE,
                run_id TEXT NOT NULL, body_hash TEXT NOT NULL, state TEXT NOT NULL,
                deadline REAL NOT NULL, cache_until REAL, nonce BLOB UNIQUE, response BLOB,
                CHECK (state IN ('STARTED','COMPLETED','UNKNOWN')))""",
            "CREATE TABLE cache_domain (singleton INTEGER PRIMARY KEY CHECK(singleton=1), salt BLOB NOT NULL CHECK(length(salt)=32))",
            "CREATE TABLE cache_key_usage (key_id TEXT PRIMARY KEY, used INTEGER NOT NULL CHECK (used >= 0 AND used <= 16777216))",
            "CREATE TABLE cache_nonces (key_id TEXT NOT NULL, nonce BLOB NOT NULL CHECK(length(nonce)=12), PRIMARY KEY(key_id, nonce))",
        ]

    @staticmethod
    def _replay_additions():
        return (("cache_key_id", "TEXT"), ("aad_version", "INTEGER NOT NULL DEFAULT 0"),
                ("account_id", "TEXT"), ("cache_response", "INTEGER NOT NULL DEFAULT 1"),
                ("control_revision", "INTEGER NOT NULL DEFAULT 0 CHECK(control_revision IN (0,1))"))

    def _create_cache_storage(self, db):
        for statement in self._cache_schema(): db.execute(statement)
        for name, declaration in self._replay_additions():
            db.execute(f'ALTER TABLE replay ADD COLUMN {name} {declaration}')
        db.execute('INSERT INTO cache_domain VALUES (1,?)', (os.urandom(32),))

    def _upgrade_legacy_cache(self, db):
        # The original revision-zero cache upgrade gives old rows revision zero,
        # never a control identity, generation or terminal fact.
        names = {row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table' LIMIT 9")}
        if names - {'replay','cache_domain','cache_key_usage','cache_nonces'} or db.execute("SELECT 1 FROM sqlite_master WHERE type IN ('view','trigger')").fetchone():
            raise ReplayConflict('partial legacy control storage')
        self._validate_replay_sql(db, allow_legacy=True)
        cache_tables = {'cache_domain','cache_key_usage','cache_nonces'}
        columns = {row[1] for row in db.execute('PRAGMA table_info(replay)')}
        raw_legacy = names == {'replay'}
        if raw_legacy:
            self._new_cache_authority = True
            if any(name in columns for name,_ in self._replay_additions()):
                raise ReplayConflict('missing established legacy cache authority')
            for statement in self._cache_schema()[1:]: db.execute(statement)
            db.execute('INSERT INTO cache_domain VALUES (1,?)', (os.urandom(32),))
        else:
            if names != {'replay'} | cache_tables:
                raise ReplayConflict('partial legacy cache authority')
            for statement in self._cache_schema()[1:]:
                name = statement.split()[2]
                actual = db.execute("SELECT sql FROM sqlite_master WHERE type='table' AND name=?", (name,)).fetchone()
                if actual is None or self._sql_normalized(actual[0]) != self._sql_normalized(statement):
                    raise ReplayConflict('changed legacy cache authority')
            self._validate_cache_rows(db)
        columns = {row[1] for row in db.execute('PRAGMA table_info(replay)')}
        for name, declaration in self._replay_additions():
            if name not in columns: db.execute(f'ALTER TABLE replay ADD COLUMN {name} {declaration}')

    @staticmethod
    def _validate_cache_rows(db, *, allow_empty_keys=False):
        domain = db.execute('SELECT singleton,salt FROM cache_domain LIMIT 2').fetchall()
        if len(domain) != 1 or domain[0][0] != 1 or not isinstance(domain[0][1], bytes) or len(domain[0][1]) != 32:
            raise ReplayConflict('missing original cache domain')
        if ((not allow_empty_keys and not db.execute('SELECT 1 FROM cache_key_usage').fetchone()) or
                db.execute("SELECT 1 FROM cache_key_usage WHERE key_id IS NULL OR typeof(used)<>'integer' OR used<0 OR used>16777216").fetchone() or
                db.execute("SELECT 1 FROM cache_nonces WHERE typeof(nonce)<>'blob'").fetchone() or
                db.execute('SELECT 1 FROM cache_nonces n LEFT JOIN cache_key_usage k ON k.key_id=n.key_id WHERE k.key_id IS NULL').fetchone() or
                db.execute('SELECT 1 FROM cache_key_usage k WHERE k.used<(SELECT COUNT(*) FROM cache_nonces n WHERE n.key_id=k.key_id)').fetchone()):
            raise ReplayConflict('missing original nonce authority')

    @contextmanager
    def connect(self):
        current = os.stat(self.path, follow_symlinks=False)
        if (not stat.S_ISREG(current.st_mode) or current.st_uid != os.getuid() or current.st_mode & 0o077 or
                current.st_nlink != 1 or (current.st_dev, current.st_ino) != self._file_identity):
            raise ReplayConflict('ledger file changed')
        if self.ownership_lease is not None: self.ownership_lease.verify()
        db = sqlite3.connect(self.path, timeout=1, isolation_level=None)
        db.execute("PRAGMA synchronous=FULL")
        db.execute("PRAGMA foreign_keys=ON")
        try:
            if self._initialized: self._check_control_meta(db)
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

    @classmethod
    def _control_schema(cls, revision):
        if revision not in (1, 2): raise ReplayConflict('unsupported control ledger')
        uuid = cls._uuid_check
        purposes = "'CONVERSATION','STUDY_LESSON'" + (",'STUDY_FEEDBACK'" if revision == 2 else '')
        identities = ','.join(f"{name} TEXT {'PRIMARY KEY NOT NULL' if name == 'request_id' else 'NOT NULL'} CHECK({uuid(name)})" for name in ('request_id','reservation_id','owner_id','run_id','context_id'))
        immutable = ' OR '.join(f'NEW.{name} IS NOT OLD.{name}' for name in (*CONTROL_COLUMNS, 'cache_policy'))
        statements = [
            f"""CREATE TABLE IF NOT EXISTS control_meta (
            singleton INTEGER PRIMARY KEY CHECK(singleton=1),
            schema_revision INTEGER NOT NULL CHECK(schema_revision={revision}),
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
            purpose TEXT NOT NULL CHECK(purpose IN ({purposes})),
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

    @staticmethod
    def _sql_normalized(sql):
        # sqlite_master removes IF NOT EXISTS and quotes renamed identifiers.
        tokens = re.findall(r"'(?:''|[^'])*'|\"(?:\"\"|[^\"])*\"|\s+|[^\s'\"]+", sql.replace('IF NOT EXISTS', ''))
        return ''.join(token[1:-1].replace('""', '"') if token.startswith('"') else token
                       for token in tokens if not token.isspace())

    def _validate_replay_sql(self, db, *, allow_legacy=False):
        sql = db.execute("SELECT sql FROM sqlite_master WHERE type='table' AND name='replay'").fetchone()
        if sql is None: raise ReplayConflict('missing replay table')
        normalized = self._sql_normalized(sql[0])
        columns = {row[1] for row in db.execute('PRAGMA table_info(replay)')}
        for name, declaration in reversed(self._replay_additions()):
            if name in columns:
                fragment = ',' + self._sql_normalized(name + ' ' + declaration)
                if normalized.count(fragment) != 1: raise ReplayConflict('changed replay schema')
                normalized = normalized.replace(fragment, '', 1)
            elif not allow_legacy:
                raise ReplayConflict('missing replay revision marker')
        # The original supported replay-only predecessor had exactly this DDL.
        # Its missing state CHECK is compensated by explicit row validation;
        # no other omitted/reordered constraint or third fingerprint is accepted.
        historical = ("CREATE TABLE replay (request_id TEXT PRIMARY KEY,owner_id TEXT NOT NULL,"
                      "reservation_id TEXT UNIQUE NOT NULL,run_id TEXT NOT NULL,body_hash TEXT NOT NULL,"
                      "state TEXT NOT NULL,deadline REAL NOT NULL,cache_until REAL,nonce BLOB UNIQUE,response BLOB)")
        approved = {self._sql_normalized(self._cache_schema()[0]),self._sql_normalized(historical)}
        if normalized not in approved:
            raise ReplayConflict('changed replay schema')
        if db.execute("SELECT 1 FROM replay WHERE request_id IS NULL OR owner_id IS NULL OR reservation_id IS NULL OR run_id IS NULL OR body_hash IS NULL OR state IS NULL OR state NOT IN ('STARTED','COMPLETED','UNKNOWN')").fetchone():
            raise ReplayConflict('invalid legacy replay row')

    def _validate_storage(self, db, *, expected_revision=None):
        tables = {'replay','cache_domain','cache_key_usage','cache_nonces','control_meta','control_identity','control_generation'}
        if {row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table' LIMIT 9")} != tables:
            raise ReplayConflict('unknown or partial ledger schema')
        if db.execute("SELECT 1 FROM sqlite_master WHERE type='view' OR name LIKE '%_upgrade_v2%'").fetchone():
            raise ReplayConflict('unexpected ledger view')
        meta = db.execute('SELECT singleton,schema_revision,ledger_instance_id,next_ordinal FROM control_meta LIMIT 2').fetchall()
        if len(meta) != 1 or meta[0][0] != 1 or meta[0][1] not in (1,2) or (expected_revision is not None and meta[0][1] != expected_revision):
            raise ReplayConflict('unsupported control ledger')
        expected = {}
        for sql in self._control_schema(meta[0][1]) + self._cache_schema()[1:]:
            words = sql.replace('IF NOT EXISTS ', '').split()
            expected[(words[1].lower(), words[2])] = self._sql_normalized(sql)
        actual = {(kind,name): self._sql_normalized(sql) for kind,name,sql in db.execute("SELECT type,name,sql FROM sqlite_master WHERE type IN ('table','trigger') AND name<>'replay' LIMIT 17")}
        if actual != expected: raise ReplayConflict('ledger schema fingerprint mismatch')
        self._validate_replay_sql(db)
        indexes = {'replay': {('request_id',),('reservation_id',),('nonce',)},
                   'cache_domain': set(), 'cache_key_usage': {('key_id',)}, 'cache_nonces': {('key_id','nonce')},
                   'control_meta': set(), 'control_generation': {('id',),('ordinal',)},
                   'control_identity': {('request_id',),('reservation_id',),('terminal_ordinal',)}}
        for table in sorted(tables):
            rows = db.execute(f'PRAGMA index_list({table})').fetchall()
            if any(not row[1].startswith('sqlite_autoindex_') or row[2] != 1 or row[4] != 0 for row in rows):
                raise ReplayConflict('unexpected ledger index')
            keys = {tuple(item[2] for item in db.execute(f'PRAGMA index_info({row[1]})')) for row in rows}
            if len(rows) != len(indexes[table]) or keys != indexes[table]:
                raise ReplayConflict('ledger index mismatch')
            fks = db.execute(f'PRAGMA foreign_key_list({table})').fetchall()
            if table == 'control_identity':
                if len(fks) != 1 or fks[0][2:] != ('control_generation','generation_id','id','NO ACTION','NO ACTION','NONE'):
                    raise ReplayConflict('control generation foreign key changed')
            elif fks:
                raise ReplayConflict('unexpected inbound ledger foreign key')
        self._validate_cache_rows(db, allow_empty_keys=self._new_cache_authority)
        self._validate_control_rows(db, meta[0])

    def _validate_control_rows(self, db, meta):
        for table,limit in (('replay',self.max_rows),('control_identity',self.max_rows),('control_generation',MAX_CONTROL_GENERATIONS)):
            if db.execute(f'SELECT COUNT(*) FROM (SELECT 1 FROM {table} LIMIT ?)',(limit+1,)).fetchone()[0] > limit:
                raise LedgerFull('ledger migration capacity exceeded')
        if db.execute('PRAGMA foreign_key_check').fetchone(): raise ReplayConflict('ledger foreign key mismatch')
        if self._logical_count(db) > self.max_rows or db.execute('SELECT COUNT(*) FROM control_generation').fetchone()[0] > MAX_CONTROL_GENERATIONS:
            raise LedgerFull('ledger migration capacity exceeded')
        if db.execute("SELECT 1 FROM control_generation WHERE ledger_instance_id<>? OR ordinal>=?", (meta[2],meta[3])).fetchone():
            raise ReplayConflict('generation authority mismatch')
        if db.execute('SELECT 1 FROM control_identity WHERE terminal_ordinal>=?', (meta[3],)).fetchone():
            raise ReplayConflict('terminal ordinal mismatch')
        if db.execute('SELECT ordinal FROM control_generation INTERSECT SELECT terminal_ordinal FROM control_identity').fetchone():
            raise ReplayConflict('duplicate control ordinal')
        if db.execute("SELECT 1 FROM replay r LEFT JOIN control_identity c ON c.request_id=r.request_id WHERE (r.control_revision=1 AND (c.request_id IS NULL OR r.cache_response<>0 OR r.owner_id<>c.owner_id OR r.run_id<>c.run_id OR r.reservation_id<>c.reservation_id)) OR (r.control_revision=0 AND c.request_id IS NOT NULL)").fetchone():
            raise ReplayConflict('control replay lineage mismatch')
        if db.execute('SELECT 1 FROM replay r JOIN control_identity c ON c.reservation_id=r.reservation_id WHERE c.request_id<>r.request_id').fetchone():
            raise ReplayConflict('reservation authority mismatch')
        if db.execute("SELECT 1 FROM control_identity c LEFT JOIN replay r ON r.request_id=c.request_id WHERE r.request_id IS NULL AND NOT COALESCE((c.fence=1 AND c.generation_id IS NULL AND c.terminal_kind='UNSTARTED_FENCED' AND c.terminal_ordinal IS NOT NULL),0)").fetchone():
            raise ReplayConflict('orphan control identity')
        if db.execute("SELECT 1 FROM replay WHERE control_revision NOT IN (0,1) OR cache_response NOT IN (0,1) OR (control_revision=1 AND (response IS NOT NULL OR nonce IS NOT NULL OR cache_until IS NOT NULL OR length(body_hash)<>64))").fetchone():
            raise ReplayConflict('invalid controlled replay storage')
        if self.ownership_lease is not None:
            device,inode = self.ownership_lease.verify()
            if db.execute('SELECT 1 FROM control_generation WHERE lock_device<>? OR lock_inode<>?', (device,inode)).fetchone():
                raise ReplayConflict('original ownership lock changed')

    @staticmethod
    def _upgrade_checkpoint(stage):
        """Fault injection seam. Production never executes a second connection."""

    def _upgrade_feedback(self, db, opening_cache_key=None):
        if self.ownership_lease is None:
            raise ReplayConflict('original ownership lease required for upgrade')
        self.ownership_lease.verify()
        self._validate_storage(db, expected_revision=1)
        if opening_cache_key is not None:
            salt = db.execute('SELECT salt FROM cache_domain WHERE singleton=1').fetchone()[0]
            original = HKDF(algorithm=hashes.SHA256(),length=32,salt=salt,
                            info=b"jarvis-inference-response-cache/v1\x00" + os.fsencode(self.path.resolve())).derive(opening_cache_key)
            original_key_id = hashlib.sha256(b"jarvis-response-cache-key\x00" + original).hexdigest()
            if not db.execute('SELECT 1 FROM cache_key_usage WHERE key_id=?', (original_key_id,)).fetchone():
                raise ReplayConflict('original cache key domain required for upgrade')
        # Exact bounded pre-drop row witnesses survive the original table drops.
        identities = db.execute('SELECT * FROM control_identity ORDER BY request_id LIMIT ?', (self.max_rows + 1,)).fetchall()
        metadata = db.execute('SELECT * FROM control_meta').fetchall()
        if len(identities) > self.max_rows or len(metadata) != 1: raise LedgerFull('upgrade witness bound exceeded')
        self._upgrade_checkpoint('before_create')
        definitions = self._control_schema(2)
        for name in ('control_identity','control_meta'):
            statement = next(sql for sql in definitions if sql.startswith(f'CREATE TABLE IF NOT EXISTS {name} ('))
            db.execute(statement.replace(f'IF NOT EXISTS {name}', f'{name}_upgrade_v2', 1))
            self._upgrade_checkpoint('after_create_' + name)
        db.execute('INSERT INTO control_identity_upgrade_v2 SELECT * FROM control_identity')
        self._upgrade_checkpoint('after_copy_identity')
        db.execute('INSERT INTO control_meta_upgrade_v2 SELECT singleton,2,ledger_instance_id,next_ordinal FROM control_meta')
        self._upgrade_checkpoint('after_copy_meta')
        if db.execute('SELECT * FROM control_identity_upgrade_v2 ORDER BY request_id').fetchall() != identities:
            raise ReplayConflict('upgrade identity copy mismatch')
        wanted_meta = [(metadata[0][0],2,*metadata[0][2:])]
        if db.execute('SELECT * FROM control_meta_upgrade_v2').fetchall() != wanted_meta:
            raise ReplayConflict('upgrade authority copy mismatch')
        if db.execute('PRAGMA foreign_key_check').fetchone(): raise ReplayConflict('upgrade foreign key mismatch')
        self._upgrade_checkpoint('after_copy_validation')
        affected = ('control_identity_update_guard','control_identity_insert_guard','control_identity_no_delete',
                    'control_meta_update_guard','control_meta_no_delete','replay_control_insert_guard')
        for name in affected:
            db.execute('DROP TRIGGER ' + name)
            self._upgrade_checkpoint('after_drop_trigger_' + name)
        for name in ('control_identity','control_meta'):
            db.execute('DROP TABLE ' + name)
            self._upgrade_checkpoint('after_drop_' + name)
            db.execute(f'ALTER TABLE {name}_upgrade_v2 RENAME TO {name}')
            self._upgrade_checkpoint('after_rename_' + name)
        for name in affected:
            statement = next(sql for sql in definitions if sql.startswith(f'CREATE TRIGGER IF NOT EXISTS {name} '))
            db.execute(statement)
            self._upgrade_checkpoint('after_create_trigger_' + name)
        self._validate_storage(db, expected_revision=2)
        if db.execute('SELECT * FROM control_identity ORDER BY request_id').fetchall() != identities or db.execute('SELECT * FROM control_meta').fetchall() != wanted_meta:
            raise ReplayConflict('upgrade final row witness mismatch')
        self.ownership_lease.verify()
        self._upgrade_checkpoint('after_final_validation')

    def _check_control_meta(self, db):
        row = db.execute('SELECT schema_revision,ledger_instance_id FROM control_meta WHERE singleton=1').fetchone()
        if row != (2, self.ledger_instance_id):
            raise ReplayConflict('control ledger changed')

    def feedback_ready(self):
        """Read-only current storage gate; a cached startup marker is insufficient."""
        try:
            if self.schema_revision != 2 or self.ownership_lease is None:
                return False
            self.ownership_lease.verify()
            with self.connect() as db:
                self._check_control_meta(db)
            return True
        except Exception:
            return False

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

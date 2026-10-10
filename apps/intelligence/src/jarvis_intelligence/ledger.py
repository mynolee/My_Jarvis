"""Private inference-only response cache and durable replay tombstones.

Key domain is this canonical ledger path plus its persistent random salt. All
instances of a ledger must use the same canonical path. Moving/copying a ledger
changes its key domain and fails closed for cached ciphertext. Legacy raw-key
caches fail closed; replay identities and STARTED/UNKNOWN tombstones survive.
Restoring stale storage cannot restore the latest replay/nonce authority safely.
"""
from __future__ import annotations

import hashlib
import os
import sqlite3
from contextlib import contextmanager
from pathlib import Path
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from .contracts import canonical, parse_json


class ReplayConflict(ValueError):
    pass


class LedgerFull(ValueError):
    pass


MAX_KEY_ENCRYPTIONS = 1 << 24
MAX_NONCE_ATTEMPTS = 8
AAD_VERSION = 1


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
                self.aes = AESGCM(key)
                self.key_id = hashlib.sha256(b"jarvis-response-cache-key\x00" + key).hexdigest()
                columns = {row[1] for row in db.execute("PRAGMA table_info(replay)")}
                for name, declaration in (("cache_key_id", "TEXT"), ("aad_version", "INTEGER NOT NULL DEFAULT 0"),
                                          ("account_id", "TEXT")):
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
                db.commit()
            except BaseException:
                db.rollback()
                raise

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.path, timeout=1, isolation_level=None)
        db.execute("PRAGMA synchronous=FULL")
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

    def begin(self, claims, now, duration):
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            try:
                row = db.execute("SELECT owner_id,reservation_id,run_id,body_hash,state,cache_until,nonce,response,account_id,cache_key_id,aad_version FROM replay WHERE request_id=?", (claims["request_id"],)).fetchone()
                if row:
                    if tuple(row[:4]) != tuple(claims[k] for k in ("owner_id", "reservation_id", "run_id", "body_hash")) or row[8] != claims.get("account_id"):
                        raise ReplayConflict("request identity changed")
                    if row[4] == "COMPLETED" and row[5] > now and row[7] is not None:
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
                total = db.execute("SELECT COUNT(*) FROM replay").fetchone()[0]
                active = db.execute("SELECT COUNT(*) FROM replay WHERE state='STARTED'").fetchone()[0]
                if total >= self.max_rows or active >= self.max_concurrency:
                    raise LedgerFull("ledger capacity exceeded")
                db.execute("INSERT INTO replay(owner_id,request_id,reservation_id,run_id,body_hash,state,deadline,account_id) VALUES (?,?,?,?,?,'STARTED',?,?)",
                           tuple(claims[k] for k in ("owner_id", "request_id", "reservation_id", "run_id", "body_hash")) + (now + duration, claims.get("account_id")))
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
                if not db.execute("SELECT 1 FROM replay WHERE request_id=? AND owner_id=? AND reservation_id=? AND run_id=? AND body_hash=? AND account_id IS ? AND state='STARTED' AND deadline>?",
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

    def complete(self, claims, response, now):
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

    def unknown(self, claims):
        with self.connect() as db:
            db.execute("UPDATE replay SET state='UNKNOWN' WHERE request_id=? AND owner_id=? AND reservation_id=? AND run_id=? AND body_hash=? AND account_id IS ? AND state='STARTED'",
                       self._identity(claims))

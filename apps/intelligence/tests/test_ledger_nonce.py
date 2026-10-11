"""Synthetic response-cache nonce accounting and isolation regressions."""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from jarvis_intelligence.contracts import canonical
from jarvis_intelligence.ledger import Ledger, LedgerFull, ReplayConflict, MAX_KEY_ENCRYPTIONS, MAX_NONCE_ATTEMPTS
from jarvis_intelligence.local_runtime import OwnershipLease


class LedgerNonceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / "replay.sqlite"
        self.key = b"k" * 32
        self.ledger = Ledger(self.path, self.key)
        self.response = {"kind": "ANSWER", "reply_text": "synthetic"}

    def claims(self, suffix="one"):
        return {"owner_id": "synthetic-owner", "request_id": suffix, "reservation_id": "reservation-" + suffix,
                "run_id": "run-" + suffix, "body_hash": "hash-" + suffix}

    def start(self, suffix="one"):
        claims = self.claims(suffix)
        self.assertIsNone(self.ledger.begin(claims, 100, 60))
        return claims

    def usage(self):
        with self.ledger.connect() as db:
            return db.execute("SELECT used FROM cache_key_usage WHERE key_id=?", (self.ledger.key_id,)).fetchone()[0]

    def test_reservation_committed_before_encrypt_without_lock(self):
        claims = self.start()
        real = self.ledger.aes
        ledger = self.ledger
        class Inspect:
            def encrypt(inner, nonce, plaintext, aad):
                with ledger.connect() as db:
                    db.execute("BEGIN IMMEDIATE")  # No pending reservation write lock.
                    self.assertEqual(db.execute("SELECT COUNT(*) FROM cache_nonces WHERE key_id=? AND nonce=?", (ledger.key_id, nonce)).fetchone()[0], 1)
                    db.rollback()
                self.assertEqual(self.usage(), 1)
                return real.encrypt(nonce, plaintext, aad)
        self.ledger.aes = Inspect()
        self.ledger.complete(claims, self.response, 101)
        self.assertEqual(Ledger(self.path, self.key).begin(claims, 102, 60), self.response)

    def test_collision_retries_bounded_and_never_encrypts_twice(self):
        first, second = self.start(), self.start("two")
        with patch("jarvis_intelligence.ledger.os.urandom", return_value=b"a" * 12):
            self.ledger.complete(first, self.response, 101)
            with patch.object(self.ledger, "aes") as aes:
                with patch("jarvis_intelligence.ledger.os.urandom", return_value=b"a" * 12) as rng:
                    with self.assertRaises(LedgerFull):
                        self.ledger.complete(second, self.response, 101)
                    self.assertEqual(rng.call_count, MAX_NONCE_ATTEMPTS)
                aes.encrypt.assert_not_called()
        self.assertEqual(self.usage(), 1)
        with patch("jarvis_intelligence.ledger.os.urandom", side_effect=[b"a" * 12, b"b" * 12]):
            self.ledger.complete(second, self.response, 101)
        self.assertEqual(self.usage(), 2)

    def test_encrypt_failure_burns_nonce_across_restart(self):
        claims = self.start()
        with patch("jarvis_intelligence.ledger.os.urandom", return_value=b"a" * 12), patch.object(self.ledger, "aes") as aes:
            aes.encrypt.side_effect = RuntimeError("synthetic failure")
            with self.assertRaises(RuntimeError):
                self.ledger.complete(claims, self.response, 101)
        self.ledger = Ledger(self.path, self.key)
        self.assertEqual(self.ledger.begin(claims, 102, 60), "UNKNOWN")
        with patch("jarvis_intelligence.ledger.os.urandom", side_effect=[b"a" * 12, b"b" * 12]):
            self.ledger.complete(claims, self.response, 102)
        self.assertEqual(self.usage(), 2)

    def test_response_rollback_does_not_release_nonce_or_ciphertext(self):
        claims = self.start()
        with self.ledger.connect() as db:
            db.execute("CREATE TRIGGER reject_response BEFORE UPDATE OF response ON replay WHEN NEW.response IS NOT NULL BEGIN SELECT RAISE(ABORT,'synthetic rollback'); END")
        with patch("jarvis_intelligence.ledger.os.urandom", return_value=b"a" * 12):
            with self.assertRaises(sqlite3.IntegrityError):
                self.ledger.complete(claims, self.response, 101)
        with self.ledger.connect() as db:
            self.assertEqual(db.execute("SELECT state,response FROM replay").fetchone(), ("STARTED", None))
            db.execute("DROP TRIGGER reject_response")
        self.ledger = Ledger(self.path, self.key)
        with patch("jarvis_intelligence.ledger.os.urandom", side_effect=[b"a" * 12, b"b" * 12]):
            self.ledger.complete(claims, self.response, 102)
        self.assertEqual(self.usage(), 2)

    def test_reservation_transaction_failure_never_encrypts(self):
        claims = self.start()
        with self.ledger.connect() as db:
            db.execute("CREATE TRIGGER reject_usage BEFORE UPDATE ON cache_key_usage BEGIN SELECT RAISE(ABORT,'synthetic rollback'); END")
        with patch.object(self.ledger, "aes") as aes:
            with self.assertRaises(sqlite3.IntegrityError):
                self.ledger.complete(claims, self.response, 101)
            aes.encrypt.assert_not_called()
        with self.ledger.connect() as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM cache_nonces").fetchone()[0], 0)
        self.assertEqual(self.usage(), 0)

    def test_key_limit_shared_between_instances(self):
        first, second = self.start(), self.start("two")
        other = Ledger(self.path, self.key)
        with self.ledger.connect() as db:
            db.execute("UPDATE cache_key_usage SET used=?", (MAX_KEY_ENCRYPTIONS - 1,))
        self.ledger.complete(first, self.response, 101)
        with patch.object(other, "aes") as aes:
            with self.assertRaises(LedgerFull):
                other.complete(second, self.response, 101)
            aes.encrypt.assert_not_called()
        self.assertEqual(self.usage(), MAX_KEY_ENCRYPTIONS)

    def test_concurrent_same_key_collision_reserves_distinct_nonces(self):
        claims = [self.start(), self.start("two")]
        ledgers = [self.ledger, Ledger(self.path, self.key)]
        local = threading.local()
        barrier = threading.Barrier(2)
        encrypted_nonces = []
        lock = threading.Lock()
        def random_nonce(length):
            self.assertEqual(length, 12)
            n = getattr(local, "calls", 0)
            local.calls = n + 1
            return b"a" * 12 if n == 0 else b"b" * 12
        real = self.ledger.aes
        class Encrypt:
            def encrypt(inner, nonce, plaintext, aad):
                with lock:
                    encrypted_nonces.append(nonce)
                barrier.wait(timeout=5)
                return real.encrypt(nonce, plaintext, aad)
        for ledger in ledgers:
            ledger.aes = Encrypt()
        with patch("jarvis_intelligence.ledger.os.urandom", side_effect=random_nonce), ThreadPoolExecutor(2) as pool:
            futures = [pool.submit(ledger.complete, claim, self.response, 101) for ledger, claim in zip(ledgers, claims)]
            for future in futures:
                future.result(timeout=10)
        self.assertEqual(set(encrypted_nonces), {b"a" * 12, b"b" * 12})
        self.assertEqual(self.usage(), 2)

    def test_full_identity_and_account_reject_before_encryption(self):
        claims = self.start()
        for field in ("owner_id", "request_id", "reservation_id", "run_id", "body_hash", "account_id"):
            altered = {**claims, field: "different"}
            with self.subTest(field=field), patch.object(self.ledger, "aes") as aes:
                with self.assertRaises(ReplayConflict):
                    self.ledger.complete(altered, self.response, 101)
                aes.encrypt.assert_not_called()
        self.assertEqual(self.usage(), 0)

    def test_aad_identity_purpose_and_version_authentication(self):
        claims = self.start()
        self.ledger.complete(claims, self.response, 101)
        with self.ledger.connect() as db:
            nonce, encrypted = db.execute("SELECT nonce,response FROM replay").fetchone()
        for field in ("owner_id", "request_id", "reservation_id", "run_id", "body_hash", "account_id"):
            with self.subTest(field=field), self.assertRaises(InvalidTag):
                self.ledger.aes.decrypt(nonce, encrypted, self.ledger._aad({**claims, field: "different"}))
        import json
        aad = json.loads(self.ledger._aad(claims))
        for field, value in (("purpose", "other"), ("format_version", 2)):
            with self.subTest(field=field), self.assertRaises(InvalidTag):
                self.ledger.aes.decrypt(nonce, encrypted, canonical({**aad, field: value}))

    def test_distinct_ledgers_derive_distinct_actual_keys(self):
        other = Ledger(self.path.with_name("other.sqlite"), self.key)
        self.assertNotEqual(self.ledger.key_id, other.key_id)
        nonce, plaintext, aad = b"a" * 12, b"synthetic", b"synthetic-aad"
        encrypted = self.ledger.aes.encrypt(nonce, plaintext, aad)
        with self.assertRaises(InvalidTag):
            other.aes.decrypt(nonce, encrypted, aad)
        self.assertEqual(Ledger(self.path, self.key).key_id, self.ledger.key_id)

    def test_copied_ledger_path_has_distinct_key_and_cannot_replay_cache(self):
        claims = self.start()
        self.ledger.complete(claims, self.response, 101)
        copied_path = self.path.with_name("copied.sqlite")
        with self.ledger.connect() as source, sqlite3.connect(copied_path) as target:
            source.backup(target)
        copied_path.chmod(0o600)
        copied = Ledger(copied_path, self.key)
        self.assertNotEqual(copied.key_id, self.ledger.key_id)
        with self.assertRaises(ReplayConflict):
            copied.begin(claims, 102, 60)

    def test_process_crash_after_encrypt_keeps_nonce_burned(self):
        claims = self.start()
        script = r"""
import os, sys
from pathlib import Path
from unittest.mock import patch
from jarvis_intelligence.ledger import Ledger
ledger = Ledger(Path(sys.argv[1]), b"k" * 32)
real = ledger.aes
class Crash:
    def encrypt(self, nonce, plaintext, aad):
        real.encrypt(nonce, plaintext, aad)
        os._exit(23)
ledger.aes = Crash()
claims = {"owner_id":"synthetic-owner", "request_id":"one", "reservation_id":"reservation-one", "run_id":"run-one", "body_hash":"hash-one"}
with patch("jarvis_intelligence.ledger.os.urandom", return_value=b"a" * 12):
    ledger.complete(claims, {"kind":"ANSWER", "reply_text":"synthetic"}, 101)
"""
        result = subprocess.run([sys.executable, "-c", script, str(self.path)], capture_output=True, timeout=10)
        self.assertEqual(result.returncode, 23, result.stderr.decode())
        self.ledger = Ledger(self.path, self.key)
        self.assertEqual(self.ledger.begin(claims, 102, 60), "UNKNOWN")
        self.assertEqual(self.usage(), 1)
        with patch("jarvis_intelligence.ledger.os.urandom", side_effect=[b"a" * 12, b"b" * 12]):
            self.ledger.complete(claims, self.response, 102)
        self.assertEqual(self.usage(), 2)

    def test_legacy_ciphertext_fails_closed_and_started_survives(self):
        legacy_path = self.path.with_name("legacy.sqlite")
        claims = self.claims()
        lease = OwnershipLease.acquire(legacy_path.with_suffix(".lock"))
        self.addCleanup(lease.close)
        nonce = b"a" * 12
        encrypted = AESGCM(self.key).encrypt(nonce, canonical(self.response), canonical(claims))
        with sqlite3.connect(legacy_path) as db:
            db.execute("CREATE TABLE replay (request_id TEXT PRIMARY KEY,owner_id TEXT NOT NULL,reservation_id TEXT UNIQUE NOT NULL,run_id TEXT NOT NULL,body_hash TEXT NOT NULL,state TEXT NOT NULL,deadline REAL NOT NULL,cache_until REAL,nonce BLOB UNIQUE,response BLOB)")
            db.execute("INSERT INTO replay VALUES (?,?,?,?,?,'COMPLETED',160,3700,?,?)",
                       (claims["request_id"], claims["owner_id"], claims["reservation_id"], claims["run_id"], claims["body_hash"], nonce, encrypted))
            started = self.claims("started")
            db.execute("INSERT INTO replay VALUES (?,?,?,?,?,'STARTED',160,NULL,NULL,NULL)",
                       tuple(started[k] for k in ("request_id", "owner_id", "reservation_id", "run_id", "body_hash")))
        legacy_path.chmod(0o600)
        migrated = Ledger(legacy_path, self.key, ownership_lease=lease)
        with self.assertRaises(ReplayConflict):
            migrated.begin(claims, 102, 60)
        self.assertEqual(migrated.begin(started, 102, 60), "UNKNOWN")
        self.assertEqual(migrated.begin(claims, 4000, 60), "UNKNOWN")

    def test_expiry_and_unknown_never_free_reserved_nonce(self):
        claims = self.start()
        self.ledger.complete(claims, self.response, 101)
        self.assertEqual(self.ledger.begin(claims, 4000, 60), "UNKNOWN")
        other = self.start("two")
        self.ledger.unknown(other)
        with self.assertRaises(ReplayConflict):
            self.ledger.complete(other, self.response, 101)
        self.assertEqual(self.usage(), 1)
        with self.ledger.connect() as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM cache_nonces").fetchone()[0], 1)


if __name__ == "__main__":
    unittest.main()

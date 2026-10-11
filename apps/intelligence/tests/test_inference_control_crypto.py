"""Real Ed25519+ASGI+SQLite CI acceptance; not part of guarded local verification."""
import base64
import json
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from fastapi.testclient import TestClient
from jarvis_intelligence.app import MOCK_MANIFEST_HASH, Settings, create_app
from jarvis_intelligence.contracts import CONTRACT_ROOT, body_hash, canonical
from test_inference_control_contract import encoded, fixture


class RealControlSignatureTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(); self.key=Ed25519PrivateKey.generate()
        self.body=fixture()['request']; self.claims={**fixture()['capability'],'body_hash':body_hash(self.body)}
        self.now=datetime(2026,1,1,tzinfo=timezone.utc).timestamp()
        self.app=create_app(Settings(Path(self.tmp.name)/'ledger.sqlite',b's'*32,
                                    {'synthetic-core':self.key.public_key()},
                                    allow_loopback_mock_without_mtls=True,mode='unavailable'),clock=lambda:self.now)
        self.client=TestClient(self.app,client=('127.0.0.1',43000))

    def tearDown(self): self.client.close(); self.tmp.cleanup()

    def signed(self,claims=None,key=None):
        raw=canonical(claims or self.claims)
        return encoded(raw)+'.'+encoded((key or self.key).sign(raw))

    def send(self,header=None,body=None,path='/internal/v1/inference-control'):
        return self.client.post(path,content=canonical(body or self.body),
                                headers={'content-type':'application/json','x-jarvis-capability':header or self.signed()})

    def no_identity(self):
        with self.app.state.ledger.connect() as db:
            self.assertEqual(db.execute('SELECT COUNT(*) FROM control_identity').fetchone()[0],0)
            self.assertEqual(db.execute('SELECT COUNT(*) FROM replay').fetchone()[0],0)

    def test_real_valid_signature_accepts_exact_canonical_request_and_replay(self):
        first=self.send(); second=self.send()
        self.assertEqual(first.status_code,200); self.assertEqual(first.json(),second.json())
        self.assertEqual(first.json()['terminal']['kind'],'UNSTARTED_FENCED')

    def test_wrong_key_and_corrupt_signature_fail_before_ledger_mutation(self):
        wrong=Ed25519PrivateKey.generate()
        self.assertEqual(self.send(self.signed(key=wrong)).status_code,401)
        payload,signature=self.signed().split('.')
        raw=bytearray(base64.urlsafe_b64decode(signature+'='*(-len(signature)%4))); raw[0]^=1
        self.assertEqual(self.send(payload+'.'+encoded(bytes(raw))).status_code,401)
        self.no_identity()

    def test_modified_signed_claim_or_body_fail_before_ledger_mutation(self):
        token=self.signed(); signature=token.split('.')[1]
        changed={**self.claims,'security_epoch':self.claims['security_epoch']+1}
        changed_body={**self.body,'security_epoch':changed['security_epoch']}
        changed['body_hash']=body_hash(changed_body)
        tampered=encoded(canonical(changed))+'.'+signature
        self.assertEqual(self.send(tampered,changed_body).status_code,401)
        self.assertEqual(self.send(token,changed_body).status_code,401)
        self.no_identity()

    def test_missing_or_unconfigured_key_selector_fails_before_mutation(self):
        claims={**self.claims}; claims.pop('key_id')
        self.assertEqual(self.send(self.signed(claims)).status_code,401)
        self.assertEqual(self.send(self.signed({**self.claims,'key_id':'unknown-core'})).status_code,401)
        self.no_identity()

    def test_real_turn_and_control_tokens_cannot_cross_routes(self):
        body=json.loads((CONTRACT_ROOT/'fixtures/valid/turn-request.json').read_text())
        body['model_manifest_hash']=MOCK_MANIFEST_HASH
        turn_claims={'issuer':'jarvis-core','audience':'jarvis-orchestrator','key_id':'synthetic-core',
                     'owner_id':self.body['owner_id'],'request_id':body['request_id'],'run_id':body['run_id'],
                     'reservation_id':body['budget']['reservation_id'],'body_hash':body_hash(body),
                     'model_id':body['model_id'],'max_model_calls':1,'max_output_tokens':body['budget']['max_output_tokens'],
                     'issued_at':self.claims['issued_at'],'expires_at':self.claims['expires_at'],'nonce':self.claims['nonce']}
        self.assertEqual(self.send(self.signed(turn_claims)).status_code,401)
        self.assertEqual(self.send(self.signed(),body,'/internal/v1/turn').status_code,401)
        self.no_identity()

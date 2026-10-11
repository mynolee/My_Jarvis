"""Pure contracts and mocked signature verification; no key, DB or HTTP setup."""
import base64
import copy
import json
import unittest
from datetime import datetime, timezone
from types import SimpleNamespace
from unittest.mock import Mock

from jarvis_intelligence.contracts import (CONTRACT_ROOT, CONTROL_IDENTITY_FIELDS, MAX_CONTROL_BYTES,
    ContractViolation, TurnRequest, body_hash, canonical, control_identity, parse_json, validate)
from jarvis_intelligence.ledger import Ledger, LedgerFull, MAX_SAFE_INTEGER, ReplayConflict
from jarvis_intelligence.security import Unauthorized, verify_capability, verify_control_capability


def fixture():
    return json.loads((CONTRACT_ROOT / 'fixtures/inference-control/synthetic-control.json').read_text())


def control_turn():
    body = json.loads((CONTRACT_ROOT / 'fixtures/context-v1/request.json').read_text())
    body.update(model_id='local-mlx-v1', prompt_revision='local-v1')
    body['control'] = {'revision': 1, 'origin_binding': 'a' * 64,
                       'dispatch_commitment': 'b' * 64, 'security_epoch': 2}
    return body


def encoded(raw):
    return base64.urlsafe_b64encode(raw).decode().rstrip('=')


def token(claims):
    # The fake verifier tests claim semantics, not Ed25519 correctness.
    return encoded(canonical(claims)) + '.' + encoded(b'synthetic-signature')


class ControlContractTests(unittest.TestCase):
    def test_revision_zero_turn_round_trip_has_identical_canonical_bytes(self):
        body = control_turn(); body.pop('control')
        self.assertNotIn('control', TurnRequest.from_wire(body).model_dump(exclude_unset=True))
        self.assertEqual(canonical(body), canonical(TurnRequest.from_wire(body).model_dump(exclude_unset=True)))

    def test_control_round_trip_and_full_body_binding(self):
        body = control_turn()
        self.assertEqual(body, TurnRequest.from_wire(body).model_dump(exclude_unset=True))
        claims = {'owner_id': fixture()['request']['owner_id']}
        identity = control_identity(body, claims)
        self.assertEqual(set(identity), set(CONTROL_IDENTITY_FIELDS))
        self.assertEqual(identity['context_id'], body['context']['id'])
        self.assertEqual(identity['dispatch_commitment'], body['control']['dispatch_commitment'])
        modified = copy.deepcopy(body); modified['control']['origin_binding'] = 'c' * 64
        self.assertNotEqual(body_hash(body), body_hash(modified))

    def test_descriptor_requires_local_context_no_response_cache(self):
        for change in (lambda b: b.pop('context'), lambda b: b.update(model_id='mock-v1'),
                       lambda b: b.update(prompt_revision='mock-v1'),
                       lambda b: b['control'].update(revision=0),
                       lambda b: b['control'].update(origin_binding='a' * 64 + '\n'),
                       lambda b: b['control'].update(security_epoch=MAX_SAFE_INTEGER + 1)):
            body = control_turn(); change(body)
            with self.subTest(change=change), self.assertRaises(ValueError):
                TurnRequest.from_wire(body)

    def test_closed_request_forbids_generation_source_and_feedback(self):
        for change in (lambda b: b.update(purpose='UNSUPPORTED_STUDY_PURPOSE'),
                       lambda b: b.update(generation_id=fixture()['completed']['terminal']['generation_id']),
                       lambda b: b.update(user_input='synthetic'), lambda b: b.update(body_hash='a' * 64),
                       lambda b: b.update(owner_id='AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA'),
                       lambda b: b.update(security_epoch=-1), lambda b: b.update(origin_binding='A' * 64)):
            body = fixture()['request']; change(body)
            with self.subTest(body=body), self.assertRaises(ContractViolation):
                validate('inference-control-request', body)

    def test_response_shape_no_producer_timestamp_or_unbound_terminal(self):
        for key in ('absent','pending','completed','stopped','fenced'):
            validate('inference-control-response', fixture()[key])
        for change in (lambda b: b.update(completed_at='2026-01-01T00:00:00Z'),
                       lambda b: b.update(ledger_instance_id=None),
                       lambda b: b['terminal'].update(generation_id=None),
                       lambda b: b['terminal'].update(ordinal=0),
                       lambda b: b.update(state='PENDING')):
            body=fixture()['completed']; change(body)
            with self.assertRaises(ContractViolation): validate('inference-control-response',body)

    def test_strict_control_envelope(self):
        for raw in (b'{"revision":1,"revision":1}', b'{"x":1.0}', b'{"x":NaN}',
                    b'{"x":"\\ud800"}', b'{"x":9007199254740992}', b'\xff'):
            with self.assertRaises(ContractViolation): parse_json(raw, limit=MAX_CONTROL_BYTES)
        with self.assertRaises(ContractViolation): parse_json(b' ' * (MAX_CONTROL_BYTES+1), limit=MAX_CONTROL_BYTES)


class ControlSecurityTests(unittest.TestCase):
    def setUp(self):
        self.body=fixture()['request']; self.claims=fixture()['capability']
        self.claims['body_hash']=body_hash(self.body)
        self.verifier=Mock(); self.keys={'synthetic-core':self.verifier}
        self.now=datetime(2026,1,1,tzinfo=timezone.utc).timestamp()

    def verify(self, claims=None, body=None, now=None):
        return verify_control_capability(token(claims or self.claims), body or self.body,
                                         self.keys, self.now if now is None else now)

    def test_exact_issue_valid_expiry_equality_invalid_without_future_tolerance(self):
        self.verify(); self.verifier.verify.assert_called_once()
        for now in (self.now-.000001,self.now+60):
            with self.assertRaises(Unauthorized): self.verify(now=now)

    def test_receipt_fraction_never_rounds_future_issue_into_authority(self):
        claims={**self.claims,'issued_at':'2026-01-01T00:00:00.000001Z'}
        with self.assertRaises(Unauthorized): self.verify(claims,now=self.now+.0000008)

    def test_every_signed_identity_binding_is_exact(self):
        for field in CONTROL_IDENTITY_FIELDS + ('operation',):
            if field=='revision': continue
            claims=copy.deepcopy(self.claims)
            if field=='security_epoch': claims[field]+=1
            elif field=='purpose': claims[field]='CONVERSATION'
            elif field=='operation': claims[field]='OBSERVE'
            elif field.endswith('_binding') or field=='dispatch_commitment': claims[field]='d'*64
            else: claims[field]='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
            with self.subTest(field=field), self.assertRaises(Unauthorized): self.verify(claims)

    def test_strict_utc_microseconds_and_lifetime(self):
        for issued, expires in [('2026-01-01T00:00:00+00:00','2026-01-01T00:01:00Z'),
                               ('2026-01-01t00:00:00z','2026-01-01T00:01:00Z'),
                               ('2026-01-01T00:00:00.0000001Z','2026-01-01T00:01:00Z'),
                               ('2026-01-01T00:00:00Z','2026-01-01T00:00:00Z'),
                               ('2026-01-01T00:00:00Z','2026-01-01T00:01:00.000001Z')]:
            claims={**self.claims,'issued_at':issued,'expires_at':expires}
            with self.assertRaises(Unauthorized): self.verify(claims)
        self.verify({**self.claims,'issued_at':'2026-01-01T00:00:00.000001Z'},now=self.now+.000002)

    def test_audiences_and_call_budget_cannot_cross(self):
        for changes in ({'audience':'jarvis-orchestrator'}, {'max_model_calls':1}, {'issuer':'client'}):
            with self.assertRaises(Unauthorized): self.verify({**self.claims,**changes})
        with self.assertRaises(Unauthorized):
            verify_capability(token(self.claims),control_turn(),self.keys,self.now)

    def test_canonical_header_and_body_are_mandatory(self):
        raw=json.dumps(self.claims).encode()
        with self.assertRaises(Unauthorized): verify_control_capability(encoded(raw)+'.c2ln',self.body,self.keys,self.now)
        for header in ('x'*8193, 'bad', '.', 'a.b.c'):
            with self.assertRaises(Unauthorized): verify_control_capability(header,self.body,self.keys,self.now)
        changed={**self.body,'operation':'OBSERVE'}
        with self.assertRaises(Unauthorized): self.verify(body=changed)

    def test_later_fresh_observation_capability_does_not_change_identity(self):
        body={**self.body,'operation':'OBSERVE'}
        claims={**self.claims,'operation':'OBSERVE','body_hash':body_hash(body),
                'issued_at':'2026-01-02T00:00:00Z','expires_at':'2026-01-02T00:00:30Z'}
        self.verify(claims,body,self.now+86400)


class ControlLedgerPureTests(unittest.TestCase):
    def test_bound_claims_do_not_store_raw_source_digest(self):
        ledger=object.__new__(Ledger); ledger.binding_key=b'synthetic-only-binding-material'
        self.assertNotEqual(ledger._bound_claims({'body_hash':'a'*64},False)['body_hash'],'a'*64)

    def test_all_immutable_control_fields_match(self):
        identity=fixture()['request']; row={k:identity[k] for k in CONTROL_IDENTITY_FIELDS if k!='revision'}
        Ledger._match_control(row,identity)
        for key in row:
            changed={**row,key:None}
            with self.assertRaises(ReplayConflict): Ledger._match_control(changed,identity)

    def test_ordinal_overflow_fails_before_update(self):
        ledger=object.__new__(Ledger); ledger.ledger_instance_id=fixture()['pending']['ledger_instance_id']
        db=Mock(); db.execute.side_effect=[SimpleNamespace(fetchone=lambda:(2,ledger.ledger_instance_id)),
                                          SimpleNamespace(fetchone=lambda:(MAX_SAFE_INTEGER,))]
        with self.assertRaises(LedgerFull): ledger._next_control_ordinal(db)
        self.assertEqual(db.execute.call_count,2)
        self.assertEqual([(call.args,call.kwargs) for call in db.execute.call_args_list], [
            (('SELECT schema_revision,ledger_instance_id FROM control_meta WHERE singleton=1',),{}),
            (('SELECT next_ordinal FROM control_meta WHERE singleton=1',),{}),
        ])  # Exhaustion performs only these reads, never an UPDATE or wrapped ordinal.

    def test_response_has_only_permanent_fact_no_clock(self):
        ledger=object.__new__(Ledger); ledger.ledger_instance_id=fixture()['pending']['ledger_instance_id']
        row={'terminal_kind':'COMPLETED','generation_id':fixture()['completed']['terminal']['generation_id'],'terminal_ordinal':7}
        response=ledger._control_response(fixture()['request'],row)
        self.assertEqual(response['terminal']['ordinal'],7)
        self.assertFalse(any('time' in k or k.endswith('_at') for k in response))

"""Synthetic protocol assertions; not authenticated runtime stop evidence."""
import copy
from datetime import datetime, timedelta, timezone
import hashlib
import json
import unittest
from inference_control_reference import ROOT, IDENTITY, bound_response, capability_binding, parse, same_fact, valid, canonical_bytes, route_ok


class InferenceControlContractsTest(unittest.TestCase):
    def setUp(self):
        self.f = json.loads((ROOT / 'fixtures/inference-control/synthetic-control.json').read_text())

    def claims(self):
        return self.f['capability'] | {'body_hash': hashlib.sha256(canonical_bytes(self.f['request'])).hexdigest()}

    def test_all_synthetic_closed_shapes_validate(self):
        self.assertTrue(self.f['synthetic'])
        for name in ('request', 'observe'):
            self.assertTrue(valid('request', self.f[name]))
        for name in ('pending', 'absent', 'completed', 'stopped', 'fenced'):
            self.assertTrue(bound_response(self.f[name], self.f['request']))
        self.assertTrue(valid('capability', self.claims()))

    def test_source_credentials_and_new_generation_authority_are_not_wire_fields(self):
        for name, shape in [('request', self.f['request']), ('response', self.f['completed']), ('capability', self.claims())]:
            for field in ('text', 'prompt', 'result', 'session_hash', 'session_token', 'private_key', 'model_id', 'max_output_tokens', 'provider_url'):
                self.assertFalse(valid(name, shape | {field: 'untrusted'}), (name, field))
        self.assertFalse(valid('capability', self.claims() | {'max_model_calls': 1}))
        self.assertFalse(valid('capability', self.claims() | {'audience': 'jarvis-orchestrator'}))

    def test_every_identity_and_commitment_is_bound(self):
        for field in IDENTITY:
            value = copy.deepcopy(self.f['completed'])
            replacement = {'revision': 2, 'security_epoch': 3, 'purpose': 'CONVERSATION', 'origin_binding': 'c' * 64, 'dispatch_commitment': 'd' * 64}.get(field, 'ffffffff-ffff-4fff-8fff-ffffffffffff')
            value[field] = replacement
            self.assertFalse(bound_response(value, self.f['request']), field)

    def test_terminal_and_generation_nullability_is_exact(self):
        for name in ('pending', 'absent'):
            value = self.f[name] | {'terminal': self.f['completed']['terminal']}
            self.assertFalse(valid('response', value))
        for name in ('completed', 'stopped'):
            value = copy.deepcopy(self.f[name]); value['terminal']['generation_id'] = None
            self.assertFalse(valid('response', value))
        value = copy.deepcopy(self.f['fenced']); value['terminal']['generation_id'] = self.f['completed']['terminal']['generation_id']
        self.assertFalse(valid('response', value))
        self.assertFalse(valid('response', self.f['completed'] | {'ledger_instance_id': None}))
        self.assertFalse(valid('response', self.f['pending'] | {'ledger_instance_id': None}))
        self.assertFalse(valid('response', self.f['completed'] | {'terminal': None}))

    def test_producer_fact_has_no_wall_clock_or_mutable_observation(self):
        for field in ('terminal_at', 'observed_at', 'recorded_at', 'heartbeat_at', 'pid'):
            value = copy.deepcopy(self.f['completed']); value['terminal'][field] = 1
            self.assertFalse(valid('response', value), field)
        self.assertTrue(same_fact(self.f['completed'], copy.deepcopy(self.f['completed'])))
        for field, replacement in [('ordinal', 6), ('kind', 'WORKER_STOPPED'), ('generation_id', 'ffffffff-ffff-4fff-8fff-ffffffffffff')]:
            value = copy.deepcopy(self.f['completed']); value['terminal'][field] = replacement
            self.assertFalse(same_fact(self.f['completed'], value), field)

    def test_capability_strict_expiry_future_and_maximum_lifetime(self):
        t = datetime(2026, 1, 1, tzinfo=timezone.utc); claims = self.claims()
        self.assertTrue(capability_binding(claims, self.f['request'], t))
        self.assertTrue(capability_binding(claims, self.f['request'], t + timedelta(seconds=59)))
        self.assertFalse(capability_binding(claims, self.f['request'], t - timedelta(microseconds=1)))
        self.assertFalse(capability_binding(claims, self.f['request'], t + timedelta(seconds=60)))
        self.assertFalse(capability_binding(claims | {'expires_at': '2026-01-01T00:01:01Z'}, self.f['request'], t))
        self.assertFalse(capability_binding(claims | {'expires_at': claims['issued_at']}, self.f['request'], t))
        self.assertFalse(capability_binding(claims, self.f['observe'], t))
        self.assertFalse(capability_binding(claims | {'body_hash': '0' * 64}, self.f['request'], t))

    def test_unsafe_numbers_uuid_case_and_unavailable_purposes_fail(self):
        for bad in (-1, 9007199254740992, 1.0, True):
            self.assertFalse(valid('request', self.f['request'] | {'security_epoch': bad}))
        for bad in (0, -1, 9007199254740992, 1.0, True):
            value = copy.deepcopy(self.f['completed']); value['terminal']['ordinal'] = bad
            self.assertFalse(valid('response', value))
        for bad in ('STUDY_REVIEW', 'MOCK', 'OTHER'):
            self.assertFalse(valid('request', self.f['request'] | {'purpose': bad}))
        self.assertFalse(valid('request', self.f['request'] | {'request_id': 'AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA'}))
        self.assertFalse(valid('request', self.f['request'] | {'origin_binding': 'A' * 64}))

    def test_three_purposes_retain_revision_one_and_cannot_retarget_response_or_capability(self):
        now=datetime(2026,1,1,tzinfo=timezone.utc)
        for purpose in ('CONVERSATION','STUDY_LESSON','STUDY_FEEDBACK'):
            request=self.f['request'] | {'purpose':purpose}
            response=self.f['completed'] | {'purpose':purpose}
            claims=self.claims() | {'purpose':purpose,'body_hash':hashlib.sha256(canonical_bytes(request)).hexdigest()}
            self.assertEqual(request['revision'],1)
            self.assertTrue(valid('request',request));self.assertTrue(bound_response(response,request))
            self.assertTrue(capability_binding(claims,request,now))
            for other in ('CONVERSATION','STUDY_LESSON','STUDY_FEEDBACK'):
                if other==purpose:continue
                self.assertFalse(bound_response(response | {'purpose':other},request))
                self.assertFalse(capability_binding(claims | {'purpose':other},request,now))

    def test_wire_duplicates_exponents_limits_and_no_dispatch_hash_fabrication(self):
        complete = json.dumps(self.f['request'])
        with self.assertRaises(ValueError): parse('request', complete.replace('"revision": 1', '"revision": 1, "revision": 1'))
        with self.assertRaises(ValueError): parse('request', complete.replace('"request_id":', '"revision": 1, "request_id":'))
        terminal = json.dumps(self.f['completed'])
        with self.assertRaises(ValueError): parse('response', terminal.replace('"ordinal": 5', '"ordinal": 5, "ordinal": 5'))
        with self.assertRaises(ValueError): parse('request', json.dumps(self.f['request']).replace('"security_epoch": 2', '"security_epoch": 2e0'))
        with self.assertRaises(ValueError): parse('request', ' ' * 16385)
        for value in (None, '', '0' * 63):
            self.assertFalse(valid('request', self.f['request'] | {'dispatch_commitment': value}))
        self.assertFalse(valid('response', self.f['completed'] | {'terminal': {'kind': 'UNSTARTED_CORE', 'generation_id': None, 'ordinal': 1}}))

    def test_microsecond_control_signer_precision_and_fractional_boundaries(self):
        claims = self.claims() | {'issued_at': '2026-01-01T00:00:00.123456Z', 'expires_at': '2026-01-01T00:01:00.123456Z'}
        issued = datetime.fromisoformat(claims['issued_at'])
        expiry = datetime.fromisoformat(claims['expires_at'])
        self.assertTrue(capability_binding(claims, self.f['request'], issued))
        self.assertFalse(capability_binding(claims, self.f['request'], issued - timedelta(microseconds=1)))
        self.assertTrue(capability_binding(claims, self.f['request'], expiry - timedelta(microseconds=1)))
        self.assertFalse(capability_binding(claims, self.f['request'], expiry))
        for stamp in ('2026-01-01T00:00:00.1Z', '2026-01-01T00:00:00.123Z', '2026-01-01T00:00:00.123456Z'):
            self.assertTrue(valid('capability', claims | {'issued_at': stamp}), stamp)
        # The new control signer truncates Java Instant to microseconds before signing.
        # It must not pass raw Instant.toString() nanoseconds to this contract.
        for stamp in ('2026-01-01T00:00:00.123456789Z', '2026-01-01T00:00:00.1234567Z', '2026-01-01T00:00:00\\123Z'):
            self.assertFalse(valid('capability', claims | {'issued_at': stamp}), stamp)

    def test_fixed_hex_commitments_reject_lf_crlf_and_other_lengths(self):
        for name, shape, fields in [('request', self.f['request'], ('origin_binding', 'dispatch_commitment')),
                                    ('response', self.f['completed'], ('origin_binding', 'dispatch_commitment')),
                                    ('capability', self.claims(), ('origin_binding', 'dispatch_commitment', 'body_hash'))]:
            for field in fields:
                for value in ('a' * 64 + '\n', 'a' * 64 + '\r\n', 'a' * 63, 'a' * 65):
                    self.assertFalse(valid(name, shape | {field: value}), (name, field, repr(value)))

    def test_both_routes_bind_their_only_operation(self):
        self.assertTrue(route_ok('/internal/v1/inference-control', self.f['request']))
        self.assertTrue(route_ok('/internal/v1/inference-control/observe', self.f['observe']))
        self.assertFalse(route_ok('/internal/v1/inference-control', self.f['observe']))
        self.assertFalse(route_ok('/internal/v1/inference-control/observe', self.f['request']))

    def test_inference_and_control_capability_domains_are_disjoint(self):
        from jsonschema import Draft202012Validator, FormatChecker
        schema = json.loads((ROOT / 'schemas/service-capability.schema.json').read_text())
        ordinary = {k: self.claims()[k] for k in ('issuer', 'key_id', 'request_id', 'run_id', 'body_hash', 'reservation_id', 'issued_at', 'expires_at', 'nonce', 'owner_id')}
        ordinary.update(audience='jarvis-orchestrator', model_id='local-mlx-v1', max_model_calls=1, max_output_tokens=512)
        v = Draft202012Validator(schema, format_checker=FormatChecker())
        self.assertTrue(v.is_valid(ordinary))
        self.assertFalse(valid('capability', ordinary))
        self.assertFalse(v.is_valid(self.claims()))

    def test_capability_binding_rejects_each_independent_identity_change(self):
        now = datetime(2026, 1, 1, tzinfo=timezone.utc)
        for field in (*IDENTITY, 'operation'):
            replacement = {'revision': 2, 'security_epoch': 3, 'purpose': 'CONVERSATION', 'origin_binding': 'c'*64,
                           'dispatch_commitment': 'd'*64, 'operation': 'OBSERVE'}.get(field, 'ffffffff-ffff-4fff-8fff-ffffffffffff')
            self.assertFalse(capability_binding(self.claims() | {field: replacement}, self.f['request'], now), field)

    def test_required_fields_and_uuid_canonicalization_cover_all_envelopes(self):
        for name, shape in [('request', self.f['request']), ('response', self.f['completed']), ('capability', self.claims())]:
            for field in shape:
                self.assertFalse(valid(name, {k:v for k,v in shape.items() if k != field}), (name, field))
                self.assertFalse(valid(name, shape | {field: None}), (name, field))
        uppercase = 'AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA'
        self.assertFalse(valid('capability', self.claims() | {'nonce': uppercase}))
        self.assertFalse(valid('response', self.f['completed'] | {'ledger_instance_id': uppercase}))
        value = copy.deepcopy(self.f['completed']); value['terminal']['generation_id'] = uppercase
        self.assertFalse(valid('response', value))

    def test_permanent_fact_reobserved_with_fresh_capability_after_expiry(self):
        old = self.claims(); later = datetime(2026, 1, 2, tzinfo=timezone.utc)
        fresh = old | {'issued_at': '2026-01-02T00:00:00Z', 'expires_at': '2026-01-02T00:01:00Z'}
        self.assertFalse(capability_binding(old, self.f['request'], later))
        self.assertTrue(capability_binding(fresh, self.f['request'], later))
        self.assertTrue(same_fact(self.f['completed'], self.f['completed']))
        self.assertFalse(same_fact(self.f['completed'], self.f['completed'] | {'ledger_instance_id': 'ffffffff-ffff-4fff-8fff-ffffffffffff'}))

    def test_control_descriptor_requires_local_context_and_does_not_widen_legacy(self):
        from jsonschema import Draft202012Validator, FormatChecker
        schema = json.loads((ROOT / 'schemas/turn-request.schema.json').read_text())
        v = Draft202012Validator(schema, format_checker=FormatChecker())
        base = json.loads((ROOT / 'fixtures/context-v1/request.json').read_text())
        self.assertTrue(v.is_valid(base))
        descriptor = {k:self.f['request'][k] for k in ('revision', 'origin_binding', 'dispatch_commitment', 'security_epoch')}
        controlled = base | {'control': descriptor}
        self.assertTrue(v.is_valid(controlled))
        self.assertFalse(v.is_valid({k:value for k,value in controlled.items() if k != 'context'}))
        self.assertFalse(v.is_valid(controlled | {'context': None}))
        self.assertFalse(v.is_valid(controlled | {'model_id': 'mock-v1', 'prompt_revision': 'mock-v1'}))
        self.assertFalse(v.is_valid(controlled | {'prompt_revision': 'other'}))
        self.assertFalse(v.is_valid(controlled | {'control': descriptor | {'session_hash': 'not-allowed'}}))
        self.assertFalse(v.is_valid(controlled | {'control': descriptor | {'dispatch_commitment': 'a'*64+'\n'}}))
        # The descriptor is optional only for old requests; Core's stored revision decides it.
        self.assertTrue(v.is_valid(base))

    def test_readiness_requires_paired_control_revision_without_quality_claim(self):
        from jsonschema import Draft202012Validator, FormatChecker
        schema = json.loads((ROOT / 'schemas/runtime-readiness.schema.json').read_text())
        v = Draft202012Validator(schema, format_checker=FormatChecker())
        ready = {'status':'READY','ready':True,'model_id':'local-mlx-v1','model_manifest_hash':'a'*64,
                 'download_complete':True,'integrity_verified':True,'runtime_ready':True,
                 'inference_verified':False,'integration_verified':False,'control_revision':1}
        self.assertTrue(v.is_valid(ready))
        self.assertFalse(v.is_valid({k:value for k,value in ready.items() if k != 'control_revision'}))
        self.assertFalse(v.is_valid(ready | {'control_revision':0}))

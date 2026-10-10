"""Synthetic-only context transport, admission and durable tombstone gates."""
import asyncio
import copy
import json
import tempfile
import time
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from jarvis_intelligence.contracts import CONTRACT_ROOT, ContractViolation, TurnRequest, body_hash
from jarvis_intelligence.ledger import Ledger, ReplayConflict
from jarvis_intelligence.local_runtime import LocalRuntime, RuntimeFailure
from jarvis_intelligence.mlx_adapter import MLXAdapter


def request():
    return json.loads((CONTRACT_ROOT / 'fixtures/context-v1/request.json').read_text())


def adapter_request(body):
    return {**body, 'max_input_tokens': 3584, 'max_output_tokens': 512}


def fake_adapter(counter):
    adapter = object.__new__(MLXAdapter)
    adapter.system = 'synthetic'
    adapter.processor = object()
    adapter.config = {}
    adapter.model = SimpleNamespace(config=SimpleNamespace(model_type='qwen3_5'))
    seen = {'candidates': [], 'tensors': [], 'streams': []}
    def render(processor, config, messages, **kwargs):
        seen['candidates'].append(copy.deepcopy(messages))
        return json.dumps(messages)
    def prepare(processor, **kwargs):
        ids = SimpleNamespace(shape=(1, counter(seen)))
        mask = object()
        seen['tensors'].append((ids, mask))
        return {'input_ids': ids, 'attention_mask': mask}
    def stream(model, processor, prompt, **kwargs):
        seen['streams'].append(kwargs)
        yield SimpleNamespace(text='{"kind":"ANSWER","reply_text":"blue","cited_source_ids":[]}',
                              generation_tokens=20, finish_reason='stop')
    adapter.render, adapter.prepare, adapter.stream = render, prepare, stream
    adapter.special = lambda *args: True
    adapter.mx = SimpleNamespace(reset_peak_memory=lambda: None, get_peak_memory=lambda: 0)
    return adapter, seen


class ContextContractTests(unittest.TestCase):
    def test_full_round_trip_and_signed_body_binding(self):
        body = request()
        self.assertEqual(TurnRequest.from_wire(body).model_dump(exclude_unset=True), body)
        for mutate in (lambda b: b['memories'][0].update(value='green'),
                       lambda b: b['memories'][0].update(version=2),
                       lambda b: b['context'].update(context_epoch=2),
                       lambda b: b['history'][0].update(text='green')):
            changed = copy.deepcopy(body); mutate(changed)
            self.assertNotEqual(body_hash(body), body_hash(changed))
    def test_context_rejections(self):
        mutations = [lambda b: b['context'].update(approved=True),
                     lambda b: b['history'].pop(),
                     lambda b: b['history'][0].update(role='SYSTEM'),
                     lambda b: b['history'][0].update(trust='MODEL_PROPOSAL'),
                     lambda b: b['history'][1].update(run_id=b['run_id']),
                     lambda b: b['history'][0].update(source_id=b['memories'][0]['source_id']),
                     lambda b: b.update(timezone='Invalid/Zone'),
                     lambda b: b['allowed_tool_ids'].append('calendar.event.create'),
                     lambda b: b['memories'][0].update(scope='PROJECT', project_id=b['run_id']),
                     lambda b: b['memories'][0].update(value='x\x00'),
                     lambda b: b.update(memories=b['memories'] * 21),
                     lambda b: b['budget'].update(max_output_tokens=513)]
        for mutation in mutations:
            body = request(); mutation(body)
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                TurnRequest.from_wire(body)
    def test_canonical_history_byte_cap(self):
        body = request()
        body['history'][0]['text'] = '😀' * 16000
        body['history'][1]['text'] = '😀' * 16000
        with self.assertRaises(ContractViolation): TurnRequest.from_wire(body)


class ContextAdmissionTests(unittest.TestCase):
    def test_data_roles_and_final_tensor_identity(self):
        body = request(); adapter, seen = fake_adapter(lambda _: 3584)
        result = adapter.generate(adapter_request(body), SimpleNamespace(is_set=lambda: False))
        messages = seen['candidates'][0]
        self.assertEqual([x['role'] for x in messages], ['system', 'user', 'user', 'assistant', 'user'])
        data = json.loads(messages[1]['content'])
        self.assertEqual(data['memories'], body['memories'])
        self.assertEqual(data['history'][0]['source_id'], body['history'][0]['source_id'])
        self.assertEqual(messages[-1]['content'], body['user_input'])
        self.assertNotIn(body['memories'][0]['source_id'], messages[0]['content'])
        self.assertIs(seen['streams'][0]['input_ids'], seen['tensors'][0][0])
        self.assertIs(seen['streams'][0]['mask'], seen['tensors'][0][1])
        self.assertEqual(result['input_tokens'], 3584)
    def test_memory_then_complete_pair_pruning(self):
        adapter, seen = fake_adapter(lambda s: 3585 if len(s['candidates']) < 3 else 3584)
        adapter.generate(adapter_request(request()), SimpleNamespace(is_set=lambda: False))
        self.assertEqual([len(json.loads(m[1]['content'])['memories']) for m in seen['candidates']], [1, 0, 0])
        self.assertEqual([len(m) for m in seen['candidates']], [5, 5, 3])
        self.assertEqual(len(seen['streams']), 1)
        self.assertIs(seen['streams'][0]['input_ids'], seen['tensors'][-1][0])
    def test_unprunable_rejected_without_model_call_or_input_truncation(self):
        adapter, seen = fake_adapter(lambda _: 3585)
        body = request()
        with self.assertRaises(RuntimeFailure) as caught:
            adapter.generate(adapter_request(body), SimpleNamespace(is_set=lambda: False))
        self.assertEqual(caught.exception.code, 'CONTEXT_LIMIT')
        self.assertEqual(len(seen['candidates']), 3)
        self.assertFalse(seen['streams'])
        self.assertTrue(all(m[-1]['content'] == body['user_input'] for m in seen['candidates']))
    def test_maximum_28_removals(self):
        body = request(); body['memories'] *= 20; body['history'] *= 8
        adapter, seen = fake_adapter(lambda _: 4096)
        with self.assertRaises(RuntimeFailure):
            adapter.generate(adapter_request(body), SimpleNamespace(is_set=lambda: False))
        self.assertEqual(len(seen['candidates']), 29)
        self.assertFalse(seen['streams'])
    def test_cancel_during_tokenization_and_expired_deadline(self):
        adapter, seen = fake_adapter(lambda _: 3584)
        with self.assertRaises(RuntimeFailure) as caught:
            adapter.generate(adapter_request(request()), SimpleNamespace(is_set=lambda: bool(seen['tensors'])))
        self.assertEqual(caught.exception.code, 'CANCELLED'); self.assertFalse(seen['streams'])
        with self.assertRaises(RuntimeFailure) as caught:
            adapter.generate({**adapter_request(request()), 'deadline_monotonic': 0}, SimpleNamespace(is_set=lambda: False))
        self.assertEqual(caught.exception.code, 'GENERATION_TIMEOUT')


class ContextPipeTests(unittest.IsolatedAsyncioTestCase):
    async def test_all_source_fields_survive_pipe(self):
        runtime = LocalRuntime(SimpleNamespace(manifest_sha256='0' * 64))
        runtime.health = lambda: {'runtime_ready': True}
        runtime._cancel = SimpleNamespace(clear=lambda: None)
        captured = []
        runtime._pipe = SimpleNamespace(send=captured.append)
        body = request()
        async def receive(deadline): return {'request_id': body['request_id']}
        runtime._receive = receive
        await runtime.generate(TurnRequest.from_wire(body), time.time() + 10)
        for key in ('context', 'project_context', 'memories', 'history', 'evidence', 'tool_results'):
            self.assertEqual(captured[0][key], body[key])
        self.assertGreater(captured[0]['deadline_monotonic'], time.monotonic())


class ContextLedgerTests(unittest.TestCase):
    def test_metadata_only_tombstones_restart_no_rerun_or_cache_policy_downgrade(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'ledger.sqlite'; ledger = Ledger(path, b'k' * 32)
            claims = dict(owner_id='owner', request_id='request', reservation_id='reservation', run_id='run', body_hash='body')
            self.assertIsNone(ledger.begin(claims, 100, 60, cache_response=False))
            with self.assertRaises(ReplayConflict): ledger.complete(claims, {'value': 'blue'}, 101)
            ledger.complete(claims, {'value': 'blue'}, 101, cache_response=False)
            with ledger.connect() as db:
                self.assertEqual(db.execute('SELECT state,response,nonce,cache_until,cache_response FROM replay').fetchone(),
                                 ('COMPLETED', None, None, None, 0))
                self.assertEqual(db.execute('SELECT SUM(used) FROM cache_key_usage').fetchone()[0], 0)
            restarted = Ledger(path, b'k' * 32)
            self.assertEqual(restarted.begin(claims, 102, 60, cache_response=False), 'UNKNOWN')
            with self.assertRaises(ReplayConflict): restarted.begin(claims, 102, 60)
            with self.assertRaises(ReplayConflict): restarted.begin({**claims, 'body_hash': 'changed'}, 102, 60, cache_response=False)


class ContextBoundaryTests(unittest.TestCase):
    from test_local_boundary import LocalBoundaryTests as _fixture
    claims = _fixture.claims
    headers = _fixture.headers
    send = _fixture.send
    tearDown = _fixture.tearDown
    def setUp(self):
        self._fixture.setUp(self)
        context = request()
        self.body['allowed_tool_ids'] = []
        self.body.update({key: context[key] for key in ('context', 'project_context', 'memories', 'history')})
    def test_completed_context_is_never_response_cached(self):
        with patch('jarvis_intelligence.app._peer_allowed', return_value=True):
            first = self.send(); replay = self.send()
        self.assertEqual(first.status_code, 200)
        self.assertEqual(replay.status_code, 409)
        self.assertEqual(replay.json()['error_code'], 'INFERENCE_UNKNOWN')
        self.assertEqual(self.runtime.calls, 1)
        with self.app.state.ledger.connect() as db:
            stored = db.execute('SELECT response,body_hash,cache_response FROM replay').fetchone()
            self.assertIsNone(stored[0]); self.assertNotEqual(stored[1], body_hash(self.body)); self.assertEqual(stored[2], 0)
    def test_changed_source_rejected_by_original_capability(self):
        headers = self.headers()
        self.body['memories'][0]['value'] = 'green'
        with patch('jarvis_intelligence.app._peer_allowed', return_value=True):
            result = self.send(headers=headers)
        self.assertEqual(result.status_code, 401)
        self.assertEqual(self.runtime.calls, 0)
    def test_context_citations_rejected_without_repair(self):
        self.runtime.output['cited_source_ids'] = [self.body['memories'][0]['source_id']]
        with patch('jarvis_intelligence.app._peer_allowed', return_value=True):
            first = self.send(); replay = self.send()
        self.assertEqual(first.status_code, 409)
        self.assertEqual(replay.json()['error_code'], 'INFERENCE_UNKNOWN')
        self.assertEqual(self.runtime.calls, 1)

"""Synthetic typed-study gates. No fixture contains real model instructions.

StudyBoundaryTests exercises ASGI/SQLite and is intended for authorized CI only.
All other classes use pure schemas, fake tensors/pipes, or temporary placeholder
files. None loads native MLX, starts a worker, installs anything, or uses a model.
"""
import base64
import json
import os
import tempfile
import time
import unittest
from dataclasses import replace
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from jsonschema import Draft202012Validator

from jarvis_intelligence.app import _error, _validate_output
from jarvis_intelligence.contracts import ContractViolation, TurnRequest, body_hash, canonical
from jarvis_intelligence.local_runtime import (
    LocalConfig, LocalRuntime, RuntimeFailure, digest, load_study_lesson_prompt,
    verify_artifacts, _worker_request_loop,
)
from jarvis_intelligence.mlx_adapter import output_schema
from jarvis_intelligence.security import Unauthorized, verify_capability
from test_context_transport import adapter_request, fake_adapter, request
from test_local_runtime import fixture


MEASURED = {'input_tokens': 100, 'output_tokens': 20, 'compute_ms': 7, 'peak_memory_bytes': 128}
PLACEHOLDER = b'SYNTHETIC TEST PLACEHOLDER ONLY. NOT A MODEL INSTRUCTION.'


def lesson():
    return {'kind': 'LESSON', 'concepts': ['concept one', 'concept two', 'concept three'],
            'questions': ['question one', 'question two']}


def study_request():
    return {**request(), 'purpose': 'STUDY_LESSON'}


def study_fixture(root):
    config = fixture(root)
    path = root / 'synthetic-study-placeholder.txt'
    path.write_bytes(PLACEHOLDER)
    path.chmod(0o600)
    manifest = json.loads(config.manifest_path.read_text())
    manifest['study_lesson_prompt_sha256'] = digest(path)
    config.manifest_path.write_text(json.dumps(manifest))
    return replace(config, manifest_sha256=digest(config.manifest_path),
                   study_lesson_prompt_path=path, study_lesson_prompt_sha256=digest(path))


def immediate(coroutine):
    """Drive fake-pipe coroutines without an event loop or its socketpair."""
    try:
        coroutine.send(None)
    except StopIteration as result:
        return result.value
    finally:
        coroutine.close()
    raise AssertionError('synthetic coroutine unexpectedly suspended')


class StudyContractTests(unittest.TestCase):
    def test_legacy_omission_preserves_exact_body_and_explicit_purpose_is_bound(self):
        body = request()
        original = canonical(body)
        parsed = TurnRequest.from_wire(body)
        self.assertEqual(parsed.purpose, 'CONVERSATION')
        self.assertEqual(canonical(parsed.model_dump(exclude_unset=True)), original)
        explicit = {**body, 'purpose': 'CONVERSATION'}
        self.assertEqual(TurnRequest.from_wire(explicit).model_dump(exclude_unset=True), explicit)
        self.assertNotEqual(body_hash(explicit), body_hash(body))
        self.assertEqual(TurnRequest.from_wire(study_request()).purpose, 'STUDY_LESSON')
        self.assertNotEqual(body_hash(study_request()), body_hash(body))

    def test_purpose_null_unknown_and_invalid_study_context_fail_closed(self):
        mutations = [lambda b: b.update(purpose=None), lambda b: b.update(purpose='STUDY'),
                     lambda b: b.update(purpose=1), lambda b: b.pop('context'),
                     lambda b: b.update(context=None), lambda b: b.update(model_id='mock-v1', prompt_revision='mock-v1'),
                     lambda b: b.update(prompt_revision='mock-v1'),
                     lambda b: b['allowed_tool_ids'].append('calendar.event.create')]
        for mutation in mutations:
            body = study_request(); mutation(body)
            with self.subTest(body=body), self.assertRaises(ValueError):
                TurnRequest.from_wire(body)

    def test_two_way_purpose_substitution_fails_original_signed_capability(self):
        key = Ed25519PrivateKey.generate()
        encode = lambda raw: base64.urlsafe_b64encode(raw).decode().rstrip('=')
        for original, changed in ((request(), study_request()), (study_request(), request()),
                                  (study_request(), {**request(), 'purpose': 'CONVERSATION'})):
            claims = {'issuer': 'jarvis-core', 'audience': 'jarvis-orchestrator', 'key_id': 'test-core',
                      'owner_id': '10000000-0000-4000-8000-000000000009',
                      'request_id': original['request_id'], 'run_id': original['run_id'],
                      'body_hash': body_hash(original), 'reservation_id': original['budget']['reservation_id'],
                      'model_id': original['model_id'], 'max_model_calls': 1,
                      'max_output_tokens': original['budget']['max_output_tokens'],
                      'issued_at': '2030-01-01T00:00:00Z', 'expires_at': '2030-01-01T00:00:50Z',
                      'nonce': '10000000-0000-4000-8000-000000000008'}
            raw = canonical(claims); header = encode(raw) + '.' + encode(key.sign(raw))
            self.assertEqual(verify_capability(header, original, {'test-core': key.public_key()}, 1893456001), claims)
            with self.assertRaises(Unauthorized):
                verify_capability(header, changed, {'test-core': key.public_key()}, 1893456001)


class StudyOutputTests(unittest.TestCase):
    def setUp(self):
        self.turn = TurnRequest.from_wire(study_request())

    def test_lesson_exact_shape_trusted_usage_and_no_normalization(self):
        output = lesson(); output['concepts'][0] = '  e\u0301 😀\t'
        complete = _validate_output(output, self.turn, measured=MEASURED)
        self.assertEqual(complete['concepts'], output['concepts'])
        self.assertEqual(complete['questions'], output['questions'])
        for key, value in MEASURED.items():
            self.assertEqual(complete['usage'][key], value)
        self.assertEqual(complete['usage']['inference_request_id'], self.turn.request_id)
        self.assertEqual(complete['usage']['reservation_id'], self.turn.budget.reservation_id)
        self.assertNotIn('usage', output)

    def test_study_accepts_need_input_and_trusted_service_error_only(self):
        output = {'kind': 'NEED_INPUT', 'question': 'Which topic?', 'missing_fields': ['topic']}
        self.assertEqual(_validate_output(output, self.turn, measured=MEASURED)['kind'], 'NEED_INPUT')
        self.assertEqual(_error(self.turn, 'MODEL_UNAVAILABLE')['kind'], 'ERROR')
        with self.assertRaises(ContractViolation):
            _validate_output({'kind': 'ERROR', 'error_code': 'MODEL_UNAVAILABLE', 'retryable': False},
                             self.turn, measured=MEASURED)

    def test_purpose_output_mismatch_and_prose_are_not_converted(self):
        conversation = TurnRequest.from_wire(request())
        with self.assertRaises(ContractViolation):
            _validate_output(lesson(), conversation, measured=MEASURED)
        for output in ({'kind': 'ANSWER', 'reply_text': json.dumps(lesson()), 'cited_source_ids': []},
                       {'kind': 'TOOL_PROPOSALS', 'summary': 'synthetic', 'proposals': []},
                       json.dumps(lesson()), 'concept one; concept two; concept three'):
            with self.subTest(output=output), self.assertRaises(ContractViolation):
                _validate_output(output, self.turn, measured=MEASURED)

    def test_lesson_cardinality_scalar_and_authority_rejections(self):
        changes = [lambda o: o.update(usage={}), lambda o: o.update(id='model-id'),
                   lambda o: o.update(owner_id='owner'), lambda o: o.update(approved=True),
                   lambda o: o.update(cited_source_ids=[]), lambda o: o['concepts'].pop(),
                   lambda o: o['concepts'].append('four'), lambda o: o['questions'].pop(),
                   lambda o: o['questions'].append('three'),
                   lambda o: o['concepts'].__setitem__(0, {'text': 'value', 'id': 'model-id'}),
                   lambda o: o['questions'].__setitem__(0, {'text': 'value', 'usage': {}})]
        for text in ('', '\x00', 'x\x00', '\ud800', '\udfff', 'x' * 1001, '😀' * 1001):
            changes.append(lambda o, text=text: o['concepts'].__setitem__(0, text))
        changes.append(lambda o: o['questions'].__setitem__(0, '😀' * 2001))
        whitespace = list(range(9, 14)) + [0x20, 0x85, 0xA0, 0x1680] + list(range(0x2000, 0x200B)) + [0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF]
        for scalar in whitespace:
            changes.append(lambda o, scalar=scalar: o['concepts'].__setitem__(0, chr(scalar)))
        for change in changes:
            output = lesson(); change(output)
            with self.subTest(output=repr(output)), self.assertRaises(ValueError):
                _validate_output(output, self.turn, measured=MEASURED)

    def test_exact_unicode_scalar_bounds_and_kernel_non_whitespace(self):
        output = lesson()
        output['concepts'][0] = '😀' * 1000
        output['concepts'][1] = '\u001c'  # Python str.isspace differs from the kernel set.
        output['questions'][0] = '😀' * 2000
        self.assertEqual(_validate_output(output, self.turn, measured=MEASURED)['concepts'], output['concepts'])

    def test_provider_cannot_forge_measured_usage(self):
        for key in MEASURED:
            for value in (-1, True, '1', 1.5):
                with self.subTest(key=key, value=value), self.assertRaises(ContractViolation):
                    _validate_output(lesson(), self.turn, measured={**MEASURED, key: value})
        for measured in ({**MEASURED, 'input_tokens': 3585}, {**MEASURED, 'output_tokens': 513}):
            with self.assertRaises(ContractViolation):
                _validate_output(lesson(), self.turn, measured=measured)


class StudyPromptTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.config = study_fixture(self.root)

    def tearDown(self):
        self.tmp.cleanup()

    def config_file(self, values):
        path = self.root / 'private-synthetic-config.json'
        path.write_text(json.dumps(values)); path.chmod(0o600)
        return path

    def test_optional_config_legacy_and_complete_pair(self):
        values = {key: str(value) for key, value in self.config.__dict__.items() if value is not None}
        self.assertEqual(LocalConfig.from_file(self.config_file(values)), self.config)
        values.pop('study_lesson_prompt_path'); values.pop('study_lesson_prompt_sha256')
        legacy = LocalConfig.from_file(self.config_file(values))
        self.assertIsNone(legacy.study_lesson_prompt_path)
        self.assertIsNone(load_study_lesson_prompt(legacy))

    def test_partial_null_bad_hash_relative_and_unknown_config_rejected(self):
        original = {key: str(value) for key, value in self.config.__dict__.items() if value is not None}
        for change in (lambda v: v.pop('study_lesson_prompt_path'), lambda v: v.pop('study_lesson_prompt_sha256'),
                       lambda v: v.update(study_lesson_prompt_path=None, study_lesson_prompt_sha256=None),
                       lambda v: v.update(study_lesson_prompt_sha256='A' * 64),
                       lambda v: v.update(study_lesson_prompt_path='relative.txt'), lambda v: v.update(extra='unknown')):
            values = dict(original); change(values)
            with self.assertRaises(ValueError): LocalConfig.from_file(self.config_file(values))
        for change in ({'study_lesson_prompt_path': None}, {'study_lesson_prompt_sha256': None},
                       {'study_lesson_prompt_path': Path('relative')}, {'study_lesson_prompt_sha256': 'bad'}):
            with self.assertRaises(ValueError): replace(self.config, **change)

    def test_exact_bytes_and_manifest_binding(self):
        self.assertEqual(load_study_lesson_prompt(self.config), PLACEHOLDER.decode())
        self.assertEqual(verify_artifacts(self.config), self.config.manifest_sha256)
        for value in (None, '0' * 64):
            manifest = json.loads(self.config.manifest_path.read_text())
            if value is None: manifest.pop('study_lesson_prompt_sha256')
            else: manifest['study_lesson_prompt_sha256'] = value
            self.config.manifest_path.write_text(json.dumps(manifest))
            with self.assertRaises(ValueError):
                verify_artifacts(replace(self.config, manifest_sha256=digest(self.config.manifest_path)))
        with self.assertRaises(ValueError):
            verify_artifacts(replace(self.config, manifest_sha256=digest(self.config.manifest_path),
                                    study_lesson_prompt_path=None, study_lesson_prompt_sha256=None))

    def test_unconfigured_legacy_manifest_remains_available(self):
        manifest = json.loads(self.config.manifest_path.read_text()); manifest.pop('study_lesson_prompt_sha256')
        self.config.manifest_path.write_text(json.dumps(manifest))
        config = replace(self.config, manifest_sha256=digest(self.config.manifest_path),
                         study_lesson_prompt_path=None, study_lesson_prompt_sha256=None)
        self.assertEqual(verify_artifacts(config), config.manifest_sha256)

    def test_hash_size_utf8_empty_permission_owner_missing_and_symlink_fail_closed(self):
        path = self.config.study_lesson_prompt_path
        for raw in (b'', b'changed', b'\xff', b'x' * 65537):
            path.write_bytes(raw)
            config = self.config if raw == b'changed' else replace(self.config, study_lesson_prompt_sha256=digest(path))
            with self.assertRaisesRegex(ValueError, '^study prompt unavailable$'):
                load_study_lesson_prompt(config)
        path.write_bytes(PLACEHOLDER); path.chmod(0o644)
        with self.assertRaisesRegex(ValueError, '^study prompt unavailable$'): load_study_lesson_prompt(self.config)
        path.chmod(0o600)
        with patch('jarvis_intelligence.local_runtime.os.getuid', return_value=os.getuid() + 1):
            with self.assertRaisesRegex(ValueError, '^study prompt unavailable$'): load_study_lesson_prompt(self.config)
        path.unlink()
        with self.assertRaisesRegex(ValueError, '^study prompt unavailable$'): load_study_lesson_prompt(self.config)
        path.symlink_to(self.config.prompt_path)
        with self.assertRaisesRegex(ValueError, '^study prompt unavailable$'): load_study_lesson_prompt(self.config)
        path.unlink(); path.mkdir()
        with self.assertRaisesRegex(ValueError, '^study prompt unavailable$'): load_study_lesson_prompt(self.config)

    def test_fd_closed_on_success_and_failures_and_errors_are_redacted(self):
        real_open, real_close = os.open, os.close
        for failure in (False, True):
            opened, closed = [], []
            def open_file(*args, **kwargs):
                fd = real_open(*args, **kwargs); opened.append(fd); return fd
            def close_file(fd):
                closed.append(fd); return real_close(fd)
            with patch('jarvis_intelligence.local_runtime.os.open', side_effect=open_file), patch('jarvis_intelligence.local_runtime.os.close', side_effect=close_file):
                if failure:
                    with self.assertRaises(ValueError) as caught:
                        load_study_lesson_prompt(replace(self.config, study_lesson_prompt_sha256='0' * 64))
                    self.assertNotIn(str(self.root), str(caught.exception))
                    self.assertNotIn(PLACEHOLDER.decode(), str(caught.exception))
                    self.assertTrue(caught.exception.__suppress_context__)
                else:
                    self.assertEqual(load_study_lesson_prompt(self.config), PLACEHOLDER.decode())
            self.assertEqual(opened, closed)
            with self.assertRaises(OSError): os.fstat(opened[0])
        self.assertNotIn(str(self.config.study_lesson_prompt_path), repr(self.config))

    def test_consumed_bytes_revalidated_after_prior_verification(self):
        self.assertEqual(verify_artifacts(self.config), self.config.manifest_sha256)
        self.config.study_lesson_prompt_path.write_bytes(b'SYNTHETIC CHANGED PLACEHOLDER')
        with self.assertRaisesRegex(ValueError, '^study prompt unavailable$'):
            load_study_lesson_prompt(self.config)

    def test_path_replacement_after_open_cannot_replace_consumed_bytes(self):
        original_open = os.open
        replacement = self.root / 'replacement-placeholder.txt'
        replacement.write_bytes(b'SYNTHETIC REPLACEMENT PLACEHOLDER')
        replacement.chmod(0o600)
        def open_then_replace(path, flags):
            fd = original_open(path, flags)
            replacement.replace(path)
            return fd
        with patch('jarvis_intelligence.local_runtime.os.open', side_effect=open_then_replace):
            self.assertEqual(load_study_lesson_prompt(self.config), PLACEHOLDER.decode())
        with self.assertRaisesRegex(ValueError, '^study prompt unavailable$'):
            load_study_lesson_prompt(self.config)


class StudyAdapterTests(unittest.TestCase):
    def test_purpose_specific_closed_schema_never_exposes_service_usage(self):
        kinds = lambda schema: {branch['properties']['kind']['const'] for branch in schema['oneOf']}
        self.assertEqual(kinds(output_schema([])), {'ANSWER', 'NEED_INPUT'})
        self.assertEqual(kinds(output_schema(['calendar.event.create'])), {'ANSWER', 'NEED_INPUT', 'TOOL_PROPOSALS'})
        study = output_schema(['calendar.event.create'], 'STUDY_LESSON')
        self.assertEqual(kinds(study), {'LESSON', 'NEED_INPUT'})
        validator = Draft202012Validator(study)
        self.assertTrue(validator.is_valid(lesson()))
        self.assertFalse(validator.is_valid({**lesson(), 'usage': {}}))
        self.assertFalse(validator.is_valid({**lesson(), 'id': 'model-supplied'}))
        for branch in study['oneOf']:
            self.assertNotIn('usage', branch['properties']); self.assertFalse(branch['additionalProperties'])
        with self.assertRaises(ContractViolation): output_schema([], 'unknown')

    def test_study_prompt_exact_tensors_and_single_generation(self):
        adapter, seen = fake_adapter(lambda _: 3584)
        adapter.study_lesson_system = PLACEHOLDER.decode()
        body = study_request()
        result = adapter.generate(adapter_request(body), SimpleNamespace(is_set=lambda: False))
        self.assertTrue(seen['candidates'][0][0]['content'].startswith(PLACEHOLDER.decode() + '\n'))
        self.assertEqual(len(seen['streams']), 1)
        self.assertIs(seen['streams'][0]['input_ids'], seen['tensors'][0][0])
        self.assertEqual(result['input_tokens'], 3584)
        # The synthetic adapter yields ordinary ANSWER: no parsing/repair into LESSON.
        self.assertEqual(result['output']['kind'], 'ANSWER')
        with self.assertRaises(ContractViolation):
            _validate_output(result['output'], TurnRequest.from_wire(body), measured=result)

    def test_missing_dependency_fails_before_render_prepare_or_stream(self):
        adapter, seen = fake_adapter(lambda _: 100)
        with self.assertRaises(RuntimeFailure) as caught:
            adapter.generate(adapter_request(study_request()), SimpleNamespace(is_set=lambda: False))
        self.assertEqual(caught.exception.code, 'MODEL_UNAVAILABLE')
        self.assertEqual(seen, {'candidates': [], 'tensors': [], 'streams': []})

    def test_ordinary_rendering_bytes_unchanged_and_never_uses_study_prompt(self):
        adapter, seen = fake_adapter(lambda _: 100)
        adapter.study_lesson_system = PLACEHOLDER.decode()
        body = request()
        adapter.generate(adapter_request(body), SimpleNamespace(is_set=lambda: False))
        expected = 'synthetic\n' + canonical(output_schema([])).decode() + '\n' + canonical({key: body[key] for key in ('current_time', 'timezone')}).decode()
        self.assertEqual(seen['candidates'][0][0]['content'], expected)
        self.assertNotIn(PLACEHOLDER.decode(), seen['candidates'][0][0]['content'])


class StudyPipeTests(unittest.TestCase):
    def test_study_purpose_and_all_context_fields_survive_synthetic_pipe(self):
        body = study_request()
        captured = []
        runtime = LocalRuntime(SimpleNamespace(manifest_sha256=body['model_manifest_hash'], study_lesson_prompt_path=Path('/synthetic-placeholder-only')))
        runtime.health = lambda: {'runtime_ready': True}
        runtime._cancel = SimpleNamespace(clear=lambda: None)
        runtime._pipe = SimpleNamespace(send=captured.append)
        async def receive(deadline): return {'request_id': body['request_id']}
        async def wait_for(coro, timeout): return await coro
        runtime._receive = receive
        with patch('jarvis_intelligence.local_runtime.asyncio.wait_for', new=wait_for):
            immediate(runtime.generate(TurnRequest.from_wire(body), time.time() + 10))
        for key in ('purpose', 'context', 'project_context', 'memories', 'history', 'evidence', 'tool_results'):
            self.assertEqual(captured[0][key], body[key])
        self.assertFalse(runtime._serial.locked())

    def test_conversation_pipe_keeps_legacy_keys_and_omits_purpose(self):
        for purpose in (None, 'CONVERSATION'):
            body = request()
            if purpose is not None:
                body['purpose'] = purpose
            captured = []
            runtime = LocalRuntime(SimpleNamespace(manifest_sha256=body['model_manifest_hash']))
            runtime.health = lambda: {'runtime_ready': True}
            runtime._cancel = SimpleNamespace(clear=lambda: None)
            runtime._pipe = SimpleNamespace(send=captured.append)
            async def receive(deadline): return {'request_id': body['request_id']}
            async def wait_for(coro, timeout): return await coro
            runtime._receive = receive
            with patch('jarvis_intelligence.local_runtime.asyncio.wait_for', new=wait_for):
                immediate(runtime.generate(TurnRequest.from_wire(body), time.time() + 10))
            self.assertEqual(set(captured[0]), {'request_id', 'user_input', 'allowed_tool_ids', 'current_time',
                                               'timezone', 'max_input_tokens', 'max_output_tokens', 'deadline_monotonic',
                                               'context', 'project_context', 'memories', 'history', 'evidence', 'tool_results'})

    def test_missing_dependency_never_queues_or_sends(self):
        captured = []
        runtime = LocalRuntime(SimpleNamespace(manifest_sha256='0' * 64))
        runtime.health = lambda: (_ for _ in ()).throw(AssertionError('no readiness access needed'))
        runtime._pipe = SimpleNamespace(send=captured.append)
        with self.assertRaises(RuntimeFailure) as caught:
            immediate(runtime.generate(TurnRequest.from_wire(study_request()), time.time() + 10))
        self.assertEqual(caught.exception.code, 'MODEL_UNAVAILABLE')
        self.assertEqual(captured, [])
        self.assertEqual(runtime._waiting, 0)
        self.assertFalse(runtime._serial.locked())

    def test_worker_loop_does_not_drop_purpose_or_add_model_authority(self):
        body = study_request(); seen, sent = [], []
        incoming = iter([body])
        def recv():
            try: return next(incoming)
            except StopIteration: raise EOFError()
        def generate(value, cancel):
            seen.append(value)
            return {'output': lesson(), **MEASURED}
        with self.assertRaises(EOFError):
            _worker_request_loop(SimpleNamespace(recv=recv, send=sent.append), SimpleNamespace(generate=generate), object())
        self.assertEqual(seen, [body]); self.assertEqual(sent, [{'request_id': body['request_id'], 'output': lesson(), **MEASURED}])


class StudyBoundaryTests(unittest.TestCase):
    """ASGI/SQLite integration coverage: do not run without separate authorization."""
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
        self.body['purpose'] = 'STUDY_LESSON'
        self.runtime.output = lesson()

    def test_lesson_measured_usage_and_no_response_cache_or_retry(self):
        with patch('jarvis_intelligence.app._peer_allowed', return_value=True):
            result = self.send(); replay = self.send()
        self.assertEqual(result.status_code, 200)
        self.assertEqual(result.json()['kind'], 'LESSON')
        self.assertEqual(result.json()['usage']['input_tokens'], 100)
        self.assertEqual(replay.status_code, 409)
        self.assertEqual(replay.json()['error_code'], 'INFERENCE_UNKNOWN')
        self.assertEqual(self.runtime.calls, 1)
        with self.app.state.ledger.connect() as db:
            self.assertEqual(db.execute('SELECT state,response,nonce,cache_until,cache_response FROM replay').fetchone(),
                             ('COMPLETED', None, None, None, 0))

    def test_malformed_lesson_is_unknown_without_fabricated_usage(self):
        self.runtime.output['concepts'].pop()
        with patch('jarvis_intelligence.app._peer_allowed', return_value=True):
            result = self.send(); replay = self.send()
        self.assertEqual(result.status_code, 409)
        self.assertEqual(result.json(), {'error_code': 'MODEL_OUTPUT_INVALID'})
        self.assertEqual(replay.json(), {'error_code': 'INFERENCE_UNKNOWN'})
        self.assertEqual(self.runtime.calls, 1)
        with self.app.state.ledger.connect() as db:
            self.assertEqual(db.execute('SELECT state,response,cache_response FROM replay').fetchone(), ('UNKNOWN', None, 0))

    def test_signed_purpose_cannot_be_changed_before_generation(self):
        headers = self.headers(); self.body['purpose'] = 'CONVERSATION'
        with patch('jarvis_intelligence.app._peer_allowed', return_value=True):
            result = self.send(headers=headers)
        self.assertEqual(result.status_code, 401); self.assertEqual(self.runtime.calls, 0)

    def test_missing_dependency_has_service_error_without_generation(self):
        async def unavailable(turn, expiry): raise RuntimeFailure('MODEL_UNAVAILABLE')
        self.runtime.generate = unavailable
        with patch('jarvis_intelligence.app._peer_allowed', return_value=True):
            result = self.send(); replay = self.send()
        self.assertEqual(result.status_code, 200)
        self.assertEqual(result.json()['kind'], 'ERROR')
        self.assertEqual(result.json()['error_code'], 'MODEL_UNAVAILABLE')
        self.assertEqual(result.json()['usage']['input_tokens'], 0)
        self.assertEqual(self.runtime.calls, 0)
        self.assertEqual(replay.json(), {'error_code': 'INFERENCE_UNKNOWN'})

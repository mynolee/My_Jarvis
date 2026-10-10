"""Synthetic Python reachability checks, not RAM/GPU zeroization evidence."""
import asyncio
import gc
import json
import sys
import unittest
import weakref

from jarvis_intelligence.local_runtime import RuntimeFailure, _worker_request_loop


class TrackedDict(dict):
    pass


class TrackedText(str):
    pass


class TrackedList(list):
    pass


class Probe:
    def __init__(self, test):
        self.test = test
        self.references = []

    def track(self, label, value):
        self.references.append((label, weakref.ref(value)))
        return value

    def assert_released(self):
        # Only weak references are kept by this probe. Include cyclic garbage in
        # the check; the guarantee is reachability, not allocator memory wiping.
        gc.collect()
        alive = [label for label, reference in self.references if reference() is not None]
        self.test.assertEqual(alive, [], 'completed turn is still reachable')


class SyntheticAdapter:
    def __init__(self, probe, cancel):
        self.probe = probe
        self.cancel = cancel
        self.calls = 0

    def generate(self, request, cancel):
        self.probe.test.assertIs(cancel, self.cancel)
        self.calls += 1
        outcome = request['outcome']
        if outcome in {'CANCELLED', 'GENERATION_TIMEOUT', 'CONTEXT_LIMIT', 'MODEL_UNAVAILABLE'}:
            raise RuntimeFailure(self.probe.track('error code', TrackedText(outcome)))
        if outcome == 'invalid':
            raise ValueError('synthetic adapter failure')
        if outcome == 'cancelled_task':
            raise asyncio.CancelledError()
        if outcome == 'interrupt':
            raise KeyboardInterrupt()
        if outcome == 'non_mapping':
            return self.probe.track('invalid result', TrackedList(['synthetic']))
        output = self.probe.track('output', TrackedDict(
            kind='ANSWER', reply_text=self.probe.track('reply', TrackedText('synthetic answer')),
            cited_source_ids=[]))
        return self.probe.track('result', TrackedDict(
            output=output, input_tokens=10, output_tokens=2, compute_ms=1,
            peak_memory_bytes=1, finish_reason='stop'))


class SyntheticPipe:
    def __init__(self, probe, outcomes, *, send_failures=None, receive_error=EOFError):
        self.probe = probe
        self.outcomes = iter(outcomes)
        self.send_failures = send_failures or {}
        self.receive_error = receive_error
        self.receives = 0
        self.sent = []

    def recv(self):
        # Inspect at the exact next-receive boundary, before creating the next
        # request. No background thread, native worker, MLX or stdout redirection.
        self.probe.assert_released()
        locals_at_receive = sys._getframe(1).f_locals
        for name in ('request', 'result', 'response'):
            self.probe.test.assertIsNone(locals_at_receive.get(name), name)
        self.receives += 1
        outcome = next(self.outcomes, None)
        if outcome is None:
            raise self.receive_error('synthetic input closed')
        return self.probe.track('request', TrackedDict(
            request_id=self.probe.track('request id', TrackedText(f'turn-{self.receives}')),
            user_input=self.probe.track('prompt', TrackedText('synthetic prompt')),
            context=self.probe.track('context', TrackedDict(summary='synthetic context')),
            outcome=outcome))

    def send(self, response):
        # Match Pipe.send's ownership boundary: retain a serialized copy only,
        # never the original dictionary or any of its tracked values.
        self.sent.append(json.loads(json.dumps(response)))
        failure = self.send_failures.get(len(self.sent))
        if failure is not None:
            raise failure('synthetic send failure')


class WorkerRetentionTests(unittest.TestCase):
    def run_loop(self, outcomes, *, send_failures=None, receive_error=EOFError,
                 terminal_error=EOFError):
        probe = Probe(self)
        cancel = object()
        adapter = SyntheticAdapter(probe, cancel)
        pipe = SyntheticPipe(probe, outcomes, send_failures=send_failures,
                             receive_error=receive_error)
        try:
            _worker_request_loop(pipe, adapter, cancel)
        except terminal_error as failure:
            # Even while a shutdown traceback is still live, the loop's own
            # frame must no longer retain a request, result or response.
            traceback = failure.__traceback__
            try:
                while traceback is not None:
                    if traceback.tb_frame.f_code is _worker_request_loop.__code__:
                        for name in ('request', 'result', 'response'):
                            self.assertIsNone(traceback.tb_frame.f_locals.get(name), name)
                    traceback = traceback.tb_next
            finally:
                traceback = None
        else:
            self.fail(f'{terminal_error.__name__} was not raised')
        probe.assert_released()
        return pipe, adapter

    def test_success_releases_request_result_and_response_before_next_receive(self):
        pipe, adapter = self.run_loop(['success', 'success'])
        self.assertEqual(adapter.calls, 2)
        self.assertEqual(pipe.receives, 3)
        self.assertEqual(pipe.sent, [
            {'request_id': f'turn-{index}', 'output': {
                'kind': 'ANSWER', 'reply_text': 'synthetic answer', 'cited_source_ids': []},
             'input_tokens': 10, 'output_tokens': 2, 'compute_ms': 1,
             'peak_memory_bytes': 1, 'finish_reason': 'stop'}
            for index in (1, 2)])

    def test_runtime_failure_releases_exception_code_and_request(self):
        for code in ('CANCELLED', 'GENERATION_TIMEOUT', 'CONTEXT_LIMIT', 'MODEL_UNAVAILABLE'):
            with self.subTest(code=code):
                pipe, adapter = self.run_loop([code, 'success'])
                self.assertEqual(pipe.sent[0], {'request_id': 'turn-1', 'error': code})
                self.assertEqual(adapter.calls, 2)
                self.assertIn('output', pipe.sent[1])

    def test_generic_failure_and_non_mapping_result_keep_error_mapping(self):
        for outcome in ('invalid', 'non_mapping'):
            with self.subTest(outcome=outcome):
                pipe, adapter = self.run_loop([outcome, 'success'])
                self.assertEqual(pipe.sent[0], {
                    'request_id': 'turn-1', 'error': 'MODEL_OUTPUT_INVALID'})
                self.assertEqual(adapter.calls, 2)

    def test_success_then_failure_does_not_retain_old_result(self):
        pipe, adapter = self.run_loop(['success', 'CANCELLED', 'invalid', 'success'])
        self.assertEqual(adapter.calls, 4)
        self.assertEqual(pipe.receives, 5)
        self.assertEqual([message.get('error') for message in pipe.sent],
                         [None, 'CANCELLED', 'MODEL_OUTPUT_INVALID', None])

    def test_success_send_failure_preserves_fallback_and_releases_result(self):
        for failure, code in ((OSError, 'MODEL_OUTPUT_INVALID'),
                              (RuntimeFailure, 'synthetic send failure')):
            with self.subTest(failure=failure):
                pipe, adapter = self.run_loop(['success', 'success'], send_failures={1: failure})
                self.assertEqual(pipe.sent[1], {'request_id': 'turn-1', 'error': code})
                self.assertEqual(adapter.calls, 2)
                self.assertEqual(pipe.receives, 3)

    def test_failed_error_send_exits_and_releases_turn(self):
        for outcome, failures in (
                ('success', {1: OSError, 2: BrokenPipeError}),
                ('CANCELLED', {1: BrokenPipeError}),
                ('invalid', {1: BrokenPipeError})):
            with self.subTest(outcome=outcome):
                pipe, adapter = self.run_loop([outcome], send_failures=failures,
                                              terminal_error=BrokenPipeError)
                self.assertEqual(adapter.calls, 1)
                self.assertEqual(pipe.receives, 1)

    def test_base_exception_during_generation_releases_request_without_response(self):
        for outcome, error in (('cancelled_task', asyncio.CancelledError),
                               ('interrupt', KeyboardInterrupt)):
            with self.subTest(outcome=outcome):
                pipe, adapter = self.run_loop([outcome], terminal_error=error)
                self.assertEqual(adapter.calls, 1)
                self.assertEqual(pipe.sent, [])

    def test_base_exception_during_send_releases_result_without_fallback(self):
        pipe, adapter = self.run_loop(['success'], send_failures={1: KeyboardInterrupt},
                                      terminal_error=KeyboardInterrupt)
        self.assertEqual(adapter.calls, 1)
        self.assertEqual(len(pipe.sent), 1)

    def test_receive_shutdown_releases_previous_turn(self):
        for error in (EOFError, OSError, KeyboardInterrupt):
            with self.subTest(error=error):
                pipe, adapter = self.run_loop(['success'], receive_error=error,
                                              terminal_error=error)
                self.assertEqual(adapter.calls, 1)
                self.assertEqual(pipe.receives, 2)

    def test_shutdown_without_request_does_not_generate_or_send(self):
        pipe, adapter = self.run_loop([])
        self.assertEqual(adapter.calls, 0)
        self.assertEqual(pipe.sent, [])

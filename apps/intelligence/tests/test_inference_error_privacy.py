"""Stdlib-only fault fixtures for the pinned API's unknown-marking error boundary.

The standard test entry reads only the fixed sibling app.py Source when needed.
An already-configured caller performs no additional file read. This module never
imports the product, ledger, FastAPI, crypto, a model, or any private payload.
Schema/auth/ASGI/timer seams are explicit fakes; these are not their tests.
"""
from __future__ import annotations

import __future__
import ast
import asyncio as real_asyncio
import builtins
import copy
import hashlib
import json
import os
import stat
import types
import unittest
from contextlib import asynccontextmanager
from contextlib import ExitStack
from datetime import datetime
from pathlib import Path

APP_BYTES = 19644
APP_SHA256 = "3e0df17feccfc42343bfceb567d26be10f4f024eb05d0eb0d7b0ee90e6bd5bba"
_SELECTED = ("_usage", "_error", "_validate_output", "create_app")
_SPEC = None
_RAW = None


def _verified_spec(raw):
    if type(raw) is not bytes or len(raw) != APP_BYTES or hashlib.sha256(raw).hexdigest() != APP_SHA256:
        raise ValueError("APP_SOURCE_PIN")
    tree = ast.parse(raw.decode("utf-8", "strict"))
    selected = []
    for name in _SELECTED:
        found = [node for node in tree.body if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and node.name == name]
        if len(found) != 1:
            raise ValueError("APP_SOURCE_SHAPE")
        selected.append(found[0])
    helpers = [node for node in ast.walk(selected[-1]) if isinstance(node, ast.FunctionDef) and node.name == "unknown_response"]
    if len(helpers) != 1:
        raise ValueError("UNKNOWN_HELPER_SHAPE")
    caught = [handler.type for node in ast.walk(helpers[0]) if isinstance(node, ast.Try) for handler in node.handlers]
    if len(caught) != 1 or not isinstance(caught[0], ast.Name) or caught[0].id != "Exception":
        raise ValueError("UNKNOWN_HELPER_EXCEPTION_SCOPE")
    return ast.Module(body=selected, type_ignores=[]), helpers[0]


def configure_verified_app(raw):
    """Required one-shot future harness hook; source pin is checked before use."""
    global _SPEC, _RAW
    spec = _verified_spec(raw)
    if _SPEC is not None:
        raise RuntimeError("APP_ALREADY_CONFIGURED")
    _SPEC, _RAW = spec, raw


def _source_identity(value):
    return (value.st_dev, value.st_ino, value.st_mode, value.st_nlink, value.st_size,
            value.st_mtime_ns, value.st_ctime_ns)


def _owned_source_open(stack, path, flags, *, dir_fd=None):
    holder = [None, False]

    def close_once():
        descriptor = holder[0]
        if holder[1] or type(descriptor) is not int or descriptor < 0:
            return
        holder[1] = True
        os.close(descriptor)

    # Register before acquisition: callback-registration failure cannot lose a FD.
    stack.callback(close_once)
    holder[0] = os.open(path, flags, dir_fd=dir_fd)
    if type(holder[0]) is not int or holder[0] < 0:
        raise ValueError("APP_SOURCE_OPEN")
    return holder[0]


def _checkout_app_bytes():
    path = Path(__file__).absolute().parent.parent / "src" / "jarvis_intelligence" / "app.py"
    if path.anchor != "/" or ".." in path.parts:
        raise ValueError("APP_SOURCE_PATH")
    directory_flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_NONBLOCK | os.O_CLOEXEC
    read_flags = os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK | os.O_CLOEXEC
    try:
        with ExitStack() as owners:
            current = _owned_source_open(owners, "/", directory_flags)
            root = os.fstat(current)
            if not stat.S_ISDIR(root.st_mode):
                raise ValueError("APP_SOURCE_PATH")
            links = [(None, "/", current, _source_identity(root)[:3])]
            for component in path.parts[1:-1]:
                named = os.stat(component, dir_fd=current, follow_symlinks=False)
                if not stat.S_ISDIR(named.st_mode):
                    raise ValueError("APP_SOURCE_PATH")
                child = _owned_source_open(owners, component, directory_flags, dir_fd=current)
                held = os.fstat(child)
                if _source_identity(named)[:3] != _source_identity(held)[:3]:
                    raise ValueError("APP_SOURCE_CHANGED")
                links.append((current, component, child, _source_identity(held)[:3]))
                current = child
            source = _owned_source_open(owners, path.name, read_flags, dir_fd=current)
            before = os.fstat(source)
            if not stat.S_ISREG(before.st_mode) or before.st_nlink != 1 or before.st_size != APP_BYTES:
                raise ValueError("APP_SOURCE_PIN")
            raw = bytearray()
            while len(raw) <= APP_BYTES:
                block = os.read(source, min(65536, APP_BYTES + 1 - len(raw)))
                if not block:
                    break
                raw.extend(block)
            named = os.stat(path.name, dir_fd=current, follow_symlinks=False)
            if (len(raw) != APP_BYTES or _source_identity(before) != _source_identity(os.fstat(source))
                    or _source_identity(before) != _source_identity(named)):
                raise ValueError("APP_SOURCE_CHANGED")
            for parent, component, child, wanted in links:
                named = os.stat(component, dir_fd=parent, follow_symlinks=False)
                if wanted != _source_identity(os.fstat(child))[:3] or wanted != _source_identity(named)[:3]:
                    raise ValueError("APP_SOURCE_CHANGED")
            result = bytes(raw)
        return result  # All callbacks, including failed-close handling, precede configuration.
    except OSError:
        raise RuntimeError("APP_SOURCE_READ") from None


class SecretError(Exception):
    def __init__(self, secret):
        self.secret, self.str_calls, self.repr_calls = secret, 0, 0
        super().__init__(secret)

    def __str__(self):
        self.str_calls += 1
        return self.secret

    def __repr__(self):
        self.repr_calls += 1
        return self.secret


class RuntimeFailure(SecretError):
    def __init__(self, code, secret="SYNTHETIC_RUNTIME_SECRET"):
        self.code = code
        super().__init__(secret)


class ContractViolation(SecretError):
    pass


class ValidationError(Exception):
    pass


class ReplayConflict(Exception):
    pass


class LedgerFull(Exception):
    pass


class Unauthorized(Exception):
    pass


class SyntheticControl(BaseException):
    pass


class Response:
    def __init__(self, content, status_code=200, headers=None):
        self.content, self.status_code, self.headers = content, status_code, dict(headers or {})


class FakeApp:
    def __init__(self, **configuration):
        self.configuration, self.state, self.routes = configuration, types.SimpleNamespace(), {}

    def _route(self, method, path):
        def attach(function):
            self.routes[(method, path)] = function
            return function
        return attach

    def get(self, path):
        return self._route("GET", path)

    def post(self, path):
        return self._route("POST", path)


class Headers:
    def getlist(self, name):
        return ["synthetic-capability"] if name == "x-jarvis-capability" else []

    def get(self, name, default=None):
        return "application/json" if name == "content-type" else default


class Request:
    def __init__(self):
        self.headers = Headers()
        self.scope = {"headers": []}
        self.url = types.SimpleNamespace(query="")

    async def stream(self):
        yield b"{}"

    async def is_disconnected(self):
        return False


class MemoryLedger:
    def __init__(self, secondary=None, replay=None):
        self.secondary, self.replay = secondary, replay
        self.begin_calls = self.unknown_calls = self.complete_calls = 0
        self.cache_flags = []

    def begin(self, claims, now, duration, *, cache_response, control):
        self.begin_calls += 1
        return self.replay

    def unknown(self, claims, *, cache_response):
        self.unknown_calls += 1
        self.cache_flags.append(cache_response)
        if self.secondary is not None:
            raise self.secondary

    def complete(self, claims, response, now, *, cache_response):
        self.complete_calls += 1


@asynccontextmanager
async def fake_timeout(_duration):
    # Faults are injected directly; this does not verify a physical timeout.
    yield


async def yield_once(_delay):
    await real_asyncio.sleep(0)


def forbidden(*_args, **_kwargs):
    raise AssertionError("UNSELECTED_EXTERNAL_EFFECT")


class Fixture:
    def __init__(self, branch="provider", *, context=None, secondary=None, replay=None, runtime_code="GENERATION_TIMEOUT"):
        if _SPEC is None:
            raise RuntimeError("CONFIGURE_VERIFIED_APP_REQUIRED")
        self.branch = branch
        self.ledger = MemoryLedger(secondary, replay)
        self.provider_calls = self.clock_calls = 0
        self.primary = (
            RuntimeFailure(runtime_code) if branch == "runtime" else
            ContractViolation("SYNTHETIC_LOCAL_PROVIDER_SECRET") if branch == "local-contract" else
            TimeoutError("SYNTHETIC_TIMEOUT_SECRET") if branch == "timeout" else
            SecretError("SYNTHETIC_PROVIDER_SECRET")
        )
        budget = types.SimpleNamespace(max_compute_ms=1000, max_input_tokens=4096, max_output_tokens=8192,
                                       reservation_id="synthetic-reservation")
        local = branch == "local-contract"
        self.turn = types.SimpleNamespace(
            model_id="local-mlx-v1" if local else "mock-v1", prompt_revision="local-v1" if local else "mock-v1",
            model_manifest_hash="a" * 64, user_input="synthetic", purpose="CONVERSATION", context=context,
            allowed_tool_ids=[], budget=budget, request_id="synthetic-request")
        self.claims = {"expires_at": "1970-01-01T00:00:30+00:00"}
        self.settings = types.SimpleNamespace(
            mode="local_mlx" if local else "mock", local_config=types.SimpleNamespace(lock_path="unused"),
            ledger_path="unused", cache_key=b"synthetic-only", max_ledger_rows=1, max_concurrency=1, core_keys={})
        safe_names = ("Exception", "BaseException", "ValueError", "TypeError", "TimeoutError", "len", "getattr",
                      "hasattr", "type", "int", "str", "dict", "bytes", "bytearray", "sum", "set", "frozenset",
                      "min", "max", "bool", "tuple", "list", "object", "isinstance", "any", "all")
        safe_builtins = {name: getattr(builtins, name) for name in safe_names}
        safe_builtins.update({"open": forbidden, "__import__": forbidden, "print": forbidden, "input": forbidden})
        fake_asyncio = types.SimpleNamespace(timeout=fake_timeout, create_task=real_asyncio.create_task,
                                             sleep=yield_once, CancelledError=real_asyncio.CancelledError)
        self.namespace = {
            "__builtins__": safe_builtins, "__name__": "verified_api_error_fixture",
            "asyncio": fake_asyncio, "asynccontextmanager": asynccontextmanager, "datetime": datetime,
            "time": types.SimpleNamespace(time=forbidden), "mock_generate": forbidden,
            "Ledger": lambda *_a, **_k: self.ledger, "LocalRuntime": forbidden,
            "OwnershipLease": types.SimpleNamespace(acquire=lambda *_a: types.SimpleNamespace(close=lambda: None)),
            "FastAPI": FakeApp, "JSONResponse": Response, "RuntimeFailure": RuntimeFailure,
            "ContractViolation": ContractViolation, "ValidationError": ValidationError,
            "ReplayConflict": ReplayConflict, "LedgerFull": LedgerFull, "Unauthorized": Unauthorized,
            "sqlite3": types.SimpleNamespace(Error=RuntimeError), "MAX_BODY_BYTES": 262144, "MAX_CONTROL_BYTES": 16384,
            "MOCK_MANIFEST_HASH": "a" * 64, "_peer_allowed": lambda *_a: True,
            "parse_json": lambda raw, **_k: json.loads(raw), "validate": self.fake_validate,
            "canonical": lambda value: json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode(),
            "TurnRequest": types.SimpleNamespace(from_wire=self.fake_turn),
            "verify_capability": self.fake_capability, "verify_control_capability": forbidden,
            "control_identity": lambda *_a: None,
        }
        module, helper = _SPEC
        flags = __future__.annotations.compiler_flag
        exec(compile(ast.fix_missing_locations(copy.deepcopy(module)), "<verified-inference-api>", "exec",
                     flags=flags, dont_inherit=True), self.namespace)
        self.local_runtime = types.SimpleNamespace(manifest_hash="a" * 64, generate=self.local_generate) if local else None
        self.app = self.namespace["create_app"](self.settings, generate=self.generate, clock=self.clock,
                                                  runtime=self.local_runtime)
        self.handler = self.app.routes[("POST", "/internal/v1/turn")]
        helper_namespace = {
            "__builtins__": safe_builtins, "ledger": self.ledger, "claims": self.claims, "turn": self.turn,
            "reject": lambda status, code: Response({"error_code": code}, status, {"Cache-Control": "no-store"})
        }
        helper_module = ast.Module(body=[copy.deepcopy(helper)], type_ignores=[])
        exec(compile(ast.fix_missing_locations(helper_module), "<verified-unknown-helper>", "exec",
                     flags=flags, dont_inherit=True), helper_namespace)
        self.unknown_response = helper_namespace["unknown_response"]

    def fake_turn(self, body):
        if body != {}:
            raise AssertionError("UNEXPECTED_SYNTHETIC_BODY")
        return self.turn

    def fake_capability(self, token, body, keys, now):
        if token != "synthetic-capability" or body != {} or now != 0.0:
            raise AssertionError("UNEXPECTED_SYNTHETIC_CAPABILITY")
        return self.claims

    def fake_validate(self, name, value):
        if name != "turn-response":
            raise AssertionError("UNSELECTED_SCHEMA")
        return value

    def clock(self):
        self.clock_calls += 1
        return 0.0

    async def generate(self, _turn):
        self.provider_calls += 1
        if self.branch == "answer":
            return {"kind": "ANSWER", "reply_text": "Synthetic answer", "cited_source_ids": []}
        raise self.primary

    async def local_generate(self, _turn, _expiry):
        self.provider_calls += 1
        raise self.primary

    def send(self):
        return real_asyncio.run(self.handler(Request()))


class InferenceErrorPrivacyTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if _SPEC is None:
            configure_verified_app(_checkout_app_bytes())

    def setUp(self):
        if _SPEC is None:
            self.fail("CONFIGURE_VERIFIED_APP_REQUIRED")

    def assert_private_response(self, fixture, response, status, code):
        self.assertEqual(response.status_code, status)
        self.assertEqual(response.content, {"error_code": code})
        self.assertEqual(response.headers.get("Cache-Control"), "no-store")
        rendered = json.dumps(response.content, sort_keys=True)
        for token in ("SYNTHETIC_PROVIDER_SECRET", "SYNTHETIC_LOCAL_PROVIDER_SECRET", "SYNTHETIC_RUNTIME_SECRET",
                      "SYNTHETIC_TIMEOUT_SECRET", "SYNTHETIC_STATE_SECRET", "traceback", "__context__", "__cause__"):
            self.assertNotIn(token, rendered)
        for error in (fixture.primary, fixture.ledger.secondary):
            if isinstance(error, SecretError):
                self.assertEqual(error.str_calls, 0)
                self.assertEqual(error.repr_calls, 0)

    def test_each_unknown_branch_hides_secondary_private_error(self):
        for branch in ("provider", "runtime", "timeout", "local-contract"):
            for context in (None, {}):
                with self.subTest(branch=branch, context=context is not None):
                    fixture = Fixture(branch, context=context, secondary=SecretError("SYNTHETIC_STATE_SECRET"))
                    self.assert_private_response(fixture, fixture.send(), 503, "INFERENCE_UNKNOWN")
                    self.assertEqual(fixture.provider_calls, 1)
                    self.assertEqual(fixture.ledger.begin_calls, 1)
                    self.assertEqual(fixture.ledger.unknown_calls, 1)
                    self.assertEqual(fixture.ledger.complete_calls, 0)
                    self.assertEqual(fixture.ledger.cache_flags, [context is None])

    def test_normal_unknown_marking_preserves_original_status_and_code(self):
        expected = {"provider": (503, "INFERENCE_UNKNOWN"), "runtime": (409, "GENERATION_TIMEOUT"),
                    "timeout": (409, "INFERENCE_UNKNOWN"), "local-contract": (409, "MODEL_OUTPUT_INVALID")}
        for branch in expected:
            for context in (None, {}):
                with self.subTest(branch=branch, context=context is not None):
                    fixture = Fixture(branch, context=context)
                    self.assert_private_response(fixture, fixture.send(), *expected[branch])
                    self.assertEqual(fixture.provider_calls, 1)
                    self.assertEqual(fixture.ledger.unknown_calls, 1)
                    self.assertEqual(fixture.ledger.complete_calls, 0)
                    self.assertEqual(fixture.ledger.cache_flags, [context is None])

    def test_unregistered_runtime_code_stays_generic_with_or_without_marker_failure(self):
        for failed_marker in (False, True):
            with self.subTest(failed_marker=failed_marker):
                fixture = Fixture("runtime", runtime_code="SYNTHETIC_PROVIDER_SECRET\r\nFORGED",
                                  secondary=SecretError("SYNTHETIC_STATE_SECRET") if failed_marker else None)
                self.assert_private_response(fixture, fixture.send(), 503 if failed_marker else 409, "INFERENCE_UNKNOWN")
                self.assertEqual(fixture.provider_calls, 1)
                self.assertEqual(fixture.ledger.unknown_calls, 1)
                self.assertEqual(fixture.ledger.complete_calls, 0)

    def test_context_limit_and_fixed_runtime_codes_keep_existing_error_results(self):
        for code in ("CONTEXT_LIMIT", "MODEL_UNAVAILABLE", "RESOURCE_LIMIT"):
            with self.subTest(code=code):
                fixture = Fixture("runtime", runtime_code=code, secondary=SecretError("SYNTHETIC_STATE_SECRET"))
                response = fixture.send()
                self.assertEqual(response.status_code, 200)
                self.assertEqual(response.content["kind"], "ERROR")
                self.assertEqual(response.content["error_code"], code)
                self.assertIs(response.content["retryable"], False)
                self.assertEqual(fixture.provider_calls, 1)
                self.assertEqual(fixture.ledger.unknown_calls, 0)
                self.assertEqual(fixture.ledger.complete_calls, 1)
                self.assertNotIn("SYNTHETIC_", json.dumps(response.content))

    def test_secondary_process_control_base_exceptions_are_not_swallowed(self):
        for control_type in (KeyboardInterrupt, SystemExit, SyntheticControl):
            for context in (None, {}):
                with self.subTest(control=control_type.__name__, context=context is not None):
                    control = control_type()
                    fixture = Fixture(context=context, secondary=control)
                    with self.assertRaises(control_type) as caught:
                        fixture.unknown_response(409, "INFERENCE_UNKNOWN")
                    self.assertIs(caught.exception, control)
                    self.assertEqual(fixture.ledger.unknown_calls, 1)
                    self.assertEqual(fixture.ledger.complete_calls, 0)
                    self.assertEqual(fixture.provider_calls, 0)
                    self.assertEqual(fixture.ledger.cache_flags, [context is None])

    def test_happy_answer_and_both_replay_results_never_mark_unknown(self):
        for replay in (None, "UNKNOWN", {"kind": "ANSWER", "reply_text": "Cached synthetic answer"}):
            with self.subTest(replay_type=type(replay).__name__):
                fixture = Fixture("answer", secondary=SecretError("SYNTHETIC_STATE_SECRET"), replay=replay)
                response = fixture.send()
                self.assertEqual(response.status_code, 409 if replay == "UNKNOWN" else 200)
                self.assertEqual(fixture.ledger.unknown_calls, 0)
                self.assertEqual(fixture.provider_calls, 1 if replay is None else 0)
                self.assertEqual(fixture.ledger.complete_calls, 1 if replay is None else 0)
                if replay == "UNKNOWN":
                    self.assertEqual(response.content, {"error_code": "INFERENCE_UNKNOWN"})
                else:
                    self.assertEqual(response.content["kind"], "ANSWER")

    def test_source_configuration_rejects_wrong_type_changed_bytes_and_reentry(self):
        for raw in (bytearray(_RAW), _RAW + b"\n"):
            with self.subTest(raw_type=type(raw).__name__):
                with self.assertRaises(ValueError):
                    _verified_spec(raw)
        with self.assertRaises(RuntimeError):
            configure_verified_app(_RAW)

"""Authenticated proposal-only service with explicit mock and local MLX modes."""
from __future__ import annotations

import asyncio
import hashlib
import sqlite3
import time
from dataclasses import dataclass
from contextlib import asynccontextmanager
from datetime import datetime
from pathlib import Path
from typing import Callable

from cryptography import x509
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from pydantic import ValidationError

from .contracts import ContractViolation, MAX_BODY_BYTES, TurnRequest, canonical, parse_json, validate
from .ledger import Ledger, LedgerFull, ReplayConflict
from .security import Unauthorized, verify_capability
from .local_runtime import LocalConfig, LocalRuntime, RuntimeFailure

MOCK_MANIFEST_HASH = hashlib.sha256(b"jarvis-m0-deterministic-mock-v1:no-model:no-tools:no-network").hexdigest()


@dataclass(frozen=True)
class Settings:
    ledger_path: Path
    cache_key: bytes
    core_keys: dict[str, Ed25519PublicKey]
    # Verified leaf certificate DER SHA256 values. HTTP headers are never used.
    core_certificate_fingerprints: frozenset[str] = frozenset()
    allow_loopback_mock_without_mtls: bool = False
    mode: str = "mock"
    max_concurrency: int = 4
    max_ledger_rows: int = 10000
    local_config: LocalConfig | None = None

    def __post_init__(self):
        if self.mode not in {"mock", "unavailable", "local_mlx"}:
            raise ValueError("unsupported provider")
        if self.mode == "local_mlx" and (self.local_config is None or self.allow_loopback_mock_without_mtls):
            raise ValueError("local inference requires private config and mTLS")
        if not self.core_keys or not (1 <= self.max_concurrency <= 4) or not (1 <= self.max_ledger_rows <= 10000):
            raise ValueError("invalid bounded service configuration")


def _peer_allowed(request, settings):
    if settings.allow_loopback_mock_without_mtls:
        return request.client is not None and request.client.host in {"127.0.0.1", "::1"}
    tls = request.scope.get("extensions", {}).get("tls", {})
    chain = tls.get("client_cert_chain", [])
    if not chain or tls.get("client_cert_error") is not None:
        return False
    try:
        leaf = x509.load_pem_x509_certificate(chain[0].encode("ascii"))
        return leaf.fingerprint(hashes.SHA256()).hex() in settings.core_certificate_fingerprints
    except (ValueError, TypeError, AttributeError):
        return False


async def mock_generate(turn: TurnRequest):
    """Explicit fixture commands, never inferred permissions or free-form execution."""
    if turn.user_input == "mock:unavailable":
        return {"kind": "ERROR", "error_code": "MODEL_UNAVAILABLE", "retryable": False}
    if turn.user_input == "mock:need-input":
        return {"kind": "NEED_INPUT", "question": "Which calendar should the mock proposal use?", "missing_fields": ["calendar_id"]}
    if turn.user_input.startswith("mock:propose "):
        arguments = parse_json(turn.user_input[len("mock:propose "):].encode())
        return {"kind": "TOOL_PROPOSALS", "summary": "Mock proposal only; nothing has been executed.",
                "proposals": [{"tool_id": "calendar.event.create", "tool_version": "1", "arguments": arguments}]}
    return {"kind": "ANSWER", "reply_text": "Mock response only. No trained model or external action was used.", "cited_source_ids": []}


def _usage(turn):
    # No tokenizer or inference engine ran: these are mock counters, not verified
    # provider billing. Core alone owns the budget reservation and settlement.
    return {"inference_request_id": turn.request_id, "model_manifest_hash": turn.model_manifest_hash,
            "input_tokens": 0, "output_tokens": 0, "compute_ms": 0,
            "peak_memory_bytes": 0, "reservation_id": turn.budget.reservation_id}


def _error(turn, code):
    return {"kind": "ERROR", "error_code": code, "retryable": False, "usage": _usage(turn)}


def _validate_output(output, turn, *, measured=None):
    # The model cannot mint service usage or authority fields. Strict schema also
    # rejects risk/approved/verified/owner fields nested inside tool arguments.
    if type(output) is not dict or "usage" in output:
        raise ContractViolation("provider cannot set usage")
    if measured is None and len(canonical(output)) > turn.budget.max_output_tokens:
        raise ContractViolation("output bound exceeded")
    usage = _usage(turn)
    if measured is not None:
        if output.get("kind") not in {"ANSWER", "NEED_INPUT", "TOOL_PROPOSALS"}:
            raise ContractViolation("invalid model output mode")
        for key in ("input_tokens", "output_tokens", "compute_ms", "peak_memory_bytes"):
            value = measured[key]
            if type(value) is not int or value < 0:
                raise ContractViolation("invalid measured usage")
            usage[key] = value
        if usage["input_tokens"] + 512 > 4096 or usage["input_tokens"] > turn.budget.max_input_tokens or usage["output_tokens"] > min(512, turn.budget.max_output_tokens):
            raise ContractViolation("measured budget exceeded")
    if getattr(turn, 'context', None) is not None:
        if output.get('kind') not in {'ANSWER', 'NEED_INPUT', 'ERROR'} or output.get('cited_source_ids', []) != []:
            raise ContractViolation('context output authority exceeded')
    complete = {**output, "usage": usage}
    validate("turn-response", complete)
    if complete["kind"] == "TOOL_PROPOSALS":
        for proposal in complete["proposals"]:
            if proposal["tool_id"] not in turn.allowed_tool_ids:
                raise ContractViolation("tool outside capability context")
            args = proposal["arguments"]
            if datetime.fromisoformat(args["end_at"].replace("Z", "+00:00")) <= datetime.fromisoformat(args["start_at"].replace("Z", "+00:00")):
                raise ContractViolation("invalid event interval")
    # Conservative UTF-8 byte bound guarantees an output upper bound without
    # pretending to have measured model-specific tokens. No network fallback.
    if measured is None and len(canonical(output)) > turn.budget.max_output_tokens:
        raise ContractViolation("output bound exceeded")
    return complete


def create_app(settings: Settings, *, generate: Callable = mock_generate, clock=time.time, runtime=None):
    local = (runtime or LocalRuntime(settings.local_config)) if settings.mode == "local_mlx" else None
    ledger = Ledger(settings.ledger_path, settings.cache_key, max_rows=settings.max_ledger_rows,
                    max_concurrency=3 if local else settings.max_concurrency)
    @asynccontextmanager
    async def lifespan(app):
        if local is not None:
            try:
                await local.start()
            except RuntimeFailure:
                pass  # Health reports unavailable; never silently use mock.
        try:
            yield
        finally:
            if local is not None:
                await local.close()

    app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)
    app.state.ledger = ledger
    app.state.mock_invocations = 0

    @app.get("/health/live")
    async def live():
        return {"status": "ok", "mode": settings.mode, "model_available": local.health()["ready"] if local else False}

    @app.get("/health/ready")
    async def ready():
        if local is None:
            return {"ready": False, "status": "MODEL_UNAVAILABLE", "model_id": "mock-v1", "model_manifest_hash": MOCK_MANIFEST_HASH}
        return local.health()

    @app.post("/internal/v1/turn")
    async def turn_endpoint(request: Request):
        def reject(status, code):
            return JSONResponse(status_code=status, content={"error_code": code}, headers={"Cache-Control": "no-store"})

        if not _peer_allowed(request, settings):
            return reject(401, "SERVICE_AUTH_REQUIRED")
        auth = request.headers.getlist("x-jarvis-capability")
        if len(auth) != 1:
            return reject(401, "SERVICE_AUTH_REQUIRED")
        if request.headers.get("content-type", "").split(";", 1)[0].lower() != "application/json" or request.headers.get("content-encoding"):
            return reject(415, "JSON_REQUIRED")
        raw = bytearray()
        try:
            # Enforce on streaming bytes, not merely attacker-supplied Content-Length.
            async with asyncio.timeout(5):
                async for chunk in request.stream():
                    if len(raw) + len(chunk) > MAX_BODY_BYTES:
                        return reject(413, "BODY_LIMIT")
                    raw.extend(chunk)
            body = parse_json(bytes(raw))
            turn = TurnRequest.from_wire(body)
        except TimeoutError:
            return reject(408, "BODY_TIMEOUT")
        except (ContractViolation, ValidationError):
            return reject(400, "CONTRACT_VIOLATION")
        try:
            claims = verify_capability(auth[0], body, settings.core_keys, clock())
        except Unauthorized:
            return reject(401, "INVALID_CAPABILITY")
        if turn.model_id != ("local-mlx-v1" if local else "mock-v1") or turn.prompt_revision != ("local-v1" if local else "mock-v1") or turn.model_manifest_hash != (local.manifest_hash if local else MOCK_MANIFEST_HASH):
            return reject(400, "MODEL_MANIFEST_MISMATCH")
        if local is None and len(turn.user_input.encode()) > turn.budget.max_input_tokens:
            return reject(429, "INPUT_BUDGET_EXCEEDED")
        expiry = datetime.fromisoformat(claims["expires_at"].replace("Z", "+00:00")).timestamp()
        duration = min(turn.budget.max_compute_ms / 1000, expiry - clock())
        if duration <= 0:
            return reject(401, "INVALID_CAPABILITY")
        try:
            replay = ledger.begin(claims, clock(), duration, cache_response=turn.context is None)
        except ReplayConflict:
            return reject(409, "REPLAY_CONFLICT")
        except LedgerFull:
            return reject(429, "RESOURCE_LIMIT")
        except Exception:
            # No generation starts unless durable reservation succeeds.
            return reject(503, "LEDGER_UNAVAILABLE")
        if replay == "UNKNOWN":
            return reject(409, "INFERENCE_UNKNOWN")
        if replay is not None:
            return JSONResponse(replay, headers={"Cache-Control": "no-store", "X-Jarvis-Replay": "true"})
        try:
            if settings.mode == "unavailable":
                response = _error(turn, "MODEL_UNAVAILABLE")
            elif local is not None:
                task = asyncio.create_task(local.generate(turn, expiry))
                try:
                    while not task.done():
                        if await request.is_disconnected():
                            task.cancel()
                            try:
                                await task
                            except asyncio.CancelledError:
                                pass
                            raise asyncio.CancelledError()
                        await asyncio.sleep(.02)
                    measured = await task
                    response = _validate_output(measured["output"], turn, measured=measured)
                finally:
                    if not task.done():
                        task.cancel()
                        try:
                            await task
                        except (asyncio.CancelledError, RuntimeFailure):
                            pass
            else:
                app.state.mock_invocations += 1
                async with asyncio.timeout(duration):
                    response = _validate_output(await generate(turn), turn)
        except RuntimeFailure as exc:
            if exc.code in {"MODEL_UNAVAILABLE", "CONTEXT_LIMIT", "RESOURCE_LIMIT"}:
                response = _error(turn, exc.code)
            else:
                # After generation starts, uncertain usage keeps the durable
                # reservation conservative, rather than returning invented zeros.
                ledger.unknown(claims, cache_response=turn.context is None)
                return reject(409, exc.code if exc.code in {"GENERATION_TIMEOUT", "CANCELLED", "MODEL_OUTPUT_INVALID"} else "INFERENCE_UNKNOWN")
        except (ContractViolation, ValidationError, ValueError, TypeError):
            if local is not None:
                ledger.unknown(claims, cache_response=turn.context is None)
                return reject(409, "MODEL_OUTPUT_INVALID")
            response = _error(turn, "CONTRACT_VIOLATION")
        except (TimeoutError, asyncio.CancelledError):
            ledger.unknown(claims, cache_response=turn.context is None)
            return reject(409, "INFERENCE_UNKNOWN")
        except Exception:
            ledger.unknown(claims, cache_response=turn.context is None)
            return reject(503, "INFERENCE_UNKNOWN")
        try:
            ledger.complete(claims, response, clock(), cache_response=turn.context is None)
        except (sqlite3.Error, ReplayConflict, ValueError):
            return reject(503, "INFERENCE_UNKNOWN")
        return JSONResponse(response, headers={"Cache-Control": "no-store"})

    return app

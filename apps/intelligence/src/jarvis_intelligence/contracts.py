"""Strict, bounded parsing and validation against the shared wire contracts."""
from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path
from typing import Annotated, Literal
from uuid import UUID
from datetime import datetime
from zoneinfo import ZoneInfo

from jsonschema import Draft202012Validator, FormatChecker
from pydantic import BaseModel, ConfigDict, Field

MAX_BODY_BYTES = 256 * 1024
MAX_CONTROL_BYTES = 16 * 1024
CONTROL_IDENTITY_FIELDS = (
    "revision", "owner_id", "request_id", "reservation_id", "run_id", "context_id",
    "purpose", "security_epoch", "origin_binding", "dispatch_commitment",
)
MAX_DEPTH = 16
CONTRACT_ROOT = Path(__file__).resolve().parents[4] / "packages" / "contracts"


class ContractViolation(ValueError):
    pass


def _pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ContractViolation("duplicate key")
        result[key] = value
    return result


def _reject_number(value):
    raise ContractViolation("only bounded integers are supported")


def _shape(value, depth=0):
    if depth > MAX_DEPTH:
        raise ContractViolation("JSON depth exceeded")
    if isinstance(value, str):
        value.encode("utf-8", errors="strict")
    elif type(value) is int:
        if abs(value) > 9007199254740991:
            raise ContractViolation("integer range exceeded")
    elif isinstance(value, dict):
        for key, item in value.items():
            _shape(key, depth + 1)
            _shape(item, depth + 1)
    elif isinstance(value, list):
        if len(value) > 1000:
            raise ContractViolation("array limit exceeded")
        for item in value:
            _shape(item, depth + 1)
    elif value is not None and type(value) is not bool:
        raise ContractViolation("unsupported JSON value")


def _scan_depth(text):
    depth, quoted, escaped = 0, False, False
    for char in text:
        if quoted:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                quoted = False
        elif char == '"':
            quoted = True
        elif char in "[{":
            depth += 1
            if depth > MAX_DEPTH:
                raise ContractViolation("JSON depth exceeded")
        elif char in "]}":
            depth -= 1


def parse_json(raw: bytes, limit=MAX_BODY_BYTES):
    if len(raw) > limit:
        raise ContractViolation("body size exceeded")
    try:
        text = raw.decode("utf-8")
        _scan_depth(text)
        value = json.loads(text, object_pairs_hook=_pairs,
                           parse_float=_reject_number, parse_constant=_reject_number)
        _shape(value)
        return value
    except (ValueError, UnicodeError, RecursionError) as exc:
        raise ContractViolation("invalid JSON") from exc


def canonical(value) -> bytes:
    """RFC 8785 serialization for the contract's integer-only JSON domain.

    Floats, non-I-JSON integers and lone surrogates fail closed. Object keys
    use UTF-16 ordering, including non-BMP keys from shared golden vectors.
    """
    _shape(value)

    def ordered(item):
        if isinstance(item, dict):
            return {key: ordered(item[key]) for key in sorted(item, key=lambda k: k.encode("utf-16-be"))}
        if isinstance(item, list):
            return [ordered(v) for v in item]
        return item

    return json.dumps(ordered(value), ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode("utf-8")


def body_hash(value) -> str:
    return hashlib.sha256(canonical(value)).hexdigest()


_FORMAT = FormatChecker()


@_FORMAT.checks("date-time", raises=(ValueError, TypeError))
def _timestamp(value):
    if not isinstance(value, str):
        return True
    # Bound RFC3339 offset components before the parser can normalize overflow.
    if not re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}[Tt][0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]+)?(?:[Zz]|[+-](?:[01][0-9]|2[0-3]):[0-5][0-9])", value):
        return False
    return datetime.fromisoformat(value.upper().replace("Z", "+00:00")).tzinfo is not None


@_FORMAT.checks("uuid", raises=(ValueError, TypeError))
def _uuid(value):
    return not isinstance(value, str) or str(UUID(value)) == value.lower()


_VALIDATORS = {}


def validate(name: str, value):
    if name not in _VALIDATORS:
        schema = json.loads((CONTRACT_ROOT / "schemas" / f"{name}.schema.json").read_text())
        Draft202012Validator.check_schema(schema)
        _VALIDATORS[name] = Draft202012Validator(schema, format_checker=_FORMAT)
    if not _VALIDATORS[name].is_valid(value):
        raise ContractViolation("contract violation")
    return value


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, frozen=True)


class Budget(StrictModel):
    reservation_id: str
    max_compute_ms: Annotated[int, Field(ge=1, le=60000)]
    max_input_tokens: Annotated[int, Field(ge=1, le=4096)]
    max_output_tokens: Annotated[int, Field(ge=1, le=512)]
    max_model_calls: Annotated[int, Field(ge=1, le=1)]


SafePositive = Annotated[int, Field(ge=1, le=9007199254740991)]


class ContextV1(StrictModel):
    id: str
    revision: Literal[1]
    conversation_id: str
    context_epoch: SafePositive
    history_epoch: SafePositive
    profile_version: SafePositive | None


class ProjectContext(StrictModel):
    id: str
    version: SafePositive


class MemorySource(StrictModel):
    source_id: str
    id: str
    version: SafePositive
    generation: SafePositive
    scope: Literal['GLOBAL', 'PROJECT']
    project_id: str | None
    kind: Literal['PROFILE', 'PREFERENCE']
    key: str
    value: Annotated[str, Field(min_length=1, max_length=2000)]
    confirmed_at: str
    updated_at: str


class HistorySource(StrictModel):
    source_id: str
    id: str
    run_id: str
    role: Literal['USER', 'ASSISTANT']
    trust: Literal['USER_DIRECT', 'MODEL_PROPOSAL']
    text: Annotated[str, Field(min_length=1, max_length=16000)]
    created_at: str


def validate_context(value):
    if 'context' not in value:
        return
    subset = {key: value[key] for key in ('context', 'project_context', 'memories', 'history')}
    if value.get('purpose') == 'STUDY_FEEDBACK':
        subset['feedback_source'] = value['feedback_source']
    for item, limit in ((value, MAX_BODY_BYTES), (subset, 196608),
                        (value['memories'], 131072), (value['history'], 65536)):
        if len(canonical(item)) > limit:
            raise ContractViolation('context byte bound exceeded')
    memories, history = value['memories'], value['history']
    for items, key in ((memories + history, 'source_id'), (memories, 'id'), (history, 'id')):
        if len({item[key] for item in items}) != len(items):
            raise ContractViolation('duplicate source identity')
    project = value['project_context']
    for item in memories:
        if len(item['value'].encode()) > 8000 or (item['scope'] == 'PROJECT' and
                (project is None or item['project_id'] != project['id'])):
            raise ContractViolation('invalid memory scope or size')
    runs = {value['run_id']}
    for offset in range(0, len(history), 2):
        user, assistant = history[offset:offset + 2]
        if (user['role'], user['trust'], assistant['role'], assistant['trust']) != ('USER', 'USER_DIRECT', 'ASSISTANT', 'MODEL_PROPOSAL'):
            raise ContractViolation('invalid history authority')
        if user['run_id'] != assistant['run_id'] or user['run_id'] in runs:
            raise ContractViolation('invalid history pair')
        runs.add(user['run_id'])
        if any(len(item['text'].encode()) > 64000 for item in (user, assistant)):
            raise ContractViolation('history byte bound exceeded')


class ControlDescriptor(StrictModel):
    revision: Literal[1]
    origin_binding: Annotated[str, Field(pattern=r"^[a-f0-9]{64}$", min_length=64, max_length=64)]
    dispatch_commitment: Annotated[str, Field(pattern=r"^[a-f0-9]{64}$", min_length=64, max_length=64)]
    security_epoch: Annotated[int, Field(ge=0, le=9007199254740991)]


def control_identity(body, claims):
    """Opaque Core HMACs are verified claims, never recomputed by Intelligence."""
    if "control" not in body:
        return None
    if (body.get("context", {}).get("revision") != 1 or
            body.get("model_id") != "local-mlx-v1" or body.get("prompt_revision") != "local-v1"):
        raise ContractViolation("invalid control context")
    value = {**body["control"], "owner_id": claims["owner_id"],
             "request_id": body["request_id"], "reservation_id": body["budget"]["reservation_id"],
             "run_id": body["run_id"], "context_id": body["context"]["id"],
             "purpose": body.get("purpose", "CONVERSATION")}
    validate("inference-control-request", {**value, "operation": "OBSERVE"})
    return value


class TurnFields(StrictModel):
    request_id: str
    run_id: str
    current_time: str
    timezone: Annotated[str, Field(min_length=1, max_length=80)]
    context: ContextV1 | None = None
    control: ControlDescriptor | None = None
    history: Annotated[list[HistorySource], Field(max_length=16)] = Field(default_factory=list)
    project_context: ProjectContext | None
    memories: Annotated[list[MemorySource], Field(max_length=20)]
    evidence: Annotated[list, Field(max_length=0)]
    tool_results: Annotated[list, Field(max_length=0)]
    allowed_tool_ids: Annotated[list[Literal["calendar.event.create"]], Field(max_length=1)]
    budget: Budget
    model_id: Literal["mock-v1", "local-mlx-v1"]
    model_manifest_hash: Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
    prompt_revision: Literal["mock-v1", "local-v1"]

    @classmethod
    def from_wire(cls, value):
        validate("turn-request", value)
        validate_context(value)
        # Select the discriminator before ever requiring a generic message.
        if value.get("purpose") == "STUDY_FEEDBACK":
            validate_feedback_source(value["feedback_source"])
            validate_feedback_identities(value)
            result = FeedbackTurnRequest.model_validate(value)
        else:
            result = TurnRequest.model_validate(value)
        try:
            ZoneInfo(result.timezone)
        except (ValueError, KeyError) as exc:
            raise ContractViolation("invalid timezone") from exc
        return result


def validate_feedback_source(value):
    validate("durable-study-feedback-source", value)
    questions = value["lesson"]["questions"]
    ids = [question["id"] for question in questions]
    if len(set(ids)) != 2 or value["question_id"] not in ids:
        raise ContractViolation("invalid feedback target")
    # Cross-domain execution/source identities must not alias. The selected
    # question_id is deliberately one of the original two question identities.
    targets = [value[key] for key in ("feedback_id", "study_session_id", "lesson_run_id", "input_id", "attempt_id")]
    if len(set(targets + ids)) != len(targets) + 2:
        raise ContractViolation("duplicate feedback identity")
    if len(value["answer"]["text"].encode("utf-8")) > 32000:
        raise ContractViolation("feedback answer byte bound exceeded")
    return value


def validate_feedback_identities(value):
    source = value['feedback_source']
    identities = [value['request_id'], value['run_id'], value['budget']['reservation_id'],
                  value['context']['id'], value['context']['conversation_id']]
    identities.extend(source[key] for key in ('feedback_id','study_session_id','lesson_run_id','input_id','attempt_id'))
    identities.extend(question['id'] for question in source['lesson']['questions'])
    if len(identities) != len(set(identities)):
        raise ContractViolation('aliased feedback execution identity')


class TurnRequest(TurnFields):
    purpose: Literal["CONVERSATION", "STUDY_LESSON"] = "CONVERSATION"
    user_input: Annotated[str, Field(min_length=1, max_length=16000)]


class FeedbackTurnRequest(TurnFields):
    purpose: Literal["STUDY_FEEDBACK"]
    feedback_source: dict
    context: ContextV1
    control: ControlDescriptor


def _computer_shape(value, depth=0):
    """Check an already-decoded finite JSON tree without changing M0 rules."""
    if depth > MAX_DEPTH:
        raise ContractViolation("computer JSON depth exceeded")
    if type(value) is str:
        value.encode("utf-8", errors="strict")
    elif type(value) is int:
        if abs(value) > 9007199254740991:
            raise ContractViolation("computer integer range exceeded")
    elif type(value) is float:
        from math import isfinite
        if not isfinite(value):
            raise ContractViolation("nonfinite computer number")
    elif type(value) is dict:
        for key, item in value.items():
            if type(key) is not str:
                raise ContractViolation("computer JSON keys must be strings")
            _computer_shape(key, depth + 1)
            _computer_shape(item, depth + 1)
    elif type(value) is list:
        if len(value) > 1000:
            raise ContractViolation("computer array limit exceeded")
        for item in value:
            _computer_shape(item, depth + 1)
    elif value is not None and type(value) is not bool:
        raise ContractViolation("unsupported computer JSON value")


def validate_computer_proposal(
    kind: Literal["session", "observe", "action"], value: dict,
) -> dict:
    """Validate proposal structure only; no wire parsing, hashing or authority.

    Callers must supply an already-decoded object. A valid object is not an
    authorized ComputerSession/action and is not a canonical approval payload.
    In particular, the existing integer-only canonical/body_hash functions must
    not be used as a fractional computer-proposal serialization profile.
    """
    if type(kind) is not str:
        raise ContractViolation("unknown computer proposal kind")
    schema = {
        "session": "computer-session",
        "observe": "computer-observe",
        "action": "computer-action",
    }.get(kind)
    if schema is None:
        raise ContractViolation("unknown computer proposal kind")
    if type(value) is not dict:
        raise ContractViolation("computer proposal must be an object")
    try:
        _computer_shape(value)
    except (UnicodeError, RecursionError) as exc:
        raise ContractViolation("invalid computer JSON") from exc
    return validate(schema, value)

_COMPUTER_WIRE_CODEC = None


def _computer_wire_codec():
    """Load only the shared source at CONTRACT_ROOT, without pyc/path pollution."""
    global _COMPUTER_WIRE_CODEC
    if _COMPUTER_WIRE_CODEC is None:
        from importlib.machinery import SourceFileLoader
        from importlib.util import module_from_spec, spec_from_file_location

        class SourceOnlyLoader(SourceFileLoader):
            def get_code(self, fullname):
                # Read/compile this .py directly: never read or write a pyc cache.
                return self.source_to_code(self.get_data(self.path), self.path)

        name = "_jarvis_intelligence_cu_wire_hash_v1"
        path = str(CONTRACT_ROOT / "cu_wire_hash_v1.py")
        try:
            loader = SourceOnlyLoader(name, path)
            spec = spec_from_file_location(name, path, loader=loader)
            if spec is None:
                raise ContractViolation("CU_WIRE_CODEC_UNAVAILABLE")
            module = module_from_spec(spec)
            loader.exec_module(module)
        except (OSError, ImportError, SyntaxError, UnicodeError):
            raise ContractViolation("CU_WIRE_CODEC_UNAVAILABLE") from None
        error = getattr(module, "CUWireError", None)
        if (not isinstance(error, type) or not issubclass(error, ValueError) or
                not callable(getattr(module, "require_profile", None)) or
                not callable(getattr(module, "admit_proposal_numbers", None))):
            raise ContractViolation("CU_WIRE_CODEC_UNAVAILABLE")
        _COMPUTER_WIRE_CODEC = module
    return _COMPUTER_WIRE_CODEC


def parse_computer_proposal(
    kind: Literal["session", "observe", "action"], raw: bytes, *,
    profile_id: str | None = None,
) -> dict:
    """Admit original CU proposal bytes, then apply the unchanged kind schema.

    An explicit known profile is required; None selects nothing. The codec keeps
    original number lexemes through all numeric admission before normalizing.
    The decoded-tree validate_computer_proposal API cannot replace this reader.
    Only proposal data is returned: no binding, hash, approval or action grant.
    """
    if type(kind) is not str or kind not in ("session", "observe", "action"):
        raise ContractViolation("UNSUPPORTED_KIND")
    if type(profile_id) is not str or profile_id != "cu-wire-hash-v1":
        raise ContractViolation("UNSUPPORTED_PROFILE")
    if type(raw) is not bytes:
        raise ContractViolation("INVALID_UTF8")
    if not 0 < len(raw) <= MAX_BODY_BYTES:
        raise ContractViolation("BODY_LIMIT")
    codec = _computer_wire_codec()
    try:
        codec.require_profile(profile_id)
        value = codec.admit_proposal_numbers(kind, raw)
    except codec.CUWireError as exc:
        code = exc.code
        if type(code) is not str or re.fullmatch(r"[A-Z][A-Z0-9_]{0,63}", code) is None:
            code = "INVALID_CU_WIRE"
        raise ContractViolation(code) from None
    try:
        return validate_computer_proposal(kind, value)
    except OSError:
        raise ContractViolation("CU_SCHEMA_UNAVAILABLE") from None

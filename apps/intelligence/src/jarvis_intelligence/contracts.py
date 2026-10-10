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
    # Require the RFC3339 time separator and an explicit timezone.
    if not re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}[Tt][0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]+)?(?:[Zz]|[+-][0-9]{2}:[0-9]{2})", value):
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


class TurnRequest(StrictModel):
    request_id: str
    run_id: str
    user_input: Annotated[str, Field(min_length=1, max_length=16000)]
    current_time: str
    timezone: Annotated[str, Field(min_length=1, max_length=80)]
    project_context: None
    memories: Annotated[list, Field(max_length=0)]
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
        result = cls.model_validate(value)
        try:
            ZoneInfo(result.timezone)
        except (ValueError, KeyError) as exc:
            raise ContractViolation("invalid timezone") from exc
        return result

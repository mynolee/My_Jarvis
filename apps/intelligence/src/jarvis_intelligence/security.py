"""Verify Core-issued Ed25519 capability; never trust a client identity header."""
from __future__ import annotations

import base64
import math
import re
from datetime import datetime, timezone, timedelta
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
from .contracts import ContractViolation, canonical, parse_json, validate, body_hash


class Unauthorized(ValueError):
    pass


def _decode(value):
    if not value or not re.fullmatch(r"[A-Za-z0-9_-]+", value):
        raise Unauthorized("invalid capability")
    return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))


def verify_capability(header: str, body: dict, keys: dict[str, Ed25519PublicKey], now: float):
    try:
        if len(header) > 8192:
            raise Unauthorized("invalid capability")
        payload, signature = header.split(".")
        raw = _decode(payload)
        claims = validate("service-capability", parse_json(raw, limit=4096))
        if canonical(claims) != raw:
            raise Unauthorized("noncanonical capability")
        keys[claims["key_id"]].verify(_decode(signature), raw)
        issued = datetime.fromisoformat(claims["issued_at"].replace("Z", "+00:00")).astimezone(timezone.utc).timestamp()
        expiry = datetime.fromisoformat(claims["expires_at"].replace("Z", "+00:00")).astimezone(timezone.utc).timestamp()
        if not (0 < expiry - issued <= 60 and issued - 30 <= now < expiry):
            raise Unauthorized("expired capability")
        expected = {
            "request_id": body["request_id"], "run_id": body["run_id"],
            "body_hash": body_hash(body), "model_id": body["model_id"],
            "reservation_id": body["budget"]["reservation_id"],
            "max_model_calls": body["budget"]["max_model_calls"],
            "max_output_tokens": body["budget"]["max_output_tokens"],
        }
        if any(claims[key] != value for key, value in expected.items()):
            raise Unauthorized("capability binding mismatch")
        return claims
    except (KeyError, ValueError, TypeError, InvalidSignature, ContractViolation) as exc:
        raise Unauthorized("invalid capability") from exc


def verify_control_capability(header: str, body: dict, keys: dict[str, Ed25519PublicKey], now: float):
    """A safety capability can neither generate nor borrow the turn audience.

    Compare UTC datetimes, preserving the wire's microsecond precision. Future
    issuance is refused without the ordinary generation token's tolerance.
    """
    try:
        if not isinstance(header, str) or len(header) > 8192:
            raise Unauthorized("invalid capability")
        validate("inference-control-request", body)
        payload, signature = header.split(".")
        raw = _decode(payload)
        claims = validate("inference-control-capability", parse_json(raw, limit=4096))
        if canonical(claims) != raw:
            raise Unauthorized("noncanonical capability")
        keys[claims["key_id"]].verify(_decode(signature), raw)
        issued = datetime.fromisoformat(claims["issued_at"].replace("Z", "+00:00"))
        expiry = datetime.fromisoformat(claims["expires_at"].replace("Z", "+00:00"))
        # Never round a receipt just before issued_at upward into authority.
        # The signed wire precision is microseconds, so floor the observed
        # subsecond value conservatively instead of datetime's nearest rounding.
        whole_seconds = math.floor(now)
        observed = (datetime.fromtimestamp(whole_seconds, timezone.utc) +
                    timedelta(microseconds=math.floor((now - whole_seconds) * 1_000_000)))
        if not (timedelta(0) < expiry - issued <= timedelta(seconds=60) and issued <= observed < expiry):
            raise Unauthorized("expired capability")
        expected = {**body, "body_hash": body_hash(body), "max_model_calls": 0}
        if any(claims[key] != value for key, value in expected.items()):
            raise Unauthorized("capability binding mismatch")
        return claims
    except (KeyError, ValueError, TypeError, OverflowError, OSError, InvalidSignature, ContractViolation) as exc:
        raise Unauthorized("invalid capability") from exc

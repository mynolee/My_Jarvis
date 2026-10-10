"""Verify Core-issued Ed25519 capability; never trust a client identity header."""
from __future__ import annotations

import base64
import re
from datetime import datetime, timezone
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

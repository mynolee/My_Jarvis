"""Wire-only synthetic oracle; signatures, TLS, workers and SQL require integration tests."""
from datetime import datetime
from functools import lru_cache
from pathlib import Path
import hashlib
import sys
from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from canonical import canonical_bytes, loads

IDENTITY = ('revision', 'owner_id', 'request_id', 'reservation_id', 'run_id', 'context_id',
            'purpose', 'security_epoch', 'origin_binding', 'dispatch_commitment')


@lru_cache(maxsize=None)
def validator(name):
    schema = loads((ROOT / 'schemas' / f'inference-control-{name}.schema.json').read_text())
    Draft202012Validator.check_schema(schema)
    return Draft202012Validator(schema, format_checker=FormatChecker())


def valid(name, value):
    try:
        encoded = canonical_bytes(value)
        return len(encoded) <= 16384 and validator(name).is_valid(value)
    except (ValueError, TypeError, UnicodeError):
        return False


def parse(name, raw):
    if len(raw.encode('utf-8')) > 16384:
        raise ValueError('BODY_LIMIT')
    value = loads(raw)
    if not valid(name, value):
        raise ValueError('INVALID_CONTROL')
    return value


def bound_response(value, request):
    return valid('response', value) and valid('request', request) and all(value[k] == request[k] for k in IDENTITY)


def capability_binding(claims, request, now):
    """Structural/time binding only. This never asserts a valid signature."""
    if not valid('capability', claims) or not valid('request', request):
        return False
    if any(claims[k] != request[k] for k in (*IDENTITY, 'operation')):
        return False
    if claims['body_hash'] != hashlib.sha256(canonical_bytes(request)).hexdigest():
        return False
    issued, expiry = (datetime.fromisoformat(claims[k]) for k in ('issued_at', 'expires_at'))
    return 0 < (expiry - issued).total_seconds() <= 60 and issued <= now < expiry


def same_fact(first, replay):
    """Producer fact identity does not contain the consumer's later observation time."""
    return (valid('response', first) and valid('response', replay)
            and first['state'] == replay['state'] == 'TERMINAL'
            and all(first[k] == replay[k] for k in (*IDENTITY, 'ledger_instance_id', 'terminal')))


def route_ok(path, request):
    return valid('request', request) and path == {
        'FENCE_OR_STOP': '/internal/v1/inference-control',
        'OBSERVE': '/internal/v1/inference-control/observe',
    }[request['operation']]

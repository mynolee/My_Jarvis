"""RFC 8785 canonicalization for the deliberately integer-only M0 domain.

This is not a general floating-point JCS implementation. Rejecting floats,
unsafe integers, duplicate keys, and lone surrogates is part of the contract.
"""
import hashlib
import json

MAX_INTEGER = 9007199254740991

def _pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('duplicate JSON key')
        result[key] = value
    return result

def loads(raw):
    value = json.loads(raw, object_pairs_hook=_pairs,
                       parse_constant=lambda _: (_ for _ in ()).throw(ValueError('nonfinite number')))
    canonical_bytes(value)
    return value

def canonical_bytes(value):
    def encode(item):
        if item is None or isinstance(item, bool):
            return json.dumps(item)
        if isinstance(item, int):
            if abs(item) > MAX_INTEGER:
                raise ValueError('integer outside interoperable range')
            return str(item)
        if isinstance(item, str):
            item.encode('utf-8', errors='strict')
            return json.dumps(item, ensure_ascii=False)
        if isinstance(item, list):
            return '[' + ','.join(encode(x) for x in item) + ']'
        if isinstance(item, dict):
            if not all(isinstance(k, str) for k in item):
                raise ValueError('non-string key')
            return '{' + ','.join(encode(k) + ':' + encode(item[k])
                                  for k in sorted(item, key=lambda k: k.encode('utf-16be'))) + '}'
        raise ValueError('unsupported JSON value')
    return encode(value).encode('utf-8')

def sha256(value):
    return hashlib.sha256(canonical_bytes(value)).hexdigest()

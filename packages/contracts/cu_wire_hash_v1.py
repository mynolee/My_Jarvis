"""CU-only, bounded wire/JCS and exact coordinate primitives.

This module has no I/O, policy selection, approval issuance, driver lookup, or
live integration. Generic JCS checksums are encoding results, not approvals.
Callers must still apply the original proposal schema and registered semantic,
region/mask, driver-bound and fresh-observation contracts. A decoded tree cannot
establish original-wire admission; use parse_wire/admit_proposal_numbers first.

Number emission searches exact binary64 rounding intervals for the shortest
decimal significand, choosing the closest decimal and even significand on a tie
(ECMA-262 Number::toString). It never delegates spelling to repr/json/format.
"""
from __future__ import annotations

import hashlib
import math
import re
import struct
from fractions import Fraction

PROFILE_ID = "cu-wire-hash-v1"
DOMAIN = "JARVIS.CU.APPROVAL"
MAP_VERSION = "cu-pixel-map-v1"
MAX_BODY_BYTES = 256 * 1024
MAX_DEPTH = 16
MAX_ARRAY_ITEMS = 1000
MAX_SAFE_INTEGER = 9007199254740991
_NUMBER = re.compile(r"-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?\Z")
_SIGNED = re.compile(r"(?:0|-?[1-9][0-9]*)\Z")
_POSITIVE = re.compile(r"[1-9][0-9]*\Z")


class CUWireError(ValueError):
    """A fixed classification; no input data, path or exception text is exposed."""
    def __init__(self, code: str):
        self.code = code
        super().__init__(code)


def _fail(code):
    raise CUWireError(code) from None


class NumberToken:
    """Immutable lexeme; no module-registration-dependent dataclass machinery."""
    __slots__ = ("_lexeme",)

    def __init__(self, lexeme):
        if (type(lexeme) is not str or not lexeme or
                len(lexeme) > MAX_BODY_BYTES or _NUMBER.fullmatch(lexeme) is None):
            _fail("INVALID_JSON_NUMBER")
        object.__setattr__(self, "_lexeme", lexeme)

    @property
    def lexeme(self):
        return self._lexeme

    def __setattr__(self, name, value):
        _fail("IMMUTABLE_NUMBER_TOKEN")

    def __eq__(self, other):
        return type(other) is NumberToken and self.lexeme == other.lexeme

    def __hash__(self):
        return hash(self.lexeme)

    def __copy__(self):
        return self

    def __deepcopy__(self, memo):
        return self


def _token(value):
    if type(value) is NumberToken:
        return value
    if type(value) is str:
        return NumberToken(value)
    _fail("INVALID_JSON_NUMBER")


def _digits_int(text):
    # Avoid host int<->decimal digit-limit dependence. Input bytes already bound.
    result = 0
    for start in range(0, len(text), 9):
        chunk = text[start:start + 9]
        result = result * 10 ** len(chunk) + int(chunk)
    return result


def _decimal_parts(value):
    text = _token(value).lexeme
    negative = text.startswith("-")
    if negative:
        text = text[1:]
    exponent = 0
    lower = text.lower()
    if "e" in lower:
        text, exp = lower.split("e")
        sign = -1 if exp.startswith("-") else 1
        exp = exp.lstrip("+-")
        # Saturation lies beyond all possible significand/fraction lengths.
        cap = MAX_BODY_BYTES + 1000
        for digit in exp:
            exponent = min(cap, exponent * 10 + ord(digit) - 48)
        exponent *= sign
    if "." in text:
        whole, fraction = text.split(".")
        exponent -= len(fraction)
        text = whole + fraction
    digits = text.lstrip("0") or "0"
    return negative, digits, exponent


def exact_integer(token, minimum=-MAX_SAFE_INTEGER, maximum=MAX_SAFE_INTEGER):
    """Validate mathematical integrality/safety before any binary64 conversion."""
    if (type(minimum) is not int or type(maximum) is not int or
            minimum < -MAX_SAFE_INTEGER or maximum > MAX_SAFE_INTEGER or
            minimum > maximum):
        _fail("INVALID_INTEGER_BOUNDS")
    negative, digits, exponent = _decimal_parts(token)
    if digits == "0":
        value = 0
    else:
        if exponent < 0:
            remove = -exponent
            if remove >= len(digits) or any(c != "0" for c in digits[-remove:]):
                _fail("NOT_EXACT_INTEGER")
            digits = digits[:-remove]
            exponent = 0
        if len(digits) + exponent > 16:
            _fail("UNSAFE_INTEGER")
        value = _digits_int(digits) * 10 ** exponent
        if negative:
            value = -value
    if abs(value) > MAX_SAFE_INTEGER:
        _fail("UNSAFE_INTEGER")
    if not minimum <= value <= maximum:
        _fail("INTEGER_OUT_OF_RANGE")
    return value


def _nearest_even(n, d):
    q, r = divmod(n, d)
    return q + (2 * r > d or (2 * r == d and q % 2 == 1))


def _ratio_to_bits(n, d, negative=False):
    """Exact rational -> binary64, one nearest/even rounding operation."""
    sign = (1 << 63) if negative else 0
    if n == 0:
        return sign
    exponent = n.bit_length() - d.bit_length()
    below_power = n < (d << exponent) if exponent >= 0 else (n << -exponent) < d
    if below_power:
        exponent -= 1
    if exponent > 1023:
        _fail("NONFINITE_BINARY64")
    if exponent < -1022:
        significand = _nearest_even(n << 1074, d)
        return sign | significand  # 2**52 naturally becomes minimum normal.
    shift = 52 - exponent
    significand = (_nearest_even(n << shift, d) if shift >= 0 else
                   _nearest_even(n, d << -shift))
    if significand == 1 << 53:
        significand >>= 1
        exponent += 1
    if exponent > 1023:
        _fail("NONFINITE_BINARY64")
    return sign | ((exponent + 1023) << 52) | (significand - (1 << 52))


def _token_bits(token):
    negative, digits, exponent = _decimal_parts(token)
    if digits == "0":
        return (1 << 63) if negative else 0
    order = len(digits) + exponent - 1
    if order > 308:
        _fail("NONFINITE_BINARY64")
    if order < -324:
        return (1 << 63) if negative else 0
    coefficient = _digits_int(digits)
    n, d = ((coefficient * 10 ** exponent, 1) if exponent >= 0 else
            (coefficient, 10 ** -exponent))
    return _ratio_to_bits(n, d, negative)


def binary64_value(token):
    """Correctly rounded value; original application checks are separate."""
    return struct.unpack(">d", _token_bits(token).to_bytes(8, "big"))[0]


def _check_coordinate_decimal(token):
    """Bounds-only admission pass: never convert even an earlier valid token."""
    negative, digits, exponent = _decimal_parts(token)
    if digits != "0":
        order = len(digits) + exponent - 1
        above_one = order > 0 or (order == 0 and
                    (digits[0] != "1" or any(c != "0" for c in digits[1:])))
        if negative or above_one:
            _fail("COORDINATE_ORIGINAL_DECIMAL_OUT_OF_RANGE")


def coordinate_value(token):
    _check_coordinate_decimal(token)
    return binary64_value(token)


def _unicode_scalar(text):
    for char in text:
        point = ord(char)
        if 0xD800 <= point <= 0xDFFF:
            _fail("INVALID_UNICODE")
        if 0xFDD0 <= point <= 0xFDEF or point & 0xFFFF in (0xFFFE, 0xFFFF):
            _fail("IJSON_NONCHARACTER")
    return text


class _Reader:
    def __init__(self, raw):
        if type(raw) is not bytes:
            _fail("INVALID_UTF8")
        if not 0 < len(raw) <= MAX_BODY_BYTES:
            _fail("BODY_LIMIT")
        if raw.startswith(b"\xef\xbb\xbf"):
            _fail("BOM")
        try:
            decoded = raw.decode("utf-8", "strict")
        except UnicodeError:
            decoded = None
        if decoded is None:
            _fail("INVALID_UTF8")
        self.text = decoded
        self.index = 0

    def whitespace(self):
        while self.index < len(self.text) and self.text[self.index] in " \t\r\n":
            self.index += 1

    def string(self):
        self.index += 1
        result = []
        while self.index < len(self.text):
            char = self.text[self.index]
            self.index += 1
            if char == '"':
                return _unicode_scalar("".join(result))
            if ord(char) < 0x20:
                _fail("INVALID_JSON")
            if char != "\\":
                result.append(char)
                continue
            if self.index >= len(self.text):
                _fail("INVALID_JSON")
            escaped = self.text[self.index]
            self.index += 1
            simple = {'"': '"', "\\": "\\", "/": "/", "b": "\b",
                      "f": "\f", "n": "\n", "r": "\r", "t": "\t"}
            if escaped in simple:
                result.append(simple[escaped])
            elif escaped == "u":
                unit = self.hex4()
                if 0xD800 <= unit <= 0xDBFF:
                    if self.text[self.index:self.index + 2] != "\\u":
                        _fail("INVALID_UNICODE")
                    self.index += 2
                    low = self.hex4()
                    if not 0xDC00 <= low <= 0xDFFF:
                        _fail("INVALID_UNICODE")
                    unit = 0x10000 + ((unit - 0xD800) << 10) + low - 0xDC00
                elif 0xDC00 <= unit <= 0xDFFF:
                    _fail("INVALID_UNICODE")
                result.append(chr(unit))
            else:
                _fail("INVALID_JSON")
        _fail("INVALID_JSON")

    def hex4(self):
        text = self.text[self.index:self.index + 4]
        if len(text) != 4 or any(c not in "0123456789abcdefABCDEF" for c in text):
            _fail("INVALID_JSON")
        self.index += 4
        return int(text, 16)

    def value(self, depth=0):
        if depth > MAX_DEPTH:
            _fail("DEPTH_LIMIT")
        self.whitespace()
        if self.index >= len(self.text):
            _fail("INVALID_JSON")
        char = self.text[self.index]
        if char == '"':
            return self.string()
        if char == "{":
            self.index += 1
            result = {}
            self.whitespace()
            if self.text[self.index:self.index + 1] == "}":
                self.index += 1
                return result
            while True:
                self.whitespace()
                if self.text[self.index:self.index + 1] != '"':
                    _fail("INVALID_JSON")
                if depth + 1 > MAX_DEPTH:
                    _fail("DEPTH_LIMIT")
                key = self.string()
                if key in result:
                    _fail("DUPLICATE_KEY")
                self.whitespace()
                if self.text[self.index:self.index + 1] != ":":
                    _fail("INVALID_JSON")
                self.index += 1
                result[key] = self.value(depth + 1)
                self.whitespace()
                delimiter = self.text[self.index:self.index + 1]
                self.index += 1
                if delimiter == "}":
                    return result
                if delimiter != ",":
                    _fail("INVALID_JSON")
        if char == "[":
            self.index += 1
            result = []
            self.whitespace()
            if self.text[self.index:self.index + 1] == "]":
                self.index += 1
                return result
            while True:
                if len(result) >= MAX_ARRAY_ITEMS:
                    _fail("ARRAY_LIMIT")
                result.append(self.value(depth + 1))
                self.whitespace()
                delimiter = self.text[self.index:self.index + 1]
                self.index += 1
                if delimiter == "]":
                    return result
                if delimiter != ",":
                    _fail("INVALID_JSON")
        for literal, value in (("true", True), ("false", False), ("null", None)):
            if self.text.startswith(literal, self.index):
                self.index += len(literal)
                return value
        start = self.index
        while self.index < len(self.text) and self.text[self.index] not in " \t\r\n,]}":
            self.index += 1
        if self.index == start:
            _fail("INVALID_JSON")
        return NumberToken(self.text[start:self.index])


def parse_wire(raw):
    """One bounded UTF-8 JSON text -> a fresh tree retaining NumberTokens."""
    reader = _Reader(raw)
    result = reader.value()
    reader.whitespace()
    if reader.index != len(reader.text):
        _fail("TRAILING_VALUE")
    return result


def normalize_numbers(value, _depth=0):
    """Copy, never mutate, a bounded JSON/token tree into binary64 values."""
    if _depth > MAX_DEPTH:
        _fail("DEPTH_LIMIT")
    if type(value) is NumberToken:
        return binary64_value(value)
    if value is None or type(value) in (bool, str):
        if type(value) is str:
            if len(value) > MAX_BODY_BYTES:
                _fail("BODY_LIMIT")
            _unicode_scalar(value)
        return value
    if type(value) in (int, float):
        if type(value) is float and not math.isfinite(value):
            _fail("NONFINITE_BINARY64")
        if type(value) is int:
            if value.bit_length() > MAX_BODY_BYTES * 4:
                _fail("BODY_LIMIT")
            bits = _ratio_to_bits(abs(value), 1, value < 0)
            return struct.unpack(">d", bits.to_bytes(8, "big"))[0]
        return value
    if type(value) is list:
        if len(value) > MAX_ARRAY_ITEMS:
            _fail("ARRAY_LIMIT")
        return [normalize_numbers(item, _depth + 1) for item in value]
    if type(value) is dict:
        result = {}
        for key, item in value.items():
            if type(key) is not str:
                _fail("INVALID_JSON_TYPE")
            normalize_numbers(key, _depth + 1)
            result[key] = normalize_numbers(item, _depth + 1)
        return result
    _fail("INVALID_JSON_TYPE")


def _bits_fraction(bits):
    exponent, fraction = bits >> 52, bits & ((1 << 52) - 1)
    if exponent == 0:
        return Fraction(fraction, 1 << 1074)
    significand = (1 << 52) | fraction
    shift = exponent - 1023 - 52
    return Fraction(significand << shift, 1) if shift >= 0 else Fraction(significand, 1 << -shift)


def _power10(exponent):
    return Fraction(10 ** exponent, 1) if exponent >= 0 else Fraction(1, 10 ** -exponent)


def _ceil(value):
    return -((-value.numerator) // value.denominator)


def _shortest_decimal(bits):
    value = _bits_fraction(bits)
    before = _bits_fraction(bits - 1)
    after = (_bits_fraction(bits + 1) if bits < 0x7FEFFFFFFFFFFFFF else
             Fraction(1 << 1024, 1))
    lower, upper = (before + value) / 2, (value + after) / 2
    inclusive = bits % 2 == 0
    order = len(str(value.numerator)) - len(str(value.denominator))
    while value < _power10(order):
        order -= 1
    while value >= _power10(order + 1):
        order += 1
    for digits in range(1, 18):
        candidates = []
        # A rounding interval may straddle a decimal power (e.g. 1e23).
        for exponent in range(order - digits, order - digits + 3):
            scale = _power10(exponent)
            low, high = lower / scale, upper / scale
            first, last = _ceil(low), high.numerator // high.denominator
            if not inclusive and low.denominator == 1:
                first += 1
            if not inclusive and high.denominator == 1:
                last -= 1
            first, last = max(first, 10 ** (digits - 1)), min(last, 10 ** digits - 1)
            if first > last:
                continue
            scaled = value / scale
            nearest = _nearest_even(scaled.numerator, scaled.denominator)
            significand = max(first, min(last, nearest))
            distance = abs(Fraction(significand) * scale - value)
            candidates.append((distance, significand % 2, significand, exponent))
        if candidates:
            _, _, significand, exponent = min(candidates)
            return str(significand), exponent
    _fail("NUMBER_FORMAT_UNSUPPORTED")


def format_binary64(value):
    """RFC8785/ECMAScript spelling of a finite binary64 (including -0)."""
    if type(value) is not float or not math.isfinite(value):
        _fail("NONFINITE_BINARY64")
    bits = int.from_bytes(struct.pack(">d", value), "big")
    negative, magnitude = bool(bits >> 63), bits & ((1 << 63) - 1)
    if magnitude == 0:
        return "0"
    digits, exponent = _shortest_decimal(magnitude)
    point = len(digits) + exponent
    if len(digits) <= point <= 21:
        text = digits + "0" * (point - len(digits))
    elif 0 < point <= 21:
        text = digits[:point] + "." + digits[point:]
    elif -6 < point <= 0:
        text = "0." + "0" * -point + digits
    else:
        scientific = point - 1
        text = digits[0] + ("." + digits[1:] if len(digits) > 1 else "")
        text += "e" + ("+" if scientific >= 0 else "-") + str(abs(scientific))
    return ("-" if negative else "") + text


def _string_bytes(value):
    _unicode_scalar(value)
    parts = ['"']
    escapes = {'"': '\\"', "\\": "\\\\", "\b": "\\b", "\t": "\\t",
               "\n": "\\n", "\f": "\\f", "\r": "\\r"}
    for char in value:
        if char in escapes:
            parts.append(escapes[char])
        elif ord(char) < 0x20:
            parts.append("\\u00" + "0123456789abcdef"[ord(char) >> 4] +
                         "0123456789abcdef"[ord(char) & 15])
        else:
            parts.append(char)
    parts.append('"')
    return "".join(parts).encode("utf-8", "strict")


def jcs_bytes(value):
    """Generic encoding only. Does not imply CU schema/semantic admission."""
    value = normalize_numbers(value)
    output = bytearray()

    def emit(part):
        if len(output) + len(part) > MAX_BODY_BYTES:
            _fail("BODY_LIMIT")
        output.extend(part)

    def walk(item):
        if item is None:
            emit(b"null")
        elif type(item) is bool:
            emit(b"true" if item else b"false")
        elif type(item) is str:
            emit(_string_bytes(item))
        elif type(item) is float:
            emit(format_binary64(item).encode("ascii"))
        elif type(item) is list:
            emit(b"[")
            for index, child in enumerate(item):
                if index:
                    emit(b",")
                walk(child)
            emit(b"]")
        elif type(item) is dict:
            emit(b"{")
            for index, key in enumerate(sorted(item, key=lambda s: s.encode("utf-16-be"))):
                if index:
                    emit(b",")
                emit(_string_bytes(key))
                emit(b":")
                walk(item[key])
            emit(b"}")
        else:
            _fail("INVALID_JSON_TYPE")

    walk(value)
    return bytes(output)


def jcs_sha256(value):
    return hashlib.sha256(jcs_bytes(value)).hexdigest()


def canonicalize_wire(raw):
    return jcs_bytes(parse_wire(raw))


def admit_proposal_numbers(kind, raw):
    """Original numeric-field checks only; caller MUST apply unchanged schema.

    This does not accept a decoded dict as evidence of original-wire checks.
    Missing/wrong nonnumeric fields remain the original schema's responsibility.
    """
    if kind not in ("session", "observe", "action") or type(kind) is not str:
        _fail("UNSUPPORTED_KIND")
    value = parse_wire(raw)
    if type(value) is not dict:
        _fail("INVALID_PROPOSAL")
    if kind == "session":
        for key, low, high in (("duration_seconds", 1, 600), ("max_actions", 1, 40),
                               ("max_frames", 0, 60)):
            if key in value:
                exact_integer(value[key], low, high)
    if kind == "action" and type(value.get("pointer")) is dict:
        pointer = value["pointer"]
        for key in ("x", "y", "end_x", "end_y"):
            if key in pointer and pointer[key] is not None:
                _check_coordinate_decimal(pointer[key])
        for key in ("delta_x", "delta_y"):
            if key in pointer and pointer[key] is not None:
                exact_integer(pointer[key], -1000, 1000)
    return normalize_numbers(value)


def require_profile(profile_id):
    if type(profile_id) is not str or profile_id != PROFILE_ID:
        _fail("UNSUPPORTED_PROFILE")


def require_map_version(map_version):
    if type(map_version) is not str or map_version != MAP_VERSION:
        _fail("UNSUPPORTED_MAP_VERSION")


def _safe_integer(value, positive=False):
    if (type(value) is not int or abs(value) > MAX_SAFE_INTEGER or
            (positive and value <= 0)):
        _fail("INVALID_GEOMETRY")
    return value


def pixel_index(token, size):
    """Public size is W/H, not span. Original decimal check precedes float."""
    size = _safe_integer(size, positive=True)
    value = coordinate_value(token)
    numerator, denominator = value.as_integer_ratio()
    return (2 * numerator * (size - 1) + denominator) // (2 * denominator)


def quantize_native(numerator, denominator):
    if type(numerator) is not int or type(denominator) is not int or denominator <= 0:
        _fail("INVALID_RATIONAL")
    value = (2 * numerator + denominator) // (2 * denominator)
    if abs(value) > MAX_SAFE_INTEGER:
        _fail("UNSAFE_INTEGER")
    return value


def _rational(value):
    if type(value) is not dict or set(value) != {"numerator", "denominator"}:
        _fail("INVALID_RATIONAL")
    n, d = value["numerator"], value["denominator"]
    if (type(n) is not str or type(d) is not str or
            not 0 < len(n) + len(d) <= MAX_BODY_BYTES or
            _SIGNED.fullmatch(n) is None or _POSITIVE.fullmatch(d) is None):
        _fail("INVALID_RATIONAL")
    numerator = _digits_int(n.lstrip("-")) * (-1 if n.startswith("-") else 1)
    denominator = _digits_int(d)
    if math.gcd(numerator, denominator) != 1:
        _fail("INVALID_RATIONAL")
    return Fraction(numerator, denominator)


def _int_decimal(value):
    if value == 0:
        return "0"
    negative, value = value < 0, abs(value)
    chunks = []
    while value:
        value, chunk = divmod(value, 1000000000)
        chunks.append(str(chunk))
    text = chunks.pop() + "".join(part.zfill(9) for part in reversed(chunks))
    return ("-" if negative else "") + text


def _rational_object(value):
    numerator, denominator = _int_decimal(value.numerator), _int_decimal(value.denominator)
    if len(numerator) + len(denominator) > MAX_BODY_BYTES:
        _fail("BODY_LIMIT")
    return {"numerator": numerator, "denominator": denominator}


def inspect_affine_pixel(pixel, matrix):
    """Mathematical probe only; derive inverse from the forward matrix.

    This proves only the mathematical same-pixel property. It does not validate
    a registered mapping/unit, authorized mask, driver range narrower than safe
    integers, fresh capture, DPI contract or executable route.
    """
    if type(pixel) is not tuple or len(pixel) != 2:
        _fail("INVALID_GEOMETRY")
    x, y = (_safe_integer(v) for v in pixel)
    if type(matrix) is not list or len(matrix) != 6:
        _fail("COORDINATE_MAPPING_UNSUPPORTED")
    coefficients = [_rational(value) for value in matrix]
    if sum(len(value["numerator"]) + len(value["denominator"]) for value in matrix) > MAX_BODY_BYTES:
        _fail("BODY_LIMIT")
    a, b, c, d, e, f = coefficients
    determinant = a * e - b * d
    if determinant == 0:
        _fail("COORDINATE_MAPPING_UNSUPPORTED")
    forward_x, forward_y = a * x + b * y + c, d * x + e * y + f
    native_x = quantize_native(forward_x.numerator, forward_x.denominator)
    native_y = quantize_native(forward_y.numerator, forward_y.denominator)
    inverse_x = (e * (native_x - c) - b * (native_y - f)) / determinant
    inverse_y = (a * (native_y - f) - d * (native_x - c)) / determinant
    effective_x = quantize_native(inverse_x.numerator, inverse_x.denominator)
    effective_y = quantize_native(inverse_y.numerator, inverse_y.denominator)
    return {
        "native_args": {"x": native_x, "y": native_y},
        "inverse_capture_point": {"x": _rational_object(inverse_x),
                                  "y": _rational_object(inverse_y)},
        "effective_capture_pixel": {"x": effective_x, "y": effective_y},
        "same_capture_pixel": (effective_x, effective_y) == (x, y),
    }


def resolve_affine_pixel(pixel, matrix):
    """Reject mapping loss; the probe result never establishes authority."""
    result = inspect_affine_pixel(pixel, matrix)
    if not result["same_capture_pixel"]:
        _fail("COORDINATE_MAPPING_UNSUPPORTED")
    return {key: result[key] for key in ("native_args", "inverse_capture_point",
                                       "effective_capture_pixel")}


def resolve_pointer(pointer, capture_size, region_crop, matrix):
    """Resolve start/end through a bounded crop and exact same-pixel mapping."""
    if (type(pointer) is not dict or
            type(capture_size) is not dict or set(capture_size) != {"width", "height"} or
            type(region_crop) is not dict or set(region_crop) != {"x", "y", "width", "height"}):
        _fail("INVALID_GEOMETRY")
    cw, ch = (_safe_integer(capture_size[k], True) for k in ("width", "height"))
    ox, oy = (_safe_integer(region_crop[k]) for k in ("x", "y"))
    width, height = (_safe_integer(region_crop[k], True) for k in ("width", "height"))
    if ox < 0 or oy < 0 or ox + width > cw or oy + height > ch:
        _fail("INVALID_GEOMETRY")

    def point(x_token, y_token):
        px, py = pixel_index(x_token, width), pixel_index(y_token, height)
        full = (ox + px, oy + py)
        result = resolve_affine_pixel(full, matrix)
        return {"region_pixel": {"x": px, "y": py},
                "full_capture_pixel": {"x": full[0], "y": full[1]}, **result}

    if "x" not in pointer or "y" not in pointer:
        _fail("INVALID_GEOMETRY")
    end_x, end_y = pointer.get("end_x"), pointer.get("end_y")
    if (end_x is None) != (end_y is None):
        _fail("INVALID_GEOMETRY")
    return {"start": point(pointer["x"], pointer["y"]),
            "end": None if end_x is None else point(end_x, end_y)}

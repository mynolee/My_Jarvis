"""Stdlib-only synthetic CU tests. No API, driver, approval or model execution.

Fixture reads occur only inside test methods and use the public golden70 file.
Run with packages/contracts on PYTHONPATH; no application package is imported.
"""
import copy
import hashlib
import json
from pathlib import Path
import struct
import unittest
from unittest import mock

import cu_wire_hash_v1 as cu


def rational(numerator, denominator=1):
    return {"numerator": str(numerator), "denominator": str(denominator)}


def identity_matrix():
    return [rational(1), rational(0), rational(0),
            rational(0), rational(1), rational(0)]


class CUWireHashV1Tests(unittest.TestCase):
    def rejected(self, code, callable_, *args, **kwargs):
        with self.assertRaises(cu.CUWireError) as caught:
            callable_(*args, **kwargs)
        self.assertEqual(caught.exception.code, code)
        self.assertEqual(str(caught.exception), code)

    def fixture(self):
        path = Path(__file__).parent.parent / "fixtures" / "cu-wire-hash-v1" / "golden-vectors.json"
        return json.loads(path.read_bytes())

    def test_golden_encoding_21(self):
        vectors = self.fixture()["encoding_vectors"]
        self.assertEqual(len(vectors), 21)
        for vector in vectors:
            with self.subTest(vector=vector["id"]):
                actual = cu.canonicalize_wire(vector["input_json"].encode("utf-8"))
                self.assertEqual(actual, vector["expected_jcs_utf8"].encode("utf-8"))
                self.assertEqual(hashlib.sha256(actual).hexdigest(), vector["expected_sha256"])

    def test_golden_wire_rejections_16(self):
        vectors = self.fixture()["rejection_vectors"][:16]
        self.assertEqual(len(vectors), 16)
        for vector in vectors:
            with self.subTest(vector=vector["id"]):
                raw = (bytes.fromhex(vector["input_utf8_hex"]) if "input_utf8_hex" in vector else
                       vector["input_json"].encode("utf-8"))
                self.rejected(vector["expected_error"], cu.canonicalize_wire, raw)

    def test_golden_coordinate_components_13(self):
        vectors = self.fixture()["coordinate_vectors"]
        self.assertEqual(len(vectors), 13)
        for vector in vectors:
            with self.subTest(vector=vector["id"]):
                values = {}
                for key, token in vector["input_tokens"].items():
                    size = vector["width"] if key in ("x", "end_x") else vector["height"]
                    self.assertEqual(cu.pixel_index(token, size), vector["expected_region_pixels"][key])
                    values[key] = cu.NumberToken(token)
                    if key in vector.get("expected_binary64_hex", {}):
                        self.assertEqual(struct.pack(">d", cu.binary64_value(token)).hex(),
                                         vector["expected_binary64_hex"][key])
                encoded = cu.jcs_bytes(values)
                self.assertEqual(encoded, vector["expected_jcs_utf8"].encode())
                self.assertEqual(hashlib.sha256(encoded).hexdigest(), vector["expected_sha256"])

    def test_golden_envelope_encoding_is_not_approval(self):
        vectors = self.fixture()["envelope_vectors"]
        self.assertEqual(len(vectors), 10)
        for vector in vectors:
            with self.subTest(vector=vector["id"]):
                raw = vector["expected_jcs_utf8"].encode()
                self.assertEqual(cu.canonicalize_wire(raw), raw)
                self.assertEqual(hashlib.sha256(raw).hexdigest(), vector["expected_sha256"])
                self.assertIs(vector["policy_contract_registered"], False)
                self.assertIs(vector["execution_authority"], False)

    def test_rfc8785_appendix_b_finite_bit_patterns(self):
        # Independent published numeric oracles: RFC8785 Appendix B.
        pairs = [
            ("0000000000000000", "0"), ("8000000000000000", "0"),
            ("0000000000000001", "5e-324"), ("8000000000000001", "-5e-324"),
            ("7fefffffffffffff", "1.7976931348623157e+308"),
            ("ffefffffffffffff", "-1.7976931348623157e+308"),
            ("4340000000000000", "9007199254740992"),
            ("c340000000000000", "-9007199254740992"),
            ("4430000000000000", "295147905179352830000"),
            ("44b52d02c7e14af5", "9.999999999999997e+22"),
            ("44b52d02c7e14af6", "1e+23"),
            ("44b52d02c7e14af7", "1.0000000000000001e+23"),
            ("444b1ae4d6e2ef4e", "999999999999999700000"),
            ("444b1ae4d6e2ef4f", "999999999999999900000"),
            ("444b1ae4d6e2ef50", "1e+21"),
            ("3eb0c6f7a0b5ed8c", "9.999999999999997e-7"),
            ("3eb0c6f7a0b5ed8d", "0.000001"),
            ("41b3de4355555553", "333333333.3333332"),
            ("41b3de4355555554", "333333333.33333325"),
            ("41b3de4355555555", "333333333.3333333"),
            ("41b3de4355555556", "333333333.3333334"),
            ("41b3de4355555557", "333333333.33333343"),
            ("becbf647612f3696", "-0.0000033333333333333333"),
            ("43143ff3c1cb0959", "1424953923781206.2"),
        ]
        for bits, expected in pairs:
            with self.subTest(bits=bits):
                value = struct.unpack(">d", bytes.fromhex(bits))[0]
                self.assertEqual(cu.format_binary64(value), expected)

    def test_normal_subnormal_and_binary_binade_boundaries(self):
        cases = (("000fffffffffffff", "2.225073858507201e-308"),
                 ("0010000000000000", "2.2250738585072014e-308"),
                 ("3fefffffffffffff", "0.9999999999999999"),
                 ("3ff0000000000000", "1"),
                 ("3ff0000000000001", "1.0000000000000002"))
        for bits, expected in cases:
            with self.subTest(bits=bits):
                value = struct.unpack(">d", bytes.fromhex(bits))[0]
                self.assertEqual(cu.format_binary64(value), expected)
                self.assertEqual(struct.pack(">d", cu.binary64_value(expected)).hex(), bits)

    def test_decimal_to_binary64_ties_even_without_double_rounding(self):
        pairs = [("9007199254740993", "4340000000000000"),
                 ("9007199254740995", "4340000000000002"),
                 ("1.00000000000000011102230246251565404236316680908203125",
                  "3ff0000000000000"),
                 ("1.00000000000000033306690738754696212708950042724609375",
                  "3ff0000000000002")]
        for token, bits in pairs:
            with self.subTest(token=token):
                self.assertEqual(struct.pack(">d", cu.binary64_value(token)).hex(), bits)

    def test_exact_subnormal_half_and_overflow_threshold(self):
        half = 5 ** 1075
        self.assertEqual(struct.pack(">d", cu.binary64_value(str(half) + "e-1075")).hex(),
                         "0000000000000000")
        self.assertEqual(struct.pack(">d", cu.binary64_value(str(half * 10 + 1) + "e-1076")).hex(),
                         "0000000000000001")
        threshold = (1 << 1024) - (1 << 970)
        self.rejected("NONFINITE_BINARY64", cu.binary64_value, str(threshold))
        self.assertEqual(struct.pack(">d", cu.binary64_value(str(threshold - 1))).hex(),
                         "7fefffffffffffff")

    def test_original_coordinate_rejection_precedes_conversion(self):
        for token in ("1.0000000000000001", "-1e-10000", "-0.0001", "2"):
            with self.subTest(token=token), mock.patch.object(
                    cu, "binary64_value", side_effect=AssertionError("conversion reached")):
                self.rejected("COORDINATE_ORIGINAL_DECIMAL_OUT_OF_RANGE",
                              cu.coordinate_value, token)

    def test_original_integer_and_narrow_schema_bounds(self):
        self.rejected("NOT_EXACT_INTEGER", cu.exact_integer, "1.0000000000000001")
        self.rejected("UNSAFE_INTEGER", cu.exact_integer, "9007199254740992")
        self.assertEqual(cu.exact_integer("1000e-3"), 1)
        self.assertEqual(cu.exact_integer("-0e99999999999999"), 0)
        self.assertEqual(cu.exact_integer("9007199254740991"), 9007199254740991)
        self.rejected("INTEGER_OUT_OF_RANGE", cu.exact_integer, "41", 1, 40)
        self.rejected("INVALID_JSON_NUMBER", cu.exact_integer, True)

    def test_proposal_original_field_checks_before_normalization(self):
        self.rejected("NOT_EXACT_INTEGER", cu.admit_proposal_numbers, "session",
                      b'{"max_actions":1.0000000000000001}')
        self.rejected("INTEGER_OUT_OF_RANGE", cu.admit_proposal_numbers, "session",
                      b'{"duration_seconds":601}')
        self.rejected("COORDINATE_ORIGINAL_DECIMAL_OUT_OF_RANGE",
                      cu.admit_proposal_numbers, "action",
                      b'{"pointer":{"x":1.0000000000000001}}')
        self.rejected("NOT_EXACT_INTEGER", cu.admit_proposal_numbers, "action",
                      b'{"pointer":{"delta_y":0.00000000000000001}}')
        value = cu.admit_proposal_numbers("action", b'{"pointer":{"x":0.3,"end_x":null}}')
        self.assertEqual(value["pointer"]["x"], cu.binary64_value("0.3"))
        self.assertIsNone(value["pointer"]["end_x"])

    def test_late_original_numeric_rejection_performs_zero_conversions(self):
        cases = (
            (b'{"pointer":{"x":0.1,"y":0.2,"end_x":1.0000000000000001,"end_y":0.4}}',
             "COORDINATE_ORIGINAL_DECIMAL_OUT_OF_RANGE"),
            (b'{"pointer":{"x":0.1,"y":0.2,"end_x":0.3,"end_y":-1e-10000}}',
             "COORDINATE_ORIGINAL_DECIMAL_OUT_OF_RANGE"),
            (b'{"pointer":{"x":0.1,"y":0.2,"end_x":0.3,"end_y":0.4,"delta_y":1.1}}',
             "NOT_EXACT_INTEGER"),
        )
        for raw, code in cases:
            with self.subTest(raw=raw), mock.patch.object(
                    cu, "binary64_value", side_effect=AssertionError("early conversion")) as convert:
                self.rejected(code, cu.admit_proposal_numbers, "action", raw)
                self.assertEqual(convert.call_count, 0)

    def test_admitted_numeric_tokens_are_converted_once_each(self):
        raw = (b'{"pointer":{"x":0.1,"y":0.2,"end_x":0.3,"end_y":0.4,'
               b'"delta_x":1.0,"delta_y":-1}}')
        with mock.patch.object(cu, "binary64_value", wraps=cu.binary64_value) as convert:
            value = cu.admit_proposal_numbers("action", raw)
        self.assertEqual(convert.call_count, 6)
        self.assertEqual([call.args[0].lexeme for call in convert.call_args_list],
                         ["0.1", "0.2", "0.3", "0.4", "1.0", "-1"])
        self.assertEqual(value["pointer"]["delta_x"], 1.0)
        self.assertEqual(value["pointer"]["delta_y"], -1.0)

    def test_number_lexemes_and_trees_are_preserved(self):
        parsed = cu.parse_wire(b'{"n":1.00,"list":[-0,0.3],"s":"original"}')
        before = copy.deepcopy(parsed)
        normalized = cu.normalize_numbers(parsed)
        cu.jcs_bytes(parsed)
        self.assertEqual(parsed, before)
        self.assertEqual(parsed["n"].lexeme, "1.00")
        self.assertIsNot(parsed, normalized)
        self.assertIsNot(parsed["list"], normalized["list"])
        self.assertIs(type(normalized["n"]), float)

    def test_decoded_duplicates_and_scalar_unicode(self):
        for raw in (b'{"a":1,"\\u0061":2}',
                    b'{"\\ud83d\\ude00":1,"\xf0\x9f\x98\x80":2}'):
            self.rejected("DUPLICATE_KEY", cu.parse_wire, raw)
        for raw in (b'"\\ud800"', b'"\\udfff"', b'"\\ud800\\u0041"'):
            self.rejected("INVALID_UNICODE", cu.parse_wire, raw)
        for text in ('"\\ufdd0"', '"\\ufffe"', '"\\udbff\\udfff"'):
            self.rejected("IJSON_NONCHARACTER", cu.parse_wire, text.encode())
        self.assertEqual(cu.parse_wire(b'"\\ud83d\\ude00"'), "\U0001f600")

    def test_string_escaping_and_unsigned_utf16_order(self):
        value = {"\ue000": 1, "\U0001f600": 2, "a": "\x00\b\t\n\f\r\"\\/\u2028\u2029"}
        expected = ('{"a":"\\u0000\\b\\t\\n\\f\\r\\"\\\\/\u2028\u2029",'
                    '"\U0001f600":2,"\ue000":1}').encode()
        self.assertEqual(cu.jcs_bytes(value), expected)
        self.assertNotEqual(cu.jcs_bytes({"s": "\u00e9"}), cu.jcs_bytes({"s": "e\u0301"}))

    def test_bounded_wire_depth_array_body_and_trailing_input(self):
        self.rejected("BODY_LIMIT", cu.parse_wire, b" " * (cu.MAX_BODY_BYTES + 1))
        self.rejected("DEPTH_LIMIT", cu.parse_wire, b"[" * 17 + b"0" + b"]" * 17)
        self.rejected("ARRAY_LIMIT", cu.parse_wire, b"[" + b"0," * 1000 + b"0]")
        self.assertEqual(len(cu.parse_wire(b"[" + b"0," * 999 + b"0]")), 1000)
        self.rejected("TRAILING_VALUE", cu.parse_wire, b"{} []")

    def test_strict_json_number_grammar(self):
        for token in ("+1", "01", "-01", ".1", "1.", "1e", "1e+", "NaN", "Infinity",
                      "-Infinity", "0x10", "1_0", "\u0661"):
            with self.subTest(token=token):
                self.rejected("INVALID_JSON_NUMBER", cu.canonicalize_wire, token.encode())
        self.rejected("INVALID_JSON", cu.parse_wire, b'{"a":1,}')
        self.rejected("INVALID_JSON", cu.parse_wire, b"[1,]")
        self.rejected("INVALID_JSON", cu.parse_wire, b'"\\x00"')

    def test_positive_underflow_negative_zero_and_extreme_exponents(self):
        for token in ("-0", "-0.0", "-0e100000000000000000000", "1e-100000000000000000000"):
            with self.subTest(token=token):
                self.assertEqual(cu.pixel_index(token, 9), 0)
                self.assertEqual(cu.canonicalize_wire(token.encode()), b"0")
        self.rejected("NONFINITE_BINARY64", cu.binary64_value, "1e100000000000000000000")

    def test_nonfinite_and_host_types_rejected_without_input_echo(self):
        for value in (float("nan"), float("inf"), -float("inf")):
            self.rejected("NONFINITE_BINARY64", cu.jcs_bytes, value)
        self.rejected("INVALID_JSON_TYPE", cu.jcs_bytes, {1: "secret"})
        self.rejected("INVALID_JSON_TYPE", cu.jcs_bytes, object())
        self.rejected("INVALID_UNICODE", cu.jcs_bytes, "\ud800")
        self.rejected("INVALID_UTF8", cu.parse_wire, b"\xffSECRET")
        try:
            cu.parse_wire(b"\xffSECRET")
        except cu.CUWireError as error:
            self.assertIsNone(error.__context__)
            self.assertNotIn("SECRET", str(error))

    def test_profile_and_map_identifiers_no_fallback(self):
        cu.require_profile("cu-wire-hash-v1")
        cu.require_map_version("cu-pixel-map-v1")
        for value in (None, "", "cu-wire-hash-v2", True):
            self.rejected("UNSUPPORTED_PROFILE", cu.require_profile, value)
        for value in (None, "", "cu-pixel-map-v2", True):
            self.rejected("UNSUPPORTED_MAP_VERSION", cu.require_map_version, value)

    def test_positive_size_not_span_and_exact_pixel_ties(self):
        self.assertEqual(cu.pixel_index("0.5", 4), 2)
        self.assertEqual(cu.pixel_index("0.49999999999999994", 2), 0)
        self.assertEqual(cu.pixel_index("0.3", 6), 1)
        self.assertEqual(cu.pixel_index("1", 1), 0)
        for size in (0, -1, True, 1.0, 9007199254740992):
            self.rejected("INVALID_GEOMETRY", cu.pixel_index, "0", size)

    def test_signed_native_quantization_not_truncation(self):
        for n, d, expected in ((-3, 2, -1), (-1, 2, 0), (-8, 5, -2),
                               (-9, 5, -2), (3, 2, 2), (1, 2, 1)):
            self.assertEqual(cu.quantize_native(n, d), expected)
        self.rejected("INVALID_RATIONAL", cu.quantize_native, 1, 0)
        self.rejected("INVALID_RATIONAL", cu.quantize_native, True, 1)
        self.rejected("UNSAFE_INTEGER", cu.quantize_native, 9007199254740992, 1)

    def test_affine_translation_negative_and_exact_derived_inverse(self):
        matrix = [rational(1), rational(0), rational(100),
                  rational(0), rational(1), rational(-50)]
        before = copy.deepcopy(matrix)
        result = cu.resolve_affine_pixel((11, 21), matrix)
        self.assertEqual(result["native_args"], {"x": 111, "y": -29})
        self.assertEqual(result["inverse_capture_point"], {"x": rational(11), "y": rational(21)})
        self.assertEqual(result["effective_capture_pixel"], {"x": 11, "y": 21})
        self.assertEqual(matrix, before)

    def test_lossy_half_scale_component_is_not_admissible(self):
        matrix = [rational(1, 2), rational(0), rational(0),
                  rational(0), rational(1, 2), rational(0)]
        probe = cu.inspect_affine_pixel((11, 21), matrix)
        self.assertEqual(probe["native_args"], {"x": 6, "y": 11})
        self.assertEqual(probe["inverse_capture_point"], {"x": rational(12), "y": rational(22)})
        self.assertEqual(probe["effective_capture_pixel"], {"x": 12, "y": 22})
        self.assertIs(probe["same_capture_pixel"], False)
        self.rejected("COORDINATE_MAPPING_UNSUPPORTED", cu.resolve_affine_pixel, (11, 21), matrix)
        self.rejected("COORDINATE_MAPPING_UNSUPPORTED", cu.resolve_affine_pixel, (1, 0), matrix)

    def test_singular_noncanonical_and_bool_geometry_rejected(self):
        self.rejected("COORDINATE_MAPPING_UNSUPPORTED", cu.resolve_affine_pixel, (1, 1),
                      [rational(0)] * 6)
        for bad in (rational(2, 2), rational(0, 2),
                    {"numerator": "-0", "denominator": "1"},
                    {"numerator": "1", "denominator": "01"},
                    {"numerator": 1, "denominator": "1"},
                    {"numerator": "1", "denominator": "1", "extra": None}):
            matrix = identity_matrix()
            matrix[0] = bad
            self.rejected("INVALID_RATIONAL", cu.resolve_affine_pixel, (1, 1), matrix)
        self.rejected("INVALID_GEOMETRY", cu.resolve_affine_pixel, (True, 1), identity_matrix())

    def test_crop_start_end_nonmutation_and_bounds(self):
        pointer = {"x": "0.25", "y": "0.75", "end_x": "0.75", "end_y": "0.25"}
        capture, crop = {"width": 100, "height": 100}, {"x": 10, "y": 20, "width": 5, "height": 5}
        before = copy.deepcopy((pointer, capture, crop))
        result = cu.resolve_pointer(pointer, capture, crop, identity_matrix())
        self.assertEqual(result["start"]["full_capture_pixel"], {"x": 11, "y": 23})
        self.assertEqual(result["end"]["native_args"], {"x": 13, "y": 21})
        self.assertEqual((pointer, capture, crop), before)
        self.rejected("INVALID_GEOMETRY", cu.resolve_pointer, pointer, capture,
                      {"x": 99, "y": 20, "width": 5, "height": 5}, identity_matrix())
        self.rejected("INVALID_GEOMETRY", cu.resolve_pointer,
                      {"x": "0", "y": "0", "end_x": "1", "end_y": None},
                      capture, crop, identity_matrix())

    def test_hash_generic_result_is_exact_utf8_not_approval_object(self):
        value = {"n": cu.NumberToken("0.1"), "s": "한글"}
        encoded = cu.jcs_bytes(value)
        self.assertEqual(cu.jcs_sha256(value), hashlib.sha256(encoded).hexdigest())
        self.assertFalse(encoded.endswith(b"\n"))
        self.assertFalse(encoded.startswith(b"\xef\xbb\xbf"))
        self.assertEqual(cu.PROFILE_ID, "cu-wire-hash-v1")
        self.assertFalse(hasattr(cu, "approve"))
        self.assertFalse(hasattr(cu, "execute"))


if __name__ == "__main__":
    unittest.main()

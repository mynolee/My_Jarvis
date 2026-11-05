"""Decoded-schema and CU raw admission checks; no actions or approval grants."""

import copy
import json
import unittest
from unittest.mock import patch

from jarvis_intelligence import contracts as consumer

from jarvis_intelligence.contracts import (
    CONTRACT_ROOT,
    MAX_DEPTH,
    ContractViolation,
    body_hash,
    canonical,
    parse_json,
    validate_computer_proposal,
)


class ComputerProposalContractsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixture = json.loads(
            (CONTRACT_ROOT / "fixtures/computer-v42/cases.json").read_text(
                encoding="utf-8"
            )
        )
        cls.cases = cls.fixture["cases"]

    def case(self, identifier):
        return copy.deepcopy(next(
            case["instance"] for case in self.cases if case["id"] == identifier
        ))

    def test_all_shared_structural_fixtures_use_the_consumer(self):
        self.assertEqual(len(self.cases), 120)
        self.assertEqual(len({case["id"] for case in self.cases}), 120)
        kinds = {
            "computer-session": "session",
            "computer-observe": "observe",
            "computer-action": "action",
        }
        for case in self.cases:
            with self.subTest(case=case["id"]):
                value = copy.deepcopy(case["instance"])
                original = copy.deepcopy(value)
                if case["expected_valid"]:
                    self.assertIs(
                        validate_computer_proposal(kinds[case["schema"]], value),
                        value,
                    )
                else:
                    with self.assertRaises(ContractViolation):
                        validate_computer_proposal(kinds[case["schema"]], value)
                self.assertEqual(value, original)

    def test_fractional_object_is_data_not_permission_or_hash_support(self):
        value = self.case("action.click-valid")
        original = copy.deepcopy(value)
        self.assertIs(validate_computer_proposal("action", value), value)
        self.assertIs(type(value["pointer"]["x"]), float)
        self.assertEqual(value["pointer"]["x"], 0.25)
        self.assertEqual(value, original)
        for name in ("approved", "authorization", "execution_authorized"):
            self.assertNotIn(name, value)
        with self.assertRaises(ContractViolation):
            canonical(value)
        with self.assertRaises(ContractViolation):
            body_hash(value)

    def test_kind_is_closed_before_schema_loading(self):
        for kind in (
            "computer-action", "../action", "Action", "action\\n", "", None, 1, True,
            b"action", [], {},
        ):
            with self.subTest(kind_type=type(kind).__name__):
                with patch("jarvis_intelligence.contracts.validate") as validator:
                    with self.assertRaises(ContractViolation):
                        validate_computer_proposal(kind, {})
                    validator.assert_not_called()

    def test_raw_bytes_and_non_objects_are_not_a_wire_parser(self):
        for value in (
            b'{"action":"click"}', "{}", [], (), None, True, 1, 0.25,
        ):
            with self.subTest(value_type=type(value).__name__):
                with patch("jarvis_intelligence.contracts.validate") as validator:
                    with self.assertRaises(ContractViolation):
                        validate_computer_proposal("action", value)
                    validator.assert_not_called()

    def test_malformed_json_tree_stops_before_schema_loading(self):
        too_deep = 0
        for _ in range(MAX_DEPTH + 1):
            too_deep = [too_deep]
        cyclic = []
        cyclic.append(cyclic)
        invalid = [
            float("nan"), float("inf"), -float("inf"),
            9007199254740992, -9007199254740992,
            "\ud800", {"\ud800": "value"}, {1: "value"}, {False: "value"},
            [0] * 1001, too_deep, cyclic, ("tuple",), {"set"}, object(),
        ]
        for item in invalid:
            with self.subTest(value_type=type(item).__name__):
                with patch("jarvis_intelligence.contracts.validate") as validator:
                    with self.assertRaises(ContractViolation):
                        validate_computer_proposal("session", {"synthetic": item})
                    validator.assert_not_called()

    def test_finite_values_still_require_the_exact_action_schema(self):
        for identifier in (
            "action.pointer-x-bool", "action.click-two-targets",
            "action.scroll-zero", "action.drag-no-endpoint",
            "action.save-no-destination", "computer-action.client-approved",
        ):
            with self.subTest(case=identifier):
                with self.assertRaises(ContractViolation):
                    validate_computer_proposal("action", self.case(identifier))

    def test_legacy_m0_float_rejection_and_integer_serialization_are_unchanged(self):
        with self.assertRaises(ContractViolation):
            parse_json(b'{"x":0.25}')
        with self.assertRaises(ContractViolation):
            canonical({"x": 0.25})
        with self.assertRaises(ContractViolation):
            body_hash({"x": 0.25})
        self.assertEqual(parse_json(b'{"x":1}'), {"x": 1})
        self.assertEqual(canonical({"x": 1}), b'{"x":1}')



class ComputerWireProposalContractsTest(unittest.TestCase):
    """Raw-token integration only; no server binding, hash or action authority."""

    PROFILE = "cu-wire-hash-v1"

    @classmethod
    def setUpClass(cls):
        data = json.loads(
            (CONTRACT_ROOT / "fixtures/computer-v42/cases.json").read_text(
                encoding="utf-8"
            )
        )
        cls.instances = {case["id"]: case["instance"] for case in data["cases"]}
        cls.codec = consumer._computer_wire_codec()

    def case(self, identifier):
        return copy.deepcopy(self.instances[identifier])

    def wire(self, identifier):
        return json.dumps(
            self.case(identifier), separators=(",", ":"), allow_nan=False
        ).encode("utf-8")

    def raw_value(self, identifier, path, literal):
        value = self.case(identifier)
        target = value
        for part in path[:-1]:
            target = target[part]
        target[path[-1]] = "__CU_RAW_TOKEN_MARKER__"
        text = json.dumps(value, separators=(",", ":"), allow_nan=False)
        marker = '"__CU_RAW_TOKEN_MARKER__"'
        self.assertEqual(text.count(marker), 1)
        return text.replace(marker, literal).encode("utf-8")

    def reject_before_schema(self, kind, raw, code):
        with patch.object(consumer, "validate_computer_proposal") as validator:
            with self.assertRaises(ContractViolation) as raised:
                consumer.parse_computer_proposal(kind, raw, profile_id=self.PROFILE)
            self.assertEqual(str(raised.exception), code)
            validator.assert_not_called()

    def test_original_lexemes_reach_numeric_admission_before_schema(self):
        raw = self.raw_value(
            "action.drag-valid", ("pointer", "end_x"), "0.99999999999999999"
        )
        seen = []
        real_parse = self.codec.parse_wire
        real_validate = consumer.validate_computer_proposal
        events = []

        def observe_wire(body):
            self.assertIs(body, raw)
            tree = real_parse(body)
            seen.append(tree["pointer"]["end_x"].lexeme)
            return tree

        def observe_schema(kind, value):
            events.append("schema")
            self.assertIs(type(value["pointer"]["end_x"]), float)
            self.assertEqual(value["pointer"]["end_x"], 1.0)
            return real_validate(kind, value)

        def observe_profile(profile):
            events.append("profile")
            return real_profile(profile)

        real_profile = self.codec.require_profile
        real_admit = self.codec.admit_proposal_numbers

        def observe_admission(kind, body):
            events.append("numeric_admission")
            return real_admit(kind, body)

        with patch.object(self.codec, "require_profile", side_effect=observe_profile), \
                patch.object(self.codec, "admit_proposal_numbers", side_effect=observe_admission) as admission, \
                patch.object(self.codec, "parse_wire", side_effect=observe_wire), \
                patch.object(consumer, "validate_computer_proposal", side_effect=observe_schema):
            result = consumer.parse_computer_proposal(
                "action", raw, profile_id=self.PROFILE
            )
        self.assertEqual(events, ["profile", "numeric_admission", "schema"])
        self.assertEqual(seen, ["0.99999999999999999"])
        admission.assert_called_once_with("action", raw)
        self.assertEqual(result["pointer"]["end_x"], 1.0)

    def test_original_bounds_cover_start_and_end_coordinates(self):
        for field in ("x", "y", "end_x", "end_y"):
            for token in ("1.0000000000000001", "-1e-10000"):
                with self.subTest(field=field, token=token):
                    raw = self.raw_value(
                        "action.drag-valid", ("pointer", field), token
                    )
                    self.reject_before_schema(
                        "action", raw, "COORDINATE_ORIGINAL_DECIMAL_OUT_OF_RANGE"
                    )

    def test_late_invalid_end_blocks_all_binary64_conversion(self):
        for field in ("end_x", "end_y"):
            with self.subTest(field=field):
                raw = self.raw_value(
                    "action.drag-valid", ("pointer", field), "1.0000000000000001"
                )
                with patch.object(
                    self.codec, "binary64_value", wraps=self.codec.binary64_value
                ) as conversion:
                    self.reject_before_schema(
                        "action", raw, "COORDINATE_ORIGINAL_DECIMAL_OUT_OF_RANGE"
                    )
                conversion.assert_not_called()

    def test_original_integer_fields_are_checked_before_normalization(self):
        for field in ("duration_seconds", "max_actions", "max_frames"):
            with self.subTest(field=field):
                raw = self.raw_value(
                    "session.local-valid", (field,), "1.0000000000000001"
                )
                self.reject_before_schema("session", raw, "NOT_EXACT_INTEGER")
        cases = (
            ("session.local-valid", ("max_actions",), "9007199254740992", "UNSAFE_INTEGER"),
            ("session.local-valid", ("max_actions",), "41", "INTEGER_OUT_OF_RANGE"),
            ("action.scroll-negative-delta-valid", ("pointer", "delta_x"), "1001", "INTEGER_OUT_OF_RANGE"),
            ("action.scroll-negative-delta-valid", ("pointer", "delta_y"), "0.1", "NOT_EXACT_INTEGER"),
            ("action.scroll-negative-delta-valid", ("pointer", "delta_x"), "true", "INVALID_JSON_NUMBER"),
        )
        for identifier, path, token, code in cases:
            with self.subTest(path=path, token=token):
                kind = "session" if identifier.startswith("session.") else "action"
                self.reject_before_schema(
                    kind, self.raw_value(identifier, path, token), code
                )

    def test_valid_integral_spellings_nullable_ends_and_underflow(self):
        raw = self.raw_value("session.local-valid", ("max_actions",), "1.0")
        session = consumer.parse_computer_proposal(
            "session", raw, profile_id=self.PROFILE
        )
        self.assertIs(type(session["max_actions"]), float)
        self.assertEqual(session["max_actions"], 1.0)
        for token in ("-0.0", "1e-400"):
            with self.subTest(token=token):
                raw = self.raw_value("action.click-valid", ("pointer", "x"), token)
                action = consumer.parse_computer_proposal(
                    "action", raw, profile_id=self.PROFILE
                )
                self.assertEqual(action["pointer"]["x"], 0.0)
                self.assertIsNone(action["pointer"]["end_x"])
                self.assertIsNone(action["pointer"]["end_y"])
        scroll = consumer.parse_computer_proposal(
            "action", self.wire("action.scroll-negative-delta-valid"),
            profile_id=self.PROFILE,
        )
        self.assertEqual(scroll["pointer"]["delta_x"], -1000.0)

    def test_decoded_api_stays_schema_only_and_does_not_infer_wire_provenance(self):
        value = self.case("action.click-valid")
        value["pointer"]["x"] = 1.0
        original = copy.deepcopy(value)
        self.assertIs(validate_computer_proposal("action", value), value)
        self.assertEqual(value, original)
        raw = self.raw_value(
            "action.click-valid", ("pointer", "x"), "1.0000000000000001"
        )
        self.reject_before_schema(
            "action", raw, "COORDINATE_ORIGINAL_DECIMAL_OUT_OF_RANGE"
        )

    def test_duplicate_decoded_names_bom_and_second_value_stop_schema(self):
        raw = self.wire("session.local-valid")
        for suffix in (b',"max_actions":3}', rb',"\u006dax_actions":3}'):
            self.reject_before_schema("session", raw[:-1] + suffix, "DUPLICATE_KEY")
        self.reject_before_schema("session", b"\xef\xbb\xbf" + raw, "BOM")
        self.reject_before_schema("session", raw + b"{}", "TRAILING_VALUE")

    def test_unicode_utf8_and_nonfinite_errors_are_closed(self):
        for literal, code in (
            (r'"\ud800"', "INVALID_UNICODE"),
            (r'"\udead"', "INVALID_UNICODE"),
            (r'"\ufdd0"', "IJSON_NONCHARACTER"),
            (r'"\udbff\udfff"', "IJSON_NONCHARACTER"),
        ):
            with self.subTest(literal=literal):
                self.reject_before_schema(
                    "session",
                    self.raw_value("session.local-valid", ("purpose",), literal),
                    code,
                )
        self.reject_before_schema("session", b"\xff", "INVALID_UTF8")
        for token in ("NaN", "Infinity", "-Infinity"):
            self.reject_before_schema(
                "session",
                self.raw_value("session.local-valid", ("max_actions",), token),
                "INVALID_JSON_NUMBER",
            )
        valid = self.raw_value(
            "session.local-valid", ("purpose",), r'"Synthetic \ud83d\ude00"'
        )
        self.assertEqual(
            consumer.parse_computer_proposal(
                "session", valid, profile_id=self.PROFILE
            )["purpose"], "Synthetic \U0001f600"
        )

    def test_unknown_kind_profile_and_nonbytes_never_load_the_codec(self):
        class TrapString(str):
            def __eq__(self, other):
                raise AssertionError("CUSTOM_COMPARISON_REACHED")

        raw = self.wire("session.local-valid")
        for kind in ("Action", "../action", None, True, [], {}, TrapString("session")):
            with self.subTest(kind_type=type(kind).__name__):
                with patch.object(consumer, "_computer_wire_codec") as loader:
                    with self.assertRaises(ContractViolation) as raised:
                        consumer.parse_computer_proposal(
                            kind, raw, profile_id=self.PROFILE
                        )
                    self.assertEqual(str(raised.exception), "UNSUPPORTED_KIND")
                    loader.assert_not_called()
        for profile in (None, "", "cu-wire-hash-v2", 1, True, [], {}, TrapString(self.PROFILE)):
            with self.subTest(profile_type=type(profile).__name__):
                with patch.object(consumer, "_computer_wire_codec") as loader:
                    with self.assertRaises(ContractViolation) as raised:
                        consumer.parse_computer_proposal(
                            "session", raw, profile_id=profile
                        )
                    self.assertEqual(str(raised.exception), "UNSUPPORTED_PROFILE")
                    loader.assert_not_called()
        with patch.object(consumer, "_computer_wire_codec") as loader:
            with self.assertRaises(ContractViolation) as raised:
                consumer.parse_computer_proposal("session", raw)
            self.assertEqual(str(raised.exception), "UNSUPPORTED_PROFILE")
            loader.assert_not_called()
        for value in ("{}", {}, bytearray(raw), memoryview(raw), None, True):
            with self.subTest(raw_type=type(value).__name__):
                with patch.object(consumer, "_computer_wire_codec") as loader:
                    with self.assertRaises(ContractViolation) as raised:
                        consumer.parse_computer_proposal(
                            "session", value, profile_id=self.PROFILE
                        )
                    self.assertEqual(str(raised.exception), "INVALID_UTF8")
                    loader.assert_not_called()

    def test_current_body_depth_and_array_limits_do_not_expand(self):
        with patch.object(consumer, "_computer_wire_codec") as loader:
            with self.assertRaises(ContractViolation) as raised:
                consumer.parse_computer_proposal(
                    "session", b" " * (consumer.MAX_BODY_BYTES + 1),
                    profile_id=self.PROFILE,
                )
            self.assertEqual(str(raised.exception), "BODY_LIMIT")
            loader.assert_not_called()
        deep = b"[" * (MAX_DEPTH + 1) + b"0" + b"]" * (MAX_DEPTH + 1)
        self.reject_before_schema("session", deep, "DEPTH_LIMIT")
        array = b'{"unexpected":[' + b",".join([b"0"] * 1001) + b"]}"
        self.reject_before_schema("session", array, "ARRAY_LIMIT")
        self.assertEqual(self.codec.MAX_BODY_BYTES, consumer.MAX_BODY_BYTES)
        self.assertEqual(self.codec.MAX_DEPTH, MAX_DEPTH)

    def test_results_are_fresh_and_inputs_and_fixture_are_unmodified(self):
        fixture_before = copy.deepcopy(self.instances)
        raw = self.wire("action.click-valid")
        saved_raw = bytes(raw)
        first = consumer.parse_computer_proposal(
            "action", raw, profile_id=self.PROFILE
        )
        first["pointer"]["x"] = 0.875
        second = consumer.parse_computer_proposal(
            "action", raw, profile_id=self.PROFILE
        )
        self.assertIsNot(first, second)
        self.assertIsNot(first["pointer"], second["pointer"])
        self.assertEqual(second["pointer"]["x"], 0.25)
        self.assertEqual(raw, saved_raw)
        self.assertEqual(self.instances, fixture_before)
        for key in ("binding", "resolved_args", "approved", "authorization"):
            self.assertNotIn(key, second)

    def test_client_binding_or_approval_fields_are_still_schema_rejected(self):
        for key in ("binding", "resolved_args", "approved", "authorization"):
            with self.subTest(key=key):
                value = self.case("action.click-valid")
                value[key] = True
                raw = json.dumps(value, separators=(",", ":")).encode()
                with self.assertRaises(ContractViolation) as raised:
                    consumer.parse_computer_proposal(
                        "action", raw, profile_id=self.PROFILE
                    )
                self.assertEqual(str(raised.exception), "contract violation")

    def test_loader_uses_fixed_source_without_pyc_or_global_import_path_changes(self):
        import sys
        from importlib.machinery import SourceFileLoader

        path_before = list(sys.path)
        name = "_jarvis_intelligence_cu_wire_hash_v1"
        absent = object()
        old_registration = sys.modules.get(name, absent)
        real_read = SourceFileLoader.get_data
        reads = []

        def read_source(loader, path):
            reads.append(path)
            return real_read(loader, path)

        with patch.object(consumer, "_COMPUTER_WIRE_CODEC", None), \
                patch.object(SourceFileLoader, "get_data", new=read_source), \
                patch.object(SourceFileLoader, "set_data", side_effect=AssertionError("PYC_WRITE")):
            loaded = consumer._computer_wire_codec()
        self.assertEqual(reads, [str(CONTRACT_ROOT / "cu_wire_hash_v1.py")])
        self.assertEqual(sys.path, path_before)
        self.assertIs(sys.modules.get(name, absent), old_registration)
        self.assertEqual(loaded.PROFILE_ID, self.PROFILE)

    def test_raw_entry_never_uses_or_changes_legacy_m0_parser_or_hash_apis(self):
        raw = self.wire("action.click-valid")
        original = (consumer.parse_json, consumer.canonical, consumer.body_hash)
        with patch.object(consumer, "parse_json", side_effect=AssertionError("M0_PARSE")), \
                patch.object(consumer, "canonical", side_effect=AssertionError("M0_CANONICAL")), \
                patch.object(consumer, "body_hash", side_effect=AssertionError("M0_HASH")):
            value = consumer.parse_computer_proposal(
                "action", raw, profile_id=self.PROFILE
            )
        self.assertEqual(value["pointer"]["x"], 0.25)
        self.assertEqual((consumer.parse_json, consumer.canonical, consumer.body_hash), original)


    def test_unavailable_codec_has_no_fallback_or_filesystem_detail_reflection(self):
        from importlib.machinery import SourceFileLoader

        raw = self.wire("session.local-valid")
        with patch.object(consumer, "_COMPUTER_WIRE_CODEC", None), \
                patch.object(SourceFileLoader, "get_data", side_effect=OSError(5, "synthetic filesystem detail")), \
                patch.object(consumer, "validate_computer_proposal") as validator:
            with self.assertRaises(ContractViolation) as raised:
                consumer.parse_computer_proposal(
                    "session", raw, profile_id=self.PROFILE
                )
        self.assertEqual(str(raised.exception), "CU_WIRE_CODEC_UNAVAILABLE")
        self.assertTrue(raised.exception.__suppress_context__)
        validator.assert_not_called()


    def test_raw_schema_validation_uses_real_schema_with_valid_payload_control(self):
        schema = CONTRACT_ROOT / "schemas" / "computer-action.schema.json"
        real_read = consumer.Path.read_text
        reads = []

        def observe_schema_read(path, *args, **kwargs):
            if path == schema:
                reads.append(path)
            return real_read(path, *args, **kwargs)

        with patch.object(consumer, "_VALIDATORS", {}), \
                patch.object(consumer.Path, "read_text", autospec=True, side_effect=observe_schema_read):
            value = consumer.parse_computer_proposal(
                "action", self.wire("action.click-valid"), profile_id=self.PROFILE
            )
            self.assertEqual(value["pointer"]["x"], 0.25)
            self.assertIn("computer-action", consumer._VALIDATORS)
            invalid = self.case("action.click-valid")
            invalid["approved"] = True
            with self.assertRaises(ContractViolation) as raised:
                consumer.parse_computer_proposal(
                    "action", json.dumps(invalid).encode(), profile_id=self.PROFILE
                )
            self.assertEqual(str(raised.exception), "contract violation")
        self.assertEqual(reads, [schema])

    def test_raw_schema_read_oserror_suppresses_path_without_bypassing_validator(self):
        schema = CONTRACT_ROOT / "schemas" / "computer-action.schema.json"
        sentinel = "/synthetic/schema-privacy-sentinel.json"
        real_read = consumer.Path.read_text
        reads = []

        def fail_selected_schema(path, *args, **kwargs):
            if path == schema:
                reads.append(path)
                raise OSError(13, "synthetic schema read denied", sentinel)
            return real_read(path, *args, **kwargs)

        with patch.object(consumer, "_VALIDATORS", {}), \
                patch.object(consumer.Path, "read_text", autospec=True, side_effect=fail_selected_schema), \
                patch.object(consumer, "validate_computer_proposal", wraps=consumer.validate_computer_proposal) as validator:
            with self.assertRaises(ContractViolation) as raised:
                consumer.parse_computer_proposal(
                    "action", self.wire("action.click-valid"), profile_id=self.PROFILE
                )
            self.assertEqual(str(raised.exception), "CU_SCHEMA_UNAVAILABLE")
            self.assertNotIn(sentinel, str(raised.exception))
            self.assertNotIn("synthetic schema read denied", str(raised.exception))
            self.assertTrue(raised.exception.__suppress_context__)
            validator.assert_called_once()
            self.assertEqual(validator.call_args.args[0], "action")
            self.assertNotIn("computer-action", consumer._VALIDATORS)
        self.assertEqual(reads, [schema])


if __name__ == "__main__":
    unittest.main()

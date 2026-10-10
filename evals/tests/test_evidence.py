import copy
import datetime as dt
import json
from pathlib import Path
import sys
import tempfile
import subprocess
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from evidence import INPUTS, InvalidEvidence, collect, digest, encoded, loads, validate


class EvidenceTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.sha = "1" * 40
        self.totals = dict(tests=2, passed=2, failed=0, skipped=0)
        self.report = dict(targetSHA=self.sha, testId="SYNTHETIC-001", totals=self.totals, unauthorizedEffects=0, secretLeaks=0, unresolvedHigh=0, unresolvedCritical=0, disabledProbes={})
        self.case = dict(testId="SYNTHETIC-001", status="PASS", executed=True, commandId="synthetic-oracle-v1", command=["python3", "synthetic.py"], startedAt="2026-01-01T00:00:00Z", endedAt="2026-01-01T00:00:01Z", exitCode=0, totals=self.totals, result=self.write("result.json", encoded(self.report)), stdout=self.write("stdout.log", b"synthetic\n"), stderr=self.write("stderr.log", b""), disabledFeature=None)
        artifacts = {name: self.write(f"inputs/{name}.txt", (name + "-synthetic").encode()) for name in INPUTS}
        hashes = {"synthetic": digest(b"synthetic")}
        self.policy = dict(targetSHA=self.sha, suiteRevision="synthetic-v1", ruleRevision="synthetic-v1", requiredCases=[self.case["testId"]], inputArtifacts=artifacts, environmentHashes=hashes, toolHashes=hashes, dependencyHashes=hashes, allowedDisabledFeatures=["synthetic-feature"], receiptSHA256="", now="2026-01-01T00:02:00Z", maxAgeSeconds=300)
        self.bundle = dict(version=1, targetSHA=self.sha, suiteRevision="synthetic-v1", ruleRevision="synthetic-v1", inputHashes={k: v["sha256"] for k, v in artifacts.items()}, environmentHashes=hashes, toolHashes=hashes, dependencyHashes=hashes, executorIdentity="synthetic-untrusted-test", cases=[self.case])

    def write(self, path, data):
        dest = self.root / path
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(data)
        return dict(path=path, sha256=digest(data))

    def check(self):
        data = encoded(self.bundle)
        self.policy["receiptSHA256"] = digest(data)
        return validate(data, self.policy, self.root)

    def update_report(self):
        self.case["result"] = self.write("result.json", encoded(self.report))

    def reject(self):
        with self.assertRaises((InvalidEvidence, ValueError, TypeError)):
            self.check()

    def test_valid_is_not_release_or_m0_completion(self):
        verdict = self.check()
        self.assertEqual(verdict["verdict"], "EVIDENCE_CONSISTENCY_VALID")
        self.assertFalse(verdict["m0Complete"])
        self.assertFalse(verdict["releaseAuthorized"])
        self.assertFalse(verdict["nativeInferenceVerified"])
        self.assertFalse(verdict["integrationVerified"])

    def test_missing_case(self):
        self.bundle["cases"] = []
        self.reject()

    def test_duplicate_case(self):
        self.bundle["cases"].append(copy.deepcopy(self.case))
        self.reject()

    def test_extra_case(self):
        self.case["testId"] = "UNREQUESTED"
        self.reject()

    def test_wrong_sha(self):
        self.bundle["targetSHA"] = "2" * 40
        self.reject()

    def test_wrong_result_sha(self):
        self.report["targetSHA"] = "2" * 40
        self.update_report()
        self.reject()

    def test_stale_evidence(self):
        self.policy["now"] = "2026-01-02T00:00:00Z"
        self.reject()

    def test_future_evidence(self):
        self.case["endedAt"] = "2027-01-01T00:00:00Z"
        self.reject()

    def test_tampered_result(self):
        (self.root / "result.json").write_bytes(b"changed")
        self.reject()

    def test_tampered_receipt(self):
        data = encoded(self.bundle)
        self.policy["receiptSHA256"] = digest(data)
        with self.assertRaises(InvalidEvidence):
            validate(data + b" ", self.policy, self.root)

    def test_stale_lock(self):
        (self.root / "inputs/lock.txt").write_bytes(b"updated-lock")
        self.reject()

    def test_missing_inputs(self):
        del self.policy["inputArtifacts"]["lock"]
        self.reject()

    def test_wrong_environment_tools_dependencies_rules(self):
        for name in ("environmentHashes", "toolHashes", "dependencyHashes", "ruleRevision", "suiteRevision"):
            with self.subTest(name=name):
                original = self.bundle[name]
                self.bundle[name] = "changed"
                self.reject()
                self.bundle[name] = original

    def test_missing_and_unknown_fields(self):
        del self.case["commandId"]
        self.reject()
        self.case["commandId"] = "synthetic"
        self.case["approved"] = True
        self.reject()

    def test_fail_unknown_not_run_cannot_pass(self):
        for status in ("FAIL", "UNKNOWN", "NOT_RUN", "garbage"):
            with self.subTest(status=status):
                self.case["status"] = status
                self.reject()

    def test_executed_is_not_inferred_from_status(self):
        self.case["executed"] = False
        self.reject()

    def test_exit_code_null_bool_failure(self):
        for value in (None, False, 1, "0"):
            with self.subTest(value=value):
                self.case["exitCode"] = value
                self.reject()

    def test_incomplete_or_false_totals(self):
        for values in (dict(tests=0, passed=0, failed=0, skipped=0), dict(tests=2, passed=1, failed=0, skipped=1), dict(tests=True, passed=True, failed=0, skipped=0)):
            self.case["totals"] = values
            self.reject()

    def test_security_metrics_cannot_be_averaged_away(self):
        for metric in ("unauthorizedEffects", "secretLeaks", "unresolvedHigh", "unresolvedCritical"):
            self.report[metric] = 1
            self.update_report()
            self.reject()
            self.report[metric] = 0

    def test_artifact_missing(self):
        (self.root / "stdout.log").unlink()
        self.reject()

    def test_path_traversal(self):
        self.case["stdout"]["path"] = "../stdout.log"
        self.reject()

    def test_symlink(self):
        (self.root / "stdout.log").unlink()
        (self.root / "stdout.log").symlink_to(self.root / "stderr.log")
        self.reject()

    def test_duplicate_json_keys(self):
        with self.assertRaises(InvalidEvidence):
            loads(b'{"status":"FAIL","status":"PASS"}')

    def test_na_disabled_claim_without_probes(self):
        self.case.update(status="NA_DISABLED", disabledFeature="synthetic-feature")
        self.reject()

    def test_na_disabled_requires_all_surfaces_executed(self):
        self.case.update(status="NA_DISABLED", disabledFeature="synthetic-feature")
        self.report["disabledProbes"] = {surface: dict(feature="synthetic-feature", attempts=1, denied=1, sideEffects=0) for surface in ("api", "tool", "ui")}
        self.update_report()
        self.assertEqual(self.check()["verdict"], "EVIDENCE_CONSISTENCY_VALID")
        self.report["disabledProbes"]["tool"]["denied"] = 0
        self.update_report()
        self.reject()

    def test_na_disabled_not_policy_allowed(self):
        self.case.update(status="NA_DISABLED", disabledFeature="synthetic-feature")
        self.policy["allowedDisabledFeatures"] = []
        self.reject()

    def test_collection_never_promotes_exit_zero_to_pass(self):
        result = collect([sys.executable, "-c", "print('synthetic')"], self.root / "run", "SYNTHETIC", self.sha)
        self.assertEqual(result["exitCode"], 0)
        self.assertEqual(result["status"], "UNKNOWN")
        self.assertIn("stdout.log", result["artifacts"])

    def test_collection_failure_and_missing_tool(self):
        result = collect([sys.executable, "-c", "raise SystemExit(3)"], self.root / "failed", "SYNTHETIC", self.sha)
        self.assertEqual(result["status"], "FAIL")
        result = collect(["/nonexistent/synthetic-tool"], self.root / "missing", "SYNTHETIC", self.sha)
        self.assertEqual(result["status"], "NOT_RUN")
        self.assertIsNone(result["exitCode"])

    def test_collection_timeout_is_unknown(self):
        result = collect([sys.executable, "-c", "import time; time.sleep(10)"], self.root / "timeout", "SYNTHETIC", self.sha, timeout=1)
        self.assertEqual(result["status"], "UNKNOWN")
        self.assertTrue(result["executed"])
        self.assertIsNone(result["exitCode"])

    def test_required_cases_cannot_be_empty(self):
        self.policy["requiredCases"] = []
        self.bundle["cases"] = []
        self.reject()

    def test_hash_maps_cannot_be_empty(self):
        self.policy["toolHashes"] = {}
        self.bundle["toolHashes"] = {}
        self.reject()

    def test_cli_returns_nonzero_for_blocked_evidence(self):
        bundle = self.root / "bundle.json"
        policy = self.root / "policy.json"
        bundle.write_bytes(encoded(self.bundle))
        policy.write_bytes(encoded(self.policy))
        run = subprocess.run([sys.executable, str(Path(__file__).resolve().parents[1] / "evidence.py"), "--bundle", str(bundle), "--policy", str(policy), "--root", str(self.root)], capture_output=True, check=False)
        self.assertEqual(run.returncode, 1)
        self.assertEqual(json.loads(run.stdout)["verdict"], "BLOCKED")

    def test_collection_rejects_old_artifacts(self):
        with self.assertRaises(InvalidEvidence):
            collect([sys.executable, "-c", "pass"], self.root, "SYNTHETIC", self.sha)


if __name__ == "__main__":
    unittest.main()

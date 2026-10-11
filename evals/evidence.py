"""Offline, fail-closed evidence checking. A local receipt is not a release attestation."""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

HASH = re.compile(r"[0-9a-f]{64}\Z")
SHA = re.compile(r"(?:[0-9a-f]{40}|[0-9a-f]{64})\Z")
STATUSES = {"PASS", "FAIL", "NOT_RUN", "UNKNOWN", "NA_DISABLED"}
INPUTS = {"config", "lock", "policy", "schema", "skill", "prompt", "model", "toolRegistry"}


class InvalidEvidence(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise InvalidEvidence(message)


def keys(value, expected):
    require(type(value) is dict and set(value) == set(expected), "unexpected or missing fields")


def nonempty(value):
    return type(value) is str and bool(value.strip())


def integer(value):
    return type(value) is int and value >= 0


def digest(data):
    return hashlib.sha256(data).hexdigest()


def encoded(value):
    return (json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False) + "\n").encode()


def loads(data):
    def unique(pairs):
        obj = {}
        for key, value in pairs:
            require(key not in obj, "duplicate JSON key")
            obj[key] = value
        return obj
    return json.loads(data, object_pairs_hook=unique, parse_constant=lambda _: (_ for _ in ()).throw(InvalidEvidence("nonfinite JSON")))


def read(root, relative):
    require(nonempty(relative), "empty artifact path")
    path = Path(relative)
    require(not path.is_absolute() and ".." not in path.parts, "unsafe artifact path")
    current = Path(root).resolve()
    for part in path.parts:
        current = current / part
        require(not current.is_symlink(), "symlink artifact")
    require(current.is_file(), "missing artifact")
    require(current.stat().st_size <= 4 * 1024 * 1024, "oversized artifact")
    return current.read_bytes()


def artifact(root, value):
    keys(value, {"path", "sha256"})
    require(type(value["sha256"]) is str and HASH.fullmatch(value["sha256"]), "invalid artifact digest")
    data = read(root, value["path"])
    require(digest(data) == value["sha256"], "artifact digest mismatch")
    return data


def timestamp(value):
    require(nonempty(value), "missing timestamp")
    parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    require(parsed.tzinfo is not None, "timezone missing")
    return parsed


def hash_map(value, required=None):
    require(type(value) is dict and bool(value), "missing hash map")
    if required is not None:
        require(set(value) == required, "incomplete input hashes")
    require(all(nonempty(k) and type(v) is str and HASH.fullmatch(v) for k, v in value.items()), "invalid hash map")


def validate(bundle_bytes, policy, root):
    """Policy and receipt digest must arrive from a separately trusted caller.

    This verifies consistency/provenance bindings, not truth of arbitrary test code.
    No commands are executed by validation.
    """
    keys(policy, {"targetSHA", "suiteRevision", "ruleRevision", "requiredCases", "inputArtifacts", "environmentHashes", "toolHashes", "dependencyHashes", "allowedDisabledFeatures", "receiptSHA256", "now", "maxAgeSeconds"})
    require(type(policy["targetSHA"]) is str and SHA.fullmatch(policy["targetSHA"]), "invalid target SHA")
    require(nonempty(policy["suiteRevision"]) and nonempty(policy["ruleRevision"]), "missing revisions")
    cases = policy["requiredCases"]
    require(type(cases) is list and bool(cases) and all(nonempty(c) for c in cases) and len(set(cases)) == len(cases), "invalid required cases")
    require(type(policy["allowedDisabledFeatures"]) is list and all(nonempty(x) for x in policy["allowedDisabledFeatures"]), "invalid disabled feature policy")
    require(integer(policy["maxAgeSeconds"]) and policy["maxAgeSeconds"] > 0, "invalid freshness limit")
    require(type(policy["receiptSHA256"]) is str and HASH.fullmatch(policy["receiptSHA256"]) and digest(bundle_bytes) == policy["receiptSHA256"], "untrusted or changed receipt")
    require(type(policy["inputArtifacts"]) is dict and set(policy["inputArtifacts"]) == INPUTS, "missing input artifacts")
    expected_inputs = {k: digest(artifact(root, v)) for k, v in policy["inputArtifacts"].items()}
    for name in ("environmentHashes", "toolHashes", "dependencyHashes"):
        hash_map(policy[name])
    bundle = loads(bundle_bytes)
    keys(bundle, {"version", "targetSHA", "suiteRevision", "ruleRevision", "inputHashes", "environmentHashes", "toolHashes", "dependencyHashes", "executorIdentity", "cases"})
    require(type(bundle["version"]) is int and bundle["version"] == 1, "unknown evidence version")
    for name in ("targetSHA", "suiteRevision", "ruleRevision", "environmentHashes", "toolHashes", "dependencyHashes"):
        require(bundle[name] == policy[name], f"{name} mismatch")
    require(bundle["inputHashes"] == expected_inputs, "stale inputs")
    require(nonempty(bundle["executorIdentity"]), "missing executor identity")
    require(type(bundle["cases"]) is list, "invalid case list")
    found = set()
    now = timestamp(policy["now"])
    for case in bundle["cases"]:
        keys(case, {"testId", "status", "executed", "commandId", "command", "startedAt", "endedAt", "exitCode", "totals", "result", "stdout", "stderr", "disabledFeature"})
        require(nonempty(case["testId"]) and case["testId"] not in found, "duplicate or invalid case")
        found.add(case["testId"])
        require(case["testId"] in cases, "unexpected case")
        require(type(case["status"]) is str and case["status"] in STATUSES, "invalid status")
        require(case["status"] in {"PASS", "NA_DISABLED"}, "required case not passing")
        require(case["executed"] is True and type(case["exitCode"]) is int and case["exitCode"] == 0, "command not successfully executed")
        require(nonempty(case["commandId"]) and type(case["command"]) is list and bool(case["command"]) and all(nonempty(s) for s in case["command"]), "missing command")
        start, end = timestamp(case["startedAt"]), timestamp(case["endedAt"])
        require(start <= end <= now and (now-start).total_seconds() <= policy["maxAgeSeconds"], "stale or future evidence")
        totals = case["totals"]
        keys(totals, {"tests", "passed", "failed", "skipped"})
        require(all(integer(v) for v in totals.values()) and totals["tests"] > 0 and totals["passed"] == totals["tests"] and totals["failed"] == totals["skipped"] == 0, "invalid passing totals")
        artifact(root, case["stdout"])
        artifact(root, case["stderr"])
        report = loads(artifact(root, case["result"]))
        keys(report, {"targetSHA", "testId", "totals", "unauthorizedEffects", "secretLeaks", "unresolvedHigh", "unresolvedCritical", "disabledProbes"})
        keys(report["totals"], {"tests", "passed", "failed", "skipped"})
        require(all(integer(v) for v in report["totals"].values()), "invalid result totals")
        require(report["targetSHA"] == policy["targetSHA"] and report["testId"] == case["testId"] and report["totals"] == totals, "result binding mismatch")
        for metric in ("unauthorizedEffects", "secretLeaks", "unresolvedHigh", "unresolvedCritical"):
            require(type(report[metric]) is int and report[metric] == 0, f"unsafe or missing metric: {metric}")
        if case["status"] == "NA_DISABLED":
            require(nonempty(case["disabledFeature"]) and case["disabledFeature"] in policy["allowedDisabledFeatures"], "unapproved disabled feature")
            probes = report["disabledProbes"]
            keys(probes, {"api", "tool", "ui"})
            for probe in probes.values():
                keys(probe, {"feature", "attempts", "denied", "sideEffects"})
                require(probe["feature"] == case["disabledFeature"] and integer(probe["attempts"]) and probe["attempts"] > 0 and type(probe["denied"]) is int and probe["denied"] == probe["attempts"] and type(probe["sideEffects"]) is int and probe["sideEffects"] == 0, "feature not demonstrably disabled")
        else:
            require(case["disabledFeature"] is None and report["disabledProbes"] == {}, "unexpected disabled claim")
    require(found == set(cases), "missing required cases")
    return {"verdict": "EVIDENCE_CONSISTENCY_VALID", "requiredCases": len(cases), "releaseAuthorized": False, "m0Complete": False, "nativeInferenceVerified": False, "integrationVerified": False}


def collect(command, output_dir, test_id, target_sha, timeout=30):
    """Run an explicit local command. Its oracle must write result.json in output_dir.

    Collection is never an acceptance verdict. Caller supplies the isolated process
    environment; this function does not claim network or resource sandboxing.
    """
    require(type(command) is list and command and all(nonempty(x) for x in command), "invalid command")
    require(nonempty(test_id) and type(target_sha) is str and SHA.fullmatch(target_sha), "invalid execution identity")
    require(type(timeout) is int and timeout > 0, "invalid timeout")
    directory = Path(output_dir)
    directory.mkdir(parents=True, exist_ok=True)
    require(not any(directory.iterdir()), "collector requires an empty output directory")
    started = dt.datetime.now(dt.timezone.utc).isoformat()
    status, exit_code, executed = "UNKNOWN", None, False
    try:
        result = subprocess.run(command, cwd=directory, capture_output=True, timeout=timeout, check=False, shell=False)
        stdout, stderr, exit_code = result.stdout, result.stderr, result.returncode
        executed = True
        status = "FAIL" if exit_code else "UNKNOWN"
    except subprocess.TimeoutExpired as exc:
        executed = True
        stdout, stderr = exc.stdout or b"", exc.stderr or b""
    except OSError as exc:
        stdout, stderr, status = b"", type(exc).__name__.encode(), "NOT_RUN"
    for name, data in (("stdout.log", stdout), ("stderr.log", stderr)):
        (directory / name).write_bytes(data)
    receipt = {"testId": test_id, "targetSHA": target_sha, "status": status, "executed": executed, "command": command, "startedAt": started, "endedAt": dt.datetime.now(dt.timezone.utc).isoformat(), "exitCode": exit_code, "artifacts": {p.name: digest(p.read_bytes()) for p in sorted(directory.iterdir()) if p.is_file()}}
    (directory / "execution.json").write_bytes(encoded(receipt))
    return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", required=True)
    parser.add_argument("--policy", required=True)
    parser.add_argument("--root", required=True)
    args = parser.parse_args()
    try:
        result = validate(Path(args.bundle).read_bytes(), loads(Path(args.policy).read_bytes()), args.root)
    except (InvalidEvidence, ValueError, TypeError, KeyError, OSError) as exc:
        print(json.dumps({"verdict": "BLOCKED", "reason": str(exc), "releaseAuthorized": False, "m0Complete": False, "nativeInferenceVerified": False, "integrationVerified": False}))
        return 1
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())

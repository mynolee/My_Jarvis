# Local evidence gate

This dependency-free harness checks evidence consistency. It does not certify M0,
execute the application security suite, authorize a release, provide trusted CI
attestation, or prove the semantics of an arbitrary test program.

Run the synthetic checker regression tests:

    python3 -m unittest discover -s evals/tests -v

Validate a separately generated bundle against a trusted caller's policy:

    python3 evals/evidence.py --bundle receipt.json --policy policy.json --root evidence-directory

Exit 0 means `EVIDENCE_CONSISTENCY_VALID`. Every other outcome blocks acceptance. Even
exit 0 always includes `releaseAuthorized: false`, `m0Complete: false`,
`nativeInferenceVerified: false` and `integrationVerified: false`.
This is a consistency result, never `LOCAL_VERIFIED`, `INTEGRATED` or
`EVAL_PASSED`. Naming a synthetic case LOC or SEC cannot elevate its evidence.
Actual-device evaluation requires separately trusted native runtime observations,
fresh-process reload, traffic and resource measurements at the exact artifact.

## Trust boundary

The caller must independently review and supply the policy. In particular, never
accept a policy or receipt digest supplied by the same untrusted producer whose
results it checks. `receiptSHA256` is an external integrity anchor, not a signature.
A producer who controls the policy and evidence can fabricate both. Protected CI
signing, identity verification, environment measurements, a complete required-case
list and actual application/security test oracles remain integration requirements.

`evidence.schema.json` documents bundle structure. `evidence.py` is the authoritative
strict semantic validator. It rejects duplicate JSON keys, unknown/missing fields,
missing/duplicate/unrequested cases, missing artifacts, symlinks, path traversal,
changed artifact bytes, changed inputs, wrong target/revisions, future/expired
execution times and mismatched tool/dependency/environment measurements.

## Policy contract

A policy contains exactly these fields:

- `targetSHA`: full lowercase Git object ID
- `suiteRevision`, `ruleRevision`: reviewed nonempty revision identifiers
- `requiredCases`: unique, nonempty list determined by the independent acceptance owner
- `inputArtifacts`: config, lock, policy, schema, skill, prompt, model and toolRegistry;
  each maps to a relative file path and its actual `sha256`
- `environmentHashes`, `toolHashes`, `dependencyHashes`: independently measured,
  nonempty name-to-SHA256 maps
- `allowedDisabledFeatures`: explicitly reviewed feature identifiers
- `receiptSHA256`: independently obtained hash of the exact bundle bytes
- `now`: trusted timezone-aware evaluation time
- `maxAgeSeconds`: positive maximum age measured from execution start

All artifact paths resolve beneath `--root`. Input content is read and rehashed;
asserting a digest in the bundle alone cannot bind changed configuration. Model or
skill inputs may reference a real sanitized manifest describing absent/disabled
components; do not publish personal settings, secrets, or internal instructions.
A policy that omits controls cannot establish their coverage. Missing controls
must remain unverified in the external acceptance map.

## Per-case oracle result

Each case identifies its execution command and ID, executor identity (at bundle
level), start/end timestamps, explicit `executed` boolean, actual exit code,
counts, and hashed result/stdout/stderr files. Its result JSON contains exactly:

- `targetSHA`, `testId`, and `totals` matching the enclosing receipt
- `unauthorizedEffects`, `secretLeaks`, `unresolvedHigh`, `unresolvedCritical`:
  all integer zero for acceptance, produced by reviewed deterministic oracles
- `disabledProbes`: empty for PASS; for NA_DISABLED contains `api`, `tool`, `ui`

Every disabled probe contains `feature`, `attempts`, `denied`, `sideEffects`.
The feature must match the case and allowlist, attempts must be positive, all
attempts must be denied, and side effects must be zero. An enabled surface, missing
probe or prose "disabled" claim blocks the gate. The result file is bound to the
same target, command receipt and digest as other assertions. Reviewing/running
real probes is still necessary: arbitrary self-authored JSON is not proof.

Totals require positive tests, all passed, no failed or skipped tests. FAIL,
NOT_RUN, UNKNOWN, unexecuted commands and missing/null exit codes never pass;
quality averages do not compensate for a security failure.

## Explicit local collector

`collect(command, output_dir, test_id, target_sha, timeout=30)` is a Python API for
running an explicitly supplied local command, without a shell, in an empty output
directory. It writes stdout, stderr and an execution receipt with artifact hashes.
A missing executable is NOT_RUN, timeout is UNKNOWN, nonzero exit is FAIL, and
exit zero remains UNKNOWN until a reviewed oracle and policy validate the result.
Collection therefore never equates a green process with a passing security case.

The collector is for synthetic local commands. It is not a process/network
sandbox; the caller must isolate untrusted code and control secrets, filesystem,
network and resource limits. Scan/redact output before sharing artifacts. Do not
use it with production credentials or external paid services. Validator execution
never runs the commands recorded inside an evidence bundle.

Regression fixtures are generated in temporary directories and labeled synthetic.
Their results validate the checker itself, not application SEC coverage.

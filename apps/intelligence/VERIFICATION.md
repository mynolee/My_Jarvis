# Local inference boundary verification

Date: 2026-10-10 UTC. Environment: Linux x86_64, Python 3.12.14.
This is source and synthetic verification, not native model inference evidence.

Command from `apps/intelligence`:

    PYTHONPATH=src .venv/bin/python -m unittest discover -s tests -v

Result: **67 tests passed**, exit 0, 17.903 seconds. This includes the existing
boundary tests and the separately implemented nonce-ledger tests. Raw test-output
SHA-256: `1749b788728f6e6649ad39c4dae7e0300c8eafcda81e1538e54ffc35e5e87dfd`.
`compileall` and the entrypoint `--help` also exited 0. Evidence is bound to source
file hashes below; no Git revision is claimed for this working-copy test.

## Tested boundaries

- Shared strict request/output validation, capability binding, durable replay,
  missing mTLS identity, no silent provider fallback, unknown authority fields.
- Exact prepared-input tensor admission: 3584 input + reserved 512 output inside
  total 4096, same tensors forwarded to generation, schema included in rendering.
- Actual spawned synthetic process: one active generation, two waiters, fourth
  request rejected; cancellation of a waiter preserves active generation.
- Cooperative cancellation, hard timeout, unresponsive cancellation, process exit
  verification before slot release, new-process reload without replaying a turn.
- A kernel lock survives closing the parent's descriptor while its child lives;
  repeated duplicate-owner attempts fail. Unconfirmed death poisons readiness.
- Artifact inventory/hash/pin verification, unlisted code and symlink rejection,
  private configuration permissions, reload tamper clears stale verification flags.
- SSL-transport identity adapter ignores spoofed scope/header certificate data.
  This is a synthetic transport test, not an actual mTLS handshake.

## Explicitly not verified

Native macOS/Metal model loading and Korean generation; real tokenizer/model
execution; actual peak RAM, memory pressure, speed and cold/warm performance;
OS-level process egress isolation; live TLS handshakes; Core→MLX→UI end-to-end
integration; complete LOC30/Q60/SEC80/E100 release gates. All remain **NOT_RUN**
in this evidence record. Source inspection of MLX-VLM 0.7.6 does not change that.

The current public-code delivery includes no actual model manifest, private
runtime configuration, model weights, personal prompts or user environment values.

## Source and test hashes

| File relative to this directory | SHA-256 |
|---|---|
| src/jarvis_intelligence/app.py | 25f083682d74e424c7318afb31b4ae9967fb6f81e2d8ff6fdd885a9dcedb14b9 |
| src/jarvis_intelligence/contracts.py | 98b0f00d0bda2a14f5d90ff56b5bba95411c3f1ef3aee3e5cda562e5578bf560 |
| src/jarvis_intelligence/__main__.py | b34e2cc617264e783eed84dbfbd5ed7efc074f90170881d55371dcb4e375127d |
| src/jarvis_intelligence/local_runtime.py | 356cbe742e2a9bda522c6e887067cea2334e6f7026c583d22a7225ad775c5d6c |
| src/jarvis_intelligence/mlx_adapter.py | 4b29c83046ed8db4334a610cb3405dd097467ab5d6e04009a6d46c743dce9c97 |
| src/jarvis_intelligence/tls.py | 50f38029ce44546ece60115b9583d7b4eed66ace4889954fd32281f2c5637a75 |
| tests/test_local_runtime.py | d91b00ed6b7926970e0669e18296e8f3e3138f4c9ed626627fc8ae6a7e39ea03 |
| tests/test_local_boundary.py | dcf980c8c95daa40503b26783251ed42b21dfce58117a2e1e58d4ca84f4b16c3 |
| tests/test_tls_transport.py | 077f877207b41d3d1a5611bf41c696fa80a03d2a409c4516f49a58db39bad403 |
| README.md | 6cbbbc4fc4598d53a2ab20fcda311708fb397b7fbd7f295577014300d2d439e6 |

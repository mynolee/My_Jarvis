# Intelligence service boundary

Python 3.12, FastAPI and Pydantic. The default mock mode returns deterministic answers
and proposals without calling a model. Explicit local MLX mode is described below. It has no Core DB
client, external AI provider adapter, calendar executor or shell executor. HTTPX is
test-only in the portable service lock; MLX has separate pinned dependencies. This is not a production
release or proof of network isolation.

## Run focused verification

From this directory:

```sh
UV_CACHE_DIR=/tmp/jarvis-intelligence-uv uv sync --frozen
PYTHONPATH=src UV_CACHE_DIR=/tmp/jarvis-intelligence-uv uv run --frozen python -m unittest discover -s tests -v
```

Keep the repository's `packages/contracts` directory beside `apps`; runtime
validation reads those canonical schemas rather than maintaining a divergent copy.
Both schemas and the integer-only JCS golden vectors are exercised in tests.

## Internal protocol

`POST /internal/v1/turn` accepts only the shared turn request, JSON <= 256 KiB,
container depth <= 16, no duplicate keys, floats, lone surrogates or coercions.
Unknown fields, server-authority fields, nonempty external context, invalid time
zones and invalid manifest identifiers fail closed. The current mock manifest is
`sha256("jarvis-m0-deterministic-mock-v1:no-model:no-tools:no-network")`.

`X-Jarvis-Capability` is unpadded base64url(JCS capability) + `.` +
base64url(Ed25519 signature of those exact bytes). Keys are pinned by key_id; all
claim fields follow the shared schema. Audience, issuer, request/run/reservation,
canonical body SHA256, model and token/call limits are verified. Lifetime <= 60s,
future issue skew <= 30s, no grace after expiry. Renewing a capability never resets
a consumed reservation. Cookies and forwarded certificate headers grant nothing.

The default app requires a server-supplied ASGI TLS extension, verified peer chain,
and configured leaf fingerprint. No HTTP header is accepted as peer evidence.
Actual mTLS ingress interoperability and firewall/egress tests are NOT_RUN.
The Core currently uses its own explicit in-process mock; it does not silently
fall back to or from this service.

For a local development process only, provision a synthetic Ed25519 public key
and a separate owner-only file with 32 random cache-key bytes outside the checkout.
Then run:

```sh
PYTHONPATH=src uv run --frozen python -m jarvis_intelligence \
  --allow-loopback-mock-without-mtls \
  --core-public-key /absolute/private/core-public.pem \
  --core-key-id local-core \
  --cache-key-file /absolute/private/cache-key \
  --ledger /absolute/private/intelligence.sqlite
```

The launcher binds only 127.0.0.1 and disables proxy headers. The explicit flag
relaxes mTLS only for this mock loopback process; Ed25519 capability checks remain.
Do not expose this listener or use this mode for production. Mock mode never uses a model provider. `mode="unavailable"` returns MODEL_UNAVAILABLE without
mock invocation. `/health/live` discloses only health, mode and current model availability.

## Mock behavior and trust boundary

Ordinary text receives a constant mock answer, without echoing private input.
`mock:need-input` gives NEED_INPUT; `mock:unavailable` gives ERROR;
`mock:propose {calendar argument JSON}` gives TOOL_PROPOSALS, only for the shared
allowlisted tool. Proposals do not execute anything. The service rejects model
usage, approval, risk, ownership, verification and unknown tool fields. It cannot
mint or settle a Core budget reservation. Mock usage numbers are zero because no
inference/tokenizer ran; they are not model performance measurements.

UTF-8 input bytes must fit the declared input-token budget and serialized mock
output bytes must fit the output-token budget, deliberately conservative bounds
without a tokenizer. Maximum four durable STARTED reservations and 10,000 ledger
identities; limits fail closed. Per-request asynchronous deadline <= 60s. These
are mock-service bounds, not a GPU/process sandbox guarantee.

## Replay and recovery

SQLite is a separate, private inference ledger. Request IDs and reservation IDs
are independently unique. BEGIN IMMEDIATE + FULL synchronous durability commits
STARTED before generation. Identical retries return the same AES-256-GCM encrypted
response, bound to owner, request, reservation, run and body hash. Changed owner/hash/run or
reservation reuse returns 409 without generation. The service never stores the
raw request. The owner ID comes only from the signed Core capability, never model
output or an unsigned HTTP header. Mock proposal arguments can contain personal
text, so all response caches use owner-bound authenticated encryption.

Completion uncertainty (crash, timeout, ledger write failure) remains UNKNOWN;
restarts never blindly regenerate. Cache access expires within one hour; expired
ciphertext is cleared on access/new admission. Replay tombstones and unique nonce
history remain, so expiry does not recreate execution rights. At 10,000 identities
admission stops; capacity migration is an operator task, not automatic deletion.
A changed/missing encryption key fails closed rather than returning plaintext.
The DB directory must be private, local and persistent; parent-directory security,
backup, disk encryption, key rotation and production multi-host storage need
separate operational verification. Preserve ledger and cache key across restart.

The tests cover real fresh Python process reopen/replay and STARTED recovery,
concurrent duplicates, changed identity, failure-before-generation, malformed
input/output, resource limits and missing peer evidence. They do not establish
mTLS transport, Core-to-Python end-to-end behavior or full SEC-001..080 acceptance.

## Dependency provenance

Direct versions were checked against their official PyPI project releases on
2026-10-09. `uv.lock` pins the complete dependency graph and artifact SHA256 values.
Python 3.12.14 was used locally. Installation used the official PyPI index.

- https://pypi.org/project/fastapi/0.142.4/
- https://pypi.org/project/pydantic/2.13.4/
- https://pypi.org/project/cryptography/50.0.2/
- https://pypi.org/project/jsonschema/4.26.0/
- https://pypi.org/project/uvicorn/0.52.1/
- https://pypi.org/project/httpx/0.28.1/ (tests only)

A successful build and version pin are not a vulnerability scan. The current
Starlette TestClient warns that its HTTPX integration is deprecated; tests still
pass. This is a test-harness migration item, not a hidden runtime fallback.

## Explicit local MLX mode (v4 source implementation)

The service now also implements an opt-in `local_mlx` provider. It is never selected
as fallback for mock mode. Its deployment requires a separately provisioned native
macOS arm64 Python 3.12 environment with MLX-VLM 0.7.6 and a fully pinned dependency
lock. The portable service lock intentionally does not install Metal dependencies.
No install, download, training, quantization, adapter creation or remote inference
is performed by this service.

`--local-config` selects an owner-only private JSON configuration. Its exact fields
are `model_path`, `manifest_path`, `runtime_lock_path`, `prompt_path`, `lock_path`,
`runtime_lock_sha256`, `prompt_sha256`, and `manifest_sha256`. All paths are absolute
and privately supplied. Actual environment values belong in the ignored local
`.env`; do not commit actual paths, prompts, manifests, dependency inventories,
weights, secrets or private configuration. A download lock is not a reviewed model
manifest: `model-artifact-manifest.schema.json` requires both upstream/conversion
provenance, reviewed license evidence, and the complete pinned file inventory.

Local launch requires `--tls-cert`, `--tls-key`, `--tls-ca` and
`--core-cert-sha256`, as well as the existing Core signing-public-key and private
ledger/cache-key arguments. `--allow-loopback-mock-without-mtls` cannot be combined
with local mode. The fixed listener is 127.0.0.1; TLS requires a CA-verified client
certificate and the turn endpoint additionally checks the exact pinned leaf.
`tls.py` obtains peer identity from the SSL transport, never a request header.
`GET /health/ready` returns the shared runtime-readiness object without generating.
`POST /internal/v1/turn` retains its signed capability/body hash/replay reservation
contract with explicit `model_id=local-mlx-v1` and `prompt_revision=local-v1`.

The supervisor owns a dedicated spawned worker and an inherited kernel lock. At
most one generation plus two waiting requests is admitted, with a 30-second queue
bound, 60-second generation bound, 300-second total cold-start bound, and the
request capability deadline. The input is rendered with the model chat template
and prepared with the pinned runtime's processor. Admission uses the actual
`input_ids.shape[1]` and reserves 512 output tokens inside the **4096 total** limit;
the same tensors go into generation. No character estimate or silent truncation
is used in local mode. Nonempty history/retrieval remains explicitly disabled by
the existing shared request contract. The supplied private system content and
closed output schema are included in the measured prompt.

A cooperative worker acknowledgement permits reuse. Unresponsive cancellation,
HTTP disconnect, or hard timeout terminates the child and verifies its exit code
before releasing the slot or loading the same pinned artifact again. Reload never
resubmits a generation. Unconfirmed process death poisons readiness and retains
the slot. Failed or uncertain post-generation usage leaves the durable reservation
UNKNOWN; it is not hidden as zero-cost success. Invalid JSON or authority-bearing
output returns MODEL_OUTPUT_INVALID, without another model call or JSON repair.

The reviewed distribution's internal `qwen3_5` / `Qwen3_5ForConditionalGeneration`
/ `qwen3_5_text` identifiers are intentionally preserved. They identify the loader
architecture; distribution identity comes from the private manifest's provenance,
full revisions and file hashes. The application never rewrites those config names.

### Evidence boundaries

Synthetic Linux tests cover tensor admission, exact tensor forwarding, closed
output parsing, capability/replay preservation, process concurrency, duplicate
owners, parent-FD loss, cancellation, timeout, worker death and reload. Synthetic
transport tests check certificate extraction; they are not a TLS handshake test.
The Python audit hook blocks normal socket/DNS and subprocess events in the worker,
and the hub is offline, but neither proves OS-level network isolation.

Actual native Metal load, Korean generation, cold/warm performance, RAM/pressure,
process-level egress capture, live mTLS handshake and Core→MLX→UI integration remain
NOT_RUN until collected on the target device. Mock/process tests must not be used
to label these gates PASS. The application is not release-certified.

Upstream API references inspected for this adapter:
- https://github.com/Blaizzy/mlx-vlm/blob/v0.7.6/mlx_vlm/utils.py
- https://github.com/Blaizzy/mlx-vlm/blob/v0.7.6/mlx_vlm/prompt_utils.py
- https://github.com/Blaizzy/mlx-vlm/blob/v0.7.6/mlx_vlm/generate/dispatch.py

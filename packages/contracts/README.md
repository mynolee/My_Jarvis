# M0 wire contracts

These are application contracts and synthetic test data. They contain no user content or executable authority supplied by a model.

## Rules

- Public routes start with `/api/v1`. JSON names are snake_case.
- Every object is closed. Unknown client/model fields fail validation; never strip them and continue.
- Parse JSON strictly before schema validation: reject duplicate keys, NaN, Infinity, floating-point values, unsafe integers, and lone Unicode surrogates.
- UUIDs and RFC3339 timestamps require format validation. Integers are bounded to the JavaScript interoperable range. Costs are integer USD micro-units.
- JCS bytes use UTF-16 code-unit property ordering, UTF-8 encoding, no whitespace, and no Unicode normalization. `canonical.py` deliberately supports only the integer-only M0 JSON domain, not general floating-point RFC8785.
- Hashes are lowercase SHA-256. Servers calculate hashes after validation. A client hash is only a reference to a previously prepared server payload.
- Approval decisions reference the saved action version and hash. They cannot replace action arguments.
- Model output is a proposal. It cannot create grants, typed intents, approvals, verified tool results, owner IDs, or risk classifications.
- `UNKNOWN` is never reported as successful and never automatically resubmitted. M0 reconciles it through `VERIFYING` only.
- The only modeled tool is synthetic `calendar.event.create` version `1`. Its empty attendee list prevents invitation side effects. This schema does not activate a real calendar provider.
- M0 context arrays and attachments are intentionally empty-only. Enabling a new feature requires a reviewed contract revision.

## Service boundary

`POST /internal/v1/turn` accepts `turn-request.schema.json`. A separate signed capability binds its canonical body hash, request/run/reservation IDs, audience, expiry, model and resource ceiling. Authentication and replay validation happen before any provider call. Web cookies do not authenticate this route.

All response kinds carry a `usage` record. Uncertain execution is not replaced with an invented zero-cost success.

## Tests

With Python 3.12 and uv installed:

    cd packages/contracts
    uv sync --frozen
    uv run --frozen python -m unittest discover -s tests -v

Java and Python runtime implementations must additionally consume `fixtures/jcs-vectors.json`. Passing the reference tests alone does not establish cross-language integration.

## Local inference extension

`local-mlx-v1` and `local-v1` are service identifiers, not paths or user settings.
The existing capability header and request envelope remain unchanged. The request
body hash binds the selected manifest. The service verifies the pinned manifest
before loading any model. Model selection never comes from generated text.

Requests reserve at most 512 output tokens and at most 4096 total tokens. The
runtime must count the actual rendered template, system text, tool schemas and
input, then require input tokens plus the output reservation to fit 4096. Schema
validation alone cannot establish this sum or tokenizer correctness.

`model-artifact-manifest.schema.json` describes a private, reviewed artifact
manifest; it is not the download lock. Manifest instances, local paths, prompts
and runtime locks stay outside this repository. The manifest digest is pinned
separately in private runtime configuration. Runtime validation additionally
checks each byte, shard index, inventory coverage and configuration architecture.

`runtime-readiness.schema.json` deliberately separates file verification, worker
readiness, real inference and integration evidence. A mock never sets real
inference or integration verification. READY is worker readiness, not release
approval. Cancel acknowledgement is not proof of process termination.

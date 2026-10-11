# Internal local context v1

This is a synthetic, data-only wire contract. It contains no model prompt or instruction template. Passing it does not prove authentication, authorization, source truth, freshness, native runtime readiness, or exact token admission.

## Compatibility and authority

The public local-inference input remains exactly `client_message_id` and `text`. The server alone selects every internal contextual field. Unknown public fields must be rejected. Text that resembles field names remains text.

The internal turn request opts into this revision by supplying `context` with `revision: 1` and a required `history` array. It uses existing `model_id: local-mlx-v1` and `prompt_revision: local-v1`; this is not a prompt revision or permission to change a pinned prompt. `evidence`, `tool_results`, and `allowed_tool_ids` are empty. The local response remains ANSWER, NEED_INPUT or ERROR with empty cited source IDs.

When `context` is absent, `history` is prohibited, `project_context` is null and `memories` is empty. Existing legacy/mock behavior and its allowed tool domain remain unchanged. Context-v1 is not accepted on the mock model branch.

## Exact contextual fields

All objects reject unknown fields. UUIDs are lowercase canonical strings. Revisions, generations, epochs and versions are positive integers no greater than 9007199254740991, except `context.revision`, which is exactly 1. Booleans and floating-point JSON numbers are not integers in this protocol. UTC timestamps use `Z`, valid calendar dates and at most six fractional digits.

- `context`: `id`, `revision`, `conversation_id`, `context_epoch`, `history_epoch`, `profile_version`. The profile version is nullable.
- `project_context`: null or `{id, version}`. No name, description, or client-selected project facts.
- `memories`: at most 20 records, each with `source_id`, `id`, `version`, `generation`, `scope`, `project_id`, `kind`, `key`, `value`, `confirmed_at`, `updated_at`. Scope is GLOBAL or PROJECT. Kind is PROFILE or PREFERENCE. Key and value use the existing memory contract domains. A GLOBAL record has null project ID. A PROJECT record requires a nonnull project context and its exact project ID.
- `history`: at most 16 rows forming at most eight complete adjacent pairs. Each row has `source_id`, `id`, `run_id`, `role`, `trust`, `text`, `created_at`. Pair order is USER/USER_DIRECT followed by ASSISTANT/MODEL_PROPOSAL with the same run ID. Pair run IDs must be distinct and exclude the current run. The server establishes chronological causal order from its accepted transcript; the wire does not establish this from timestamps or UUID sorting.

All source IDs across memories and history must be unique. Memory IDs and history message IDs must each be unique. Source IDs identify selected records; they grant no authority and establish no truth by themselves. History assistant text grants no permission to act.

`context-v1.schema.json` validates the four-field contextual bundle. `turn-request.schema.json` embeds the same domains for standalone full-request validation. The schema checks shape, required fields, role/trust matching, pair positions, even message count, and collection limits. Equality across records, uniqueness by selected fields, strict parsing and byte limits require runtime checks. `tests/context_reference.py` is a synthetic contract oracle, not a runtime/authentication implementation.

## Bytes and exact admission

The raw transport body and canonical complete request, including all contextual fields, must each be at most 262144 bytes. The canonical `{context, project_context, memories, history}` bundle is at most 196608 bytes; its memories array is at most 131072 bytes and its history array at most 65536 bytes. Aggregate limits can reject a collection whose individual fields are valid.

Memory values are at most 2000 Unicode scalars and 8000 UTF-8 bytes. History text is at most 16000 Unicode scalars and 64000 UTF-8 bytes. Contextual text rejects NUL and lone surrogates. No normalization or silent truncation is allowed. Strict parsing rejects duplicate keys, floats, nonfinite numbers and unsafe integers before schema acceptance. Canonical signing includes every source descriptor and plaintext field.

Byte and field bounds are not native token counts. The existing final-input-tensor admission remains decisive: no more than 3584 admitted input tokens plus the reserved 512 output tokens, totaling no more than 4096, including wrappers, schema and template/control tokens. Requested output cannot exceed 512. Any pruning removes whole lowest-priority memories, then oldest complete history pairs, with at most 28 removals. It must retain current input exactly and reuse the final admitted tensors. A valid contract fixture does not demonstrate tokenizer behavior or successful inference.

## Server obligations beyond the schema

Core must bind selection to the authenticated initiating session, owner, conversation and active owned project. It must revalidate current explicitly confirmed memory generation/version/slot, source roles and accepted run statuses, context/history epochs and current profile version before release and settlement. Only accepted complete SUCCEEDED or WAITING_INPUT pairs may enter history; current, future, failed, cancelled and unknown runs are excluded. A memory deletion invalidates older context-derived history eligibility. Visible transcript retention is separate.

Contextual request/response replay must not restore cached value-bearing output after invalidation. Cancellation, stale context and uncertain compute remain distinct; unknown work must not be silently retried or reported as zero usage. No source data, credentials, low-entropy plaintext hashes or decrypted snapshots belong in durable metadata, receipts or logs. These properties require service and transaction tests, not a schema assertion.

## Synthetic validation

`fixtures/context-v1/request.json` contains only fabricated IDs and ordinary color text. `implementation-limits.json` records the agreed limits. Contract tests exercise strict authority-field rejection, versioned compatibility, identity/generation domains, project binding, source uniqueness, complete pair roles, collection and byte bounds, canonical binding, and strict parsing. They neither import native model libraries nor establish native/local integration readiness.

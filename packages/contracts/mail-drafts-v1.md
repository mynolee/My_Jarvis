# Internal mail drafts v1

This contract describes manual, private, editable drafts in the application. It
adds no model operation, outbound mail, provider account, mailto launch, clipboard
action, attachment access, scheduler action or executable tool. Saving a draft
never represents sending it. All records have `delivery_status: "NOT_SENT"`.
Incomplete drafts are valid. Address-looking text is unverified text, not a
resolved recipient or an authorization to contact anyone.

## Routes and envelopes

All routes below have prefix `/api/v1` and use the existing success envelope
`{data, request_id}`. Schemas describe `data`, not that envelope.

- `POST /mail-drafts`: create request; returns mutation result.
- `PATCH /mail-drafts/{id}`: patch request; returns mutation result.
- `GET /mail-drafts/{id}`: full record.
- `GET /mail-drafts`: list-query parameters; returns metadata list.
- `GET /mail-draft-mutations/{key}`: metadata-only mutation receipt.

POST/PATCH require exactly one `Idempotency-Key` header containing a canonical
lowercase UUID. Path IDs and keys use the same canonical UUID syntax. No send,
delete, attachment-upload or generic execution endpoint is introduced.

## Editable fields

Create requires all eight fields, including nulls and empty arrays. PATCH requires
`expected_version` and at least one editable field; omissions preserve fields.
Unknown keys are rejected at every level. Null is allowed only where stated.

| Field | Representation and bounds |
| --- | --- |
| sender_candidate | null, or nonblank single-line text; 320 Unicode scalar values / 1280 UTF-8 bytes |
| to, cc, bcc | text arrays; each entry follows sender text rules; each array at most 30, combined at most 30 |
| subject | text, empty allowed; 200 scalar values / 800 UTF-8 bytes |
| body | text, empty allowed; 16000 scalar values / 64000 UTF-8 bytes |
| project_id | null, or canonical UUID of an owned active project for a new write |
| attachments | empty array only |

Single-line fields reject U+0000–001F and U+007F–009F. Body permits TAB, LF and CR
but rejects all other characters in those ranges. All strings reject isolated
surrogates. A nonblank value contains a character outside Unicode White_Space
plus U+FEFF. Preserve exact valid text; do not trim, normalize case, deduplicate
addresses or interpret HTML. Empty recipient arrays are valid; empty entries
inside a recipient array are invalid. There is intentionally no email-address
syntax or deliverability claim.

Each canonical request has a 65536-byte UTF-8 ceiling. The merged complete set of
eight editable fields also has that ceiling. PATCH checks combined recipient
count and all field bounds against the resulting full draft, not just the patch.
An unchanged submitted value is permitted and still creates one new version.

Versions are strict JSON integers in 1..9007199254740991; booleans and decimal
lexemes are not integers in this protocol. Reject duplicate JSON keys, duplicate
headers/query keys, nonfinite values and malformed Unicode before processing.
The existing HTTP raw request-body limit still applies independently.

## Results and listing

A record contains all editable fields plus `id`, `version`, `created_at`,
`updated_at`, and `delivery_status`. Timestamps are UTC `Z`, four-digit year
0001..9999, real calendar instants and optional 1..6 fractional digits.
`updated_at` must not precede `created_at`.

A metadata record contains exactly `id`, `project_id`, `version`, `created_at`,
`updated_at`, `delivery_status`. Lists and receipts never include sender,
recipients, subject, body or attachment content.

List query permits only optional `limit`, `cursor`, and `project_id`. Limit is a
string in canonical decimal 1..100, default 20. A project filter must refer to an
owned project; inactive owned projects remain listable. Absent filter means all
owned drafts, not only drafts with null project. Results are ordered by
`created_at DESC, id DESC`, use keyset pagination, and contain no duplicate IDs.
Response is `{items, next_cursor}`; `next_cursor` is null on the final page and
must be null on an empty page. A cursor is 1..1024 ASCII characters from
`[A-Za-z0-9_.-]`; syntactic validity alone never authenticates it.

The cursor payload is canonical JSON containing exactly:
`{owner_id, project_id, created_at, id}`. `project_id` is the filter or null for
an absent filter, not necessarily the anchor draft's project. Cursor payloads
are metadata, are authenticated, and are bound to owner and filter. A cursor
is not a snapshot: later inserts/edits are handled by normal keyset semantics.

## Mutations and reconciliation

A committed receipt is exactly
`{status:"COMMITTED", draft_id:UUID, version:integer, operation:"CREATE"|"UPDATE"}`.
A not-observed receipt is exactly
`{status:"NOT_OBSERVED", draft_id:null, version:null, operation:null}`.

A successful write result is `{draft:currentRecord, mutation:committedReceipt}`.
Receipt ID must match the record ID; receipt version must be no greater than
current record version. Exact committed replay returns the current record plus
the original metadata receipt, never a cached private draft body. An update
uses optimistic version matching. Version exhaustion fails instead of wrapping.

Bind each mutation key to authenticated owner, session and security epoch, plus
a keyed canonical commitment to method, path and request body. A changed request
under the same key fails, even across create/update routes. Missing/foreign keys
return NOT_OBSERVED; an existing owner's key with changed session binding fails.
State write, version increment, receipt and redacted audit event are atomic.
Concurrent equal requests cannot create duplicate drafts or duplicate increments.

An unknown HTTP outcome does not permit automatic resubmission. Pause related
writes and read the metadata receipt. COMMITTED permits an explicit current
record read. NOT_OBSERVED is not proof the operation cannot still commit; retain
the pending state and do not automatically generate a replacement key. Sign-out,
session replacement and disposal clear private form/recovery identifiers and
fence late responses. Do not store this material in browser persistent storage.

## Security and pause policy

All reads and writes require a live authenticated owner session and current
security epoch. Enforce this in transactionally relevant service paths, not only
an HTTP precheck. New writes respect global `execution_paused` and fail while
paused, matching the conservative behavior of existing internal data features.
Reads and reconciliation remain available while paused. An exact previously
committed replay is allowed only after live session/epoch validation.

Internal drafting is a direct authenticated form operation; it does not require
a new grant, typed intent or recent reauthentication. Respecting global pause is
an application safety choice, not reclassification as an external action.

All private editable text, recipient lists and attachments representation are
encrypted at rest with owner/object/purpose-bound authenticated encryption.
Never persist unkeyed hashes of guessable private requests. Audit events contain
only action kind and identifiers. Conflicts, logs, list responses and mutation
receipts must not echo private draft content. Existing CSRF, Origin, JSON and
no-store controls apply. Foreign draft/project identifiers fail without content
or existence disclosure. UI renders text, not markup.

## Key derivation and synthetic vectors

Use the existing private 32-byte `jarvis.key-file`; no new credentials or config
are introduced. Apply the existing regular-file, owner-only, no-symlink checks.
Derive separate subkeys with HMAC-SHA256:

- request key = HMAC(raw key, UTF8("jarvis:mail-drafts:request:v1"))
- cursor key = HMAC(raw key, UTF8("jarvis:mail-drafts:cursor:v1"))

Request digest is lowercase hex HMAC(request key,
JCS({owner_id, method, target, body})). Method is POST or PATCH; target is the
exact API path including draft ID for PATCH. The request key itself does not
replace persisted session/epoch binding checks.

Cursor token is unpadded canonical base64url(payload bytes), then `.`, then
unpadded canonical base64url(HMAC(cursor key, payload bytes)). Reject malformed,
noncanonical, oversized or unauthenticated encodings, unknown payload fields,
owner/filter mismatch and invalid payload timestamps/UUIDs. Compare MACs in
constant time. No expiry is claimed. Clear derived keys on disposal.
`fixtures/mail-drafts/commitment-vector.json` uses deliberately synthetic bytes
00..1f, reserved example addresses and synthetic IDs; it is not a secret.

## Errors

Use the existing error envelope. Error details are empty for this feature,
including optimistic conflicts; reread detail explicitly if needed.

- 400 INVALID_REQUEST: malformed input, unsupported attachments, cursor failure,
  bound violation or invalid/inactive target project for a write.
- 401 AUTHENTICATION_REQUIRED or SESSION_REVOKED: authentication/session failure.
- 403 MUTATION_SESSION_CHANGED: known mutation no longer bound to this session.
- 403 ORIGIN_REJECTED or CSRF_REJECTED: existing mutation guards.
- 404 NOT_FOUND: missing/foreign draft or project.
- 409 IDEMPOTENCY_CONFLICT: same key, different request.
- 409 PRECONDITION_CHANGED: stale expected_version.
- 409 EXECUTION_PAUSED: new mutation blocked by global pause.
- 409 VERSION_EXHAUSTED: version cannot increment safely.
- 415 JSON_REQUIRED: existing transport content-type guard.

## Evidence boundaries

Schemas and Python reference tests establish synthetic wire and commitment
consistency only. Runtime must separately verify merged-state bounds, encrypted
storage, authorization, atomic PostgreSQL behavior, concurrency, HTTP guards,
unknown-outcome behavior and browser rendering. JSON Schema alone does not reject
all noninteger numeric lexemes or establish authorization. No schema, test plan,
fixture or authored test constitutes a passing runtime or release result.

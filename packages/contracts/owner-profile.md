# Owner-profile wire contract

This independent contract slice describes three explicit settings. All examples
are synthetic. Schemas validate the `data` payload inside the existing
`{data, request_id}` API envelope; they do not replace authentication or prove
runtime security guarantees.

## Routes

- `GET /api/v1/me`: `owner-profile.schema.json`, preserving existing owner fields.
- `PATCH /api/v1/me`: `owner-profile-patch-request.schema.json`, normalized header
  projection `owner-profile-mutation-headers.schema.json`, response
  `owner-profile-patch-result.schema.json`.
- `GET /api/v1/profile-options`: `owner-profile-options.schema.json`.
- `GET /api/v1/profile-mutations/{key}`:
  `owner-profile-mutation-path.schema.json` and
  `owner-profile-mutation-receipt.schema.json`.
- All these routes accept no query parameters; `owner-profile-query.schema.json`
  is the decoded query-object contract. Unknown and duplicate parameters are
  rejected by the HTTP implementation. The header schema is only the extracted
  idempotency header, not the full HTTP header collection. HTTP header names are
  case-insensitive; the server requires exactly one UUID Idempotency-Key value.

PATCH requires a positive safe `expected_version` and at least one submitted
editable field. Omitted fields remain unchanged; only `display_name` permits
null, which explicitly clears it. No implicit trimming or Unicode normalization.
Defaults are null display name, `ko-KR` locale, `UTC` timezone, and null update
time. These are product defaults, not inferred personal preferences.

## Validation boundaries

Portable schema checks cover closed object shapes, nullability, exact locales,
safe integer bounds, Unicode scalar/control/nonblank/codepoint constraints,
syntactic timezone candidates, content-free receipt status variants, sorted
nonempty submitted-field combinations, and fixed boolean effect flags. The inference timezone must equal the saved timezone; consumers enforce this sibling-field equality semantically. Consumers must
activate `uuid` and `date-time` format assertions and validate actual dates.
UTC timestamps use `Z`, years 0001..9999 and at most six fractional digits.

The backend additionally enforces:

- Strict JSON: no duplicate keys, floating-point/exponent integer forms,
  nonfinite numbers, malformed Unicode or unsafe integers. Canonical PATCH body
  maximum 4096 UTF-8 bytes; existing raw HTTP body maximum 262144 bytes.
- Display name 1..80 codepoints and at most 320 UTF-8 bytes, no ISO controls
  U+0000..001F or U+007F..009F, and not entirely Unicode White_Space plus FEFF.
  Empty string is invalid. A valid scalar sequence within the codepoint bound
  also meets the byte bound; the server checks both explicitly.
- Exact case-sensitive timezone membership in its startup set: `UTC` plus
  Java's bundled TZDB IDs containing `/`, excluding `SystemV/`. Each ID is ASCII
  and at most 80 bytes and resolves to ZoneRules. Aliases are retained exactly.
  The regex deliberately cannot establish that a region exists or has correct
  case. Raw offsets, offset-only UTC/GMT forms and short abbreviations fail.
- Options expose the exact sorted, unique supported IDs and deployed
  `tzdb_version`; browser Intl support does not define server acceptance.
- Authenticated owner/live session and security epoch, expiry, revocation,
  Origin/CSRF/JSON protections, owner/session transaction locking, encryption,
  DB-clock timestamps, atomic version/audit/receipt persistence, and request
  digest/session-bound idempotency. None is proven by a passing schema test.

## Writes, receipts and effect timing

A newly accepted key increments the shared owner version once, even when values
are unchanged. Replay never increments it. A matching same-session replay
returns the current profile plus the original committed metadata; these version
numbers can differ. The receipt contains only status, owner ID, committed
version, and lexicographically sorted submitted field names. It contains no
submitted or historical profile values. A newly valid session may reconcile an
owner's receipt, but cannot replay a previous session's mutation.

`NOT_OBSERVED` has null metadata and is inconclusive while a request could still
commit. Clients must not automatically resubmit unknown writes. After a
committed receipt, fetch the current profile separately. Version conflict is
409 `PRECONDITION_CHANGED`, with only `current_version` in details; review the
current profile before an explicit new save with a new key. A changed body
under the same key is 409 `IDEMPOTENCY_CONFLICT`; a changed write-session binding
is 403 `MUTATION_SESSION_CHANGED`; an unsupported timezone is 400
`INVALID_TIMEZONE`. Runtime tests, not these schemas, establish those errors and
ordering guarantees.

The saved timezone affects the settings timestamp preview and contextual local-model
requests when the runtime is ready. `profile_effects.inference_timezone` equals
the enclosing `timezone` exactly, including aliases. The other effect flags retain
false for UI translation, display-name/locale chat personalization, and scheduling.
Explicit memories and newer, causally ordered history are eligible context data;
that does not promise correct model use or establish native answer quality.
No memory is automatically written from a chat. Pre-context history with unknown
causal order stays visible in the transcript but is excluded from model context.
Settings and memory edits conservatively invalidate active context without
changing the security epoch. Late output is discarded and may be UNKNOWN;
computation can continue until cancellation is observed, so no immediate stop
is promised. No task
instants are rewritten, local wall times created, schedules moved or permissions
changed. A preview formats a known server UTC instant and shows the selected ID
and numeric offset, including across DST. Unsupported browser formatting must
show the saved ID and a clear UTC fallback; it must not silently substitute a
zone. A stale server-time sample must be labelled as checked-at rather than a
live clock.

## Tests

Run `.venv/bin/python -m unittest discover -s tests -v` from this package.
`tests/test_owner_profile_contracts.py` adds independent wire regressions alongside the existing contracts. Fixtures cover defaults, partial writes, explicit clear,
first success, current-profile replay with a newer version, both receipt states,
options, empty query, headers and path. Runtime concurrency, encryption, audit
rollback, session races, authoritative TZDB membership, inference regression and
UI behavior require backend/frontend integration tests.

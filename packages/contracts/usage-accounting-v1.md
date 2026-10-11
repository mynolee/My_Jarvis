# Usage-accounting summary contract

This is an application contract with synthetic examples. It contains no user
content, private configuration, model paths, credentials, prompts or pricing.
The reference tests describe accounting semantics; they do not prove deployed
HTTP authorization, transactions, real model inference or native measurement.

## Route and bounded projection

`GET /api/v1/usage` returns the `usage-response.schema.json` data payload inside
the existing `{data, request_id}` API envelope. It accepts no query parameters
(`usage-query.schema.json`) and no nonempty request body. Unknown or duplicate
parameters fail. There is no caller-selected owner, timezone, date range,
model, cursor or action. The two fixed summaries contain no per-request rows,
identifiers, model hashes, content, local paths or authority fields.

The server authenticates the owner and revalidates the live session, security
epoch, expiry and revocation in the read transaction. Recent reauthentication
is not required just to read. Another owner's data is never included. The read
must not enqueue work, dispatch or cancel inference, release reservations,
rewrite historical rows, reconcile unknown execution, change profile settings,
reprice resources or contact a model/provider. Reading while execution is
paused remains possible for a valid owner session.

The result has these closed fields:

- `checked_at`: exact database-clock UTC observation timestamp.
- `timezone`: exact saved owner-profile timezone, preserving supported aliases.
- `attribution`: always `RESERVATION_CREATED_AT`.
- `quota_timezone`: always `UTC`.
- `day` and `month`: the current profile-local calendar periods.

Every period contains `local_key`, `start_at`, `end_at`, `request_count`,
`counts`, `measured` and `conservative_reserved`. Day keys are `YYYY-MM-DD`;
month keys are `YYYY-MM`. Bounds are UTC instants, start-inclusive and
end-exclusive. `request_count` counts reservations in that period, one
inference request per reservation. The response size is fixed independently
of how many requests exist. Storage queries are owner-scoped and restricted
to the current local month/day; this route does not export historical events.

## Coherent snapshot, attribution and time

Both periods, the profile timezone, classification and measurements come from
one consistent database snapshot. The authenticated session is checked inside
that snapshot. `checked_at` is sampled from the database, never replaced by a
browser or application host clock. Reservations created after this timestamp
and observations recorded after it are excluded. The field is a checked-at
sample, not a live clock or proof that no work continued afterward.

A reservation's immutable `created_at` chooses its period for every category
and subtotal. A late measurement remains attributed to that original instant,
even if observed in a later day or month. The private ledger retains its
separate database `recorded_at`. Changing the saved display timezone can regroup
an existing instant; it does not rewrite the reservation or usage event.

Compute bounds from civil calendar starts in the saved server-supported
TZDB zone. A day can be 23 or 25 hours, a transition can skip local midnight,
and month lengths vary. Do not obtain the next start by adding a fixed 24
hours or a fixed number of seconds to the current start. At an overlap use
the earliest valid start; at a gap use the first valid instant of that date
or the next valid date if the entire date is skipped. Reject unrepresentable
bounds rather than silently substituting another zone or overflowing dates.

Timestamps are real UTC calendar instants, years 0001 through 9999, uppercase
`T` and `Z`, at most six fractional digits. The timezone uses the profile's
exact server-supported zone set; lexical schema validation alone cannot prove
zone membership. Browser TZDB/Intl support is not the acceptance authority.
Visible timestamps use Korean time (KST), separately from the saved accounting
zone. Preserve the exact UTC instants in machine-readable `time.dateTime`
attributes. If the browser cannot format the saved accounting zone, show that
zone ID and an explicit limitation; do not replace the server's accounting zone
or display a browser-derived accounting period as verified.

The browser validates exact current calendar bounds for UTC and conservative
duration/calendar-proximity constraints for other zones. Exact non-UTC civil
bounds remain the responsibility of the server's supported TZDB. Passing the
browser's plausibility checks does not independently establish non-UTC TZDB
correctness or validate membership in the server's complete zone set.

These profile-local summaries do not redefine quota periods. Existing
reservation-based daily/monthly UTC enforcement remains unchanged. There is
no remaining-budget, remaining-call or execution-permission claim in this
response.

## Complete, disjoint classification

`counts` contains all six safe nonnegative integer fields:

- `measured`: a trusted, fully validated, request-bound final `ANSWER` or
  `NEED_INPUT` observation has accepted actual metrics.
- `pending`: accounting-supported `QUEUED` reservation with no dispatch marker
  and no final error/measurement observation.
- `in_progress`: accounting-supported `STARTED` reservation with a dispatch
  marker and no final error/measurement observation. It does not claim that
  process liveness has independently been verified.
- `unstarted`: non-execution is positively established by accounting-supported
  `CANCELLED` with no dispatch marker. This is not inferred from zero metrics.
- `uncertain`: an observed `ERROR`, unknown/failed/otherwise terminal outcome
  without valid actual measurement, started cancellation, or inconsistent
  state/dispatch markers. Full actual metrics remain unknown.
- `legacy_unavailable`: historical accounting provenance predates this ledger
  and there is no later usable observation. A historical success status is not
  measurement evidence.

Classification priority is: accepted measured observation; observed error;
legacy-unavailable provenance; positively unstarted cancellation; consistent
pending; consistent in-progress; otherwise uncertain. Each included request
occupies exactly one category. Their checked sum equals `request_count`.
Explicit error evidence can improve a historical record's classification to
uncertain, and late actual evidence can improve it to measured.

A `QUEUED` row with a dispatch timestamp, or a `STARTED` row without one, is
uncertain. The backend must preserve its actual provenance rather than fix
inconsistent history during a read. Schema validation does not establish that
these source classifications were truthful.

## Measured subtotal and unknown money

`measured` has these fields:

- `request_count`, equal to `counts.measured`.
- `input_tokens`, `output_tokens`, `compute_ms`: checked sums of actual values
  from measured requests only.
- `peak_memory_bytes`: maximum observed peak across measured requests, never
  a sum, mean, reservation ceiling or current-process-memory claim.
- `external_cost_usd_micro`: always null in this slice because no external
  monetary-cost source is connected to this ledger.

When measured count is zero, additive subtotals are zero as empty sums and
peak memory is null because no peak was observed. A count greater than zero
requires a nonnegative integer peak; an actually reported zero remains zero.
An empty subtotal does not assert native computation consumed nothing.
The UI must show category counts alongside actual totals so missing evidence
is not hidden by an apparently complete zero.

Every current Python `ERROR` response is unmeasured, including zero-shaped
fallback metrics and even nonzero-shaped error metrics. Its token, compute
and memory fields cannot be reused as trusted native consumption. Invalid,
unauthenticated or mismatched responses likewise cannot create actual usage.
A future measured-error protocol would require an explicit reviewed revision.

Null monetary cost never means free service, zero electricity cost, zero
external spending or a calculated model-token price. Do not convert local
tokens, compute time or memory into money, attach an invented currency quote,
or infer a paid-provider charge. The USD-micro field is a reserved monetary
unit name, not a currency/pricing integration.

## Separate conservative reservation subtotal

`conservative_reserved` has `request_count`, `max_compute_ms` and
`max_output_tokens`. It aggregates the original ceilings of all included
non-`CANCELLED` reservations, including measured, pending, in-progress,
uncertain and legacy requests where that retention condition applies.

This is a separate view of the same requests, not additional measured
consumption. Never add its ceilings to actual totals. Never replace its
ceilings with actual metrics, release it because a summary was read, or
interpret it as the amount still available. Existing UTC enforcement and
cancellation retention behavior are preserved, including the distinction
between a retained uncertain reservation and an excluded cancelled one.
Consequently, a late measured cancelled request can contribute actual usage
while contributing no retained ceiling.

The subtotal's request count cannot exceed the period request count. Empty
reservation count requires both ceiling sums to be zero. Each retained request
has original positive ceilings at most 60000 compute milliseconds and 512
output tokens, so each sum is between its request count and the corresponding
per-request ceiling multiplied by that count.

## Immutable accounting, separate from execution authority

The internal ledger retains verified owner, run, reservation, inference request,
model ID, manifest hash, trusted budget revision, original ceilings, measured
metrics, nullable external cost and the database observation timestamp. These
identities come from the authenticated dispatch/validated response binding,
never model-generated authority. The public summary does not expose them.

Stored `budget_revision=1` identifies the Core local-text protocol: one model
call, at most 3584 input tokens, 512 output tokens and 60000 compute milliseconds.
It is distinct from `accounting_version`, which marks whether a reservation has
accounting provenance or predates this accounting rollout.

At most one accepted measured observation exists per request. Repeating the
same identity and metrics is a no-op and retains its first observation time.
A conflicting duplicate cannot overwrite, erase, add a second measurement,
or revise historical usage. A separately immutable `ERROR` observation is
allowed; it must not occupy or invalidate the later actual-measurement slot.
Error observation after actual measurement never downgrades measured evidence.
The source payload, identity binding, ceilings and safe numbers must be
validated before either kind of observation is persisted.

Accounting persistence is independent of applying generated output. A fully
valid late measurement after pause, logout, epoch change, timeout or context
invalidation is still factual usage evidence. It can be recorded without a
live interactive session, provided it belongs to the original trusted dispatch.
It must never revive a cancelled/unknown Run, reveal discarded output, settle
an invalid context, recreate an approval/grant, or authorize another dispatch.
Unknown outcome is not automatically retried. Dispatch that was positively
never started cannot fabricate measurement evidence.

## Numeric and semantic validation

All wire integers are within 0 through 9007199254740991. No floating-point
forms, exponent forms, NaN, Infinity, duplicate keys, malformed Unicode or
unsafe integers are accepted. Schema validation must enable format checks.
Objects are closed at every level.

Each accepted request stays within its original verified ceilings: at most
3584 input tokens, 512 output tokens and 60000 compute milliseconds for this
local-inference slice. Memory is a safe nonnegative integer. Aggregate addition
uses checked arithmetic or exact wider arithmetic followed by safe-range
validation. `USAGE_AGGREGATE_OVERFLOW` fails the whole summary; never wrap,
round, clamp, return partial subtotals or silently replace overflow with zero.
The frontend can use BigInt for intermediate sums/products while keeping
ordinary safe JSON integers on the wire.

Semantic checks supplement schema shape:

- Period keys and exact civil bounds agree with `checked_at` and timezone.
- Every request category is counted once; measured count agrees with its
  category, and reservation count does not exceed period count.
- Actual aggregate input/output/compute values cannot exceed the number of
  measured requests multiplied by each respective per-request ceiling.
- Day is contained in month; day category counts and additive subtotals do not
  exceed month values; a known day peak does not exceed the known month peak.
- Month-minus-day additive actuals stay within their extra measured request
  count times each cap; equal measured counts require equal additive totals and
  equal peak. Reservation ceiling differences stay between their extra retained
  count and that count times each cap.
- The peak is null exactly when no measurement exists; monetary cost is null.
- No arithmetic relation implies a measured total must equal a reservation.

## Verification

From `packages/contracts`, run:

    .venv/bin/python -m unittest discover -s tests -p test_usage_contracts.py -v
    .venv/bin/python -m unittest discover -s tests -v

`fixtures/usage` contains only synthetic fixed-size response examples.
`tests/usage_reference.py` models the classification, calendar aggregation and
immutable-observation rules independently of the Java implementation.
`tests/test_usage_contracts.py` covers strict wire validation, null versus zero,
error fallback, duplicate conflicts, late evidence without authority revival,
legacy provenance, owner filtering, read-only behavior, overflow, period
attribution, DST and snapshot cutoffs.

Passing these tests does not prove database uniqueness/rollback/isolation,
HTTP/session security, migration safety, concurrent settlement, native Python
measurement quality, browser rendering or production quotas. Those require
separate backend, frontend and runtime evidence. This slice enables no native
inference, external provider, GPU, scheduling or quota-management action.

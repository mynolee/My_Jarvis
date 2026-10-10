# Internal schedules and inbox v1

This data-only application contract contains synthetic examples. It does not
activate a real reminder, grant model/tool authority, or prove runtime security.
Schemas describe `data` inside the existing `{data, request_id}` API envelope.

## Frozen routes and bodies

Every route starts with `/api/v1`. Every JSON object is closed. Every field listed
below is required, including explicitly nullable fields. HTTP paths use canonical
lowercase UUIDs. No route accepts an owner ID, external destination, tool name,
callback, provider, permission, model output, or client-chosen status.

- POST `/schedule-preparations`: `{operation,target_id,expected_version,definition}`.
  CREATE requires null target_id and expected_version. REPLACE requires an owned
  schedule UUID and positive safe expected_version. No Idempotency-Key is required
  for preparation: a retry may create a separate inert preview. Response is the
  preparation record below. Preparation alone creates no schedule/job/notification.
- POST `/schedule-preparations/{id}/confirm`: `{expected_preparation_version,
  payload_hash,confirm}`; version is exactly 1 and confirm exactly true. Exactly
  one UUID Idempotency-Key is required. Response is a mutation receipt only.
- POST `/schedules/{id}/disable`: `{expected_version}` and exactly one UUID
  Idempotency-Key. Response is a mutation receipt only. No delete/re-enable route.
- GET `/schedules`, GET `/schedules/{id}`, GET `/schedules/{id}/occurrences`.
- GET `/schedule-mutations/{key}`: metadata-only mutation receipt.
- GET `/notifications`, GET `/notifications/{id}`.
- POST `/notifications/{id}/open`: exact body `{}`; no Idempotency-Key required.
  The write is inherently idempotent and returns the notification record. A retry
  cannot change its original opened_at. A GET never changes opened_at.

Only list routes accept query parameters: optional limit (decimal ASCII 1..50,
no signs/leading zeroes, default 20), cursor (opaque nonempty ASCII URL-safe token,
maximum 1024 characters), and project_id (UUID, only schedules/notifications).
The occurrence route permits only limit/cursor. Omission means all owned projects;
project_id=null, empty, arrays, unknown and repeated parameters are invalid.
Decoded-query schemas represent limit as an integer after strict HTTP decoding.
Other routes reject every query parameter. Header schemas describe only the
extracted Idempotency-Key, not the complete HTTP header collection.

## Exact records

Definition:
`{title,text,project_id,recurrence,start_date,local_time,timezone,weekdays,
authorization_expires_at,max_deliveries}`.

- title: 1..200 Unicode scalars, nonblank, at most 800 UTF-8 bytes.
- text: 1..2000 Unicode scalars, nonblank, at most 8000 UTF-8 bytes.
- Neither text field permits ISO controls U+0000..001F/U+007F..009F or lone
  surrogates. No trimming, Unicode normalization, interpretation or truncation.
- project_id: null or owned UUID. Content containing HTML/URLs is inert text.
- recurrence: ONCE, DAILY, WEEKDAYS, WEEKLY, CUSTOM_DAYS.
- start_date: real Gregorian date YYYY-MM-DD, year 0001..9999.
- local_time: exactly HH:MM, 00:00..23:59, minute precision.
- timezone: exact case-sensitive ID from `/profile-options` timezones. Same
  existing domain: UTC or a supported slash-separated Java TZDB ID, excluding
  SystemV/. No raw offsets, short abbreviations or inferred timezone.
- weekdays: sorted unique ISO weekdays 1=Monday..7=Sunday. ONCE/DAILY/WEEKDAYS
  require []; WEEKLY requires exactly one day matching start_date's ISO weekday;
  CUSTOM_DAYS requires 1..7 days. WEEKDAYS means Monday through Friday.
- authorization_expires_at: UTC timestamp. Must be later than issuance and at
  most 2592000 seconds after issuance. Expiry is exclusive at planning/delivery.
- max_deliveries: integer 1..31; ONCE requires exactly 1.

An occurrence preview is `{planned_fire_at_utc,local_datetime,offset_seconds,
dst_adjustment}`. local_datetime is the resolved wall time YYYY-MM-DDTHH:MM:SS,
without zone or fraction; offset_seconds is an integer -64800..64800.
dst_adjustment is NONE, GAP_FORWARD or FOLD_FIRST. planned_fire_at_utc is UTC.

A preparation is `{id,version,status,operation,target_id,expected_version,
definition,destination,next_occurrence,planned_delivery_count,
max_lateness_seconds,issued_at,expires_at,payload_hash}`.
version=1, status=PREPARED, destination=OWN_INBOX, max_lateness_seconds=3600.
target_id is always nonnull (reserved by the server on CREATE); expected_version
is null on CREATE or positive safe integer on REPLACE. next_occurrence is
nonnull, and planned_delivery_count is 1..max_deliveries. The count includes only
eligible slots strictly after issued_at and strictly before authorization expiry,
starting no earlier than start_date. Enumeration is bounded to the issuance-to-
expiry local-date window even when start_date is year 0001. expires_at is issued_at + 300 seconds. Confirmation must
occur before both preparation expiry and the first previewed occurrence; otherwise
prepare and review again. The exact preview is never silently recomputed on confirm.

A schedule is `{id,version,status,definition,destination,next_occurrence,
delivered_count,created_at,updated_at}`. destination=OWN_INBOX. status is ENABLED,
DISABLED, EXPIRED, EXHAUSTED or RECONFIRM_REQUIRED. next_occurrence is nullable;
non-ENABLED records have null next_occurrence. delivered_count is integer 0..31
for the current version's authorization. Replacing resets this count; disable advances version with count zero and no
authorization for that new version. Prior
occurrences and inbox records retain their original schedule_version. ENABLED
means currently eligible under owner epoch/pause/expiry/quota, not mere stored flag.

An occurrence-history record is `{id,schedule_id,schedule_version,
planned_fire_at_utc,status,reason_code,notification_id,lateness_seconds,settled_at}`.
status is PLANNED, DELIVERED, SKIPPED_MISSED, BLOCKED or CANCELLED.
reason_code is null, PAUSED, EPOCH_CHANGED, DISABLED, REPLACED,
AUTHORIZATION_EXPIRED, QUOTA_EXHAUSTED, OPERATIONAL_FAILURE or
SUPERSEDED_MISSED. SUPERSEDED_MISSED is permitted only with CANCELLED.
PLANNED has null reason_code/notification_id/lateness_seconds/settled_at.
DELIVERED has null reason_code, nonnull notification_id/settled_at, lateness 0..3600.
SKIPPED_MISSED has null reason_code/notification_id, nonnull settled_at, lateness
at least 3601. BLOCKED/CANCELLED have a nonnull reason_code/settled_at, null
notification_id and nonnegative lateness. This history carries no reminder text.
SKIPPED_MISSED rounds elapsed seconds up; all other terminal statuses round down.
The execution grace check uses
full-precision duration <=3600 seconds, not this floored display value.

A notification is `{id,occurrence_id,schedule_id,schedule_version,project_id,
title,text,planned_fire_at_utc,created_at,opened_at,status,lateness_seconds}`.
Content is an immutable encrypted copy from the delivered schedule version.
status=CREATED iff opened_at is null; otherwise OPENED. lateness_seconds is
integer 0..3600. No SENT, RECEIVED or provider-delivery claim is made.

All three list responses are `{items,next_cursor}`. next_cursor is null or an
opaque token. Schedules/notifications order by `(created_at,id)` descending;
occurrences order by `(planned_fire_at_utc,id)` descending. Cursor boundaries are
exclusive; tokens bind owner, list kind, optional project filter and schedule ID
where applicable. They never confer access. Foreign resource IDs and cursor
ownership/scope mismatches fail without disclosure. Lists are keyset pagination,
not a cross-request snapshot: later inserts can require a fresh first-page read.

A mutation receipt is exactly `{status,schedule_id,version,operation}`.
COMMITTED requires schedule UUID, positive safe committed version and operation
CREATE/REPLACE/DISABLE. NOT_OBSERVED requires all three metadata fields null.
Receipts contain no definition, project, title/text, request echo or payload hash.
NOT_OBSERVED is inconclusive while the original request may still commit.
After COMMITTED fetch current schedule separately; it can have a newer version.

## Strict values, canonical binding and privacy

Versions/epochs are positive integers <=9007199254740991. Parse strict integer-only
JSON before schema validation: reject duplicate keys, floats/exponents, nonfinite
numbers, unsafe integers and lone surrogates. Booleans are not integer values.
Canonical request bodies are at most 16384 UTF-8 bytes; the existing 262144-byte
raw HTTP ceiling also applies. JCS uses existing integer-only canonical.py:
UTF-16 code-unit property order, UTF-8, no whitespace, no normalization.

UTC timestamps require uppercase T/Z, real dates, year 0001..9999, seconds 00..59,
and at most six fractional digits. Offsets, lowercase t/z, leap seconds and extra
precision are rejected. Resolved local preview seconds permit historical zone
transitions; date/time validity and sibling-field consistency need semantic checks.

payload_hash is an opaque, lowercase 64-hex server HMAC-SHA256 commitment, not a
client-generated plaintext content hash. The exact canonical preparation payload
is `{revision,owner_id,session_hash,security_epoch,preparation}` where revision is
`schedules-inbox-v1` and preparation is the entire preparation response with only
payload_hash omitted. session_hash is the existing server session-token digest;
it is never returned in the preview. Given the existing 32-byte private Vault key:
K = HMAC-SHA256(vault_key, UTF8("jarvis:schedules-inbox:v1")); commitment =
HMAC-SHA256(K, UTF8(owner_id + "|preparation|" + JCS(payload))).
Synthetic fixtures use a deliberately public all-zero test key; never use it at
runtime. Stored request digests use the same K with separate purpose `mutation`
and canonical `{operation,target_id,body}`; target_id is the preparation ID on
CREATE/REPLACE confirmation and schedule ID on DISABLE. This binds route targets
and operation, not just JSON. Bind mutation keys to the current session/epoch too.
Do not store plaintext canonical payloads/content or ordinary low-entropy hashes
in mutation/audit/error rows. Preparation snapshots and notification content use
purpose/resource/owner-bound encryption. Tests below cannot prove this storage.

## Runtime authority, timing and product choices

Live owner/session/epoch and existing Origin/CSRF protections govern all writes.
Activation/replacement requires recent reauthentication under the existing policy.
Preparation can precede reauth, but session rotation invalidates it. Prepare and
review anew; never silently retry/replay a confirmation after rotation. Preparation
is immutable, single-use, session/epoch bound and consumed atomically with the
schedule version, authorization, audit and receipt. Matching same-session/epoch
key/body replay returns the original receipt only; changed body conflicts, another
session cannot replay, and a different key cannot consume a used preparation.

Disable remains available while paused and without recent reauth; it increments
version and fences old undelivered work. Re-enable is an explicit REPLACE
preparation/confirmation. Logout does not revoke an intentionally confirmed bounded
reminder. Pause/epoch change requires fresh confirmation even after resume: this is
a conservative product choice. A profile timezone edit never retimes a schedule.
The UI must show the definition timezone, own-inbox-only effect, recurrence/end/
count, exact local/UTC next occurrence, any DST adjustment and missed-time policy.
No model call, external network, task-state mutation, generic grant or generic jobs
queue is part of delivery. A dedicated durable reminder queue is mandatory.

Capture one authoritative database clock instant per transaction. Both planning
and settlement check owner/epoch/pause, current schedule version, authorization,
expiry/quota and worker lease/generation under consistent owner-first locking.
No effect may commit after a serialized pause/disable/replacement. At most one
notification per occurrence; delivery count, notification, settlement and audit
commit atomically. Restart/takeover never resurrects revoked authority.

Recurrence recomputes local dates from the original wall-time rule. DST gap moves
to the first valid local instant, e.g. 02:30 in a 02:00..03:00 gap becomes 03:00,
not 03:30. Fold chooses the first/earlier UTC instant exactly once. Non-hour and
whole-date gaps follow the same rule; two nominal dates resolving to the same UTC
instant are deduplicated before counts/queueing. Unsupported ZoneRules fail closed.

At lateness <=3600 seconds deliver at most the latest eligible slot once, still
subject to expiry/quota. At >3600 seconds mark SKIPPED_MISSED, retain metadata,
and emit no inbox item. Applying this skip rule to simple reminders is a documented
product choice. Skipping uses full duration; reported skipped lateness is ceiling
elapsed seconds so even 3600.000001 is represented as 3601. Other reported lateness
uses floor. If multiple distinct due slots are still within grace, cancel earlier ones with
SUPERSEDED_MISSED (floor lateness, no notification) and queue only the latest. This
no-storm product behavior is separate from same-instant deduplication and the
>one-hour skip rule. At most 100 schedules can hold active authorization per owner.
No alert storm, automatic run-now, silent renewal, native push, email,
calendar provider or full release/native acceptance is claimed by this slice.

Stable existing-style errors: INVALID_REQUEST (400), INVALID_TIMEZONE (400),
BODY_TOO_LARGE (413), NOT_FOUND (404), PRECONDITION_CHANGED (409, metadata-only
current_version when applicable), IDEMPOTENCY_CONFLICT (409),
MUTATION_SESSION_CHANGED (403), APPROVAL_EXPIRED (409), APPROVAL_CONSUMED (409),
REAUTHENTICATION_REQUIRED (403), EXECUTION_PAUSED (409), VERSION_EXHAUSTED (409), SCHEDULE_LIMIT_REACHED (409), and
UNSUPPORTED_LOCAL_TIME (400).
Authentication retains existing SESSION_REVOKED/other auth errors. Unknown outcomes
freeze write resubmission; reconcile metadata, never infer failure from missing data.

In the UI, an unknown request freezes replay while its panel remains mounted. A
mounted same-owner session rotation may retain only content-free IDs/key/operation/
version metadata for explicit read-only reconciliation. Disposal, sign-out and 401
clear private IDs and content. Across sign-out the app may retain only a
nonidentifying warning to review saved schedules/inbox after signing in; it must
not automatically replay or recover the former write. The receipt endpoint can
serve same-owner metadata under a new valid session when the caller separately
has its key; that capability does not imply persistent browser storage.

## Verification boundary

`tests/test_schedules_inbox.py` and `tests/reminder_reference.py` validate synthetic
wire shapes, strict parsing, canonical commitments, byte/privacy limits and pure
recurrence/lateness vectors. They do not prove Java/TypeScript integration, DB
clock use, transactions/races, ownership/session enforcement, encryption, restart,
UI rendering, real notifications, external effects or model/native readiness.
Consumers must use these exact fixtures and separately execute runtime tests.

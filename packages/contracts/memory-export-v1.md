# Current explicit-memory export v1

This bounded slice implements the memory-export part of PRIV-01, not the whole
privacy requirement. The reviewed v4 source has SHA-256
`7f8aff7a138a5253fc048effafeb8d5394577974fb756ebe400a0c5024956207`:
§3.3 PRIV-01 (line 317), §17.3 POST /exports (line 1986), and §18.1 privacy settings
(line 2166). The exact wire names, limits and session binding below are deliberate
implementation decisions, not claims that the specification mandates each detail.

Only an owner's explicit request can create a job for current explicit memories
in GLOBAL or one owned PROJECT. Archived owned projects remain eligible. This
slice does not export conversations, revisions, deleted records, retrieval indexes,
attachments, account credentials, system prompts, inferred memories, audit history,
provider data or another owner's data. It invokes no model or external provider.
All examples and executable reference models are synthetic.

## Routes and closed wire shapes

All metadata and receipt responses use the existing `{data, request_id}` envelope;
the schemas describe `data`. The download is a raw UTF-8 JSON attachment instead.
All routes reject every query parameter (including duplicate/empty/unknown query
keys). GET routes reject any body. UUID paths and the one extracted Idempotency-Key
header use the closed path/header schemas. HTTP header names remain case-insensitive.

- POST /api/v1/exports takes `export-create-request.schema.json`: exactly `kind`
  EXPLICIT_MEMORIES, `scope` GLOBAL or PROJECT, `project_id` null for GLOBAL or one
  canonical lowercase UUID for PROJECT, and `format` JSON_UTF8. It requires one
  canonical UUID Idempotency-Key. It returns `export-job.schema.json`.
- GET /api/v1/exports/{job_id} returns the job schema.
- GET /api/v1/exports/requests/{key} returns `export-receipt.schema.json`: COMMITTED
  with a job, or NOT_OBSERVED with null job. Job IDs and request keys are identifiers,
  never bearer authority. Static `/requests/` routing precedes variable job paths.
- GET /api/v1/exports/{job_id}/download returns `export-document.schema.json` as
  `application/json; charset=UTF-8`, with an attachment Content-Disposition and a
  fixed server-controlled ASCII filename containing at most the validated job UUID.
  Never use memory content, keys, project names or request strings in a filename.

Job fields are exactly id, kind, scope, project_id, format, semantics, status,
created_at, expires_at and checked_at. `semantics` is always CURRENT_AT_DOWNLOAD.
Status is AVAILABLE before expires_at, EXPIRED at or after it. AVAILABLE means the
metadata job can be requested, subject to current checks; it does not mean content
has been prepared or that a future download will fit the limits. There is no
COMPLETED/SAVED status or implicit claim that a file was saved on a device.

Document fields are exactly schema_version (integer 1), kind, scope, project_id,
export_job_id, exported_at, semantics and items. Items are ordered by canonical
lowercase UUID id ascending, unique, and belong to the exact envelope scope. Each
item contains only id, key, kind, value, change_reason, version, confirmed_at,
created_at, updated_at and source:{type:EXPLICIT_OWNER_FORM,submitted_at}. Source
submission IDs/hashes, generation/slot IDs, owner/session/epoch, ciphertext/storage
references, deletion markers and all authority payloads are excluded. No generic
row or payload map is copied into the output; build every level with an allowlist.

Owner-entered text is exported unchanged, without trimming or Unicode normalization.
It may itself contain personal information or secrets that the owner entered. The
allowlist excludes application secret/authority fields; it is not a guarantee that
arbitrary free text contains no secrets. No content-derived hashes, search keys,
source hashes, credentials or file storage locations are retained in a job.

Strict JSON parsing rejects duplicate keys, floats/exponents, nonfinite values,
unsafe integers, malformed UTF-8 and lone Unicode surrogates. JSON Schema format
assertions must check real UUIDs/calendar instants, not only regexes. UTC machine
timestamps use Z, years 0001..9999 and at most six fractional digits. UI timestamps
are explicitly rendered in Asia/Seoul with KST and the checked/generated label;
this display choice does not rewrite the UTC wire or downloaded bytes.

## Authentication, binding and bounded job retention

Every route revalidates the authenticated owner, live database session, expiry,
revocation and security epoch. Every existing-job access is bound to its original
session and epoch as well as owner. Creation and download additionally require the
existing recent-reauthentication window. POST requires same-origin, JSON and CSRF
protection. GETs are credentialed same-origin reads with no cross-origin access;
no signed/public URL, cookie-independent bearer token, redirect or query credential
is issued. Return no-store on metadata, files and errors, the existing security
headers, and nosniff on attachment responses. Do not expose download content to
logs, telemetry, caches or service workers.

Explicit reauthentication rotates the session, invalidating access to earlier
jobs. After rotation the user must explicitly create a fresh job. Never silently
rebind, reissue, resubmit or reinterpret an earlier ambiguous create as failed.
Status/receipt GETs do not require recent reauthentication, but still require the
original live session and epoch. A foreign owner's job is indistinguishable from
missing; same-owner binding failures use AUTHORITY_NOT_VALID. Account execution
pause blocks new job creation through the normal write gate. Status, receipt and
download remain privacy reads permitted while paused when all other checks pass;
pausing may separately invalidate sessions/epochs under the existing pause rules.

Within the creation owner lock, first check current authorization and same-session
idempotency. The same owner/key/session/epoch and canonical-body digest returns the
same job with current checked_at/status, without extending lifetime, adding a job,
or appending another create event. A changed digest is IDEMPOTENCY_CONFLICT; changed
session/epoch cannot replay it. An expired replay remains EXPIRED. Then apply the
new-creation pause gate and bounded cleanup/insert atomically. A matching replay is
an observation of the old job, not a new creation, and may return while paused.

Persist only metadata needed for ownership, session/epoch binding, the request key
and digest, selected kind/scope/project/format, server job ID, created_at/expires_at.
No exported content, record IDs, content hashes, artifact bytes, encrypted copies,
object URLs, file paths, or download histories are persisted. The request digest
covers only this content-free request, never memory text. Retain at most 100 jobs
per owner. On an explicit new creation, remove that owner's metadata created at
least 24 hours earlier before testing capacity, all under the same owner lock.
The row count remains bounded even with no subsequent cleanup; without another
creation, old content-free metadata may remain beyond 24 hours. Thus 24 hours is
the cleanup eligibility/key-retention window, not a promised background erasure
SLA. Cleanup must not remove unexpired jobs. Never evict younger receipts to admit
more jobs. Return EXPORT_JOB_LIMIT at capacity. No background worker is required.

NOT_OBSERVED or a 404 is inconclusive while a create could still commit, after
metadata cleanup, or when the session no longer has access. Reconciliation never
automatically creates/replays work. A lost/malformed/aborted create response stays
UNKNOWN until an authenticated matching receipt is observed; NOT_OBSERVED does
not resolve it. A matching receipt proves job creation, not a successful download.
After the retention window a previously used key might be absent; only a new
explicit user request may create another job, using a fresh key.

## Current-data snapshot and deletion ordering

There is no queued artifact worker and no create-time content snapshot. Each
explicit download freshly selects current, active explicit memories in the job's
exact scope. Revised values replace old ones; later explicit additions may appear;
deleted memories and their old revisions never reappear through an old job.
Zero matching records produces a valid empty document, not fabricated memories.

A download uses the same owner-first lock order as memory mutations. Under that
lock revalidate live session/epoch, recent reauthentication, job expiry and project
ownership, then select at most 1001 active rows in deterministic ID order, decrypt
and project into a capped in-memory UTF-8 buffer. No output bytes are emitted before
the full document is validated, byte count checked, and final database-clock
session/reauth/expiry/project checks and monotonic deadline succeed. A failed check
returns an error and discards the buffer. No paging/truncation/partial success is
permitted. exported_at is the database-clock time of the final accepted snapshot.

Owner-lock ordering defines the release point. If deletion/revision commits before
this snapshot is accepted, download uses the changed current data. If download
wins the lock and accepts its snapshot first, a subsequent deletion cannot recall
bytes already being delivered or saved. The transaction completes and releases
locks before HTTP transfer; it does not lock the database for an unbounded network
transfer. This is a bounded current-data release, not a claim of perpetual freshness
until browser save. Existing jobs do not retain a copy to serve again later: a
later download must reread current data. Deleting a memory cannot delete or recall
an earlier local download, an in-flight response, or a copy the owner made elsewhere.

## Configured limits and explicit failures

These are configured safety limits, not benchmark or performance claims:

- Raw request: 262144 bytes; canonical create body: 4096 UTF-8 bytes.
- Document: at most 1000 records and 8388608 actual UTF-8 bytes, including envelope,
  escaped strings and all JSON syntax. Emit canonical integer-only JCS JSON with no
  BOM or trailing newline. Do not confuse character count with encoded byte count.
- Transaction plus generation: 5000 milliseconds including lock waits and database
  work. Set transaction/statement/lock timeouts and check a monotonic deadline while
  constructing and before releasing. Network transfer is outside this bound.
- Job validity: 900 seconds from creation, never refreshed by status/replay/download.
- Per owner: at most 100 metadata jobs; cleanup eligibility at 86400 seconds.

A 1001st row fails 413 EXPORT_ROW_LIMIT; document overflow fails 413
EXPORT_BYTE_LIMIT; deadline/lock/statement timeout fails 503 EXPORT_TIME_LIMIT;
expired download fails 410 EXPORT_EXPIRED; job capacity fails 429 EXPORT_JOB_LIMIT.
Never send a successful prefix, truncate rows/strings, silently narrow the scope,
change format, fall back to stale content, or persist an artifact to avoid a limit.
All errors remain sanitized and retryable:false. Existing authentication, recent-
reauthentication, invalid-request, project ownership and idempotency errors retain
their established API treatment. An unsuccessful download leaves only the existing
metadata job, never a partial artifact or a claim of local save.

## Privacy settings behavior and verification boundary

Place the feature in settings privacy/export controls. Require explicit GLOBAL or
one-project choice, explain current-data semantics and sensitive local-file handling,
and label creation, status and download separately. Only an explicit owner click
creates a job or initiates a download. Disable overlapping requests. Download is
not automatically triggered by creating a job, refreshing status, receiving a
receipt, rotating a session, reopening settings or returning focus to the page.
Do not automatically retry a mutation after any ambiguous response.

Clear private buffers and fence late responses on logout, session replacement,
scope/project changes and disposal. Do not let an older scope response populate
newer settings. A validated download response may permit a temporary object URL
for the browser's local attachment action, revoked promptly afterward. Never
persist that URL or bytes in localStorage/IndexedDB/service-worker caches. "Download
started" is not "saved"; browser cancellation, failed local save and mobile behavior
require actual browser/device evidence. Preview/status never becomes authority.

Run from packages/contracts: `.venv/bin/python -m unittest discover -s tests -v`.
The reference model demonstrates sequential rules and closed projection only. Its
abstract session flags, elapsed milliseconds and release order do not implement
real authentication, database concurrency, timeout enforcement, encryption, HTTP,
CSRF or browser behavior. Passing these tests is code/synthetic evidence only.
Actual browser download, mobile save, native inference, provider operations and
full PRIV-01 acceptance remain NOT_RUN/out of scope for this slice.

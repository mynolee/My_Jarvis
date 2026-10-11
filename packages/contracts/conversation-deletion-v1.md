# Single-conversation durable source-content deletion v1

Application contract and synthetic examples only. This operation lets the
currently authenticated owner review and explicitly confirm irreversible removal
of one quiescent conversation's current durable source content. It does not create
model/tool authority, cancel work, perform account erasure or verify physical
storage erasure. No model output, imported text, schedule or inferred preference
may issue or confirm it. All source changes, redaction, tombstone, preparation
consumption, receipt and audit writes commit atomically or none commit.

## Scope and truthful review

Remove the target title, every stored message body and its content commitment,
run-result copies, terminal action payload copies and their raw commitments in
proposal/approval/authorization/synthetic-effect records, target inference
source bindings, study lesson/source commitments and recorded study answer bodies. Clear earlier target previews' temporary commitments and revoke
competing preparations. Never persist a title/transcript snapshot in a preview.
Unexpected content stores or cross-conversation source bindings fail closed; a
new store must join the reviewed deletion inventory before it can be supported.

Retain IDs, owner/FK relationships, actual final states, execution/approval versions,
timestamps, usage measurements and ceilings, and anti-replay identities. Preserve
historical authorization lineage, epoch, validity and one-use limit as a permanently
revoked, source-deleted shell with payload_hash cleared. Deletion never changes a
terminal result to cancelled or successful. Immutable inference accounting/replay records and deletion-request replay
identities retain their protected, purpose-separated keyed request bindings
unchanged (including the prepare issuance HMAC and confirm request HMAC); these are
content-derived HMAC commitments, not literally content-independent data. Do not
retain raw transcript/proposal hashes under this exception or expose these private
accounting bindings in the deletion API.

Required review meaning, before an unchecked exact-target acknowledgement and a
separate destructive button:

- Permanently delete this conversation's stored title, messages, response copies
  and action-content copies. This cannot be undone in the application.
- Separately saved memories, tasks, drafts, reminders, profile and project remain.
  Execution history, usage totals and protected request-verification records remain
  without the conversation's text. Independent records may contain copied text.
- Saved files, copied text, existing screens on other devices and generated or
  in-flight responses cannot be recalled. Backups and database storage remnants
  are outside this feature and their removal is unverified. Old-backup deletion
  and a safe restore workflow are not provided.

Use “I understand these limits and confirm permanent deletion of this exact
conversation”, “Permanently delete this conversation”, and “Keep conversation”,
or faithful translations. Opening, dismissing, navigating, a default checkbox,
or Enter in a surrounding form never confirms. Do not claim secure wiping, no
remaining copies, memory deletion, automatic retention or an unverified backup
expiry. Physical JVM/Python/OS/GPU memory zeroization is outside this operation.

## Routes and exact schemas

Successful responses use `{data, request_id}`; the schemas describe `data`.
Errors use the existing `{error, request_id}` envelope. UUID paths
and Idempotency-Key values are canonical lowercase UUIDs. GET rejects any body
and every query parameter; POST rejects every query parameter and requires exactly
one Idempotency-Key header. No scope override, owner/session/epoch field, TTL,
arbitrary target list, tool choice or redirect is accepted. HTTP header names
remain case-insensitive; the headers schema describes extracted values.

- GET /api/v1/conversations/{conversation_id}/deletion-state → state schema.
  Read-only, live owner/session/epoch required, no recent reauthentication. ACTIVE
  ELIGIBLE contains a keyed content_digest; BLOCKED contains a null digest and
  sorted, unique, nonempty closed blocker codes. DELETED contains only target ID,
  deletion ID, deleted_at and checked_at, accessible to a new live owner session.
- POST /api/v1/conversations/{conversation_id}/delete-preparations takes
  prepare-request → preparation. Requires recent reauthentication, an unpaused
  write gate and exact expected_version/expected_digest from eligible state.
  Returns a five-minute, single-use, same-session PREPARED record. The displayed
  title is reconstructed from the still-live target, never stored in the preview.
- POST /api/v1/conversation-delete-preparations/{preparation_id}/confirm takes
  confirm-request → COMMITTED outcome, HTTP 200 only after synchronous commit.
  Uses a separate idempotency key and exactly boolean confirm:true. A matching
  committed replay is read-only and can succeed after recent-auth ages or pause,
  but only with the original live owner/session/epoch. New deletion rechecks every
  precondition and requires recent reauthentication and an unpaused owner.
- GET /api/v1/conversation-deletions/requests/{idempotency_key} → outcome.
  Read-only, original live owner/session/epoch required, no recent reauthentication.
  COMMITTED has a content-free receipt; NOT_OBSERVED has null receipt and is not
  proof of failure. Foreign-owner requests are indistinguishable from absent.

Schema prefix is `conversation-deletion-`. Files are `state`, `prepare-request`,
`preparation`, `confirm-request`, `receipt`, `outcome`, `conversation-path`,
`preparation-path`, `request-path`, `headers` and `query`, each `.schema.json`.
Every object is closed at every nesting level. Strict UTF-8 parsing rejects
invalid bytes, duplicate keys, floats/exponents, unsafe integers and lone surrogates.
UTC timestamps are actual calendar instants, year 0001–9999, Z, at most six
fractional digits. Digests are 64 lowercase hex characters. Title is unchanged,
NUL-free and 1–200 Unicode scalar values. No Unicode normalization occurs.

An issued preparation's expected_version and a receipt's deleted_version are
1–9007199254740990; tombstone_version is 2–9007199254740991. State and request
versions may reach MAX_SAFE_INTEGER, but issuance fails VERSION_EXHAUSTED when
there is no room for the next tombstone version.

Cross-field validation is mandatory beyond JSON Schema: target/preparation identity
and reviewed version/digest must match the current operation; run_results and
inference_contexts do not exceed runs; expires_at equals prepared_at plus exactly
300 seconds; prepared_at is not earlier than the eligible state's checked_at;
a receipt has tombstone_version = deleted_version + 1, safe-integer
bounded; deletion cannot precede its preparation or follow its expiry; observation
cannot precede deletion; sorted blocker arrays must be enforced. No sensitive
extra field is tolerated in metadata-only state or receipt.

## Snapshot, authorization and transaction semantics

Conversation content_version starts at 1, is distinct from run/owner epochs, and
advances for every source-closure change under owner-then-conversation serialization.
Message admission, accepted reply/result and relevant action/authority changes
invalidate prior previews. Reversal does not reuse a version. Inert preparation,
receipt reads and content-free audit insertion do not advance it. Reject overflow
before mutation.

content_digest is owner-domain-separated HMAC-SHA256 of a canonical manifest that
binds target, content_version, source-copy bindings, stable dependency IDs/versions,
counts, lifecycle/provenance and scope revision. Collections have stable ordering.
It excludes observation time, unrelated conversations and independent memories.
Counts alone are insufficient. Reconstruct and compare under lock before commit.

The confirmation commitment binds owner_id, session_hash, security_epoch,
preparation_id, conversation_id, tool_id `conversation.source.delete`, tool_version
`1`, policy_version `conversation-source-v1-r5`, scope_policy
`CONVERSATION_SOURCE_V1`, expected_version, expected_digest, manifest_revision 1,
prepared_at, expires_at and max_executions 1. Use separate keyed domains for
manifest, confirmation and prepare/confirm request bindings. Protect temporary
commitments at rest. Identifiers and digests are never bearer authority.

Acquire owner, initiating session, target and ordered dependency locks. Recheck
live database session, current epoch, expiry/revocation, recent authentication and
pause after waits and at final authority boundary. Recent authentication must be
within 300 seconds and cannot be future-dated. Same-origin JSON and CSRF remain
independent requirements. Reauthentication rotates the session; never rebind an
old preparation or auto-resume. Deletion itself never increments owner security,
context or history epochs or invalidates unrelated work.

Same owner/key/session/epoch/canonical prepare request replays the same still-valid
preparation without extending expiry or duplicating audit. Changed body conflicts;
stale/expired/consumed/revoked/deleted previews cannot become fresh authority.
For confirmation, authenticate then check matching receipt replay before recent-auth
or pause gates. Changed target/preparation/body under a used key conflicts. A
different-key repeat of a consumed preparation is APPROVAL_CONSUMED; a different
preparation for the deleted target is CONVERSATION_DELETED. After live-session validation and committed-receipt replay, a new confirmation
first passes recent-auth/pause gates, then binds the known preparation and rejects
key reuse/consumption. Check target liveness before revoked/expiry/snapshot checks.
Graph-integrity blockers take precedence over active/UNKNOWN/pending work, which
takes precedence over unsupported local provenance; eligibility precedes comparison
with the current version/manifest. These refusal combinations never issue authority.
Preparation replay refuses a database observation before prepared_at with
AUTHORITY_NOT_VALID. New confirmation requires prepared_at <= database deletion
time < expires_at and repeats this bound at the final authorization boundary:
future issuance is AUTHORITY_NOT_VALID; equality with expiry is APPROVAL_EXPIRED.
A committed receipt remains recoverable later, so checked_at may be after expiry.
Committed replay, receipt reconciliation and DELETED-state reads refuse an
observation before deleted_at with AUTHORITY_NOT_VALID rather than emit impossible
chronology. This is read-only and cannot alter the committed deletion.
One preparation consumption, one deletion, one receipt and one deletion audit occur.

## Eligibility and bounds

Check every dependent row, not only the newest run. Empty targets are supported.
UNKNOWN never proves quiescence; expired worker leases and pending-approval deadlines
do not either. WAITING_INPUT is allowed only when all its dependents are settled.
Closed final-state domains are in `fixtures/conversation-deletion/quiescence-domains.json`.
Unknown states, graph/owner/run/target mismatch and unsupported source relations
fail closed. Deletion does not cancel, reconcile, retry or contact another system.

Every local reservation must have accounting_version 1, a real start marker and
future-only `dispatch_cache_policy=CONTEXT_V1_NO_RESPONSE_CACHE` plus exact
`dispatch_context_id`, with a same-owner/run/conversation SETTLED or INVALIDATED
context and context_history_eligible=true run. The marker is minted only in the
supported atomic QUEUED/unstarted → STARTED contextual dispatch with matching
context/request digest. The context identity, marker and keyed digest become
immutable. INSERT, migration, prepare, deletion and recovery cannot mint/backfill
it. Historical rows, manually backfilled flags, contextless/mismatched rows and
never-dispatched CANCELLED rows without the marker all refuse. There is no
cancelled-before-dispatch exception. No Python ledger cleanup or runtime reset is
part of deletion. Separate worker-idle reference-release tests do not prove RAM
or GPU zeroization.

Fixed caps are 2,000 messages, 1,000 runs, 1,000 actions, 40,000 scoped dependent
rows, 16 MiB ciphertext plus title UTF-8 bytes and a 5,000 ms transaction/statement/
lock/monotonic budget. Use cap+1 bounded reads; no prefix deletion or unbounded
manifest. A timeout rolls back. These are safety bounds, not performance claims.

At most 100 retained UNCONSUMED preparations per owner. New preparation may clean
unconsumed preparation-only metadata older than 24 hours (the exact 24-hour
boundary remains retained). Consumed confirmation
identities, receipts and tombstones are permanent and excluded from this cap and
cleanup. No background-erasure deadline is promised; no younger authority is
silently evicted. Capacity and cleanup are atomic with new preparation.

Refusals: CONVERSATION_NOT_QUIESCENT 409; CONVERSATION_DELETION_UNSUPPORTED 409;
CONVERSATION_GRAPH_UNVERIFIED 409; CONVERSATION_DELETED 410;
CONVERSATION_DELETION_SCOPE_LIMIT 413; CONVERSATION_DELETION_PREPARATION_LIMIT 429;
CONVERSATION_DELETION_TIME_LIMIT 503. Existing authentication, precondition,
approval expiry/consumption, idempotency, pause and version-exhaustion codes retain
their meaning. Invalid wire is generic 400; absent/foreign target generic 404.
Errors expose no source text, graph rows, commitments or database details.

## Tombstone, UI and unknown outcomes

All target readers/writers must observe current liveness at their final serialized
boundary: lists, messages, runs/results, approvals, event reconnect, mock/local
admission and old client_message_id replay, context capture/prepare/dispatch,
worker settlement/reconciliation and reply append. Deleted target returns 410;
retained global client IDs cannot be moved elsewhere to bypass deduplication.
No source can successfully linearize after deletion. An old REPEATABLE_READ
snapshot is insufficient for a final check. Pre-deletion serialized/in-flight
bytes cannot be recalled. Accounting-only immutable usage insertion may remain
permitted, but cannot restore source or execution authority.

Tombstones and consumed identities survive restart indefinitely in this version.
A future restore needs the latest independent deletion ledger before release;
a tombstone only in the stale backup is insufficient. Safe old-backup restore
and physical erasure are not claimed here.

UI states: IDLE, READING_STATE, PREPARING, REVIEW, CONFIRMING, COMMITTED, BLOCKED,
REAUTH_REQUIRED, OUTCOME_UNKNOWN. Review shows exact target ID/title/creation time,
version, counts and all limits. Display dates explicitly in Asia/Seoul with KST;
wire timestamps stay UTC. Local expiry disables consent but server time decides.
Cancel/Back/Escape before dispatch clears review with zero confirm requests.
During or after dispatch these controls do not cancel server execution.

Fence every result by session, selection and operation generation. Selection,
project/session changes, logout, successful reauthentication, epoch refresh, expiry
or new snapshot invalidate staged consent. Double-submit sends once. Global pending
coordination fences conflicting writes/selection while confirming or uncertain.
Pending recovery descriptors stay in memory and contain only stage, UUID key,
target, expected_version and, for CONFIRM, preparation_id plus the reviewed
prepared_at and expires_at UTC values. These two content-free timestamps bind
receipt chronology even after private review content is cleared. Never persist source,
digest, preview or pending authority in URL, localStorage, telemetry or caches.

Lost prepare may be explicitly abandoned because no confirm exists. Lost/aborted
confirm, 5xx, malformed 2xx, parser failure or mismatched receipt is OUTCOME_UNKNOWN:
clear target-private displays, preserve the content-free pending descriptor, and
reconcile through GET only. A COMMITTED receipt must match the pending target,
preparation and version and fall within its exact 300-second reviewed lifetime;
checked_at can be later than expiry. NOT_OBSERVED keeps unknown and does not enable writes.
Never auto-retry or allocate a new mutation key. Matching committed receipt clears
all target display/review, removes its list item, closes streams and refetches
canonical history. Late results cannot repaint it. A current 401 clears session
private state; a stale response cannot invalidate a newer session.

After explicit session rotation, old authority is unusable; use a fresh state read.
DELETED proves target state without attributing it to the prior request. ACTIVE
after serialized rotation requires a new explicit review and confirmation. This
recovery claim requires actual commit-time session/race tests, not a client guess.

Metadata and errors use no-store, nosniff and existing security headers. No deletion
file export, public bearer URL, redirect, source-bearing error or telemetry is added.

## Verification boundary

Run installed reference tests from this directory:
`.venv/bin/python -m unittest discover -s tests -v`.
The Python oracle is sequential synthetic contract evidence only: not production
authentication, CSRF, SQL transactions, trigger enforcement, concurrency, HTTP,
browser accessibility or runtime provenance proof. Real Java/PostgreSQL/HTTP,
React, restart, rollback/race, native inference/device, backup restore and physical
erasure remain NOT_RUN unless independently exercised and separately reported.

## Durable-study scope revision

The current reviewed schema is exactly 16 and the current deletion policy is r5.
V16 revokes unconsumed older preparations; consumed historical values and immutable
accounting are preserved. Additive study count fields default to zero for old
records. The seven review counts are messages, runs, run_results, action_payloads,
inference_contexts, study_sessions and study_answers. The last two describe source
bindings and retained answer bodies to remove, not deletion of immutable identities.

The shared SQL inventory includes study_sessions, study_owner_answer_inputs and
study_mutations, with converse references checked for scope corruption. Every
receipt and metadata-only shell contributes to the row ceiling; live lesson/input
commitment ciphertext, input text ciphertext and receipt request HMACs contribute
to the explicit byte formula. Limits additionally allow at most 1,000 sessions
and 2,000 answers per conversation. The 16 MiB source-byte cap does not bound
physical database, audit or nonce-ledger size. Passive open study sessions do not
constitute executing work. Existing inference/action quiescence requirements still
apply. A source-deletion timestamp clears the study source without rewriting its
historical version, lifecycle, receipt or authentication attribution.

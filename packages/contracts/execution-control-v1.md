# Account execution control v1

This interface changes the authenticated owner's account-wide execution boundary.
It does not call a model or an external provider. It uses existing owner-session,
Origin, CSRF and recent-reauthentication enforcement. A browser control, generated
text, status response or historical audit row is not execution authority.

## Wire contract

GET /api/v1/execution takes no query parameters and no request body. It returns
the existing `{data, request_id}` envelope with exactly these data fields:

- `execution_paused`: boolean; the account execution boundary at this observation
- `version`: the shared owner version, an integer in 1..9007199254740991
- `checked_at`: database-clock UTC observation time, with Z and at most six
  fractional digits; valid calendar date, year 0001..9999

`execution-control-state.schema.json` describes this same closed snapshot for GET
and successful mutations. It contains no owner ID, epoch, session identifier,
request receipt, jobs, provider outcome, authority or cancellation-completion
claim. `checked_at` is not a pause start time or evidence of a particular request.
Return Cache-Control: no-store and the existing security headers. Failed reads
must not become a successful empty/default-active snapshot.

POST /api/v1/execution/pause and POST /api/v1/execution/resume accept exactly
`{"expected_version": <version from a current snapshot>}`. The request schema is
`execution-control-request.schema.json`. Query parameters, missing/extra body
fields, client-supplied owner/scope/epoch, duplicate JSON keys, non-integer numeric
spellings, unsafe integers and malformed Unicode are rejected. The endpoint
selects the desired state and the authenticated session selects the owner. The
scope is always the whole account; no caller-supplied scope can narrow or expand
it. A request has no idempotency-key or mutation-receipt protocol.

Status is a read-only domain operation and does not require recent reauthentication.
It still revalidates the current owner's session, revocation, security epoch and
session lifetimes in the same database transaction as the snapshot. Authentication
middleware can update last-seen time. Mutations require the existing recent
reauthentication window and recheck live server session records under the owner
lock. The read is an observation, not a lock held until delivery.

## Atomic transition and duplicate handling

Within one transaction, lock the authenticated owner and revalidate the session
and recent reauthentication before applying the following order:

1. Compare expected_version with the current shared owner version. A mismatch
   returns 409 PRECONDITION_CHANGED with no mutation, even if the requested state
   already matches. Changes to other owner settings can therefore invalidate an
   execution-control snapshot.
2. If the version matches and the state already matches, return a current
   snapshot. This is a no-op: do not increment version/epoch, invalidate work,
   refresh old authority, interrupt processes or append another transition event.
3. Otherwise commit the changed state, one owner-version increment, one security-
   epoch increment, invalidation/fencing of old execution authority, and one
   matching account audit event atomically. Retain only the initiating session
   at the new epoch. Other sessions become invalid and must authenticate again.
   Never wrap or silently overflow version/epoch; an exhausted transition fails
   as 409 VERSION_EXHAUSTED without partial changes.

A real transition returns a snapshot at expected_version + 1 with the requested
state. A same-state no-op returns the same version. No successful response means
that an external side effect was canceled or reversed. The initiating session's
cookie and CSRF token are not rotated by this endpoint; explicit reauthentication
uses the existing separate credential/session rotation flow.

A repeated old request cannot repeat the transition: after the first committed
change its expected_version is stale. A simultaneous change from another session
can instead invalidate that session before the version check. Error envelopes
remain sanitized and non-retryable; 401 AUTHENTICATION_REQUIRED / SESSION_REVOKED
and 403 REAUTHENTICATION_REQUIRED remain distinct from a stale-version conflict.
No automatic retry, version refresh followed by resubmission, or opposite action
is authorized by an error or by a successful GET.

## What pause and resume mean

Pause blocks new execution admission and subsequent execution steps. Unstarted
queued work is canceled, pending approvals are revoked, and pre-pause authority
becomes unusable at the new security epoch. Active leases are fenced so an old
worker cannot overwrite a newer result. Already started effects or inference can
remain UNKNOWN; cancellation/interruption attempts are not proof of process
termination, provider cancellation, rollback or completion. Prior terminal
results remain historical facts. No count or label may imply every task was
canceled or that nothing happened.

Resume opens the account boundary for newly authorized work. It does not revive
canceled jobs, approvals, typed intents, old prepared actions, expired/revoked
grants, previous schedule authorization or uncertain runs. Existing schedules
whose authorization predates a transition require an explicit fresh confirmation
through their own workflow; resume itself must not deliver a missed occurrence.
UNKNOWN remains uncertain until separately authorized result verification can
establish what happened. Neither control route reconciles or retries a run.

## Browser behavior and ambiguous responses

Display the observed paused/active boundary separately from individual run states
and show its checked time. Validate all response fields before using a snapshot.
A paused boundary and an UNKNOWN in-flight run can be true simultaneously.
Without a valid current snapshot, fail closed for starting new execution and
keep a read-only refresh/recovery path available.

Only an explicit owner click may submit pause or resume. Use one bounded request
per click; prevent overlapping control writes. For a snapshot whose state differs
from the target, the response must match the
requested state and expected_version + 1. Only a request whose checked snapshot
already matched its target may return that state and expected_version as a no-op.
Reject any other successful-looking result as ambiguous rather than displaying
a claimed transition.

If a control response is lost, malformed or ambiguous, preserve the original
request's UNKNOWN outcome. A later GET can establish only the currently observed
account state. Even a matching state and version cannot prove that the original
request committed: another operation could have caused them. Do not relabel the
request as successful, create a receipt, clear another domain's uncertain write,
resubmit automatically, or auto-resume. After a fresh valid read, a new explicit
owner action can request the desired state using that observed version. Keep the
historical uncertainty distinct from this new request's outcome.

Once a fresh valid GET verifies an active account boundary and its current
version, later ordinary work may proceed when the owner separately requests that
new work and no unrelated unresolved-work fence remains. The earlier control
request still has UNKNOWN attribution. This eligibility does not trigger work,
retry a request, resume an old job or restore previous authority automatically;
all authorization checks for the new work still apply. An execution-status GET
cannot clear a different domain's UNKNOWN or satisfy that domain's recovery
requirements. A paused, missing or unverified snapshot does not permit new work.

Emergency pause and read-only status refresh remain reachable when another
request or run is UNKNOWN. This accessibility must not bypass current-session,
Origin/CSRF, expected-version or recent-reauthentication checks. Reauthentication
must also remain reachable for that owner-directed pause. A successful explicit
reauthentication requires a fresh status read and another explicit control click;
do not chain the mutation automatically. Lost reauthentication responses remain
uncertain because the session cookie/CSRF pair may have rotated. Do not reuse a
possibly stale CSRF token or repeat reauthentication automatically; use explicit
session recovery. A status GET cannot establish the reauthentication outcome.

Invalidate dependent execution/profile views after a control transition. Fence
late responses by request/session generation; clear private snapshots on logout,
session replacement and disposal. A failed refresh must not give old state a new
checked_at. Preserve unrelated mutation/run uncertainty across these operations.

## Verification scope

Fixtures use invented times and abstract synthetic authority identifiers. The
Python reference models sequential transition rules and wire validation only.
Its session flags and serial transition order do not implement real authentication,
SQL locking, CSRF, worker fencing, process termination or browser scheduling.
Passing these synthetic tests does not prove HTTP/DB concurrency, Java integration,
real-provider effects, frontend accessibility or release acceptance. Those need
separate executed runtime evidence.

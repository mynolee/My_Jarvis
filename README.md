# My Jarvis

A development-stage personal-assistant service with a Java 21 / Spring Core,
Python inference boundary, React interface and strict shared contracts.
Core owns permissions and execution; model output is proposal data only.

## Verification

Use Java 21, the checked-in Gradle Wrapper, Python 3.12, uv and Node. From the
repository root, run `scripts/check.sh`. It runs synthetic component checks,
not a release gate or proof of native inference. Independent CI jobs check Python
lock freshness and audit Python/npm dependency identifiers. Audit failures block
those jobs; they never count as a clean scan. Separate secret scanning, SAST and
Java dependency review remain required. Gradle dependencies must be
available from the configured repositories; a dependency-resolution failure is
not a passing Core test. Never regenerate dependency locks as a normal test step.

No model download, installation, training or external AI fallback runs in CI.
No personal settings, prompts, private model manifests or dependency snapshots
belong in this repository. Actual configuration belongs in an ignored `local.env`
file (owner-only permissions) and private files outside the source tree. Do not
commit `.env` variants, even example files containing real values.

## Local backend lifecycle

`scripts/local_stack.py setup` lists the required environment variable names and
performs no installation. Populate a private `local.env` separately. This file is
plain `NAME=value` data, not a shell script: no `export`, quoting, interpolation,
command substitution or unknown keys. Secret values are never printed by the
launcher. Certificate/key/configuration files must be private, owner-only regular
files outside this repository. No certificate or signing key is generated here.

Prerequisites are an already provisioned local PostgreSQL database, private keys
and certificates, a reviewed/pinned model artifact, its exact installed Python
environment and a built Core jar. Build the frontend with its locked package
manager, then build `:apps:core:packageWeb :apps:core:bootJar`. If static web
packaging or the required security checks are unavailable, do not claim a usable
full application.

The lifecycle commands accept `--env-file /absolute/path/to/local.env`:

- `doctor`: checks static artifact/runtime/build inputs. It does not generate text.
- `start`: remains in the foreground, starts one TLS inference service, waits for
  matching authenticated readiness, then starts Core. It does not auto-restart.
- `status`: reads the private supervisor socket. RUNNING means managed processes
  are alive; it does not establish real inference, integration or release approval.
- `stop`: requests shutdown. STOPPING is only acknowledgement. The foreground
  supervisor reports STOPPED after verifying its process groups have exited.

The backend binds only loopback. The inference URL is `https://localhost:9443`;
Core uses `https://localhost:8443`. Certificates must cover those names. Remote
phone exposure, reverse proxies and certificate installation are not configured
by this launcher. Runtime state uses a separate owner-only directory outside the
repository. A missing socket is uncertain, not proof of termination. Unconfirmed
termination leaves a blocking marker requiring operator investigation before
another start. Never delete it merely to bypass a failed shutdown.

The dedicated model worker is inference-only. The service defaults to a total
4096-token context, 512-token output ceiling, one active generation, two waiting
requests and a 60-second generation deadline. Actual template/tokenizer accounting,
artifact checks and worker termination are enforced in the Python boundary.

Static checks, synthetic tests, native model output, fresh-process reload, resource
measurements and full application integration are separate evidence categories.
Mac/Metal inference is NOT_RUN until tested on the actual supported hardware.

## Internal reminders and inbox

The reminder panel supports explicit one-time or bounded recurring reminders to
the signed-in owner's internal inbox. Preparation is inert: review the exact
content, timezone, next occurrence, missed-time policy and authorization bounds,
then confirm after recent reauthentication. A confirmed authorization expires
within 30 days and permits at most 31 deliveries. No reminder is inferred from a
chat, and no model, provider, email, push notification or generic tool is invoked.

A dedicated PostgreSQL queue uses database time, occurrence uniqueness and lease
fencing. Both the global worker switch and `JARVIS_REMINDER_WORKER_ENABLED` must
allow polling; disabling a worker is not a substitute for revoking a reminder.
The private environment file may optionally set that switch to exact lowercase
`true` or `false`; omission retains the application default. Keep all actual
environment values in the ignored private file.
Disabling or replacing a reminder fences old pending deliveries. Pausing the
owner invalidates the old authorization even after execution resumes; renewed
confirmation is required. Profile timezone edits do not retime saved reminders.

The inbox labels records CREATED or OPENED, not delivered to a device. Reading a
list or detail does not mark it opened. Missed occurrences over one hour are kept
as skipped metadata; among distinct in-grace overdue slots only the latest may
create an inbox record. Earlier superseded slots remain visible as cancelled.
These are documented application policies, not proof of external delivery.

Unknown write outcomes are reconciled through metadata reads without automatic
resubmission. Sign-out or disposal clears private recovery identifiers and
content; a nonidentifying warning can prompt review of saved data on a later
sign-in. Cross-session automatic recovery and replay are not claimed.

Synthetic contracts, component tests, PostgreSQL execution, browser layout and
actual deployment are separate checks. The contracts alone do not establish
transactional safety or native notification delivery. CI's sanitized Java report
must have its complete matching trailer, sequence/count checks and digest before
individual method outcomes are used; declared method inventories are not passes.

## Internal mail drafts

The owner can explicitly create, open and edit a private draft without connecting an email account. Drafts are saved internally and always show `NOT_SENT`; a candidate sender is unverified text. Empty recipient lists and incomplete text are permitted. Attachments must be empty until a separately verified attachment flow exists. There is no sending, provider connection, scheduling, clipboard action or automatic model drafting in this feature.

Sensitive draft fields are encrypted. Lists and mutation receipts contain metadata only; opening a draft requires a current owner session. Updates use an expected version and idempotency binding. Global execution pause blocks new saves. Unknown write outcomes require a read-only outcome check rather than automatic resubmission. Logout and disposal clear private form and recovery state. These boundaries do not establish provider delivery, conversational drafting, full privacy export/deletion, browser layout acceptance or native-model quality.

## Today overview

The read-only Today view summarizes the owner's open tasks from a coherent server-side snapshot, using the saved timezone and database time. It shows up to three ranked tasks and up to twenty overdue or remaining-today deadlines, with an exact deadline count and a visible truncation indicator. The deadline window ends at the next local midnight, including daylight-saving transitions; it is not a fixed 24-hour interval.

Ranking is an explicit product default: overdue tasks first, then priority, due time and stable identifier. A failed read is not an empty or all-clear result. Calendar appointments, morning briefings and device status remain visibly unavailable until their independent integrations exist. This view does not execute models, providers, reminder jobs or notification acknowledgements. Browser layout and native-device acceptance remain separate verification work.

## Execution history

The execution history panel reads owner-scoped audit metadata from the existing server ledger. Opening, closing, refreshing or paging the panel never approves, retries, resumes, reconciles or cancels a run. It is separate from the active conversation and execution controls. Displayed timestamps use Korean Standard Time (Asia/Seoul); wire timestamps remain UTC.

Only explicitly allowlisted event and resource metadata is returned. Unknown event types are sanitized, and any linked run state is its state at the current page's database snapshot, not a reconstruction of its state when the event occurred. A status does not prove an external action or real-provider verification. Message bodies, draft text, authentication material and encrypted payload references are excluded.

Each page has a fresh coherent read snapshot and a descending time/ID boundary. The signed cursor is bound to the owner, session, epoch and scope and has a fixed expiry. Paging does not freeze the entire event collection: later inserts with older event timestamps can appear below the current boundary. The panel keeps a bounded window and reports failed reads separately from an empty history. Session changes and disposal clear private UI state; request-generation fences ignore late responses without claiming to cancel transport already in progress.

## Account execution controls

The account-wide controls read a database-timed execution snapshot before enabling new work. Pause and resume are explicit owner actions requiring the existing session, origin/CSRF checks and recent identity confirmation. Each transition supplies the observed owner version: stale versions fail without changes, while the same state at the current version is a no-op. A successful transition invalidates older authority once. Resume permits new work; it does not restore canceled jobs, invalidated approvals, old intents or schedule grants.

Pause prevents unstarted work from crossing the execution boundary. Already-started outcomes remain UNKNOWN where completion cannot be established; the UI does not claim all external work was canceled. A lost response is not retried automatically. A fresh status read establishes current account state only, not whether a particular earlier request committed. An explicit new request may use the newly observed state; unrelated unresolved operations remain separately fenced.

The dedicated identity/control flow is separate from ordinary pending-work navigation. Private UI generations and API credential generations fence late responses, and sign-out is serialized against pending identity/control requests. These client checks do not prove browser cookie ordering or server-side session-lineage revocation. Real browser/mobile/keyboard and late Set-Cookie behavior remain separate verification requirements; local component tests are not substitutes.

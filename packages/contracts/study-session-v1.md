# Study-session domain kernel v1

This is an unregistered, pure component and a synthetic interchange contract.
There are no study endpoints, storage, migrations, background work, model calls,
review-task writes, or retention selectors. It is not an implemented end-to-end
study service. Schema-valid identities and events do not authenticate an owner,
prove that an answer came from them, or prove accepted model provenance.

## Bounded workflow

A session has an immutable session/owner/conversation identity and optional
project identity, version 1 initially, three concepts and two distinct questions.
The fixed counts describe this first default lesson shape, not a general lesson
builder. Each question accepts exactly one explicit answer with a distinct
attempt ID. Feedback can attach only to that exact question and attempt. Both
answers and both feedback entries make the derived stage REVIEW_AVAILABLE.
This means feedback/checklists are available, not that the user mastered the
subject or completed a review task. Closing is an explicit caller operation and
can occur without any answer; it produces CLOSED_BY_OWNER, never a mastery claim.

The kernel assumes a future adapter has established authority before invoking a
transition. It does not accept an `approved`, `verified`, or authority boolean.
FEEDBACK and SOURCE_UNAVAILABLE are internal domain events, not owner-facing
HTTP request contracts. A browser/model must not be allowed to submit them as
proof. State `owner_id` is a stored identity to be established by that adapter,
not a caller-selected account on an API.

The derived stages are WAITING_ANSWERS (fewer than two answers), FEEDBACK_PENDING
(both answers but missing feedback), REVIEW_AVAILABLE, CLOSED_BY_OWNER and
SOURCE_UNAVAILABLE. A partial answer can already have feedback while the overall
stage still waits for the other answer. Per-question readiness is read from that
attempt's nullable feedback. Outcomes CORRECT, NEEDS_WORK and NEEDS_CLARIFICATION
are feedback data, never verified truth or new user-profile facts.

## Integrity and limits

- Session ID and exact expected version are required on each transition.
- Versions are integers 1 through 9007199254740991. No mutation can increment the
  maximum. All transitions preserve owner, conversation and project identities.
- Questions are distinct; current attempt IDs and question bindings are unique.
  Feedback must match both the recorded question ID and attempt ID.
- A repeated/stale command fails instead of silently overwriting. This slice
  deliberately has no receipt store, durable idempotency or answer revision/
  retry history. A future explicit retry design must preserve causal attribution.
- Concepts: exactly three, each 1–1000 Unicode code points. Questions: exactly
  two, each 1–2000. Answers: 1–8000. Feedback explanation/example: 1–4000 each.
  Assumptions/checklist: 1–5 items, each 1–1000. All text must be nonblank, reject
  NUL and isolated UTF-16 surrogate code points, and preserve other input text.
- Blank characters are U+0009–000D, U+0020, U+0085, U+00A0, U+1680,
  U+2000–200A, U+2028, U+2029, U+202F, U+205F, U+3000 and U+FEFF.
- Contract objects reject unknown fields. UUID strings are canonical lowercase
  hexadecimal, exactly 36 characters with no surrounding whitespace. JSON Schema checks shape; cross-record target relationships need
  policy checks. Neither is an authorization system.
- Records defensively copy collections. No generated code is compiled/executed;
  code examples and compilation evidence are outside this first shape. Synthetic
  Dijkstra content includes the nonnegative-edge condition without claiming
  native model quality or tested Java examples.

## Privacy and future integration

SOURCE_UNAVAILABLE returns a new state containing no lesson, answers or feedback.
It prevents further answer/feedback transitions. It does not erase earlier
immutable values, caller references, memory, logs, databases, or backups. It can
also supersede a closed session. Establishing source unavailability belongs to
the future authenticated storage adapter. This kernel does not select a retention
unit, age anchor, cutoff, duration or deletion target.

Raw answer/feedback/lesson text is transient caller-owned data here. Do not log
or persist these values by adopting this component. A later durable service must
bind source identities to owner/conversation/project, enforce live authenticated
sessions, CSRF, pause and revocation gates, and join the reviewed deletion graph
before introduction of any new content-bearing store. Source selection and
retention enforcement remain unimplemented. Separate study reports need their
own lifecycle; do not disguise them as long-term memory.

A review checklist is data only. Explicitly saving it as a task must use the
existing exact review/one-time task-intent/mutation flow. Only a separately
confirmed task update can mark that task done. Reading feedback, closing a study
session, or generating an answer cannot create a task, mark it done or change
learning-profile preferences. No task link is claimed by this component.

Local inference integration must preserve reserved turns and current context
freshness, exact question/attempt/output binding and bounded input. Never use
arbitrary prose parsing, output flags, or these synthetic event kinds as model
execution/authorization evidence. No runtime adapter or application wiring is
included in this contract.

## Verification boundary

The shared synthetic fixture contains an initial state and six events with full
expected snapshots. Java unit tests invoke the actual pure policy against those
snapshots. Python checks validate schemas, fixture relationships and malformed
wire shapes; they do not execute or type-check Java. Syntax parsing is also not
Java compilation or passing JUnit. PostgreSQL, HTTP security, browser integration,
concurrency, durable retention, actual local-model teaching quality and native
execution all require separate implementations and verification.

JSON Schema treats numeric values such as `1.0` as integers mathematically.
These schemas do not enforce the lexical form of JSON number tokens. The existing
strict application JSON parser rejects floating-point tokens; a future adapter
must preserve that parser boundary rather than treating schema success as parser
or admission evidence. No adapter is introduced here.

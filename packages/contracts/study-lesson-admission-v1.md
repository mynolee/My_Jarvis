# Purpose-bound study lesson admission

This slice wires lesson generation through the existing authenticated reserved-turn
path. It does not create a durable StudySession, record answers, grade work, or
claim model quality. Synthetic provider/service/UI tests are distinct from native
response-shaping verification, which remains unverified.

## Explicit owner request

`POST /api/v1/conversations/{id}/study-lessons` requires the current owner session,
origin, CSRF and JSON protections. The path UUID and `client_message_id` are
canonical lowercase UUIDs. Exactly one `Idempotency-Key` must equal that message
ID. The closed body contains only `client_message_id` and `text` (1..8000 Unicode
code points). No purpose, context, verified flag, lesson, source, usage or owner
field is accepted from the client. Response is the existing 202
`{run_id,status:"QUEUED"}` data envelope. A lost response must not be automatically
resubmitted. Same-body repeats inspect the existing run; a changed purpose or
body conflicts.

The route selects `STUDY_LESSON` server-side. Ordinary local inference remains
`CONVERSATION`: its admission and dispatch canonical bytes omit the new purpose
field, retaining old hashes and replay semantics. Legacy unkeyed-digest recovery
is available only to ordinary, explicitly legacy reservations.

## Internal wire and trust

The signed turn request has optional `purpose`: `CONVERSATION` (also the omission
default) or `STUDY_LESSON`. Study requires the existing current ContextV1, local
model identity, local prompt revision and empty tool set. Purpose is immutable on
the reservation and included in both study admission and signed dispatch hashes.
The existing request, run, reservation, model-manifest and context relationships
are checked; purpose does not create authority.

Study permits only `LESSON`, `NEED_INPUT` and trusted service `ERROR` responses.
Ordinary purpose rejects `LESSON`. A LESSON is exactly:

- kind: LESSON
- concepts: exactly three strings, each 1..1000 Unicode scalar code points
- questions: exactly two strings, each 1..2000 Unicode scalar code points
- usage: the unchanged service-owned bound usage object

Text is nonblank, NUL-free and unchanged. The whitespace set is the existing
StudySession kernel's set, including nonbreaking spaces and BOM. Models cannot
supply question IDs, usage, verification or authority. Arbitrary prose is never
parsed into a lesson. Native output schemas are purpose-specific and exclude
service-generated ERROR. The worker pipe carries the server-selected purpose.

The model result is validated before the existing independent usage transaction.
A valid, exactly bound response can record measured usage even when its output is
subsequently rejected due to pause, logout, expiry, rotation or source invalidation.
Malformed, unknown-kind or purpose-mismatched responses cannot claim measurements:
they preserve conservative UNKNOWN reservation behavior and are not treated as
zero-cost or automatically retried. ERROR remains a non-measured observation.

## Existing storage, projection and deletion

Only after current settlement gates pass does Core mint distinct question UUIDs
and construct the existing StudySessionPolicy.Lesson. Existing encrypted
`runs.result_ref` stores canonical JSON with closed envelope
`{kind:"LESSON",revision:1,lesson:{concepts,questions:[{id,text}]}}`. It is selected
only by successful status plus immutable STUDY_LESSON reservation purpose, never
by guessing whether ordinary text looks like JSON. Strict decode checks canonical
bytes, version, exact shape, text bounds and question identities.

Ordinary run reads, current-run history and the accepted assistant transcript use
a deterministic plain-text projection. Assistant provenance stays MODEL_PROPOSAL.
No new content table, column, browser persistence or hidden payload store is added.
Existing ciphertext caps, source guards, monotonic conversation content versions,
per-row keyed deletion commitments, source counts and redaction cover both the
canonical result and rendered message copy. Context history receives only the
ordinary source-message projection.

`GET /api/v1/runs/{id}/study-lesson` rejects bodies and query parameters. It requires
current owner/session/live-conversation access and returns the closed
`study-lesson-result` shape: run_id, status, and a lesson only for successful study
runs. Missing or non-study runs use NOT_FOUND; deleted sources use
CONVERSATION_DELETED; malformed retained lesson data uses fixed
STUDY_LESSON_UNAVAILABLE without exposing content. Historical accepted content may
be read after a context change, but this does not re-establish execution authority.

V15 adds only immutable reservation purpose and usage-receipt kind/purpose
constraints. All prior migrations and V12/V14 protections remain unchanged.
Deletion policy advances to `conversation-source-v1-r4` with exact supported
schema version 15; it does not accept arbitrary later migrations. V15 revokes and
clears unconsumed pre-V15 deletion preparations, preserving consumed records.
The deletion graph and five source counts remain unchanged. No automatic retention
unit, cleanup policy or deletion activation is introduced.

## Private runtime prerequisite

The optional private runtime configuration group
`study_lesson_prompt_path`/`study_lesson_prompt_sha256` must be complete and match
optional manifest `study_lesson_prompt_sha256`. Configured bytes are validated
without publishing their content or path. No prompt template or actual private
configuration belongs in this repository. Missing study support returns
MODEL_UNAVAILABLE before generation; ordinary readiness can remain available and
does not assert study readiness. Invalid configured artifacts fail closed.
No native model was activated or run to develop this slice.

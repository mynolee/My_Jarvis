# Durable study API v1

The durable study adapter records owner-submitted answers to an already accepted
lesson. It provides no grading or model-feedback service. Creating a session,
recording an answer and closing a session create no message, inference reservation,
provider call or worker job. The original lesson's question IDs remain unchanged.

## Requests and reads

All data lives in the existing `{data, request_id}` envelope. Mutation requests use
one canonical lowercase UUID Idempotency-Key. Authentication, Origin, CSRF and
strict integer-only JSON gates apply. Every object is closed. No browser field can
supply an actor, authority label, source identity, attempt identity or model output.

- POST `/api/v1/conversations/{id}/study-sessions` takes `lesson_run_id` and
  `expected_conversation_version`.
- POST `/api/v1/study-sessions/{id}/answers` takes `expected_version`,
  `expected_conversation_version`, `question_id` and `text`.
- POST `/api/v1/study-sessions/{id}/close` takes both expected versions.
- GET `/api/v1/study-sessions/{id}` reads the retained lesson and exact answers.
- GET `/api/v1/study-mutations/{key}` reads only a receipt observation.
- GET `/api/v1/conversations/{id}/study-index` lists accepted typed lesson sources
  and their optional durable sessions. Only `limit` and `cursor` are accepted;
  page size defaults to 20 and cannot exceed 50. The authenticated cursor is bound
  to the owner and conversation. Every page supplies its current conversation
  version; paging does not reserve that version for a later write.

Mutation responses are HTTP 200 COMMITTED observations. Receipt GET can also
return NOT_OBSERVED with null receipt. The latter does not establish rollback.
After an ambiguous response the browser keeps an in-memory, content-free target
and key, clears answer drafts, and reconciles by GET. It must not resend or mint
another key automatically. An original receipt is available only to its original
live authentication session and security epoch. Another login uses canonical
study/index reads instead.

Receipt `mutation_id` equals the idempotency key. START has null prior_version and
resulting_version 1. ANSWER and CLOSE advance by one. Only ANSWER includes input,
attempt and question IDs. Every receipt advances the conversation version by one.
Its committed_at cannot exceed the observation's checked_at. Receipt bodies never
include lesson contents, answers, encryption references or private request HMACs.

## State and text boundaries

A durable session has version 1–4 and accepts at most two inputs, one per original
question. OPEN with zero or one answer is WAITING_ANSWERS; OPEN with two answers is
FEEDBACK_PENDING. This is stored state, not a queued job. The read always reports
feedback_availability UNAVAILABLE and null feedback. Owner closure reports
CLOSED_BY_OWNER without claiming learning progress or task completion.

Text is preserved exactly, including whitespace and Unicode composition. It must
contain 1–8000 scalar values, include a nonblank character, exclude NUL and isolated
surrogates, and use at most 32,000 UTF-8 bytes. Canonical request bodies are bounded
at 65,536 bytes. Complete read envelopes are bounded at 262,144 bytes for session
content and 65,536 for the index. Oversized or malformed retained data is refused
as a whole. Clients render lesson and answer text without executing it.

## Source, storage and deletion

A source session must resolve to an owned successful STUDY_LESSON reservation with
accepted dispatch/context and exact measured LESSON lineage. Historical accepted
lessons remain usable after context invalidation. Missing or substituted source
bytes cannot be repaired from displayed prose. An encrypted lesson commitment
binds the retained canonical envelope and its owned identities.

Answers use a dedicated encrypted source table. The internal source policy uses
a tagged identity distinguishing conversation messages from owner study inputs.
For a study input the explicit command, selected stored target and separately
observed stored target must all match. The stored target includes study ID,
expected version, question and server-generated attempt ID. USER/USER_DIRECT
means an authenticated submission, not correctness or original authorship.

Each admission stores the actual locked pre-operation conversation version.
A receipt's AFTER INSERT trigger is the only conversation-version bump for that
operation. Deferred constraints enforce an unbroken START, zero-to-two ANSWER,
optional final CLOSE chain and exactly one receipt per input. Old input evidence
remains historical after unrelated conversation writes. Every new operation leaves
one safe conversation-version increment available for deletion.

Migration V16 integrates all three study relations into explicit deletion r5.
The review adds study_sessions and study_answers counts. Deletion removes answer
ciphertexts and lesson/source commitments while retaining immutable metadata and
private, content-derived request HMACs for replay checks. Content endpoints and
mutation replays return 410 after deletion; a bound metadata-only receipt lookup
can still confirm the old outcome. Source deletion is an overlay and does not
consume another study version. There is no automatic retention selector here.

The shared SQL inventory enforces 1,000 sessions, 2,000 answers, 40,000 scoped rows
and 16 MiB of explicitly counted source bytes. Every study receipt and source shell
counts as a row. Actual encrypted commitment/text bytes and retained request HMACs
count toward the byte ceiling. This is not a physical database-size guarantee;
audit records and independent encryption-nonce reservations are outside it.
At receipt insertion the full inventory is counted once, without re-adding the
candidate's projected delta. Replays write nothing and use no additional capacity.

## Verification scope

Schemas and Python reference checks validate synthetic wire shapes and relational
rules. Java suites cover actual service, HTTP and PostgreSQL behavior when run by
CI, using synthetic provider output. A source parse is not Java compilation or a
passing JUnit run. None of these tests proves native model teaching quality,
feedback quality, browser deployment security, or compiled lesson examples.

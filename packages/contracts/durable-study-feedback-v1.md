# Saved-answer feedback, revision 1

This surface lets an authenticated owner explicitly request one model-generated review of one saved study answer. It is separate from the original durable study API and the pure feedback helper contract. The original study response still reports its original unavailable feedback shape.

A request identifies an original question, saved input and attempt. It contains no answer text, lesson text, prompt, profile, model choice or authority claim. An open study must already contain both saved answers. Each input can be admitted once, including requests that later fail or are revoked. A study has at most two admissions and a conversation has at most one active feedback authority.

## Operations

- GET /api/v1/study-sessions/{study_id}/feedback returns the original two ordered targets and any retained request. Source or completeness failures refuse the entire response.
- POST /api/v1/study-sessions/{study_id}/feedback-requests takes the closed request body and one Idempotency-Key. A successful response is a committed receipt with HTTP 202, not generated feedback.
- POST /api/v1/study-feedback-requests/{id}/revoke takes the closed revoke body and its own Idempotency-Key. HTTP 200 means the output authority has been revoked. It does not establish worker termination or zero usage.
- GET /api/v1/study-feedback-mutations/{key} returns COMMITTED with the original receipt, or NOT_OBSERVED. Only the original operation session and epoch may observe that receipt. NOT_OBSERVED is not evidence of rollback.

Responses use the existing data/request_id envelope and Cache-Control: no-store. Request bodies are limited to 8,192 bytes, mutation envelopes to 16,384 bytes and read envelopes to 262,144 bytes. Objects are closed; duplicate keys, non-integer numbers, invalid Unicode and noncanonical identities fail validation. A lost or malformed success is an unknown outcome. Reconciliation uses GET only and never automatically repeats a POST or creates another key.

## Dedicated inference input

STUDY_FEEDBACK requires context and control revision 1 and the dedicated feedback_source object. It forbids user_input and requires empty memory, history, evidence and tool arrays. Core supplies the retained lesson and one original owner answer after independently checking their stored bindings. The second answer and ordinary conversation history are absent.

The local transport uses its existing local-mlx-v1 model and local-v1 prompt identifiers. The per-request limits are 3,584 actual input tokens, 512 output tokens, one model call and 60 seconds. The actual prepared chat-template tokens must fit a total context of 4,096. Oversize input is rejected without trimming the saved source. Invalid output never causes a repair generation.

A private feedback template is an optional integrity-pinned configuration dependency. It is not distributed in this contract. GET /health/study-feedback is authenticated, nongenerating and closed to five fields: revision, control_revision, model_id, model_manifest_hash and ready. Fresh admission requires both ordinary readiness and feedback readiness. The original readiness response is unchanged.

## Output and interpretation

The native model returns FEEDBACK with assessment, explanation, example, assumptions and review_checklist. Assessment is CORRECT, PARTIALLY_CORRECT, NEEDS_WORK or INSUFFICIENT_INFORMATION. The service adds measured usage; the model cannot author usage, ERROR, identities or authorization fields. A trusted service may separately return its existing finite ERROR response with retryable=false.

Accepted public results include revision 1 and code_execution=NOT_RUN. Examples are inert text and have not been executed or compiled. Feedback does not establish mastery or program correctness, complete a study, update a learning profile, schedule work or create memories, tasks, tools or messages. Accepted content is stored once in a dedicated encrypted result; the generic run result remains null and is excluded from conversation history.

Output authority, execution evidence and accounting are separate fields. Revocation closes future output immediately after its transaction completes. Confirmed execution facts can arrive later and do not manufacture usage measurements. Prior accepted feedback can remain readable after owner study closure or a new login when its retained source still passes authorization and binding checks.

## Storage and verification boundaries

The internal SQLite control ledger advances to schema revision 2 while the external control protocol remains revision 1. Upgrading an existing control ledger requires its original verified ownership lock before the ledger is opened for migration. A transactional two-table rebuild preserves replay identities, generation relationships, terminal facts, cache domains and nonce reservations. Missing, replaced, incomplete or future storage is refused; it is not reset to recover service availability.

Synthetic schemas and tests establish only the checks they actually exercise. SQLite migration, service routes, worker termination, PostgreSQL closure and native teaching quality each require their own execution evidence. An unavailable private template keeps feedback unavailable.

# Explicit learning profile

This bounded web feature edits three product keys in the existing explicit-memory store. It adds no profile database, memory API, model instruction, learning-session engine, or task dispatcher.

## Product catalog

| Field | Key | Required existing memory kind |
| --- | --- | --- |
| Learning level | `learning.level` | `PROFILE` |
| Explanation style | `learning.explanation_style` | `PREFERENCE` |
| Code example language | `learning.code_language` | `PREFERENCE` |

The level and explanation-style key names are product engineering choices. The catalog does not define an assessment scale, default level, allowed language list, or preset instructions. Values are opaque, exact owner-entered text, validated using existing explicit-memory bounds: nonblank Unicode scalar text without NUL, at most 2,000 code points / 8,000 UTF-8 bytes. The separately entered reason is at most 300 code points / 1,200 bytes. No owner values are bundled in application source.

## Identity and effective values

A slot is identified by scope, project ID and key. Kind is not a second identity dimension. An active record with a catalog key but a different kind is a conflict: this editor neither coerces the record nor creates a parallel slot. The owner can inspect it through Explicit memories.

For each field, an active selected-project slot overrides its global slot. An absent project slot inherits global. A deleted project slot masks the global value; it does not silently restore inheritance. A project kind conflict also blocks global fallback. Editing a project always writes that project's own slot, never its inherited global record.

Every active key-state must match the returned memory's ID, key, scope, project ID, version and generation. Missing, invalid or inconsistent reads show UNKNOWN and disable editing. Empty data is not assigned a beginner level. Each field displays its own server `confirmed_at` as the last owner confirmation. It is a historical statement, not proof of current ability or a combined profile timestamp.

## One-field mutation and history

A save reads the selected slot again, checks the loaded generation/version, issues an existing typed memory intent, presents the exact value and reason, and requires explicit confirmation before the existing create/revise call. Each field is committed separately. There is no atomic three-field save. A fresh save over a tombstone requires explicit consent bound to the observed generation and does not recover old values or revisions.

Revision history uses the existing memory revision endpoint. Deletion remains in Explicit memories with its existing reauthentication and deletion-preview controls. Both editors see the same memory IDs and records. Only one editor can be open; switching explicitly discards unsaved drafts and staged reviews and reads canonical state afresh. A synchronous editor-generation guard prevents outgoing handlers or cleanup from acquiring or releasing the new editor's shared memory-domain ownership.

Duplicate submissions are synchronously locked. UNKNOWN save outcomes retain only content-free receipt metadata, block writes/navigation and use read-only receipt reconciliation. NOT_OBSERVED is inconclusive; there is no automatic retry or resend. An uncertain intent can be explicitly abandoned only when no actual save was submitted. Refresh, field/scope changes, pause and authentication boundaries invalidate staged authority. App authentication boundaries retain the existing unresolved-work fence if an in-flight operation loses its private recovery view.

## Limits and verification

These owner statements may be eligible for existing local-model context selection. Inclusion is bounded by the existing memory-count and token budgets, and does not guarantee every field is supplied or followed. This feature does not claim lessons, answer feedback, model assessment, measured proficiency, training, compilation or verified model behavior.

Synthetic offline tests cover catalog/slot identity, inheritance and tombstones, kind conflicts, per-field intent and receipt binding, revision timestamps, stale/authentication/pause fences, UNKNOWN recovery, and same-tick editor switching in the real App. These tests and a production frontend build do not establish Core/PostgreSQL integration, real browser/device behavior, or local model quality.

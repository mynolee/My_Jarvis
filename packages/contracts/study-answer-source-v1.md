# Study answer source relationships v1

This pure, unregistered guard advances the study kernel's explicit-answer source
boundary. It does not implement a study HTTP service, authentication, durable
provenance, model feedback admission, or completed end-to-end study functionality.
The existing study state and ANSWER event are reused without changes.

## Relationship rule

The caller supplies an explicit answer command and a server-observed selection
of one source message and its conversation content version, bound to the exact
study session, expected study version, question and attempt from that explicit
selection. The separately supplied answer-command target must match all four
fields. The internal Target record is only a projection of those existing ANSWER
event fields, not another event protocol. A current source
observation contains that message's exact text and stored role/trust, plus the
owner, conversation, project and content version resolved from the owned source
graph. The guard requires the supplied authenticated owner to match both the study
owner and source owner; conversation and nullable project must also match exactly.
The selected message ID and content version must match the current observation.
Only USER / USER_DIRECT text can supply an answer. The explicit answer must equal
that source text exactly, with no trimming, normalization, paraphrase or fallback.

On success the existing kernel checks the exact study session/version, question,
attempt, lifecycle and bounded text, then returns its next immutable state. There
is no new study transition protocol, retry behavior, feedback path or inferred
mastery. The explicit target is matched to the trusted selection and then validated
by the kernel; the guard does not infer a target from prose. It does not prove that
the user authored rather than quoted the text or that an answer is correct.

## Trusted observations and transaction boundary

These records are internal data, not capabilities. A caller can fabricate matching
records. Schema validity, USER_DIRECT labels, UUIDs and content versions cannot
authenticate anyone or grant permission. A future adapter must obtain the actor
from its current authenticated session, resolve the source under the existing
owner/session/conversation locking discipline, check session revocation, pause,
project ownership, and conversation/message tombstones, and hold those guards
through any persistence. It must not deserialize actor/selection/source fields
from a browser or model as trusted observations. No such adapter is added here.

Missing, deleted, inaccessible or otherwise unavailable sources are passed as
null, with no source text or identity. The guard returns no state for them and
uses the same fixed STUDY_SOURCE_UNAVAILABLE error for owner, source, version,
scope, target, provenance and text mismatches. It never returns another owner's lesson
or source text. The source record's diagnostic string omits all fields. Do not
log input records, study states, answer commands or returned content.

Conversation content version uses the existing monotonic conversation revision;
it is not a new message revision or a context/authentication epoch. Any revision
change, even an unrelated new message, conservatively requires a fresh source
observation and explicit action. A matching supplied version cannot detect a
change after observation without the future adapter's transaction. The check
cannot establish real-time freshness, atomic commit or protect against fabricated
or stale observations. The guard creates no authority. The adapter must establish
current-session admission independently; unchanged conversation content version
does not imply an unchanged authenticated session.

The guard returns only the existing kernel state. It adds no retained message
reference, source receipt, content store or uniqueness ledger. Source reuse across
different explicit commands is not durably prevented. Durable source attribution,
replay prevention and integration with the source-deletion graph must be reviewed
before adding persistence. No retention duration, unit, cutoff, deletion selector
or automatic cleanup is chosen. No existing immutable object is erased.

## Synthetic contract and evidence

study-answer-source-case.schema.json is a closed synthetic test envelope, not an
HTTP request contract. It references the existing study-state and ANSWER event
schemas. Its current_source is either null or a complete observation; null does
not carry an unavailable source identity. Role/trust are structural source labels
only. Cross-record equality and source/actor checks belong to the Java policy;
Python fixture checks are a synthetic oracle, not execution of that Java policy.
The existing strict parser remains required at any future wire boundary, including
duplicate-key, floating-token and unsafe-integer rejection. JSON Schema alone
accepts mathematically integral floating-point values and is not that parser.

Java tests invoke the real pure guard against shared synthetic cases. Syntax
parsing is not semantic compilation or JUnit execution. No Spring registration,
endpoint, migration, database, model/provider call, task/profile/memory mutation,
background worker or retention activation is added. Authenticated adapter,
concurrency, database, browser and actual model verification remain separate work.

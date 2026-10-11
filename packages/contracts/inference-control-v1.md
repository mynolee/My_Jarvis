# Inference control facts v1

This internal protocol can stop or fence one previously authorized local request,
then observe durable evidence about that request. It cannot authorize generation.
It is not a browser API, a usage estimate or a claim that memory has been erased.
Study feedback execution is not part of this contract.

The two POST routes are `/internal/v1/inference-control` (FENCE_OR_STOP) and
`/internal/v1/inference-control/observe` (OBSERVE). Identity bindings stay out of
URLs. Both require the configured mutually authenticated TLS connection and a
Core-signed, operation-bound capability with audience `jarvis-inference-control`
and max_model_calls zero. Inference capabilities are invalid here; control
capabilities are invalid at the turn route. Keys are configured privately.

Requests and responses are closed objects. The request identifies the existing
owner, run, reservation, request and context, purpose, original security epoch,
and two protected commitments. No source text, credentials, model settings or
new generation permission is accepted. The full UTF-8 body is limited to16KiB;
the signed header is limited to8KiB. Reject duplicate keys, invalid Unicode,
noncanonical UUIDs, unsafe integers and unrecognized fields.

The origin commitment binds the admitted auth/context identity. The dispatch
commitment covers the canonical inference request before its control descriptor
is added. The ordinary inference capability still binds the complete final
request. These commitments are not interchangeable or bearer authority. Old
requests without control support cannot be upgraded by adding a control call.

A control capability is valid from issued_at inclusive until expires_at exclusive,
with a positive lifetime no greater than60seconds. Future issuance is rejected.
The control signer truncates its service-clock Instant to microseconds before
serializing issued_at, and derives expires_at from that truncated instant. UTC
wire fractions allow at most six digits; raw Java nanosecond strings are rejected.
A fresh service capability can observe an older permanent fact. Cross-process
wall timestamps do not prove worker completion; facts use an immutable ledger
identity and ordinal. Core records its own observation time separately.

Responses distinguish NOT_OBSERVED, PENDING and TERMINAL. An absent row does not
prove that execution never started. Fencing must durably consume the exact
identity before UNSTARTED_FENCED can be returned. COMPLETED refers to the exact
finished call; WORKER_STOPPED requires confirmed exit of its bound generation.
Neither means that usage was zero or that physical memory was wiped. Socket
closure, timeout, expired authority or a new worker generation supplies no proof.
OBSERVE cannot create a fence, signal a worker or invoke a model.

Core can prove cancellation before its own dispatch locally without constructing
a missing dispatch commitment. UNSTARTED_CORE is therefore not a service response.
After dispatch, even a lost HTTP request has a real stored commitment and can be
fenced. Matching facts replay unchanged; changed generation, ledger, ordinal or
kind conflicts. Later observation timestamps are not part of fact identity.

Control facts occupy a row allocated with the original request. Later factual
updates do not add source rows, grow the declared source-byte budget, advance
conversation versions or restore deleted content. Usage measurements remain
independent immutable facts. The current legacy UNKNOWN/quota/deletion rules
are not relaxed by this protocol.

Schemas and examples are synthetic application contracts. Their reference tests
validate wire shape and binding only. Authenticated transport, SQL concurrency,
worker lifecycle, crash recovery and native inference require separate evidence.

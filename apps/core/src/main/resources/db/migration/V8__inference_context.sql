-- Metadata-only causal anchors and revocation state; no contextual plaintext snapshots.
ALTER TABLE owners ADD COLUMN context_epoch bigint NOT NULL DEFAULT 1 CHECK(context_epoch BETWEEN 1 AND 9007199254740991);
ALTER TABLE owners ADD COLUMN history_epoch bigint NOT NULL DEFAULT 1 CHECK(history_epoch BETWEEN 1 AND 9007199254740991);
ALTER TABLE runs ADD COLUMN history_epoch bigint NOT NULL DEFAULT 1 CHECK(history_epoch BETWEEN 1 AND 9007199254740991);
-- Legacy transcript ordering was not causal; leave it visible but ineligible for new context.
ALTER TABLE runs ADD COLUMN context_history_eligible boolean NOT NULL DEFAULT false;
ALTER TABLE runs ALTER COLUMN context_history_eligible SET DEFAULT true;
ALTER TABLE messages ADD COLUMN causal_sequence bigint GENERATED ALWAYS AS IDENTITY;
CREATE TABLE inference_contexts (
 id uuid PRIMARY KEY, owner_id uuid NOT NULL REFERENCES owners(id), run_id uuid NOT NULL UNIQUE,
 conversation_id uuid NOT NULL, project_id uuid, project_version bigint,
 session_hash text NOT NULL REFERENCES sessions(token_hash), security_epoch bigint NOT NULL,
 context_epoch bigint NOT NULL, history_epoch bigint NOT NULL, profile_version bigint,
 state text NOT NULL CHECK(state IN ('CAPTURED','PREPARED','DISPATCHED','SETTLED','INVALIDATED')),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), invalidated_at timestamptz,
 UNIQUE(owner_id,id), FOREIGN KEY(owner_id,run_id) REFERENCES runs(owner_id,id),
 FOREIGN KEY(owner_id,conversation_id) REFERENCES conversations(owner_id,id),
 FOREIGN KEY(owner_id,project_id) REFERENCES projects(owner_id,id)
);
CREATE TABLE inference_context_history (
 context_id uuid NOT NULL REFERENCES inference_contexts(id), ordinal integer NOT NULL CHECK(ordinal BETWEEN 0 AND 15),
 source_id uuid NOT NULL UNIQUE, message_id uuid NOT NULL REFERENCES messages(id), run_id uuid NOT NULL REFERENCES runs(id),
 role text NOT NULL CHECK(role IN ('USER','ASSISTANT')), PRIMARY KEY(context_id,ordinal), UNIQUE(context_id,message_id)
);
CREATE TABLE inference_context_memories (
 context_id uuid NOT NULL REFERENCES inference_contexts(id), source_id uuid NOT NULL UNIQUE,
 memory_id uuid NOT NULL REFERENCES memories(id), slot_id uuid NOT NULL REFERENCES memory_slots(id),
 version bigint NOT NULL, generation bigint NOT NULL,
 PRIMARY KEY(context_id,memory_id)
);
CREATE INDEX inference_contexts_owner_active ON inference_contexts(owner_id,state);

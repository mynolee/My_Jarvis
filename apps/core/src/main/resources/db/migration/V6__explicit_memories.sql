-- Explicit owner-form memory, scoped authority, and value-free deletion ledger.
CREATE TABLE memory_slots (
 id uuid PRIMARY KEY, owner_id uuid NOT NULL REFERENCES owners(id),
 scope text NOT NULL CHECK(scope IN ('GLOBAL','PROJECT')), project_id uuid,
 key_digest text NOT NULL CHECK(key_digest ~ '^[0-9a-f]{64}$'),
 generation bigint NOT NULL DEFAULT 0 CHECK(generation BETWEEN 0 AND 9007199254740991),
 current_memory_id uuid, tombstone boolean NOT NULL DEFAULT false,
 UNIQUE(owner_id,id), UNIQUE NULLS NOT DISTINCT(owner_id,scope,project_id,key_digest),
 FOREIGN KEY(owner_id,project_id) REFERENCES projects(owner_id,id),
 CHECK((scope='GLOBAL' AND project_id IS NULL) OR (scope='PROJECT' AND project_id IS NOT NULL)),
 CHECK(NOT tombstone OR current_memory_id IS NULL)
);
CREATE TABLE memories (
 id uuid PRIMARY KEY, owner_id uuid NOT NULL REFERENCES owners(id), slot_id uuid NOT NULL,
 kind text NOT NULL CHECK(kind IN ('PROFILE','PREFERENCE')),
 generation bigint NOT NULL CHECK(generation BETWEEN 1 AND 9007199254740991),
 version bigint NOT NULL CHECK(version BETWEEN 1 AND 9007199254740991),
 payload_ref text, confirmed_at timestamptz NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(), deleted_at timestamptz,
 UNIQUE(owner_id,id), UNIQUE(owner_id,slot_id,generation),
 FOREIGN KEY(owner_id,slot_id) REFERENCES memory_slots(owner_id,id),
 CHECK((deleted_at IS NULL AND payload_ref IS NOT NULL) OR (deleted_at IS NOT NULL AND payload_ref IS NULL))
);
ALTER TABLE memory_slots ADD FOREIGN KEY(owner_id,current_memory_id) REFERENCES memories(owner_id,id);
CREATE INDEX memories_owner_page ON memories(owner_id,created_at DESC,id DESC);
CREATE TABLE memory_revisions (
 owner_id uuid NOT NULL, memory_id uuid NOT NULL, version bigint NOT NULL CHECK(version BETWEEN 1 AND 9007199254740991),
 payload_ref text NOT NULL, confirmed_at timestamptz NOT NULL,
 PRIMARY KEY(owner_id,memory_id,version), FOREIGN KEY(owner_id,memory_id) REFERENCES memories(owner_id,id)
);
CREATE TABLE memory_write_intents (
 id uuid PRIMARY KEY, owner_id uuid NOT NULL REFERENCES owners(id), slot_id uuid NOT NULL,
 session_hash text NOT NULL REFERENCES sessions(token_hash), security_epoch bigint NOT NULL,
 issuance_key uuid NOT NULL, issuance_digest text NOT NULL,
 operation text NOT NULL CHECK(operation IN ('CREATE','REVISE')), target_id uuid NOT NULL,
 expected_generation bigint NOT NULL CHECK(expected_generation BETWEEN 0 AND 9007199254740991),
 expected_version bigint CHECK(expected_version BETWEEN 1 AND 9007199254740991),
 payload_hash_ref text, arguments_ref text, expires_at timestamptz NOT NULL,
 consumed_at timestamptz, revoked_at timestamptz,
 UNIQUE(owner_id,id), UNIQUE(owner_id,issuance_key),
 FOREIGN KEY(owner_id,slot_id) REFERENCES memory_slots(owner_id,id),
 CHECK((operation='CREATE' AND expected_version IS NULL) OR (operation='REVISE' AND expected_version IS NOT NULL)),
 CHECK((revoked_at IS NULL AND payload_hash_ref IS NOT NULL AND arguments_ref IS NOT NULL) OR (revoked_at IS NOT NULL AND payload_hash_ref IS NULL AND arguments_ref IS NULL))
);
CREATE TABLE memory_delete_preparations (
 id uuid PRIMARY KEY, owner_id uuid NOT NULL REFERENCES owners(id), memory_id uuid NOT NULL, slot_id uuid NOT NULL,
 session_hash text NOT NULL REFERENCES sessions(token_hash), security_epoch bigint NOT NULL,
 issuance_key uuid NOT NULL, issuance_digest text NOT NULL,
 expected_version bigint NOT NULL CHECK(expected_version BETWEEN 1 AND 9007199254740991),
 expected_generation bigint NOT NULL CHECK(expected_generation BETWEEN 1 AND 9007199254740991),
 payload_hash_ref text, expires_at timestamptz NOT NULL, consumed_at timestamptz, revoked_at timestamptz,
 UNIQUE(owner_id,id), UNIQUE(owner_id,issuance_key),
 FOREIGN KEY(owner_id,memory_id) REFERENCES memories(owner_id,id),
 FOREIGN KEY(owner_id,slot_id) REFERENCES memory_slots(owner_id,id)
);
CREATE TABLE memory_delete_approvals (
 id uuid PRIMARY KEY, owner_id uuid NOT NULL, preparation_id uuid NOT NULL UNIQUE,
 session_hash text NOT NULL REFERENCES sessions(token_hash), security_epoch bigint NOT NULL,
 consumed_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(owner_id,preparation_id) REFERENCES memory_delete_preparations(owner_id,id)
);
CREATE TABLE memory_mutations (
 owner_id uuid NOT NULL REFERENCES owners(id), idempotency_key uuid NOT NULL,
 session_hash text NOT NULL REFERENCES sessions(token_hash), security_epoch bigint NOT NULL,
 request_digest text NOT NULL, authority_id uuid NOT NULL UNIQUE,
 memory_id uuid NOT NULL, version bigint NOT NULL CHECK(version BETWEEN 1 AND 9007199254740991),
 generation bigint NOT NULL CHECK(generation BETWEEN 1 AND 9007199254740991),
 operation text NOT NULL CHECK(operation IN ('CREATE','REVISE','DELETE')),
 created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(owner_id,idempotency_key), FOREIGN KEY(owner_id,memory_id) REFERENCES memories(owner_id,id)
);
CREATE TABLE memory_deletion_ledger (
 id uuid PRIMARY KEY, owner_id uuid NOT NULL, slot_id uuid NOT NULL, memory_id uuid NOT NULL UNIQUE,
 generation bigint NOT NULL, deleted_version bigint NOT NULL, deleted_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(owner_id,slot_id) REFERENCES memory_slots(owner_id,id),
 FOREIGN KEY(owner_id,memory_id) REFERENCES memories(owner_id,id)
);
CREATE TABLE memory_invalidation_outbox (
 id uuid PRIMARY KEY, owner_id uuid NOT NULL, memory_id uuid NOT NULL,
 version bigint NOT NULL, event_type text NOT NULL CHECK(event_type IN ('CREATED','REVISED','DELETED')),
 created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(owner_id,memory_id) REFERENCES memories(owner_id,id)
);

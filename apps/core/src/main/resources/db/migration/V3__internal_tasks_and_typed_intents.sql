-- Internal Tasks are user-managed records, never execution Runs or model grants.
CREATE TABLE tasks (
 id uuid PRIMARY KEY, owner_id uuid NOT NULL REFERENCES owners(id), project_id uuid,
 title text NOT NULL CHECK(char_length(title) BETWEEN 1 AND 200), description_ref text NOT NULL,
 due_at timestamptz, priority text NOT NULL CHECK(priority IN ('LOW','NORMAL','HIGH')),
 status text NOT NULL CHECK(status IN ('TODO','IN_PROGRESS','DONE','CANCELLED')),
 estimated_minutes integer CHECK(estimated_minutes BETWEEN 1 AND 525600),
 version bigint NOT NULL DEFAULT 1 CHECK(version BETWEEN 1 AND 9007199254740991),
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(owner_id,id), FOREIGN KEY(owner_id,project_id) REFERENCES projects(owner_id,id)
);
CREATE INDEX tasks_owner_page ON tasks(owner_id,created_at DESC,id DESC);
CREATE TABLE task_user_intents (
 id uuid PRIMARY KEY, owner_id uuid NOT NULL REFERENCES owners(id),
 session_hash text NOT NULL REFERENCES sessions(token_hash), security_epoch bigint NOT NULL,
 issuance_key uuid NOT NULL, issuance_hash text NOT NULL, response_ref text NOT NULL,
 tool_id text NOT NULL CHECK(tool_id IN ('task.create','task.update')), target_id uuid NOT NULL,
 expected_version bigint, payload_hash text NOT NULL,
 expires_at timestamptz NOT NULL, consumed_at timestamptz,
 UNIQUE(owner_id,id), UNIQUE(owner_id,issuance_key),
 CHECK((tool_id='task.create' AND expected_version IS NULL) OR (tool_id='task.update' AND expected_version IS NOT NULL AND expected_version BETWEEN 1 AND 9007199254740991))
);
CREATE TABLE task_mutations (
 owner_id uuid NOT NULL REFERENCES owners(id), idempotency_key uuid NOT NULL,
 session_hash text NOT NULL REFERENCES sessions(token_hash), security_epoch bigint NOT NULL,
 request_hash text NOT NULL, intent_id uuid NOT NULL UNIQUE,
 task_id uuid NOT NULL, response_ref text NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(owner_id,idempotency_key),
 FOREIGN KEY(owner_id,intent_id) REFERENCES task_user_intents(owner_id,id),
 FOREIGN KEY(owner_id,task_id) REFERENCES tasks(owner_id,id)
);

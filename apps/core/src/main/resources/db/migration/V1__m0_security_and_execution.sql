CREATE TABLE owners (
 id uuid PRIMARY KEY, singleton boolean NOT NULL DEFAULT true UNIQUE CHECK(singleton),
 login_name text NOT NULL UNIQUE, password_hash text NOT NULL,
 security_epoch bigint NOT NULL DEFAULT 1, execution_paused boolean NOT NULL DEFAULT false,
 version bigint NOT NULL DEFAULT 1, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE bootstrap_tokens (
 singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton), token_hash text NOT NULL,
 expires_at timestamptz NOT NULL, attempts integer NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 5), consumed_at timestamptz
);
CREATE TABLE sessions (
 token_hash text PRIMARY KEY, owner_id uuid REFERENCES owners(id), csrf_hash text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), last_seen_at timestamptz NOT NULL DEFAULT now(),
 reauthenticated_at timestamptz, security_epoch bigint, revoked_at timestamptz
);
CREATE TABLE login_limits (key_hash text PRIMARY KEY, failures integer NOT NULL, window_start timestamptz NOT NULL, blocked_until timestamptz);
CREATE TABLE crypto_nonces (key_id text NOT NULL, nonce text NOT NULL, PRIMARY KEY(key_id,nonce));
CREATE TABLE projects (id uuid PRIMARY KEY,owner_id uuid NOT NULL REFERENCES owners(id),name text NOT NULL,UNIQUE(owner_id,id));
CREATE TABLE conversations (
 id uuid PRIMARY KEY,owner_id uuid NOT NULL REFERENCES owners(id),project_id uuid,title text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),UNIQUE(owner_id,id),
 FOREIGN KEY(owner_id,project_id) REFERENCES projects(owner_id,id)
);
CREATE TABLE runs (
 id uuid PRIMARY KEY,owner_id uuid NOT NULL REFERENCES owners(id),conversation_id uuid NOT NULL,
 status text NOT NULL CHECK(status IN ('ACCEPTED','PLANNING','WAITING_INPUT','WAITING_APPROVAL','QUEUED','RUNNING','SUCCEEDED','PARTIAL_SUCCESS','FAILED','CANCEL_REQUESTED','CANCELLED','UNKNOWN','EXPIRED','BLOCKED')),
 version bigint NOT NULL DEFAULT 1,cancel_requested_at timestamptz,result_ref text,
 created_at timestamptz NOT NULL DEFAULT now(),UNIQUE(owner_id,id),
 FOREIGN KEY(owner_id,conversation_id) REFERENCES conversations(owner_id,id)
);
CREATE TABLE messages (
 id uuid PRIMARY KEY,owner_id uuid NOT NULL REFERENCES owners(id),conversation_id uuid NOT NULL,run_id uuid NOT NULL,
 client_message_id uuid NOT NULL,body_hash text NOT NULL,content_ref text NOT NULL,role text NOT NULL CHECK(role IN ('USER','ASSISTANT')),
 trust text NOT NULL CHECK(trust IN ('USER_DIRECT','MODEL_PROPOSAL')),created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(owner_id,client_message_id),FOREIGN KEY(owner_id,conversation_id) REFERENCES conversations(owner_id,id),FOREIGN KEY(owner_id,run_id) REFERENCES runs(owner_id,id)
);
CREATE TABLE action_proposals (
 id uuid PRIMARY KEY,owner_id uuid NOT NULL REFERENCES owners(id),run_id uuid NOT NULL,tool_id text NOT NULL,tool_version text NOT NULL,
 payload_ref text NOT NULL,payload_hash text NOT NULL,status text NOT NULL, fault_mode text NOT NULL DEFAULT 'SUCCESS' CHECK(fault_mode IN ('SUCCESS','TIMEOUT_AFTER_EFFECT')),version bigint NOT NULL DEFAULT 1,
 security_epoch bigint NOT NULL,valid_until timestamptz NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(owner_id,id),UNIQUE(owner_id,tool_id,run_id),FOREIGN KEY(owner_id,run_id) REFERENCES runs(owner_id,id)
);
CREATE TABLE approvals (
 id uuid PRIMARY KEY,owner_id uuid NOT NULL REFERENCES owners(id),action_id uuid NOT NULL,payload_hash text NOT NULL,
 status text NOT NULL CHECK(status IN ('PENDING','CONSUMED','REJECTED','EXPIRED','REVOKED')),
 expires_at timestamptz NOT NULL,consumed_at timestamptz,approved_session_hash text,action_version bigint NOT NULL,
 UNIQUE(owner_id,id),UNIQUE(action_id,payload_hash),FOREIGN KEY(owner_id,action_id) REFERENCES action_proposals(owner_id,id)
);
CREATE TABLE authorizations (
 id uuid PRIMARY KEY,owner_id uuid NOT NULL REFERENCES owners(id),action_id uuid NOT NULL UNIQUE,
 approval_id uuid,grant_id uuid,user_intent_id uuid,payload_hash text NOT NULL,security_epoch bigint NOT NULL,
 max_executions integer NOT NULL DEFAULT 1 CHECK(max_executions=1),valid_until timestamptz NOT NULL,
 CHECK(num_nonnulls(approval_id,grant_id,user_intent_id)=1),
 CHECK(grant_id IS NULL AND user_intent_id IS NULL),
 FOREIGN KEY(owner_id,action_id) REFERENCES action_proposals(owner_id,id),FOREIGN KEY(owner_id,approval_id) REFERENCES approvals(owner_id,id)
);
CREATE TABLE jobs (
 id uuid PRIMARY KEY,owner_id uuid NOT NULL REFERENCES owners(id),kind text NOT NULL CHECK(kind IN ('PLAN','EXECUTE')),
 logical_key text NOT NULL,run_id uuid NOT NULL,action_id uuid,status text NOT NULL CHECK(status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED','UNKNOWN','EXPIRED','BLOCKED')),
 available_at timestamptz NOT NULL DEFAULT now(),lease_owner uuid,lease_until timestamptz,lease_generation bigint NOT NULL DEFAULT 0,
 attempt_count integer NOT NULL DEFAULT 0,started_effect boolean NOT NULL DEFAULT false,created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(kind,logical_key),FOREIGN KEY(owner_id,run_id) REFERENCES runs(owner_id,id),FOREIGN KEY(owner_id,action_id) REFERENCES action_proposals(owner_id,id)
);
CREATE INDEX jobs_pick ON jobs(status,available_at);
CREATE TABLE action_executions (
 action_id uuid PRIMARY KEY REFERENCES action_proposals(id),owner_id uuid NOT NULL REFERENCES owners(id),
 status text NOT NULL,lease_generation bigint NOT NULL,started_at timestamptz NOT NULL DEFAULT now(),finished_at timestamptz,
 FOREIGN KEY(owner_id,action_id) REFERENCES action_proposals(owner_id,id)
);
CREATE TABLE mock_effects (action_id uuid PRIMARY KEY REFERENCES action_proposals(id),owner_id uuid NOT NULL REFERENCES owners(id),tool_id text NOT NULL,payload_hash text NOT NULL,created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE audit_events (id uuid PRIMARY KEY,owner_id uuid NOT NULL REFERENCES owners(id),event_type text NOT NULL,resource_id uuid NOT NULL,happened_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE outbox_events (id uuid PRIMARY KEY,owner_id uuid NOT NULL REFERENCES owners(id),aggregate_id uuid NOT NULL,event_type text NOT NULL,created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE stream_events (event_id uuid NOT NULL DEFAULT gen_random_uuid() UNIQUE,sequence bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,owner_id uuid NOT NULL REFERENCES owners(id),conversation_id uuid NOT NULL,run_id uuid NOT NULL,event_type text NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),FOREIGN KEY(owner_id,conversation_id) REFERENCES conversations(owner_id,id),FOREIGN KEY(owner_id,run_id) REFERENCES runs(owner_id,id));

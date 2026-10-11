-- An export job is a short-lived, session-bound scope selection, never an artifact.
-- No memory keys, values, revisions, source payloads, file paths or download tokens.
CREATE TABLE memory_export_jobs (
 id uuid PRIMARY KEY, owner_id uuid NOT NULL REFERENCES owners(id),
 kind text NOT NULL CHECK(kind='EXPLICIT_MEMORIES'),
 scope text NOT NULL CHECK(scope IN ('GLOBAL','PROJECT')), project_id uuid,
 format text NOT NULL CHECK(format='JSON_UTF8'),
 session_hash text NOT NULL REFERENCES sessions(token_hash), security_epoch bigint NOT NULL,
 idempotency_key uuid NOT NULL, request_digest text NOT NULL CHECK(request_digest ~ '^[0-9a-f]{64}$'),
 created_at timestamptz NOT NULL, expires_at timestamptz NOT NULL,
 UNIQUE(owner_id,id), UNIQUE(owner_id,idempotency_key),
 FOREIGN KEY(owner_id,project_id) REFERENCES projects(owner_id,id),
 CHECK((scope='GLOBAL' AND project_id IS NULL) OR (scope='PROJECT' AND project_id IS NOT NULL)),
 CHECK(expires_at=created_at+interval '15 minutes')
);
CREATE INDEX memory_export_jobs_retention ON memory_export_jobs(owner_id,created_at,id);

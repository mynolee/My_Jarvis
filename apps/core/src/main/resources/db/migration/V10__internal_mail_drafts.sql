-- Internal owner-authored text only. No mailbox account, provider, worker, send or delete capability.
CREATE TABLE mail_drafts (
 id uuid PRIMARY KEY, owner_id uuid NOT NULL REFERENCES owners(id), project_id uuid,
 version bigint NOT NULL DEFAULT 1 CHECK(version BETWEEN 1 AND 9007199254740991),
 content_ref text NOT NULL,
 delivery_status text NOT NULL DEFAULT 'NOT_SENT' CHECK(delivery_status='NOT_SENT'),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(owner_id,id), FOREIGN KEY(owner_id,project_id) REFERENCES projects(owner_id,id),
 CHECK(updated_at>=created_at)
);
CREATE INDEX mail_drafts_owner_page ON mail_drafts(owner_id,created_at DESC,id DESC);
CREATE INDEX mail_drafts_project_page ON mail_drafts(owner_id,project_id,created_at DESC,id DESC);
CREATE TABLE mail_draft_mutations (
 owner_id uuid NOT NULL REFERENCES owners(id), idempotency_key uuid NOT NULL,
 session_hash text NOT NULL REFERENCES sessions(token_hash), security_epoch bigint NOT NULL,
 request_digest text NOT NULL CHECK(request_digest ~ '^[0-9a-f]{64}$'),
 draft_id uuid NOT NULL, version bigint NOT NULL CHECK(version BETWEEN 1 AND 9007199254740991),
 operation text NOT NULL CHECK(operation IN ('CREATE','UPDATE')),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(owner_id,idempotency_key), FOREIGN KEY(owner_id,draft_id) REFERENCES mail_drafts(owner_id,id)
);

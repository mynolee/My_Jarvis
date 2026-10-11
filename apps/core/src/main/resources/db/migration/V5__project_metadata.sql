-- Explicit owner-managed project metadata, not model/tool authority.
ALTER TABLE projects ADD COLUMN description_ref text;
ALTER TABLE projects ADD COLUMN active boolean NOT NULL DEFAULT true;
ALTER TABLE projects ADD COLUMN version bigint NOT NULL DEFAULT 1 CHECK(version BETWEEN 1 AND 9007199254740991);
ALTER TABLE projects ADD COLUMN created_at timestamptz NOT NULL DEFAULT now();
CREATE INDEX projects_owner_page ON projects(owner_id,created_at DESC,id DESC);
CREATE TABLE project_creations (
 owner_id uuid NOT NULL REFERENCES owners(id), idempotency_key uuid NOT NULL,
 request_hash text NOT NULL, project_id uuid NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(owner_id,idempotency_key),
 FOREIGN KEY(owner_id,project_id) REFERENCES projects(owner_id,id)
);

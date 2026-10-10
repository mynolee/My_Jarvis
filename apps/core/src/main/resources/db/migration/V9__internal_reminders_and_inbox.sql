-- Narrow, same-owner database inbox only. This queue never participates in generic jobs or tools.
CREATE TABLE schedules (
 id uuid PRIMARY KEY, owner_id uuid NOT NULL REFERENCES owners(id), project_id uuid,
 version bigint NOT NULL CHECK(version BETWEEN 1 AND 9007199254740991),
 enabled boolean NOT NULL DEFAULT true, definition_ref text NOT NULL, definition_digest text NOT NULL,
 security_epoch bigint NOT NULL, next_fire_at timestamptz,
 created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
 UNIQUE(owner_id,id), FOREIGN KEY(owner_id,project_id) REFERENCES projects(owner_id,id)
);
CREATE INDEX schedules_due ON schedules(next_fire_at,id) WHERE enabled;
CREATE INDEX schedules_owner_page ON schedules(owner_id,created_at DESC,id DESC);
CREATE TABLE schedule_preparations (
 id uuid PRIMARY KEY, owner_id uuid NOT NULL REFERENCES owners(id),
 session_hash text NOT NULL, security_epoch bigint NOT NULL,
 operation text NOT NULL CHECK(operation IN ('CREATE','REPLACE')), target_id uuid NOT NULL,
 expected_version bigint CHECK(expected_version BETWEEN 1 AND 9007199254740991),
 version bigint NOT NULL DEFAULT 1 CHECK(version=1), snapshot_ref text NOT NULL, payload_hash text NOT NULL,
 first_fire_at timestamptz NOT NULL, issued_at timestamptz NOT NULL, expires_at timestamptz NOT NULL,
 consumed_at timestamptz,
 UNIQUE(owner_id,id), CHECK(expires_at>issued_at),
 CHECK((operation='CREATE' AND expected_version IS NULL) OR (operation='REPLACE' AND expected_version IS NOT NULL))
);
CREATE TABLE schedule_authorizations (
 id uuid PRIMARY KEY, owner_id uuid NOT NULL REFERENCES owners(id), schedule_id uuid NOT NULL,
 schedule_version bigint NOT NULL CHECK(schedule_version BETWEEN 1 AND 9007199254740991),
 preparation_id uuid NOT NULL, action text NOT NULL DEFAULT 'inbox.reminder.create' CHECK(action='inbox.reminder.create'),
 recipient_owner_id uuid NOT NULL, definition_digest text NOT NULL, security_epoch bigint NOT NULL,
 issued_at timestamptz NOT NULL, expires_at timestamptz NOT NULL,
 max_deliveries integer NOT NULL CHECK(max_deliveries BETWEEN 1 AND 31),
 delivered_count integer NOT NULL DEFAULT 0 CHECK(delivered_count>=0 AND delivered_count<=max_deliveries),
 revoked_at timestamptz,
 UNIQUE(owner_id,id), UNIQUE(owner_id,id,schedule_id,schedule_version), UNIQUE(owner_id,schedule_id,schedule_version), UNIQUE(owner_id,preparation_id),
 CHECK(owner_id=recipient_owner_id), CHECK(expires_at>issued_at AND expires_at<=issued_at+interval '2592000 seconds'),
 FOREIGN KEY(owner_id,schedule_id) REFERENCES schedules(owner_id,id),
 FOREIGN KEY(owner_id,preparation_id) REFERENCES schedule_preparations(owner_id,id)
);
CREATE TABLE schedule_mutations (
 owner_id uuid NOT NULL REFERENCES owners(id), idempotency_key uuid NOT NULL,
 session_hash text NOT NULL, security_epoch bigint NOT NULL, request_digest text NOT NULL,
 schedule_id uuid NOT NULL, result_version bigint NOT NULL CHECK(result_version BETWEEN 1 AND 9007199254740991),
 operation text NOT NULL CHECK(operation IN ('CREATE','REPLACE','DISABLE')), committed_at timestamptz NOT NULL,
 PRIMARY KEY(owner_id,idempotency_key), FOREIGN KEY(owner_id,schedule_id) REFERENCES schedules(owner_id,id)
);
CREATE TABLE schedule_occurrences (
 id uuid PRIMARY KEY, owner_id uuid NOT NULL REFERENCES owners(id), schedule_id uuid NOT NULL,
 schedule_version bigint NOT NULL CHECK(schedule_version BETWEEN 1 AND 9007199254740991), authorization_id uuid NOT NULL,
 planned_fire_at timestamptz NOT NULL,
 status text NOT NULL CHECK(status IN ('PLANNED','DELIVERED','SKIPPED_MISSED','BLOCKED','CANCELLED')),
 created_at timestamptz NOT NULL, settled_at timestamptz,
 reason_code text CHECK(reason_code IN ('PAUSED','EPOCH_CHANGED','DISABLED','REPLACED','AUTHORIZATION_EXPIRED','QUOTA_EXHAUSTED','OPERATIONAL_FAILURE','SUPERSEDED_MISSED')),
 lateness_seconds bigint CHECK(lateness_seconds>=0),
 UNIQUE(owner_id,id), UNIQUE(owner_id,id,schedule_id,schedule_version), UNIQUE(owner_id,id,schedule_id), UNIQUE(owner_id,schedule_id,schedule_version,planned_fire_at),
 FOREIGN KEY(owner_id,schedule_id) REFERENCES schedules(owner_id,id),
 FOREIGN KEY(owner_id,authorization_id,schedule_id,schedule_version) REFERENCES schedule_authorizations(owner_id,id,schedule_id,schedule_version)
);
CREATE INDEX schedule_occurrences_page ON schedule_occurrences(owner_id,schedule_id,planned_fire_at DESC,id DESC);
CREATE TABLE reminder_jobs (
 id uuid PRIMARY KEY, owner_id uuid NOT NULL REFERENCES owners(id), schedule_id uuid NOT NULL, occurrence_id uuid NOT NULL,
 status text NOT NULL CHECK(status IN ('QUEUED','RUNNING','SUCCEEDED','CANCELLED','BLOCKED','FAILED','SKIPPED_MISSED')),
 available_at timestamptz NOT NULL, lease_owner uuid, lease_until timestamptz,
 lease_generation bigint NOT NULL DEFAULT 0 CHECK(lease_generation BETWEEN 0 AND 9007199254740991),
 attempt_count integer NOT NULL DEFAULT 0 CHECK(attempt_count BETWEEN 0 AND 3),
 created_at timestamptz NOT NULL, finished_at timestamptz,
 UNIQUE(owner_id,id), UNIQUE(owner_id,occurrence_id),
 FOREIGN KEY(owner_id,schedule_id) REFERENCES schedules(owner_id,id),
 FOREIGN KEY(owner_id,occurrence_id,schedule_id) REFERENCES schedule_occurrences(owner_id,id,schedule_id)
);
CREATE INDEX reminder_jobs_pick ON reminder_jobs(owner_id,status,available_at,id);
CREATE TABLE notifications (
 id uuid PRIMARY KEY, owner_id uuid NOT NULL REFERENCES owners(id), project_id uuid,
 schedule_id uuid NOT NULL, schedule_version bigint NOT NULL CHECK(schedule_version BETWEEN 1 AND 9007199254740991),
 occurrence_id uuid NOT NULL, content_ref text NOT NULL, planned_fire_at timestamptz NOT NULL,
 lateness_seconds bigint NOT NULL CHECK(lateness_seconds BETWEEN 0 AND 3600),
 created_at timestamptz NOT NULL, opened_at timestamptz,
 CHECK(created_at>=planned_fire_at), CHECK(opened_at IS NULL OR opened_at>=created_at),
 UNIQUE(owner_id,id), UNIQUE(owner_id,occurrence_id),
 FOREIGN KEY(owner_id,project_id) REFERENCES projects(owner_id,id),
 FOREIGN KEY(owner_id,schedule_id) REFERENCES schedules(owner_id,id),
 FOREIGN KEY(owner_id,occurrence_id,schedule_id,schedule_version) REFERENCES schedule_occurrences(owner_id,id,schedule_id,schedule_version)
);
CREATE INDEX notifications_owner_page ON notifications(owner_id,created_at DESC,id DESC);

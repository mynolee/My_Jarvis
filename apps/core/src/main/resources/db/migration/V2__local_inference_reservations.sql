CREATE TABLE inference_reservations (
 id uuid PRIMARY KEY, owner_id uuid NOT NULL REFERENCES owners(id), run_id uuid NOT NULL UNIQUE,
 request_id uuid NOT NULL UNIQUE, body_hash text NOT NULL, model_manifest_hash text NOT NULL,
 status text NOT NULL CHECK(status IN ('QUEUED','STARTED','SUCCEEDED','FAILED','CANCELLED','UNKNOWN')),
 max_compute_ms bigint NOT NULL CHECK(max_compute_ms BETWEEN 1 AND 60000),
 reserved_output_tokens integer NOT NULL CHECK(reserved_output_tokens BETWEEN 1 AND 512),
 security_epoch bigint NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), started_at timestamptz,
 completed_at timestamptz, cancel_requested_at timestamptz,
 FOREIGN KEY(owner_id,run_id) REFERENCES runs(owner_id,id)
);
CREATE INDEX inference_reservations_pick ON inference_reservations(status,created_at);

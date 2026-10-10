-- Explicit owner display settings. Does not change authentication or execution authority.
ALTER TABLE owners ADD COLUMN profile_ref text;
ALTER TABLE owners ADD COLUMN profile_updated_at timestamptz;
ALTER TABLE owners ADD CONSTRAINT owner_profile_complete CHECK (
 (profile_ref IS NULL AND profile_updated_at IS NULL) OR
 (profile_ref IS NOT NULL AND profile_updated_at IS NOT NULL)
);
CREATE TABLE owner_profile_mutations (
 owner_id uuid NOT NULL REFERENCES owners(id), idempotency_key uuid NOT NULL,
 session_hash text NOT NULL REFERENCES sessions(token_hash), security_epoch bigint NOT NULL,
 request_digest text NOT NULL CHECK(request_digest ~ '^[0-9a-f]{64}$'),
 result_version bigint NOT NULL CHECK(result_version BETWEEN 1 AND 9007199254740991),
 submitted_display_name boolean NOT NULL, submitted_locale boolean NOT NULL, submitted_timezone boolean NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(owner_id,idempotency_key),
 CHECK(submitted_display_name OR submitted_locale OR submitted_timezone)
);

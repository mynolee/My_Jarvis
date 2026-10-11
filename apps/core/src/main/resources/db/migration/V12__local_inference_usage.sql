-- Existing reservations have no durable native usage provenance. Never backfill zero measurements.
ALTER TABLE inference_reservations ADD COLUMN accounting_version smallint NOT NULL DEFAULT 0
 CHECK (accounting_version IN (0,1));
ALTER TABLE inference_reservations ALTER COLUMN accounting_version SET DEFAULT 1;
ALTER TABLE inference_reservations ADD COLUMN model_id text NOT NULL DEFAULT 'local-mlx-v1'
 CHECK (model_id='local-mlx-v1');
-- Core local-text budget protocol v1: one call, input<=3584, output<=512, compute<=60000.
ALTER TABLE inference_reservations ADD COLUMN budget_revision smallint NOT NULL DEFAULT 1 CHECK(budget_revision=1);
ALTER TABLE inference_reservations ADD CONSTRAINT inference_reservation_usage_identity
 UNIQUE(owner_id,run_id,id,request_id,model_id,model_manifest_hash,created_at,body_hash,budget_revision,max_compute_ms,reserved_output_tokens);
CREATE INDEX inference_reservations_owner_usage ON inference_reservations(owner_id,created_at);

-- Preserve original attribution and ceilings even before a measurement arrives. The prepared
-- request digest is bound exactly once in the existing atomic QUEUED-to-STARTED transition.
CREATE FUNCTION protect_inference_reservation_usage_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF ROW(OLD.id,OLD.owner_id,OLD.run_id,OLD.request_id,OLD.model_id,OLD.model_manifest_hash,
        OLD.created_at,OLD.budget_revision,OLD.max_compute_ms,OLD.reserved_output_tokens,
        OLD.accounting_version,OLD.security_epoch)
    IS DISTINCT FROM
    ROW(NEW.id,NEW.owner_id,NEW.run_id,NEW.request_id,NEW.model_id,NEW.model_manifest_hash,
        NEW.created_at,NEW.budget_revision,NEW.max_compute_ms,NEW.reserved_output_tokens,
        NEW.accounting_version,NEW.security_epoch) THEN
   RAISE EXCEPTION 'Inference reservation identity and ceilings are immutable';
 END IF;
 IF OLD.started_at IS NOT NULL AND NEW.started_at IS DISTINCT FROM OLD.started_at THEN
   RAISE EXCEPTION 'Inference dispatch attribution is immutable';
 END IF;
 IF NEW.body_hash IS DISTINCT FROM OLD.body_hash AND NOT
    (OLD.status='QUEUED' AND OLD.started_at IS NULL AND NEW.status='STARTED' AND NEW.started_at IS NOT NULL) THEN
   RAISE EXCEPTION 'Inference request digest is immutable after dispatch';
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER inference_reservation_usage_identity_immutable BEFORE UPDATE ON inference_reservations
 FOR EACH ROW EXECUTE FUNCTION protect_inference_reservation_usage_identity();

-- An ERROR is an observation, not a native measurement. It cannot occupy the measured slot.
-- A later valid measurement is retained independently without reopening the run or its authority.
CREATE TABLE inference_usage_receipts (
 owner_id uuid NOT NULL, run_id uuid NOT NULL, reservation_id uuid NOT NULL,
 request_id uuid NOT NULL, model_id text NOT NULL CHECK(model_id='local-mlx-v1'),
 model_manifest_hash text NOT NULL CHECK(model_manifest_hash ~ '^[0-9a-f]{64}$'),
 request_digest text NOT NULL CHECK(request_digest ~ '^[0-9a-f]{64}$'),
 budget_revision smallint NOT NULL CHECK(budget_revision=1),
 max_compute_ms bigint NOT NULL CHECK(max_compute_ms BETWEEN 1 AND 60000),
 reserved_output_tokens integer NOT NULL CHECK(reserved_output_tokens BETWEEN 1 AND 512),
 reserved_at timestamptz NOT NULL, recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 receipt_kind text NOT NULL CHECK(receipt_kind IN ('MEASURED','ERROR')),
 response_kind text NOT NULL CHECK(response_kind IN ('ANSWER','NEED_INPUT','ERROR')),
 error_code text,
 input_tokens bigint NOT NULL CHECK(input_tokens BETWEEN 0 AND 3584),
 output_tokens bigint NOT NULL CHECK(output_tokens BETWEEN 0 AND 512),
 compute_ms bigint NOT NULL CHECK(compute_ms BETWEEN 0 AND 60000),
 peak_memory_bytes bigint NOT NULL CHECK(peak_memory_bytes BETWEEN 0 AND 9007199254740991),
 PRIMARY KEY(reservation_id,receipt_kind),
 CHECK(compute_ms<=max_compute_ms AND output_tokens<=reserved_output_tokens),
 CHECK ((receipt_kind='MEASURED' AND response_kind IN ('ANSWER','NEED_INPUT') AND error_code IS NULL)
     OR (receipt_kind='ERROR' AND response_kind='ERROR' AND error_code IS NOT NULL AND error_code IN
       ('MODEL_UNAVAILABLE','CONTRACT_VIOLATION','RESOURCE_LIMIT','CONTEXT_LIMIT','MODEL_OUTPUT_INVALID','GENERATION_TIMEOUT','CANCELLED'))),
 FOREIGN KEY(owner_id,run_id,reservation_id,request_id,model_id,model_manifest_hash,reserved_at,request_digest,budget_revision,max_compute_ms,reserved_output_tokens)
 REFERENCES inference_reservations(owner_id,run_id,id,request_id,model_id,model_manifest_hash,created_at,body_hash,budget_revision,max_compute_ms,reserved_output_tokens)
);
CREATE FUNCTION reject_inference_usage_receipt_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Inference usage receipts are immutable'; END;
$$;
CREATE TRIGGER inference_usage_receipt_immutable BEFORE UPDATE OR DELETE ON inference_usage_receipts
 FOR EACH ROW EXECUTE FUNCTION reject_inference_usage_receipt_change();

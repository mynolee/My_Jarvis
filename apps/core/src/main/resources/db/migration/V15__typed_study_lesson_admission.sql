-- Purpose is metadata on the existing reserved turn, not a new content store or authority.
-- All old requests retain CONVERSATION semantics and their original digests.
ALTER TABLE inference_reservations ADD COLUMN purpose text NOT NULL DEFAULT 'CONVERSATION'
 CONSTRAINT inference_reservation_purpose CHECK(purpose IN ('CONVERSATION','STUDY_LESSON'));
CREATE FUNCTION protect_inference_purpose() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.purpose IS DISTINCT FROM OLD.purpose THEN RAISE EXCEPTION 'Inference purpose is immutable'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER inference_purpose_immutable BEFORE UPDATE ON inference_reservations
 FOR EACH ROW EXECUTE FUNCTION protect_inference_purpose();

-- Preserve the immutable receipt FK, budgets, slots and all V12/V14 protections.
ALTER TABLE inference_usage_receipts DROP CONSTRAINT inference_usage_receipts_response_kind_check;
ALTER TABLE inference_usage_receipts ADD CONSTRAINT inference_usage_receipts_response_kind_check
 CHECK(response_kind IN ('ANSWER','NEED_INPUT','LESSON','ERROR'));
ALTER TABLE inference_usage_receipts DROP CONSTRAINT inference_usage_receipts_check1;
ALTER TABLE inference_usage_receipts ADD CONSTRAINT inference_usage_receipts_check1
 CHECK ((receipt_kind='MEASURED' AND response_kind IN ('ANSWER','NEED_INPUT','LESSON') AND error_code IS NULL)
     OR (receipt_kind='ERROR' AND response_kind='ERROR' AND error_code IS NOT NULL AND error_code IN
       ('MODEL_UNAVAILABLE','CONTRACT_VIOLATION','RESOURCE_LIMIT','CONTEXT_LIMIT','MODEL_OUTPUT_INVALID','GENERATION_TIMEOUT','CANCELLED')));

-- A receipt is accepted only for its exact immutable reservation purpose.
CREATE FUNCTION protect_inference_receipt_purpose() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE expected text;
BEGIN
 SELECT purpose INTO expected FROM inference_reservations
  WHERE owner_id=NEW.owner_id AND run_id=NEW.run_id AND id=NEW.reservation_id;
 IF NOT FOUND OR (NEW.response_kind='LESSON' AND expected<>'STUDY_LESSON')
    OR (NEW.response_kind='ANSWER' AND expected<>'CONVERSATION') THEN
  RAISE EXCEPTION 'Inference receipt purpose mismatch';
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER inference_receipt_purpose_guard BEFORE INSERT ON inference_usage_receipts
 FOR EACH ROW EXECUTE FUNCTION protect_inference_receipt_purpose();

-- The existing deletion snapshot commits every reservation column through x.*.
-- Old approvals cannot be reused against the new exact policy/schema revision.
UPDATE conversation_delete_preparations SET revoked_at=COALESCE(revoked_at,clock_timestamp()),
 expected_digest_ref=NULL,payload_hash_ref=NULL WHERE consumed_at IS NULL;

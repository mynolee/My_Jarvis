-- Forward-only control evidence; legacy execution, usage and deletion states stay unchanged.
-- Transaction insertion evidence is DB-minted, never inferred from wall time/xmin.
ALTER TABLE runs ADD COLUMN control_admission_xid text CHECK(control_admission_xid IS NULL OR (length(control_admission_xid) BETWEEN 1 AND 20 AND control_admission_xid ~ '^[0-9]+$'));
ALTER TABLE inference_contexts ADD COLUMN control_admission_xid text CHECK(control_admission_xid IS NULL OR (length(control_admission_xid) BETWEEN 1 AND 20 AND control_admission_xid ~ '^[0-9]+$'));
CREATE FUNCTION protect_control_admission_xid() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='INSERT' THEN NEW.control_admission_xid=pg_current_xact_id()::text;
 ELSIF NEW.control_admission_xid IS DISTINCT FROM OLD.control_admission_xid THEN RAISE EXCEPTION 'Admission insertion identity is immutable'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER control_admission_xid_guard BEFORE INSERT OR UPDATE ON runs FOR EACH ROW EXECUTE FUNCTION protect_control_admission_xid();
CREATE TRIGGER control_admission_xid_guard BEFORE INSERT OR UPDATE ON inference_contexts FOR EACH ROW EXECUTE FUNCTION protect_control_admission_xid();

ALTER TABLE inference_reservations ADD COLUMN control_revision smallint NOT NULL DEFAULT 0 CHECK(control_revision IN (0,1));
ALTER TABLE inference_reservations ADD CONSTRAINT inference_control_reservation_identity UNIQUE(owner_id,run_id,id,request_id);
ALTER TABLE inference_contexts ADD CONSTRAINT inference_control_context_identity UNIQUE(owner_id,run_id,id,conversation_id);

CREATE TABLE inference_control_slots (
 reservation_id uuid PRIMARY KEY, owner_id uuid NOT NULL, run_id uuid NOT NULL UNIQUE,
 request_id uuid NOT NULL UNIQUE, conversation_id uuid NOT NULL, context_id uuid NOT NULL UNIQUE,
 revision smallint NOT NULL CHECK(revision=1),
 purpose text NOT NULL CHECK(purpose IN ('CONVERSATION','STUDY_LESSON')),
 origin_binding char(64) NOT NULL CHECK(origin_binding ~ '^[0-9a-f]{64}$'),
 security_epoch bigint NOT NULL CHECK(security_epoch BETWEEN 0 AND 9007199254740991),
 created_at timestamptz NOT NULL CHECK(isfinite(created_at)),
 dispatch_commitment char(64) CHECK(dispatch_commitment IS NULL OR dispatch_commitment ~ '^[0-9a-f]{64}$'), bound_at timestamptz CHECK(bound_at IS NULL OR isfinite(bound_at)),
 decision text CHECK(decision IS NULL OR decision IN ('OWNER_CANCEL','SECURITY_REVOKED','CONTEXT_INVALIDATED','RECOVERY_UNKNOWN')), decision_at timestamptz CHECK(decision_at IS NULL OR isfinite(decision_at)),
 terminal_kind text CHECK(terminal_kind IS NULL OR terminal_kind IN ('UNSTARTED_CORE','UNSTARTED_FENCED','COMPLETED','WORKER_STOPPED')),
 generation_id uuid, ledger_instance_id uuid,
 terminal_ordinal bigint CHECK(terminal_ordinal IS NULL OR terminal_ordinal BETWEEN 1 AND 9007199254740991), recorded_at timestamptz CHECK(recorded_at IS NULL OR isfinite(recorded_at)),
 FOREIGN KEY(owner_id,run_id,reservation_id,request_id) REFERENCES inference_reservations(owner_id,run_id,id,request_id),
 FOREIGN KEY(owner_id,run_id,context_id,conversation_id) REFERENCES inference_contexts(owner_id,run_id,id,conversation_id),
 FOREIGN KEY(owner_id,conversation_id) REFERENCES conversations(owner_id,id),
 CHECK((dispatch_commitment IS NULL AND bound_at IS NULL) OR (dispatch_commitment IS NOT NULL AND bound_at IS NOT NULL AND bound_at>=created_at)),
 CHECK((decision IS NULL AND decision_at IS NULL) OR (decision IS NOT NULL AND decision_at IS NOT NULL AND decision_at>=created_at)),
 CHECK((terminal_kind IS NULL AND generation_id IS NULL AND ledger_instance_id IS NULL AND terminal_ordinal IS NULL AND recorded_at IS NULL)
  OR (terminal_kind IS NOT NULL AND recorded_at IS NOT NULL AND recorded_at>=created_at AND (bound_at IS NULL OR recorded_at>=bound_at)
   AND ((terminal_kind='UNSTARTED_CORE' AND dispatch_commitment IS NULL AND generation_id IS NULL AND ledger_instance_id IS NULL AND terminal_ordinal IS NULL AND decision IS NOT NULL)
    OR (terminal_kind='UNSTARTED_FENCED' AND dispatch_commitment IS NOT NULL AND generation_id IS NULL AND ledger_instance_id IS NOT NULL AND terminal_ordinal IS NOT NULL)
    OR (terminal_kind IN ('COMPLETED','WORKER_STOPPED') AND dispatch_commitment IS NOT NULL AND generation_id IS NOT NULL AND ledger_instance_id IS NOT NULL AND terminal_ordinal IS NOT NULL))))
);
CREATE INDEX inference_control_target ON inference_control_slots(owner_id,conversation_id,reservation_id);
CREATE INDEX inference_control_pending ON inference_control_slots(created_at,reservation_id) WHERE terminal_kind IS NULL;

CREATE FUNCTION protect_inference_control_revision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='UPDATE' AND NEW.control_revision IS DISTINCT FROM OLD.control_revision THEN RAISE EXCEPTION 'Control revision cannot be backfilled'; END IF;
 IF TG_OP='INSERT' AND NEW.control_revision=1 AND NOT COALESCE(NEW.status='QUEUED' AND NEW.started_at IS NULL AND NEW.cancel_requested_at IS NULL
  AND NEW.accounting_version=1 AND NEW.dispatch_context_id IS NULL AND NEW.dispatch_cache_policy IS NULL,false)
  THEN RAISE EXCEPTION 'Control slot requires new contextual admission'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER inference_control_revision_guard BEFORE INSERT OR UPDATE ON inference_reservations FOR EACH ROW EXECUTE FUNCTION protect_inference_control_revision();

CREATE FUNCTION verify_inference_control_slot(p_owner uuid,p_reservation uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE v_res inference_reservations%ROWTYPE; v_slot inference_control_slots%ROWTYPE; v_ctx inference_contexts%ROWTYPE; v_run runs%ROWTYPE;
BEGIN
 SELECT * INTO v_res FROM inference_reservations WHERE owner_id=p_owner AND id=p_reservation;
 IF NOT FOUND THEN RAISE EXCEPTION 'Missing control reservation'; END IF;
 SELECT * INTO v_slot FROM inference_control_slots WHERE reservation_id=p_reservation;
 IF v_res.control_revision=0 THEN
  IF FOUND THEN RAISE EXCEPTION 'Historical reservation has no control evidence'; END IF;
  RETURN;
 END IF;
 IF NOT FOUND THEN RAISE EXCEPTION 'Missing preallocated control slot'; END IF;
 SELECT * INTO v_ctx FROM inference_contexts WHERE id=v_slot.context_id;
 IF NOT FOUND THEN RAISE EXCEPTION 'Missing control context'; END IF;
 SELECT * INTO v_run FROM runs WHERE id=v_res.run_id;
 IF NOT FOUND OR v_slot.owner_id IS DISTINCT FROM v_res.owner_id OR v_slot.run_id IS DISTINCT FROM v_res.run_id
  OR v_slot.request_id IS DISTINCT FROM v_res.request_id OR v_slot.purpose IS DISTINCT FROM v_res.purpose
  OR v_slot.security_epoch IS DISTINCT FROM v_res.security_epoch OR v_slot.revision IS DISTINCT FROM v_res.control_revision
  OR v_ctx.owner_id IS DISTINCT FROM v_slot.owner_id OR v_ctx.run_id IS DISTINCT FROM v_slot.run_id OR v_ctx.conversation_id IS DISTINCT FROM v_slot.conversation_id
  OR v_ctx.security_epoch IS DISTINCT FROM v_slot.security_epoch OR v_run.owner_id IS DISTINCT FROM v_slot.owner_id
  OR v_run.conversation_id IS DISTINCT FROM v_slot.conversation_id OR v_slot.created_at<v_ctx.created_at
  OR v_run.control_admission_xid IS NULL OR v_ctx.control_admission_xid IS DISTINCT FROM v_run.control_admission_xid
  THEN RAISE EXCEPTION 'Unverified control identity'; END IF;
 IF v_res.started_at IS NULL THEN
  IF v_slot.dispatch_commitment IS NOT NULL OR v_slot.bound_at IS NOT NULL OR v_res.dispatch_context_id IS NOT NULL OR v_res.dispatch_cache_policy IS NOT NULL
   THEN RAISE EXCEPTION 'Unstarted control has no dispatch binding'; END IF;
 ELSE
  IF v_slot.dispatch_commitment IS NULL OR v_slot.bound_at IS NULL OR v_slot.bound_at IS DISTINCT FROM v_res.started_at
   OR v_res.dispatch_context_id IS DISTINCT FROM v_slot.context_id OR v_res.dispatch_cache_policy IS DISTINCT FROM 'CONTEXT_V1_NO_RESPONSE_CACHE'
   OR v_ctx.state NOT IN ('DISPATCHED','SETTLED','INVALIDATED') THEN RAISE EXCEPTION 'Missing exact control dispatch'; END IF;
 END IF;
 IF v_slot.terminal_kind='UNSTARTED_CORE' AND NOT COALESCE(v_res.started_at IS NULL AND v_res.status='CANCELLED'
  AND v_res.cancel_requested_at IS NOT NULL AND v_run.cancel_requested_at IS NOT NULL
  AND (v_run.status='CANCELLED' OR (v_run.status='BLOCKED' AND v_ctx.state='INVALIDATED' AND v_ctx.invalidated_at IS NOT NULL)),false)
  THEN RAISE EXCEPTION 'Unproven local unstarted fact'; END IF;
END; $$;

CREATE FUNCTION inference_control_decision_reason(p_owner uuid,p_reservation uuid) RETURNS text LANGUAGE plpgsql AS $$
DECLARE v_owner owners%ROWTYPE; v_res inference_reservations%ROWTYPE; v_ctx inference_contexts%ROWTYPE; v_session sessions%ROWTYPE; v_run runs%ROWTYPE; v_now timestamptz;
BEGIN
 SELECT * INTO v_owner FROM owners WHERE id=p_owner FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Missing control owner'; END IF;
 SELECT * INTO v_res FROM inference_reservations WHERE owner_id=p_owner AND id=p_reservation;
 IF NOT FOUND OR v_res.control_revision<>1 THEN RAISE EXCEPTION 'Missing controlled reservation'; END IF;
 SELECT * INTO v_ctx FROM inference_contexts WHERE owner_id=p_owner AND run_id=v_res.run_id;
 IF NOT FOUND THEN RAISE EXCEPTION 'Missing controlled context'; END IF;
 SELECT * INTO v_session FROM sessions WHERE owner_id=p_owner AND token_hash=v_ctx.session_hash FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Missing original control session'; END IF;
 PERFORM 1 FROM conversations WHERE owner_id=p_owner AND id=v_ctx.conversation_id FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Missing control target'; END IF;
 SELECT * INTO v_run FROM runs WHERE owner_id=p_owner AND id=v_res.run_id;
 IF NOT FOUND THEN RAISE EXCEPTION 'Missing control run'; END IF;
 v_now=clock_timestamp();
 IF NOT isfinite(v_now) OR v_now<v_ctx.created_at OR v_now<v_session.created_at OR v_now<v_session.last_seen_at THEN RAISE EXCEPTION 'Control clock is not valid'; END IF;
 IF v_owner.security_epoch IS DISTINCT FROM v_res.security_epoch OR v_session.security_epoch IS DISTINCT FROM v_res.security_epoch
  OR v_session.revoked_at IS NOT NULL OR v_session.created_at<=v_now-interval '12 hours' OR v_session.last_seen_at<=v_now-interval '2 hours'
  THEN RETURN 'SECURITY_REVOKED'; END IF;
 IF v_ctx.state='INVALIDATED' OR v_ctx.context_epoch IS DISTINCT FROM v_owner.context_epoch OR v_ctx.history_epoch IS DISTINCT FROM v_owner.history_epoch
  OR (v_ctx.profile_version IS NOT NULL AND v_ctx.profile_version IS DISTINCT FROM v_owner.version)
  OR (v_ctx.project_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM projects p WHERE p.owner_id=p_owner AND p.id=v_ctx.project_id AND p.version=v_ctx.project_version AND p.active))
  THEN RETURN 'CONTEXT_INVALIDATED'; END IF;
 IF v_res.cancel_requested_at IS NOT NULL OR v_run.cancel_requested_at IS NOT NULL THEN RETURN 'OWNER_CANCEL'; END IF;
 IF v_res.status='UNKNOWN' THEN RETURN 'RECOVERY_UNKNOWN'; END IF;
 RETURN NULL;
END; $$;

CREATE FUNCTION protect_control_context_transition() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.state IS DISTINCT FROM OLD.state AND EXISTS(SELECT 1 FROM inference_reservations q WHERE q.owner_id=NEW.owner_id AND q.run_id=NEW.run_id AND q.control_revision=1) THEN
  IF NOT ((OLD.state='CAPTURED' AND NEW.state='PREPARED') OR (OLD.state='PREPARED' AND NEW.state='DISPATCHED' AND OLD.invalidated_at IS NULL AND NEW.invalidated_at IS NULL)
   OR (OLD.state='DISPATCHED' AND NEW.state='SETTLED') OR (OLD.state IN ('CAPTURED','PREPARED','DISPATCHED','SETTLED') AND NEW.state='INVALIDATED'))
   THEN RAISE EXCEPTION 'Unsupported controlled context edge'; END IF;
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER inference_control_context_edge BEFORE UPDATE ON inference_contexts FOR EACH ROW EXECUTE FUNCTION protect_control_context_transition();

CREATE FUNCTION protect_inference_control_slot() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_res inference_reservations%ROWTYPE; v_ctx inference_contexts%ROWTYPE; v_run runs%ROWTYPE; v_con conversations%ROWTYPE; v_owner owners%ROWTYPE; v_now timestamptz; v_reason text;
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Control identity is permanent'; END IF;
 SELECT * INTO v_owner FROM owners WHERE id=NEW.owner_id FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Missing control owner'; END IF;
 SELECT * INTO v_res FROM inference_reservations WHERE owner_id=NEW.owner_id AND id=NEW.reservation_id;
 IF NOT FOUND OR v_res.control_revision<>1 THEN RAISE EXCEPTION 'Unsupported control reservation'; END IF;
 SELECT * INTO v_ctx FROM inference_contexts WHERE owner_id=NEW.owner_id AND id=NEW.context_id;
 IF NOT FOUND THEN RAISE EXCEPTION 'Missing control context'; END IF;
 PERFORM 1 FROM sessions WHERE owner_id=NEW.owner_id AND token_hash=v_ctx.session_hash FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Missing original control session'; END IF;
 SELECT * INTO v_con FROM conversations WHERE owner_id=NEW.owner_id AND id=NEW.conversation_id FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Missing control conversation'; END IF;
 SELECT * INTO v_run FROM runs WHERE owner_id=NEW.owner_id AND id=NEW.run_id;
 IF NOT FOUND THEN RAISE EXCEPTION 'Missing control run'; END IF;
 v_now=clock_timestamp();
 IF NOT isfinite(v_now) OR NEW.created_at>v_now OR NEW.bound_at>v_now OR NEW.decision_at>v_now OR NEW.recorded_at>v_now THEN RAISE EXCEPTION 'Future control observation'; END IF;
 IF TG_OP='INSERT' THEN
  IF v_run.control_admission_xid IS DISTINCT FROM pg_current_xact_id()::text OR v_ctx.control_admission_xid IS DISTINCT FROM pg_current_xact_id()::text
   OR v_run.status<>'QUEUED' OR v_con.deleted_at IS NOT NULL OR v_run.content_deleted_at IS NOT NULL OR v_res.status<>'QUEUED' OR v_res.started_at IS NOT NULL
   OR v_res.cancel_requested_at IS NOT NULL OR v_run.cancel_requested_at IS NOT NULL OR v_ctx.state<>'CAPTURED'
   OR NEW.dispatch_commitment IS NOT NULL OR NEW.bound_at IS NOT NULL OR NEW.decision IS NOT NULL OR NEW.decision_at IS NOT NULL
   OR NEW.terminal_kind IS NOT NULL OR NEW.recorded_at IS NOT NULL THEN RAISE EXCEPTION 'Control slot requires live new admission'; END IF;
 ELSE
  IF ROW(NEW.reservation_id,NEW.owner_id,NEW.run_id,NEW.request_id,NEW.conversation_id,NEW.context_id,NEW.revision,NEW.purpose,NEW.origin_binding,NEW.security_epoch,NEW.created_at)
   IS DISTINCT FROM ROW(OLD.reservation_id,OLD.owner_id,OLD.run_id,OLD.request_id,OLD.conversation_id,OLD.context_id,OLD.revision,OLD.purpose,OLD.origin_binding,OLD.security_epoch,OLD.created_at)
   THEN RAISE EXCEPTION 'Control identity is immutable'; END IF;
  IF OLD.dispatch_commitment IS NOT NULL AND ROW(NEW.dispatch_commitment,NEW.bound_at) IS DISTINCT FROM ROW(OLD.dispatch_commitment,OLD.bound_at)
   THEN RAISE EXCEPTION 'Control dispatch is immutable'; END IF;
  IF OLD.dispatch_commitment IS NULL AND NEW.dispatch_commitment IS NOT NULL AND (v_con.deleted_at IS NOT NULL OR v_res.status<>'STARTED'
   OR v_res.started_at IS NULL OR v_res.cancel_requested_at IS NOT NULL OR v_run.cancel_requested_at IS NOT NULL OR v_run.status<>'RUNNING' OR v_run.content_deleted_at IS NOT NULL
   OR v_res.dispatch_context_id IS DISTINCT FROM NEW.context_id OR v_ctx.state<>'DISPATCHED' OR v_ctx.invalidated_at IS NOT NULL OR OLD.decision IS NOT NULL OR OLD.terminal_kind IS NOT NULL)
   THEN RAISE EXCEPTION 'Unsupported control binding'; END IF;
  IF OLD.decision IS NOT NULL AND ROW(NEW.decision,NEW.decision_at) IS DISTINCT FROM ROW(OLD.decision,OLD.decision_at)
   THEN RAISE EXCEPTION 'Control decision is immutable'; END IF;
  IF OLD.terminal_kind IS NOT NULL AND ROW(NEW.terminal_kind,NEW.generation_id,NEW.ledger_instance_id,NEW.terminal_ordinal,NEW.recorded_at)
   IS DISTINCT FROM ROW(OLD.terminal_kind,OLD.generation_id,OLD.ledger_instance_id,OLD.terminal_ordinal,OLD.recorded_at)
   THEN RAISE EXCEPTION 'Control fact is immutable'; END IF;
  IF OLD.terminal_kind IS NULL AND NEW.terminal_kind IS NOT NULL AND NEW.decision_at IS NOT NULL AND NEW.recorded_at<NEW.decision_at
   THEN RAISE EXCEPTION 'Control fact observation precedes decision'; END IF;
 END IF;
 IF NEW.decision IS NOT NULL AND (TG_OP='INSERT' OR OLD.decision IS NULL) THEN
  v_reason=inference_control_decision_reason(NEW.owner_id,NEW.reservation_id);
  IF v_reason IS NULL OR NEW.decision IS DISTINCT FROM v_reason THEN RAISE EXCEPTION 'Control decision reason is not authoritative'; END IF;
 END IF;
 -- No source/counter writes here. Late facts occupy only this preallocated fixed metadata slot.
 RETURN NEW;
END; $$;
CREATE TRIGGER inference_control_slot_guard BEFORE INSERT OR UPDATE OR DELETE ON inference_control_slots FOR EACH ROW EXECUTE FUNCTION protect_inference_control_slot();

-- Shared bounded inventory includes preallocated bindings before source-byte reads.
CREATE FUNCTION conversation_source_inventory_v17(p_owner uuid,p_conversation uuid)
 RETURNS TABLE(scoped_rows bigint,source_bytes bigint,study_sessions bigint,study_answers bigint,control_slots bigint)
 LANGUAGE plpgsql AS $$
DECLARE tables text[]; conditions text[]; byte_terms text[]; caps integer[]; i integer; n bigint; b bigint; remaining integer;
 run_scope text := 'SELECT id FROM runs WHERE conversation_id=$1';
 action_scope text := 'SELECT a.id FROM action_proposals a JOIN runs r ON r.id=a.run_id WHERE r.conversation_id=$1';
 context_scope text; study_scope text; input_scope text; c conversations%ROWTYPE;
BEGIN
 PERFORM 1 FROM owners WHERE id=p_owner FOR UPDATE;
 SELECT * INTO c FROM conversations WHERE owner_id=p_owner AND id=p_conversation FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='23503',MESSAGE='Missing inventory conversation'; END IF;
 context_scope='SELECT id FROM inference_contexts WHERE conversation_id=$1 OR run_id IN ('||run_scope||')';
 study_scope='SELECT id FROM study_sessions WHERE conversation_id=$1 OR lesson_run_id IN ('||run_scope||')';
 input_scope='SELECT id FROM study_owner_answer_inputs WHERE conversation_id=$1 OR study_session_id IN ('||study_scope||')';
 tables=ARRAY['runs','messages','action_proposals','approvals','authorizations','jobs','action_executions','mock_effects','inference_reservations','inference_contexts','inference_context_history','inference_context_memories','study_sessions','study_owner_answer_inputs','study_mutations','inference_control_slots'];
 conditions=ARRAY[
 'x.conversation_id=$1',
 'x.conversation_id=$1 OR x.run_id IN ('||run_scope||')',
 'x.run_id IN ('||run_scope||')',
 'x.action_id IN ('||action_scope||')',
 'x.action_id IN ('||action_scope||')',
 'x.run_id IN ('||run_scope||') OR x.action_id IN ('||action_scope||')',
 'x.action_id IN ('||action_scope||')',
 'x.action_id IN ('||action_scope||')',
 'x.run_id IN ('||run_scope||')',
 'x.conversation_id=$1 OR x.run_id IN ('||run_scope||')',
 'x.context_id IN ('||context_scope||') OR x.message_id IN (SELECT id FROM messages WHERE conversation_id=$1) OR x.run_id IN ('||run_scope||')',
 'x.context_id IN ('||context_scope||')',
 'x.conversation_id=$1 OR x.lesson_run_id IN ('||run_scope||')',
 'x.conversation_id=$1 OR x.study_session_id IN ('||study_scope||')',
 'x.conversation_id=$1 OR x.study_session_id IN ('||study_scope||') OR x.lesson_run_id IN ('||run_scope||') OR x.input_id IN ('||input_scope||') OR x.attempt_id IN (SELECT attempt_id FROM study_owner_answer_inputs WHERE id IN ('||input_scope||'))',
 'x.conversation_id=$1 OR x.run_id IN ('||run_scope||') OR x.context_id IN ('||context_scope||') OR x.reservation_id IN (SELECT id FROM inference_reservations WHERE run_id IN ('||run_scope||'))'];
 byte_terms=ARRAY['octet_length(x.result_ref)','COALESCE(octet_length(x.content_ref),0)+COALESCE(octet_length(x.body_hash),0)',
 'COALESCE(octet_length(x.payload_ref),0)+COALESCE(octet_length(x.payload_hash),0)','octet_length(x.payload_hash)','octet_length(x.payload_hash)',
 NULL,NULL,'octet_length(x.payload_hash)',NULL,NULL,NULL,NULL,'octet_length(x.lesson_binding_ref)',
 'COALESCE(octet_length(x.text_ref),0)+COALESCE(octet_length(x.source_binding_ref),0)','octet_length(x.request_digest)','128'];
 caps=ARRAY[1000,2000,1000,40000,40000,40000,40000,40000,40000,1000,40000,40000,1000,2000,40000,1000];
 scoped_rows=0; source_bytes=0; study_sessions=0; study_answers=0; control_slots=0;
 FOR i IN 1..array_length(tables,1) LOOP
  remaining=LEAST(caps[i],40000-scoped_rows::integer);
  EXECUTE format('SELECT count(*) FROM (SELECT 1 FROM %I x WHERE %s LIMIT %s) bounded',tables[i],conditions[i],remaining+1) INTO n USING p_conversation;
  IF n>remaining THEN RAISE EXCEPTION USING ERRCODE='54000',MESSAGE='Conversation source scope limit'; END IF;
  scoped_rows=scoped_rows+n;
  IF tables[i]='study_sessions' THEN study_sessions=n; END IF;
  IF tables[i]='study_owner_answer_inputs' THEN study_answers=n; END IF;
  IF tables[i]='inference_control_slots' THEN control_slots=n; END IF;
 END LOOP;
 source_bytes=COALESCE(octet_length(c.title),0);
 FOR i IN 1..array_length(tables,1) LOOP
  IF byte_terms[i] IS NOT NULL THEN
   EXECUTE format('SELECT COALESCE(sum(%s),0) FROM %I x WHERE %s',byte_terms[i],tables[i],conditions[i]) INTO b USING p_conversation;
   source_bytes=source_bytes+b;
   IF source_bytes>16777216 THEN RAISE EXCEPTION USING ERRCODE='54000',MESSAGE='Conversation source scope limit'; END IF;
  END IF;
 END LOOP;
 RETURN NEXT;
END; $$;

-- Identity/source guards deliberately do NOT use V14's per-row version bump.

CREATE OR REPLACE FUNCTION conversation_source_inventory_v16(p_owner uuid,p_conversation uuid)
 RETURNS TABLE(scoped_rows bigint,source_bytes bigint,study_sessions bigint,study_answers bigint)
 LANGUAGE sql AS $$ SELECT scoped_rows,source_bytes,study_sessions,study_answers FROM conversation_source_inventory_v17(p_owner,p_conversation); $$;

CREATE OR REPLACE FUNCTION commit_study_receipt_v16() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE c conversations%ROWTYPE; reviewed_schema text;
BEGIN
 SELECT version INTO reviewed_schema FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1;
 IF reviewed_schema IS DISTINCT FROM '17' THEN RAISE EXCEPTION 'Unreviewed study schema'; END IF;
 PERFORM 1 FROM owners WHERE id=NEW.owner_id FOR UPDATE;
 SELECT * INTO c FROM conversations WHERE owner_id=NEW.owner_id AND id=NEW.conversation_id FOR UPDATE;
 IF NOT FOUND OR c.deleted_at IS NOT NULL OR c.project_id IS DISTINCT FROM NEW.project_id
  OR c.content_version>9007199254740989 OR NEW.prior_conversation_version<>c.content_version OR NEW.resulting_conversation_version<>c.content_version+1
  THEN RAISE EXCEPTION 'Study receipt conversation precondition changed'; END IF;
 PERFORM * FROM conversation_source_inventory_v16(NEW.owner_id,NEW.conversation_id);
 UPDATE conversations SET content_version=content_version+1 WHERE owner_id=NEW.owner_id AND id=NEW.conversation_id;
 RETURN NULL;
END; $$;

-- No legacy UNKNOWN/quiescence exception is introduced by control metadata.
UPDATE conversation_delete_preparations SET revoked_at=COALESCE(revoked_at,clock_timestamp()),
 expected_digest_ref=NULL,payload_hash_ref=NULL WHERE consumed_at IS NULL;

-- Deferred checks observe the complete transaction, never a partial slot prefix.
CREATE FUNCTION inference_control_closure() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_identity record;
BEGIN
 IF TG_TABLE_NAME='inference_reservations' THEN
  PERFORM verify_inference_control_slot(NEW.owner_id,NEW.id);
 ELSIF TG_TABLE_NAME='inference_control_slots' THEN
  PERFORM verify_inference_control_slot(NEW.owner_id,NEW.reservation_id);
  IF TG_OP='INSERT' THEN PERFORM * FROM conversation_source_inventory_v17(NEW.owner_id,NEW.conversation_id); END IF;
 ELSE
  FOR v_identity IN SELECT s.owner_id,s.reservation_id FROM inference_control_slots s
   WHERE (TG_TABLE_NAME='runs' AND s.run_id=NEW.id) OR (TG_TABLE_NAME='inference_contexts' AND s.context_id=NEW.id)
   LIMIT 2 LOOP
   PERFORM verify_inference_control_slot(v_identity.owner_id,v_identity.reservation_id);
  END LOOP;
 END IF;
 RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER inference_control_complete AFTER INSERT OR UPDATE ON inference_reservations DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION inference_control_closure();
CREATE CONSTRAINT TRIGGER inference_control_complete AFTER INSERT OR UPDATE ON inference_control_slots DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION inference_control_closure();
CREATE CONSTRAINT TRIGGER inference_control_complete AFTER UPDATE ON runs DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION inference_control_closure();
CREATE CONSTRAINT TRIGGER inference_control_complete AFTER UPDATE ON inference_contexts DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION inference_control_closure();

CREATE FUNCTION inference_control_observe_decision(p_owner uuid,p_reservation uuid) RETURNS text LANGUAGE plpgsql AS $$
DECLARE v_reason text; v_slot inference_control_slots%ROWTYPE; v_now timestamptz;
BEGIN
 v_reason=inference_control_decision_reason(p_owner,p_reservation);
 SELECT * INTO v_slot FROM inference_control_slots WHERE owner_id=p_owner AND reservation_id=p_reservation FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Missing preallocated control slot'; END IF;
 IF v_reason IS NULL THEN RETURN NULL; END IF;
 IF v_slot.decision IS NOT NULL THEN RETURN v_slot.decision; END IF;
 v_now=clock_timestamp();
 IF NOT isfinite(v_now) OR v_now<v_slot.created_at OR (v_slot.bound_at IS NOT NULL AND v_now<v_slot.bound_at) THEN RAISE EXCEPTION 'Control observation clock moved backward'; END IF;
 UPDATE inference_control_slots SET decision=v_reason,decision_at=v_now WHERE owner_id=p_owner AND reservation_id=p_reservation;
 RETURN v_reason;
END; $$;

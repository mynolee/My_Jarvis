-- A separate, one-shot feedback aggregate. Existing migrations and old wire contracts remain unchanged.
ALTER TABLE sessions ADD CONSTRAINT feedback_owned_session_identity UNIQUE(owner_id,token_hash);
ALTER TABLE study_owner_answer_inputs ADD CONSTRAINT feedback_owned_input_identity
 UNIQUE(owner_id,id,attempt_id,study_session_id,question_id,conversation_id);
ALTER TABLE inference_reservations DROP CONSTRAINT inference_reservation_purpose;
ALTER TABLE inference_reservations ADD CONSTRAINT inference_reservation_purpose CHECK(purpose IN ('CONVERSATION','STUDY_LESSON','STUDY_FEEDBACK'));
ALTER TABLE inference_control_slots DROP CONSTRAINT inference_control_slots_purpose_check;
ALTER TABLE inference_control_slots ADD CONSTRAINT inference_control_slots_purpose_check CHECK(purpose IN ('CONVERSATION','STUDY_LESSON','STUDY_FEEDBACK'));
ALTER TABLE inference_control_slots DROP CONSTRAINT inference_control_slots_decision_check;
ALTER TABLE inference_control_slots ADD CONSTRAINT inference_control_slots_decision_check CHECK(decision IS NULL OR decision IN ('OWNER_CANCEL','SECURITY_REVOKED','CONTEXT_INVALIDATED','RECOVERY_UNKNOWN','FEEDBACK_OUTPUT_CLOSED'));
ALTER TABLE inference_usage_receipts DROP CONSTRAINT inference_usage_receipts_response_kind_check;
ALTER TABLE inference_usage_receipts ADD CONSTRAINT inference_usage_receipts_response_kind_check CHECK(response_kind IN ('ANSWER','NEED_INPUT','LESSON','FEEDBACK','ERROR'));
ALTER TABLE inference_usage_receipts DROP CONSTRAINT inference_usage_receipts_check1;
ALTER TABLE inference_usage_receipts ADD CONSTRAINT inference_usage_receipts_check1 CHECK(
 (receipt_kind='MEASURED' AND response_kind IN ('ANSWER','NEED_INPUT','LESSON','FEEDBACK') AND error_code IS NULL)
 OR (receipt_kind='ERROR' AND response_kind='ERROR' AND error_code IS NOT NULL AND error_code IN ('MODEL_UNAVAILABLE','CONTRACT_VIOLATION','RESOURCE_LIMIT','CONTEXT_LIMIT','MODEL_OUTPUT_INVALID','GENERATION_TIMEOUT','CANCELLED')));
CREATE OR REPLACE FUNCTION protect_inference_receipt_purpose() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE expected text;
BEGIN
 SELECT purpose INTO expected FROM inference_reservations WHERE owner_id=NEW.owner_id AND run_id=NEW.run_id AND id=NEW.reservation_id;
 IF NOT FOUND OR NOT COALESCE(NEW.response_kind='ERROR'
  OR (expected='CONVERSATION' AND NEW.response_kind IN ('ANSWER','NEED_INPUT'))
  OR (expected='STUDY_LESSON' AND NEW.response_kind IN ('LESSON','NEED_INPUT'))
  OR (expected='STUDY_FEEDBACK' AND NEW.response_kind='FEEDBACK'),false) THEN RAISE EXCEPTION 'Inference receipt purpose mismatch'; END IF;
 RETURN NEW;
END; $$;

CREATE TABLE study_feedback_requests (
 id uuid PRIMARY KEY, owner_id uuid NOT NULL, conversation_id uuid NOT NULL, study_session_id uuid NOT NULL,
 lesson_run_id uuid NOT NULL, question_id uuid NOT NULL, input_id uuid NOT NULL, attempt_id uuid NOT NULL,
 run_id uuid NOT NULL, reservation_id uuid NOT NULL, request_id uuid NOT NULL, context_id uuid NOT NULL,
 project_id uuid, project_version bigint CHECK(project_version BETWEEN 1 AND 9007199254740991),
 initiating_session_hash text NOT NULL, security_epoch bigint NOT NULL CHECK(security_epoch BETWEEN 1 AND 9007199254740991),
 admitted_study_version smallint NOT NULL CHECK(admitted_study_version=3), lesson_run_version bigint NOT NULL CHECK(lesson_run_version BETWEEN 1 AND 9007199254740991),
 admission_conversation_version bigint NOT NULL CHECK(admission_conversation_version BETWEEN 1 AND 9007199254740990),
 context_epoch bigint NOT NULL CHECK(context_epoch BETWEEN 1 AND 9007199254740991), history_epoch bigint NOT NULL CHECK(history_epoch BETWEEN 1 AND 9007199254740991),
 profile_version bigint CHECK(profile_version BETWEEN 1 AND 9007199254740991), timezone text NOT NULL CHECK(timezone='UTC'),
 created_at timestamptz NOT NULL CHECK(isfinite(created_at)), admission_xid text NOT NULL CHECK(admission_xid ~ '^[0-9]{1,20}$'),
 version smallint NOT NULL CHECK(version BETWEEN 1 AND 3), state text NOT NULL CHECK(state IN ('QUEUED','RUNNING','ACCEPTED','FAILED','REVOKED')),
 output_authority text NOT NULL CHECK(output_authority IN ('ACTIVE','CLOSED','REVOKED')),
 updated_at timestamptz NOT NULL CHECK(isfinite(updated_at) AND updated_at>=created_at), closed_at timestamptz CHECK(closed_at IS NULL OR isfinite(closed_at)),
 source_binding_ref text, result_ref text, source_deleted_at timestamptz CHECK(source_deleted_at IS NULL OR isfinite(source_deleted_at)),
 phase_xid text NOT NULL CHECK(phase_xid ~ '^[0-9]{1,20}$'),
 phase_prior_conversation_version bigint NOT NULL CHECK(phase_prior_conversation_version BETWEEN 1 AND 9007199254740990),
 phase_started_at timestamptz NOT NULL CHECK(isfinite(phase_started_at)),
 phase_initial_invalidatable_contexts integer NOT NULL CHECK(phase_initial_invalidatable_contexts BETWEEN 0 AND 1000),
 phase_invalidation_scope text NOT NULL CHECK(phase_invalidation_scope IN ('NONE','OWNER_ALL','SESSION_ACTIVE','PAUSE_ACTIVE','RUN_EXACT')),
 phase_invalidation_session_hash text, phase_initial_scope_contexts integer NOT NULL CHECK(phase_initial_scope_contexts BETWEEN 0 AND 1000),
 CHECK((project_id IS NULL)=(project_version IS NULL)),
 CHECK((source_deleted_at IS NULL AND source_binding_ref IS NOT NULL AND (state='ACCEPTED')=(result_ref IS NOT NULL))
    OR (source_deleted_at IS NOT NULL AND source_binding_ref IS NULL AND result_ref IS NULL)),
 CHECK((state IN ('QUEUED','RUNNING') AND output_authority='ACTIVE') OR (state IN ('ACCEPTED','FAILED') AND output_authority='CLOSED') OR (state='REVOKED' AND output_authority='REVOKED')),
 UNIQUE(owner_id,id), UNIQUE(owner_id,input_id), UNIQUE(owner_id,run_id), UNIQUE(owner_id,reservation_id), UNIQUE(owner_id,request_id), UNIQUE(owner_id,context_id),
 UNIQUE(owner_id,study_session_id,question_id), UNIQUE(owner_id,id,conversation_id,study_session_id,lesson_run_id,question_id,input_id,attempt_id),
 CONSTRAINT feedback_owner_conversation_fk FOREIGN KEY(owner_id,conversation_id) REFERENCES conversations(owner_id,id),
 CONSTRAINT feedback_project_fk FOREIGN KEY(owner_id,project_id) REFERENCES projects(owner_id,id),
 CONSTRAINT feedback_study_fk FOREIGN KEY(owner_id,study_session_id) REFERENCES study_sessions(owner_id,id),
 CONSTRAINT feedback_lesson_fk FOREIGN KEY(owner_id,lesson_run_id) REFERENCES runs(owner_id,id),
 CONSTRAINT feedback_input_target_fk FOREIGN KEY(owner_id,input_id,attempt_id,study_session_id,question_id,conversation_id) REFERENCES study_owner_answer_inputs(owner_id,id,attempt_id,study_session_id,question_id,conversation_id),
 CONSTRAINT feedback_context_fk FOREIGN KEY(owner_id,run_id,context_id,conversation_id) REFERENCES inference_contexts(owner_id,run_id,id,conversation_id) DEFERRABLE INITIALLY DEFERRED,
 CONSTRAINT feedback_reservation_fk FOREIGN KEY(owner_id,run_id,reservation_id,request_id) REFERENCES inference_reservations(owner_id,run_id,id,request_id) DEFERRABLE INITIALLY DEFERRED,
 CONSTRAINT feedback_session_fk FOREIGN KEY(owner_id,initiating_session_hash) REFERENCES sessions(owner_id,token_hash),
 CONSTRAINT feedback_scope_session_fk FOREIGN KEY(owner_id,phase_invalidation_session_hash) REFERENCES sessions(owner_id,token_hash)
);
CREATE UNIQUE INDEX feedback_one_active_conversation ON study_feedback_requests(owner_id,conversation_id) WHERE output_authority='ACTIVE';
CREATE INDEX feedback_conversation ON study_feedback_requests(conversation_id,id);
CREATE INDEX feedback_study ON study_feedback_requests(study_session_id,id);
CREATE INDEX feedback_input ON study_feedback_requests(input_id,id);
CREATE INDEX feedback_attempt ON study_feedback_requests(attempt_id,id);
CREATE INDEX feedback_lesson ON study_feedback_requests(lesson_run_id,id);
CREATE INDEX feedback_run ON study_feedback_requests(run_id,id);
CREATE INDEX feedback_reservation ON study_feedback_requests(reservation_id,id);
CREATE INDEX feedback_context ON study_feedback_requests(context_id,id);
CREATE INDEX feedback_active_owner ON study_feedback_requests(owner_id,id) WHERE output_authority='ACTIVE';
CREATE INDEX feedback_initiating_session ON study_feedback_requests(initiating_session_hash);
CREATE INDEX feedback_scope_session ON study_feedback_requests(phase_invalidation_session_hash) WHERE phase_invalidation_session_hash IS NOT NULL;
CREATE TABLE study_feedback_events (
 owner_id uuid NOT NULL, feedback_id uuid NOT NULL, version smallint NOT NULL CHECK(version BETWEEN 1 AND 3),
 conversation_id uuid NOT NULL, study_session_id uuid NOT NULL, lesson_run_id uuid NOT NULL, question_id uuid NOT NULL, input_id uuid NOT NULL, attempt_id uuid NOT NULL, project_id uuid,
 operation text NOT NULL CHECK(operation IN ('REQUEST','DISPATCH','ACCEPT','FAIL','REVOKE')), prior_version smallint CHECK(prior_version BETWEEN 1 AND 2),
 resulting_state text NOT NULL CHECK(resulting_state IN ('QUEUED','RUNNING','ACCEPTED','FAILED','REVOKED')), output_authority text NOT NULL CHECK(output_authority IN ('ACTIVE','CLOSED','REVOKED')),
 idempotency_key uuid, operation_session_hash text, operation_security_epoch bigint CHECK(operation_security_epoch BETWEEN 1 AND 9007199254740991),
 request_digest text CHECK(request_digest IS NULL OR request_digest ~ '^[0-9a-f]{64}$'),
 reason_code text CHECK(reason_code IN ('OWNER_REVOKE','SESSION_REVOKED','OWNER_PAUSED','SOURCE_INVALIDATED','CONTEXT_INVALIDATED','TRANSPORT_UNKNOWN','RECOVERY_UNKNOWN','PREDISPATCH_FAILURE','RUN_CANCELLED','MODEL_ERROR')),
 prior_conversation_version bigint NOT NULL CHECK(prior_conversation_version BETWEEN 1 AND 9007199254740990),
 resulting_conversation_version bigint NOT NULL CHECK(resulting_conversation_version>prior_conversation_version AND resulting_conversation_version<=9007199254740990),
 committed_at timestamptz NOT NULL CHECK(isfinite(committed_at)), phase_xid text NOT NULL CHECK(phase_xid ~ '^[0-9]{1,20}$'),
 invalidated_contexts integer NOT NULL CHECK(invalidated_contexts BETWEEN 0 AND 1000),
 PRIMARY KEY(owner_id,feedback_id,version),
 CHECK((idempotency_key IS NOT NULL AND operation_session_hash IS NOT NULL AND operation_security_epoch IS NOT NULL AND request_digest IS NOT NULL AND operation IN ('REQUEST','REVOKE'))
    OR (idempotency_key IS NULL AND operation_session_hash IS NULL AND operation_security_epoch IS NULL AND request_digest IS NULL AND operation<>'REQUEST')),
 CONSTRAINT feedback_event_target_fk FOREIGN KEY(owner_id,feedback_id,conversation_id,study_session_id,lesson_run_id,question_id,input_id,attempt_id) REFERENCES study_feedback_requests(owner_id,id,conversation_id,study_session_id,lesson_run_id,question_id,input_id,attempt_id),
 CONSTRAINT feedback_event_session_fk FOREIGN KEY(owner_id,operation_session_hash) REFERENCES sessions(owner_id,token_hash)
);
CREATE UNIQUE INDEX feedback_operation_key ON study_feedback_events(owner_id,idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX feedback_event_conversation ON study_feedback_events(conversation_id,feedback_id,version);
CREATE INDEX feedback_event_study ON study_feedback_events(study_session_id,feedback_id);
CREATE INDEX feedback_event_lesson ON study_feedback_events(lesson_run_id,feedback_id);
CREATE INDEX feedback_event_input ON study_feedback_events(input_id,feedback_id);
CREATE INDEX feedback_event_attempt ON study_feedback_events(attempt_id,feedback_id);
CREATE INDEX feedback_event_operation_session ON study_feedback_events(operation_session_hash) WHERE operation_session_hash IS NOT NULL;
ALTER TABLE conversation_delete_preparations ADD COLUMN feedback_requests_count integer NOT NULL DEFAULT 0 CHECK(feedback_requests_count BETWEEN 0 AND 2000);
ALTER TABLE conversation_delete_preparations ADD COLUMN feedback_results_count integer NOT NULL DEFAULT 0 CHECK(feedback_results_count BETWEEN 0 AND feedback_requests_count);
UPDATE conversation_delete_preparations SET revoked_at=COALESCE(revoked_at,clock_timestamp()),expected_digest_ref=NULL,payload_hash_ref=NULL WHERE consumed_at IS NULL;

-- All metadata caps are checked before any source-byte aggregation.
CREATE FUNCTION conversation_source_inventory_v18(p_owner uuid,p_conversation uuid)
 RETURNS TABLE(scoped_rows bigint,source_bytes bigint,study_sessions bigint,study_answers bigint,control_slots bigint,feedback_requests bigint,feedback_events bigint,feedback_results bigint)
 LANGUAGE plpgsql AS $$
DECLARE tables text[]; conditions text[]; byte_terms text[]; caps integer[]; i integer; n bigint; b bigint; remaining integer;
 run_scope text := 'SELECT id FROM runs WHERE conversation_id=$1';
 action_scope text := 'SELECT a.id FROM action_proposals a JOIN runs r ON r.id=a.run_id WHERE r.conversation_id=$1';
 context_scope text; study_scope text; input_scope text; feedback_scope text; c conversations%ROWTYPE;
BEGIN
 PERFORM 1 FROM owners WHERE id=p_owner FOR UPDATE;
 SELECT * INTO c FROM conversations WHERE owner_id=p_owner AND id=p_conversation FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='23503',MESSAGE='Missing inventory conversation'; END IF;
 context_scope='SELECT id FROM inference_contexts WHERE conversation_id=$1 OR run_id IN ('||run_scope||')';
 study_scope='SELECT id FROM study_sessions WHERE conversation_id=$1 OR lesson_run_id IN ('||run_scope||')';
 input_scope='SELECT id FROM study_owner_answer_inputs WHERE conversation_id=$1 OR study_session_id IN ('||study_scope||')';
 feedback_scope='SELECT id FROM study_feedback_requests WHERE conversation_id=$1 OR study_session_id IN ('||study_scope||') OR input_id IN ('||input_scope||') OR attempt_id IN (SELECT attempt_id FROM study_owner_answer_inputs WHERE id IN ('||input_scope||')) OR lesson_run_id IN ('||run_scope||') OR run_id IN ('||run_scope||') OR context_id IN ('||context_scope||') OR reservation_id IN (SELECT id FROM inference_reservations WHERE run_id IN ('||run_scope||'))';
 tables=ARRAY['runs','messages','action_proposals','approvals','authorizations','jobs','action_executions','mock_effects','inference_reservations','inference_contexts','inference_context_history','inference_context_memories','study_sessions','study_owner_answer_inputs','study_mutations','inference_control_slots','study_feedback_requests','study_feedback_events'];
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
 'x.conversation_id=$1 OR x.run_id IN ('||run_scope||') OR x.context_id IN ('||context_scope||') OR x.reservation_id IN (SELECT id FROM inference_reservations WHERE run_id IN ('||run_scope||'))',
 'x.id IN ('||feedback_scope||')',
 'x.conversation_id=$1 OR x.feedback_id IN ('||feedback_scope||') OR x.study_session_id IN ('||study_scope||') OR x.lesson_run_id IN ('||run_scope||') OR x.input_id IN ('||input_scope||') OR x.attempt_id IN (SELECT attempt_id FROM study_owner_answer_inputs WHERE id IN ('||input_scope||'))'];
 byte_terms=ARRAY['octet_length(x.result_ref)','COALESCE(octet_length(x.content_ref),0)+COALESCE(octet_length(x.body_hash),0)',
 'COALESCE(octet_length(x.payload_ref),0)+COALESCE(octet_length(x.payload_hash),0)','octet_length(x.payload_hash)','octet_length(x.payload_hash)',
 NULL,NULL,'octet_length(x.payload_hash)',NULL,NULL,NULL,NULL,'octet_length(x.lesson_binding_ref)',
 'COALESCE(octet_length(x.text_ref),0)+COALESCE(octet_length(x.source_binding_ref),0)','octet_length(x.request_digest)','128','COALESCE(octet_length(x.source_binding_ref),0)+COALESCE(octet_length(x.result_ref),0)','octet_length(x.request_digest)'];
 caps=ARRAY[1000,2000,1000,40000,40000,40000,40000,40000,40000,1000,40000,40000,1000,2000,40000,1000,2000,6000];
 scoped_rows=0; source_bytes=0; study_sessions=0; study_answers=0; control_slots=0; feedback_requests=0; feedback_events=0; feedback_results=0;
 FOR i IN 1..array_length(tables,1) LOOP
  remaining=LEAST(caps[i],40000-scoped_rows::integer);
  EXECUTE format('SELECT count(*) FROM (SELECT 1 FROM %I x WHERE %s LIMIT %s) bounded',tables[i],conditions[i],remaining+1) INTO n USING p_conversation;
  IF n>remaining THEN RAISE EXCEPTION USING ERRCODE='54000',MESSAGE='Conversation source scope limit'; END IF;
  scoped_rows=scoped_rows+n;
  IF tables[i]='study_sessions' THEN study_sessions=n; END IF;
  IF tables[i]='study_owner_answer_inputs' THEN study_answers=n; END IF;
  IF tables[i]='inference_control_slots' THEN control_slots=n; END IF;
  IF tables[i]='study_feedback_requests' THEN feedback_requests=n; END IF;
  IF tables[i]='study_feedback_events' THEN feedback_events=n; END IF;
 END LOOP;
 EXECUTE 'SELECT count(*) FROM study_feedback_requests x WHERE ('||conditions[17]||') AND x.state=''ACCEPTED'' AND x.result_ref IS NOT NULL' INTO feedback_results USING p_conversation;
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

CREATE OR REPLACE FUNCTION conversation_source_inventory_v17(p_owner uuid,p_conversation uuid)
 RETURNS TABLE(scoped_rows bigint,source_bytes bigint,study_sessions bigint,study_answers bigint,control_slots bigint)
 LANGUAGE sql AS $$ SELECT scoped_rows,source_bytes,study_sessions,study_answers,control_slots FROM conversation_source_inventory_v18(p_owner,p_conversation); $$;
CREATE OR REPLACE FUNCTION conversation_source_inventory_v16(p_owner uuid,p_conversation uuid)
 RETURNS TABLE(scoped_rows bigint,source_bytes bigint,study_sessions bigint,study_answers bigint)
 LANGUAGE sql AS $$ SELECT scoped_rows,source_bytes,study_sessions,study_answers FROM conversation_source_inventory_v18(p_owner,p_conversation); $$;
CREATE OR REPLACE FUNCTION commit_study_receipt_v16() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE c conversations%ROWTYPE; reviewed_schema text;
BEGIN
 SELECT version INTO reviewed_schema FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1;
 IF reviewed_schema IS DISTINCT FROM '18' THEN RAISE EXCEPTION 'Unreviewed study schema'; END IF;
 PERFORM 1 FROM owners WHERE id=NEW.owner_id FOR UPDATE;
 SELECT * INTO c FROM conversations WHERE owner_id=NEW.owner_id AND id=NEW.conversation_id FOR UPDATE;
 IF NOT FOUND OR c.deleted_at IS NOT NULL OR c.project_id IS DISTINCT FROM NEW.project_id
  OR c.content_version>9007199254740989 OR NEW.prior_conversation_version<>c.content_version OR NEW.resulting_conversation_version<>c.content_version+1
  THEN RAISE EXCEPTION 'Study receipt conversation precondition changed'; END IF;
 PERFORM * FROM conversation_source_inventory_v16(NEW.owner_id,NEW.conversation_id);
 UPDATE conversations SET content_version=content_version+1 WHERE owner_id=NEW.owner_id AND id=NEW.conversation_id;
 RETURN NULL;
END; $$;


CREATE FUNCTION protect_study_feedback_request_v18() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE c conversations%ROWTYPE; o owners%ROWTYPE; s sessions%ROWTYPE; st study_sessions%ROWTYPE; a study_owner_answer_inputs%ROWTYPE;
 n timestamptz; e study_feedback_events%ROWTYPE; scope_count integer; all_count integer; allowed text[];
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Feedback identity is permanent'; END IF;
 SELECT * INTO o FROM owners WHERE id=NEW.owner_id FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Missing feedback owner'; END IF;
 SELECT * INTO s FROM sessions WHERE owner_id=NEW.owner_id AND token_hash=NEW.initiating_session_hash FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Missing feedback initiating session'; END IF;
 SELECT * INTO c FROM conversations WHERE owner_id=NEW.owner_id AND id=NEW.conversation_id FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Missing feedback conversation'; END IF;
 n=clock_timestamp();
 IF NOT isfinite(n) OR n<s.created_at OR n<s.last_seen_at THEN RAISE EXCEPTION 'Feedback clock is invalid'; END IF;
 IF TG_OP='UPDATE' AND NEW IS NOT DISTINCT FROM OLD THEN RETURN NEW; END IF;
 IF c.deleted_at IS NOT NULL THEN
  IF TG_OP<>'UPDATE' OR OLD.source_deleted_at IS NOT NULL OR NEW.source_deleted_at IS DISTINCT FROM c.deleted_at
   OR NEW.source_binding_ref IS NOT NULL OR NEW.result_ref IS NOT NULL
   OR (to_jsonb(NEW)-ARRAY['source_binding_ref','result_ref','source_deleted_at']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['source_binding_ref','result_ref','source_deleted_at'])
   OR NOT EXISTS(SELECT 1 FROM conversation_deletion_ledger l WHERE l.owner_id=NEW.owner_id AND l.conversation_id=c.id AND l.deleted_at=c.deleted_at)
   THEN RAISE EXCEPTION 'Feedback redaction requires exact deletion'; END IF;
  RETURN NEW;
 END IF;
 IF NEW.source_deleted_at IS NOT NULL THEN RAISE EXCEPTION 'Feedback source requires live target'; END IF;
 IF TG_OP='UPDATE' THEN
  SELECT * INTO e FROM study_feedback_events WHERE owner_id=OLD.owner_id AND feedback_id=OLD.id AND version=OLD.version AND phase_xid=pg_current_xact_id()::text;
  IF FOUND AND OLD.phase_xid=pg_current_xact_id()::text
   AND (to_jsonb(NEW)-ARRAY['updated_at','closed_at']) IS NOT DISTINCT FROM (to_jsonb(OLD)-ARRAY['updated_at','closed_at'])
   AND NEW.updated_at IS NOT DISTINCT FROM e.committed_at
   AND NEW.closed_at IS NOT DISTINCT FROM (CASE WHEN NEW.output_authority='ACTIVE' THEN NULL ELSE e.committed_at END) THEN RETURN NEW; END IF;
  IF OLD.output_authority<>'ACTIVE' OR OLD.phase_xid=pg_current_xact_id()::text OR NEW.version<>OLD.version+1
   OR NOT ((OLD.state='QUEUED' AND NEW.state IN ('RUNNING','REVOKED')) OR (OLD.state='RUNNING' AND NEW.state IN ('ACCEPTED','FAILED','REVOKED')))
   THEN RAISE EXCEPTION 'Feedback transition requires one exact new phase'; END IF;
  allowed=ARRAY['version','state','output_authority','result_ref','phase_xid','phase_prior_conversation_version','phase_started_at','phase_initial_invalidatable_contexts','phase_invalidation_scope','phase_invalidation_session_hash','phase_initial_scope_contexts'];
  IF (to_jsonb(NEW)-allowed) IS DISTINCT FROM (to_jsonb(OLD)-allowed) THEN RAISE EXCEPTION 'Feedback identity and source are immutable'; END IF;
  IF c.content_version IS DISTINCT FROM (SELECT resulting_conversation_version FROM study_feedback_events WHERE owner_id=OLD.owner_id AND feedback_id=OLD.id AND version=OLD.version)
   THEN RAISE EXCEPTION 'Feedback prior phase is not sealed'; END IF;
 ELSE
  PERFORM * FROM conversation_source_inventory_v18(NEW.owner_id,c.id);
  IF EXISTS(SELECT 1 FROM study_feedback_requests z WHERE z.owner_id=NEW.owner_id AND z.conversation_id=c.id AND (z.output_authority='ACTIVE' OR z.phase_xid=pg_current_xact_id()::text OR NOT study_feedback_execution_quiescent_v18(z.owner_id,z.reservation_id)))
   OR EXISTS(SELECT 1 FROM runs r WHERE r.conversation_id=c.id AND (r.owner_id IS DISTINCT FROM NEW.owner_id OR (r.status NOT IN ('SUCCEEDED','PARTIAL_SUCCESS','FAILED','CANCELLED','EXPIRED','BLOCKED','WAITING_INPUT') AND NOT EXISTS(SELECT 1 FROM study_feedback_requests z WHERE z.owner_id=r.owner_id AND z.run_id=r.id AND study_feedback_execution_quiescent_v18(z.owner_id,z.reservation_id)))))
   OR EXISTS(SELECT 1 FROM inference_reservations q JOIN runs r ON r.id=q.run_id WHERE r.conversation_id=c.id AND q.purpose<>'STUDY_FEEDBACK' AND (q.status NOT IN ('SUCCEEDED','FAILED','CANCELLED') OR q.accounting_version<>1 OR q.started_at IS NULL OR q.dispatch_context_id IS NULL OR q.dispatch_cache_policy IS DISTINCT FROM 'CONTEXT_V1_NO_RESPONSE_CACHE' OR NOT r.context_history_eligible))
   OR EXISTS(SELECT 1 FROM inference_contexts x WHERE x.conversation_id=c.id AND (x.owner_id IS DISTINCT FROM NEW.owner_id OR x.state NOT IN ('SETTLED','INVALIDATED') OR NOT EXISTS(SELECT 1 FROM inference_reservations q WHERE q.owner_id=x.owner_id AND q.run_id=x.run_id AND (q.dispatch_context_id=x.id OR q.purpose='STUDY_FEEDBACK' AND study_feedback_execution_quiescent_v18(q.owner_id,q.id)))))
   OR EXISTS(SELECT 1 FROM action_proposals action_row JOIN runs r ON r.id=action_row.run_id WHERE r.conversation_id=c.id AND action_row.status NOT IN ('SUCCEEDED','FAILED','CANCELLED','EXPIRED','BLOCKED'))
   OR EXISTS(SELECT 1 FROM approvals approval_row JOIN action_proposals p ON p.id=approval_row.action_id JOIN runs r ON r.id=p.run_id WHERE r.conversation_id=c.id AND approval_row.status NOT IN ('CONSUMED','REJECTED','EXPIRED','REVOKED'))
   OR EXISTS(SELECT 1 FROM jobs j JOIN runs r ON r.id=j.run_id WHERE r.conversation_id=c.id AND j.status NOT IN ('SUCCEEDED','FAILED','CANCELLED','EXPIRED','BLOCKED'))
   OR EXISTS(SELECT 1 FROM action_executions execution_row JOIN action_proposals p ON p.id=execution_row.action_id JOIN runs r ON r.id=p.run_id WHERE r.conversation_id=c.id AND (execution_row.status NOT IN ('SUCCEEDED','FAILED','CANCELLED','EXPIRED','BLOCKED') OR execution_row.finished_at IS NULL))
   THEN RAISE EXCEPTION 'Feedback admission requires a quiescent complete graph'; END IF;
  IF NEW.version<>1 OR NEW.state<>'QUEUED' OR NEW.output_authority<>'ACTIVE' OR NEW.result_ref IS NOT NULL OR NEW.closed_at IS NOT NULL
   OR NEW.created_at>n OR NEW.updated_at IS DISTINCT FROM NEW.created_at OR NEW.admission_conversation_version IS DISTINCT FROM c.content_version
   OR EXISTS(SELECT 1 FROM runs WHERE id=NEW.run_id) OR EXISTS(SELECT 1 FROM inference_reservations WHERE id=NEW.reservation_id)
   OR EXISTS(SELECT 1 FROM inference_contexts WHERE id=NEW.context_id) THEN RAISE EXCEPTION 'Feedback requires a new queued allocation'; END IF;
  NEW.admission_xid=pg_current_xact_id()::text;
 END IF;
 IF NEW.state<>'REVOKED' THEN
  SELECT * INTO st FROM study_sessions WHERE owner_id=NEW.owner_id AND id=NEW.study_session_id;
  SELECT * INTO a FROM study_owner_answer_inputs WHERE owner_id=NEW.owner_id AND id=NEW.input_id;
  IF st.id IS NULL OR a.id IS NULL OR st.lifecycle<>'OPEN' OR st.version<>3 OR st.source_deleted_at IS NOT NULL
   OR st.conversation_id IS DISTINCT FROM c.id OR st.project_id IS DISTINCT FROM NEW.project_id OR c.project_id IS DISTINCT FROM NEW.project_id
   OR st.lesson_run_id IS DISTINCT FROM NEW.lesson_run_id OR st.lesson_run_version IS DISTINCT FROM NEW.lesson_run_version
   OR a.project_id IS DISTINCT FROM NEW.project_id OR a.study_session_id IS DISTINCT FROM st.id OR a.question_id IS DISTINCT FROM NEW.question_id OR a.attempt_id IS DISTINCT FROM NEW.attempt_id
   OR a.content_deleted_at IS NOT NULL OR NEW.question_id NOT IN (st.question_1_id,st.question_2_id)
   OR o.security_epoch IS DISTINCT FROM NEW.security_epoch OR s.security_epoch IS DISTINCT FROM NEW.security_epoch OR s.revoked_at IS NOT NULL
   OR s.created_at<=n-interval '12 hours' OR s.last_seen_at<=n-interval '2 hours' OR o.execution_paused
   OR o.context_epoch IS DISTINCT FROM NEW.context_epoch OR o.history_epoch IS DISTINCT FROM NEW.history_epoch OR o.version IS DISTINCT FROM NEW.profile_version
   OR (NEW.project_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM projects p WHERE p.owner_id=NEW.owner_id AND p.id=NEW.project_id AND p.version=NEW.project_version AND p.active))
   OR (SELECT count(*) FROM study_owner_answer_inputs WHERE owner_id=NEW.owner_id AND study_session_id=st.id)<>2
   THEN RAISE EXCEPTION 'Feedback source or initiating authority is invalid'; END IF;
  PERFORM verify_study_history_v16(NEW.owner_id,NEW.study_session_id);
  NEW.phase_invalidation_scope='NONE'; NEW.phase_invalidation_session_hash=NULL;
 ELSE
  IF NEW.result_ref IS NOT NULL OR NEW.phase_invalidation_scope NOT IN ('OWNER_ALL','SESSION_ACTIVE','PAUSE_ACTIVE','RUN_EXACT') THEN RAISE EXCEPTION 'Unsupported feedback revocation scope'; END IF;
  IF NEW.phase_invalidation_scope='OWNER_ALL' AND o.context_epoch IS NOT DISTINCT FROM OLD.context_epoch AND o.history_epoch IS NOT DISTINCT FROM OLD.history_epoch AND o.version IS NOT DISTINCT FROM OLD.profile_version AND o.security_epoch IS NOT DISTINCT FROM OLD.security_epoch
   THEN RAISE EXCEPTION 'Owner-wide feedback scope needs observed authority loss'; END IF;
  IF NEW.phase_invalidation_scope='SESSION_ACTIVE' AND (NEW.phase_invalidation_session_hash IS DISTINCT FROM OLD.initiating_session_hash OR (s.revoked_at IS NULL AND s.security_epoch=OLD.security_epoch AND o.security_epoch=OLD.security_epoch AND s.created_at>n-interval '12 hours' AND s.last_seen_at>n-interval '2 hours'))
   THEN RAISE EXCEPTION 'Session feedback scope needs observed authority loss'; END IF;
  IF NEW.phase_invalidation_scope='PAUSE_ACTIVE' AND NOT o.execution_paused THEN RAISE EXCEPTION 'Pause scope requires paused owner'; END IF;
  IF NEW.phase_invalidation_scope<>'SESSION_ACTIVE' AND NEW.phase_invalidation_session_hash IS NOT NULL THEN RAISE EXCEPTION 'Unexpected scope session'; END IF;
 END IF;
 SELECT count(*) INTO all_count FROM (SELECT 1 FROM inference_contexts WHERE owner_id=NEW.owner_id AND conversation_id=c.id AND state<>'INVALIDATED' LIMIT 1001) b;
 IF all_count>1000 THEN RAISE EXCEPTION USING ERRCODE='54000',MESSAGE='Feedback context scope limit'; END IF;
 SELECT count(*) INTO scope_count FROM inference_contexts x WHERE x.owner_id=NEW.owner_id AND x.conversation_id=c.id AND x.state<>'INVALIDATED' AND
  (NEW.phase_invalidation_scope='OWNER_ALL' OR (NEW.phase_invalidation_scope='SESSION_ACTIVE' AND x.session_hash=NEW.phase_invalidation_session_hash AND x.state IN ('CAPTURED','PREPARED','DISPATCHED'))
   OR (NEW.phase_invalidation_scope='PAUSE_ACTIVE' AND x.state IN ('CAPTURED','PREPARED','DISPATCHED')) OR (NEW.phase_invalidation_scope='RUN_EXACT' AND x.id=NEW.context_id));
 IF NEW.state='REVOKED' AND (scope_count<1 OR NOT EXISTS(SELECT 1 FROM inference_contexts WHERE owner_id=NEW.owner_id AND id=NEW.context_id AND state IN ('CAPTURED','PREPARED','DISPATCHED'))) THEN RAISE EXCEPTION 'Revocation scope must include the exact feedback context'; END IF;
 NEW.phase_xid=pg_current_xact_id()::text; NEW.phase_prior_conversation_version=c.content_version; NEW.phase_started_at=n;
 NEW.phase_initial_invalidatable_contexts=all_count; NEW.phase_initial_scope_contexts=scope_count;
 RETURN NEW;
END; $$;
CREATE TRIGGER study_feedback_request_guard BEFORE INSERT OR UPDATE OR DELETE ON study_feedback_requests FOR EACH ROW EXECUTE FUNCTION protect_study_feedback_request_v18();

-- Metadata closure deliberately does not require a producer terminal fact.
CREATE FUNCTION verify_study_feedback_history_v18(p_owner uuid,p_feedback uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE f study_feedback_requests%ROWTYPE; r runs%ROWTYPE; q inference_reservations%ROWTYPE; x inference_contexts%ROWTYPE;
 k inference_control_slots%ROWTYPE; st study_sessions%ROWTYPE; a study_owner_answer_inputs%ROWTYPE; c conversations%ROWTYPE; e study_feedback_events%ROWTYPE; prev study_feedback_events%ROWTYPE; i integer:=0; expected_delta integer;
BEGIN
 SELECT * INTO f FROM study_feedback_requests WHERE owner_id=p_owner AND id=p_feedback;
 IF NOT FOUND THEN RAISE EXCEPTION 'Missing feedback aggregate'; END IF;
 SELECT * INTO r FROM runs WHERE owner_id=p_owner AND id=f.run_id;
 SELECT * INTO q FROM inference_reservations WHERE owner_id=p_owner AND id=f.reservation_id;
 SELECT * INTO x FROM inference_contexts WHERE owner_id=p_owner AND id=f.context_id;
 SELECT * INTO k FROM inference_control_slots WHERE owner_id=p_owner AND reservation_id=f.reservation_id;
 SELECT * INTO st FROM study_sessions WHERE owner_id=p_owner AND id=f.study_session_id;
 SELECT * INTO a FROM study_owner_answer_inputs WHERE owner_id=p_owner AND id=f.input_id;
 SELECT * INTO c FROM conversations WHERE owner_id=p_owner AND id=f.conversation_id;
 IF r.id IS NULL OR q.id IS NULL OR x.id IS NULL OR k.reservation_id IS NULL OR st.id IS NULL OR a.id IS NULL OR c.id IS NULL
  OR r.conversation_id IS DISTINCT FROM f.conversation_id OR r.version IS DISTINCT FROM f.version::bigint OR r.context_history_eligible IS DISTINCT FROM false OR r.result_ref IS NOT NULL OR r.history_epoch IS DISTINCT FROM f.history_epoch
  OR q.run_id IS DISTINCT FROM f.run_id OR q.request_id IS DISTINCT FROM f.request_id OR q.purpose IS DISTINCT FROM 'STUDY_FEEDBACK' OR q.accounting_version<>1 OR q.control_revision<>1 OR q.security_epoch IS DISTINCT FROM f.security_epoch
  OR q.model_id IS DISTINCT FROM 'local-mlx-v1' OR q.budget_revision<>1 OR q.max_compute_ms<>60000 OR q.reserved_output_tokens<>512 OR q.body_hash !~ '^[0-9a-f]{64}$' OR q.model_manifest_hash !~ '^[0-9a-f]{64}$'
  OR x.run_id IS DISTINCT FROM f.run_id OR x.conversation_id IS DISTINCT FROM f.conversation_id OR x.project_id IS DISTINCT FROM f.project_id OR x.project_version IS DISTINCT FROM f.project_version
  OR x.session_hash IS DISTINCT FROM f.initiating_session_hash OR x.security_epoch IS DISTINCT FROM f.security_epoch OR x.context_epoch IS DISTINCT FROM f.context_epoch OR x.history_epoch IS DISTINCT FROM f.history_epoch
  OR (f.version>=2 AND NOT(f.version=2 AND f.state='REVOKED') AND x.profile_version IS DISTINCT FROM f.profile_version)
  OR r.control_admission_xid IS DISTINCT FROM f.admission_xid OR x.control_admission_xid IS DISTINCT FROM f.admission_xid
  OR k.run_id IS DISTINCT FROM f.run_id OR k.request_id IS DISTINCT FROM f.request_id OR k.context_id IS DISTINCT FROM f.context_id OR k.conversation_id IS DISTINCT FROM f.conversation_id OR k.purpose IS DISTINCT FROM q.purpose OR k.security_epoch IS DISTINCT FROM f.security_epoch OR k.revision<>1
  OR st.conversation_id IS DISTINCT FROM f.conversation_id OR st.project_id IS DISTINCT FROM f.project_id OR st.lesson_run_id IS DISTINCT FROM f.lesson_run_id OR st.lesson_run_version IS DISTINCT FROM f.lesson_run_version
  OR st.version NOT IN (3,4) OR (st.version=3 AND st.lifecycle<>'OPEN') OR (st.version=4 AND st.lifecycle<>'CLOSED_BY_OWNER')
  OR a.conversation_id IS DISTINCT FROM f.conversation_id OR a.study_session_id IS DISTINCT FROM f.study_session_id OR a.project_id IS DISTINCT FROM f.project_id OR a.question_id IS DISTINCT FROM f.question_id OR a.attempt_id IS DISTINCT FROM f.attempt_id
  OR f.question_id NOT IN (st.question_1_id,st.question_2_id) OR (SELECT count(*) FROM study_owner_answer_inputs WHERE owner_id=p_owner AND study_session_id=st.id)<>2
  OR NOT EXISTS(SELECT 1 FROM sessions WHERE owner_id=p_owner AND token_hash=f.initiating_session_hash)
  OR EXISTS(SELECT 1 FROM messages WHERE run_id=f.run_id) OR EXISTS(SELECT 1 FROM action_proposals WHERE run_id=f.run_id) OR EXISTS(SELECT 1 FROM jobs WHERE run_id=f.run_id)
  OR EXISTS(SELECT 1 FROM inference_context_history WHERE context_id=f.context_id OR run_id=f.run_id) OR EXISTS(SELECT 1 FROM inference_context_memories WHERE context_id=f.context_id)
  THEN RAISE EXCEPTION 'Feedback metadata lineage is incomplete'; END IF;
 IF NOT COALESCE(isfinite(r.created_at) AND isfinite(q.created_at) AND isfinite(x.created_at)
  AND r.created_at<=x.created_at AND x.created_at>=q.created_at
  AND (q.started_at IS NULL OR isfinite(q.started_at) AND q.started_at>=q.created_at)
  AND (q.completed_at IS NULL OR isfinite(q.completed_at) AND q.completed_at>=q.created_at)
  AND (q.cancel_requested_at IS NULL OR isfinite(q.cancel_requested_at) AND q.cancel_requested_at>=q.created_at)
  AND (r.cancel_requested_at IS NULL OR isfinite(r.cancel_requested_at) AND r.cancel_requested_at>=r.created_at)
  AND (x.invalidated_at IS NULL OR isfinite(x.invalidated_at) AND x.invalidated_at>=x.created_at AND x.invalidated_at<=clock_timestamp()),false)
  THEN RAISE EXCEPTION 'Feedback descendant timestamps are invalid'; END IF;
 PERFORM verify_study_history_v16(p_owner,f.study_session_id);
 PERFORM verify_inference_control_slot(p_owner,f.reservation_id);
 IF c.deleted_at IS NULL THEN
  IF f.source_deleted_at IS NOT NULL OR f.source_binding_ref IS NULL OR octet_length(f.source_binding_ref)<>142
   OR (f.state='ACCEPTED') IS DISTINCT FROM (f.result_ref IS NOT NULL) OR octet_length(f.result_ref)>87438
   OR r.content_deleted_at IS NOT NULL OR a.content_deleted_at IS NOT NULL OR st.source_deleted_at IS NOT NULL THEN RAISE EXCEPTION 'Feedback source overlay is invalid'; END IF;
 ELSE
  IF f.source_deleted_at IS DISTINCT FROM c.deleted_at OR f.source_binding_ref IS NOT NULL OR f.result_ref IS NOT NULL OR r.content_deleted_at IS DISTINCT FROM c.deleted_at
   OR x.state<>'INVALIDATED' OR x.invalidated_at IS NULL OR NOT EXISTS(SELECT 1 FROM conversation_deletion_ledger WHERE owner_id=p_owner AND conversation_id=c.id AND deleted_at=c.deleted_at)
   THEN RAISE EXCEPTION 'Feedback deleted overlay is invalid'; END IF;
 END IF;
 FOR e IN SELECT * FROM study_feedback_events WHERE owner_id=p_owner AND feedback_id=f.id ORDER BY version LIMIT 4 LOOP
  i=i+1;
  IF e.version<>i OR e.conversation_id IS DISTINCT FROM f.conversation_id OR e.study_session_id IS DISTINCT FROM f.study_session_id OR e.lesson_run_id IS DISTINCT FROM f.lesson_run_id
   OR e.question_id IS DISTINCT FROM f.question_id OR e.input_id IS DISTINCT FROM f.input_id OR e.attempt_id IS DISTINCT FROM f.attempt_id OR e.project_id IS DISTINCT FROM f.project_id
   OR e.committed_at<f.created_at OR (i>1 AND (e.prior_version IS DISTINCT FROM (i-1)::smallint OR e.prior_conversation_version IS DISTINCT FROM prev.resulting_conversation_version OR e.committed_at<prev.committed_at OR e.phase_xid=prev.phase_xid))
   THEN RAISE EXCEPTION 'Feedback event chain is invalid'; END IF;
  IF i=1 THEN
   IF r.created_at>e.committed_at OR q.created_at>e.committed_at OR x.created_at>e.committed_at THEN RAISE EXCEPTION 'Feedback admission chronology is invalid'; END IF;
   IF e.operation<>'REQUEST' OR e.prior_version IS NOT NULL OR e.resulting_state<>'QUEUED' OR e.output_authority<>'ACTIVE' OR e.reason_code IS NOT NULL
    OR e.idempotency_key IS NULL OR e.operation_session_hash IS DISTINCT FROM f.initiating_session_hash OR e.operation_security_epoch IS DISTINCT FROM f.security_epoch
    OR e.prior_conversation_version IS DISTINCT FROM f.admission_conversation_version OR e.phase_xid IS DISTINCT FROM f.admission_xid THEN RAISE EXCEPTION 'Feedback request receipt is invalid'; END IF;
  ELSIF e.operation='DISPATCH' THEN
   IF i<>2 OR e.resulting_state<>'RUNNING' OR e.output_authority<>'ACTIVE' OR e.reason_code IS NOT NULL OR e.idempotency_key IS NOT NULL THEN RAISE EXCEPTION 'Feedback dispatch receipt is invalid'; END IF;
  ELSIF e.operation IN ('ACCEPT','FAIL') THEN
   IF i<>3 OR prev.operation<>'DISPATCH' OR e.resulting_state IS DISTINCT FROM (CASE e.operation WHEN 'ACCEPT' THEN 'ACCEPTED' ELSE 'FAILED' END) OR e.output_authority<>'CLOSED'
    OR e.reason_code IS DISTINCT FROM (CASE WHEN e.operation='FAIL' THEN 'MODEL_ERROR' ELSE NULL END) OR e.idempotency_key IS NOT NULL THEN RAISE EXCEPTION 'Feedback completion receipt is invalid'; END IF;
  ELSIF e.operation='REVOKE' THEN
   IF e.resulting_state<>'REVOKED' OR e.output_authority<>'REVOKED' OR e.reason_code IS NULL OR e.reason_code='MODEL_ERROR' OR (i=3 AND prev.operation<>'DISPATCH')
    OR (e.reason_code='OWNER_REVOKE') IS DISTINCT FROM (e.idempotency_key IS NOT NULL) THEN RAISE EXCEPTION 'Feedback revocation receipt is invalid'; END IF;
  ELSE RAISE EXCEPTION 'Unsupported feedback event'; END IF;
  expected_delta=CASE e.operation WHEN 'REQUEST' THEN 4 WHEN 'DISPATCH' THEN 5 WHEN 'REVOKE' THEN 3+e.invalidated_contexts ELSE 4 END;
  IF e.resulting_conversation_version-e.prior_conversation_version<>expected_delta OR (e.operation='REVOKE' AND e.invalidated_contexts<1) OR (e.operation<>'REVOKE' AND e.invalidated_contexts<>0) THEN RAISE EXCEPTION 'Feedback phase has incorrect source cost'; END IF;
  prev=e;
 END LOOP;
 IF i<>f.version OR prev.resulting_state IS DISTINCT FROM f.state OR prev.output_authority IS DISTINCT FROM f.output_authority OR prev.committed_at IS DISTINCT FROM f.updated_at
  OR prev.phase_xid IS DISTINCT FROM f.phase_xid OR f.closed_at IS DISTINCT FROM (CASE WHEN f.output_authority='ACTIVE' THEN NULL ELSE prev.committed_at END) THEN RAISE EXCEPTION 'Feedback phase is not completely sealed'; END IF;
 IF f.state='QUEUED' THEN
  IF f.version<>1 OR r.status<>'QUEUED' OR q.status<>'QUEUED' OR x.state<>'CAPTURED' OR x.profile_version IS NOT NULL OR q.started_at IS NOT NULL OR q.completed_at IS NOT NULL OR q.cancel_requested_at IS NOT NULL OR r.cancel_requested_at IS NOT NULL OR x.invalidated_at IS NOT NULL OR k.decision IS NOT NULL OR k.terminal_kind IS NOT NULL THEN RAISE EXCEPTION 'Incomplete queued feedback'; END IF;
 ELSIF f.state='RUNNING' THEN
  IF f.version<>2 OR r.status<>'RUNNING' OR q.status<>'STARTED' OR x.state<>'DISPATCHED' OR q.started_at IS NULL OR q.completed_at IS NOT NULL OR q.cancel_requested_at IS NOT NULL OR r.cancel_requested_at IS NOT NULL OR x.invalidated_at IS NOT NULL THEN RAISE EXCEPTION 'Incomplete dispatched feedback'; END IF;
 ELSIF f.state IN ('ACCEPTED','FAILED') THEN
  IF f.version<>3 OR r.status IS DISTINCT FROM (CASE f.state WHEN 'ACCEPTED' THEN 'SUCCEEDED' ELSE 'FAILED' END) OR q.status IS DISTINCT FROM r.status OR q.started_at IS NULL OR q.completed_at IS NULL OR q.cancel_requested_at IS NOT NULL OR r.cancel_requested_at IS NOT NULL
   OR NOT (x.state='SETTLED' AND x.invalidated_at IS NULL OR x.state='INVALIDATED' AND x.invalidated_at IS NOT NULL AND x.invalidated_at>=f.closed_at)
   OR NOT EXISTS(SELECT 1 FROM inference_usage_receipts u WHERE u.owner_id=p_owner AND u.reservation_id=q.id AND u.run_id=r.id AND u.request_id=q.request_id AND u.request_digest=q.body_hash
    AND u.receipt_kind=CASE f.state WHEN 'ACCEPTED' THEN 'MEASURED' ELSE 'ERROR' END AND u.response_kind=CASE f.state WHEN 'ACCEPTED' THEN 'FEEDBACK' ELSE 'ERROR' END)
   THEN RAISE EXCEPTION 'Incomplete closed feedback'; END IF;
 ELSE
  IF r.status IS DISTINCT FROM (CASE f.version WHEN 2 THEN 'CANCELLED' ELSE 'UNKNOWN' END) OR q.status IS DISTINCT FROM r.status OR r.cancel_requested_at IS NULL OR q.cancel_requested_at IS NULL OR q.completed_at IS NULL
   OR x.state<>'INVALIDATED' OR x.invalidated_at IS NULL OR (f.version=2) IS DISTINCT FROM (q.started_at IS NULL) THEN RAISE EXCEPTION 'Incomplete revoked feedback'; END IF;
 END IF;
 IF q.started_at IS NOT NULL AND NOT EXISTS(SELECT 1 FROM study_feedback_events d JOIN study_feedback_events admission_event ON admission_event.owner_id=d.owner_id AND admission_event.feedback_id=d.feedback_id AND admission_event.version=1
  WHERE d.owner_id=p_owner AND d.feedback_id=f.id AND d.operation='DISPATCH' AND q.started_at>=admission_event.committed_at AND q.started_at<=d.committed_at)
  THEN RAISE EXCEPTION 'Feedback start is outside its immutable dispatch phase'; END IF;
 IF f.output_authority<>'ACTIVE' AND (q.completed_at>f.closed_at OR q.cancel_requested_at>f.closed_at OR r.cancel_requested_at>f.closed_at
  OR (f.state='REVOKED' AND (x.invalidated_at>f.closed_at OR q.cancel_requested_at<(SELECT committed_at FROM study_feedback_events WHERE owner_id=p_owner AND feedback_id=f.id AND version=f.version-1)
   OR r.cancel_requested_at<(SELECT committed_at FROM study_feedback_events WHERE owner_id=p_owner AND feedback_id=f.id AND version=f.version-1))))
  THEN RAISE EXCEPTION 'Feedback terminal markers are outside their phase'; END IF;
 IF q.started_at IS NOT NULL AND (q.started_at<f.created_at OR q.completed_at<q.started_at OR k.bound_at IS DISTINCT FROM q.started_at OR q.dispatch_context_id IS DISTINCT FROM x.id OR q.dispatch_cache_policy IS DISTINCT FROM 'CONTEXT_V1_NO_RESPONSE_CACHE') THEN RAISE EXCEPTION 'Feedback dispatch chronology is invalid'; END IF;
END; $$;

CREATE FUNCTION protect_study_feedback_event_v18() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE f study_feedback_requests%ROWTYPE; c conversations%ROWTYPE; s sessions%ROWTYPE; o owners%ROWTYPE; n timestamptz; remaining integer; qualifying integer; delta integer;
BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'Feedback events are immutable'; END IF;
 SELECT * INTO o FROM owners WHERE id=NEW.owner_id FOR UPDATE;
 SELECT * INTO f FROM study_feedback_requests WHERE owner_id=NEW.owner_id AND id=NEW.feedback_id FOR UPDATE;
 IF NOT FOUND OR f.phase_xid IS DISTINCT FROM pg_current_xact_id()::text OR NEW.version IS DISTINCT FROM f.version
  OR EXISTS(SELECT 1 FROM study_feedback_events WHERE owner_id=f.owner_id AND feedback_id=f.id AND version=f.version)
  THEN RAISE EXCEPTION 'Feedback event requires one open database phase'; END IF;
 SELECT * INTO c FROM conversations WHERE owner_id=f.owner_id AND id=f.conversation_id FOR UPDATE;
 IF c.deleted_at IS NOT NULL OR NEW.conversation_id IS DISTINCT FROM f.conversation_id OR NEW.project_id IS DISTINCT FROM f.project_id
  OR NEW.study_session_id IS DISTINCT FROM f.study_session_id OR NEW.lesson_run_id IS DISTINCT FROM f.lesson_run_id OR NEW.question_id IS DISTINCT FROM f.question_id
  OR NEW.input_id IS DISTINCT FROM f.input_id OR NEW.attempt_id IS DISTINCT FROM f.attempt_id OR NEW.resulting_state IS DISTINCT FROM f.state OR NEW.output_authority IS DISTINCT FROM f.output_authority
  OR NEW.operation IS DISTINCT FROM (CASE f.state WHEN 'QUEUED' THEN 'REQUEST' WHEN 'RUNNING' THEN 'DISPATCH' WHEN 'ACCEPTED' THEN 'ACCEPT' WHEN 'FAILED' THEN 'FAIL' ELSE 'REVOKE' END)
  OR NEW.prior_version IS DISTINCT FROM (CASE WHEN f.version=1 THEN NULL ELSE (f.version-1)::smallint END) THEN RAISE EXCEPTION 'Feedback event target mismatch'; END IF;
 n=clock_timestamp();
 IF NOT isfinite(n) OR n<f.phase_started_at OR n<f.updated_at THEN RAISE EXCEPTION 'Feedback event clock moved backward'; END IF;
 IF NEW.idempotency_key IS NOT NULL THEN
  SELECT * INTO s FROM sessions WHERE owner_id=f.owner_id AND token_hash=NEW.operation_session_hash FOR UPDATE;
  n=clock_timestamp();
  IF NOT FOUND OR NOT isfinite(n) OR n<f.phase_started_at OR n<f.updated_at OR s.security_epoch IS DISTINCT FROM NEW.operation_security_epoch OR o.security_epoch IS DISTINCT FROM NEW.operation_security_epoch OR s.revoked_at IS NOT NULL
   OR n<s.created_at OR n<s.last_seen_at OR s.created_at<=n-interval '12 hours' OR s.last_seen_at<=n-interval '2 hours' THEN RAISE EXCEPTION 'Feedback owner operation authority expired'; END IF;
 END IF;
 IF NEW.operation='REVOKE' THEN
  IF NEW.reason_code='OWNER_REVOKE' THEN
   IF NEW.idempotency_key IS NULL OR f.phase_invalidation_scope<>'RUN_EXACT' THEN RAISE EXCEPTION 'Owner revocation requires exact authenticated operation'; END IF;
  ELSIF NEW.idempotency_key IS NOT NULL THEN RAISE EXCEPTION 'Safety closure is not an owner receipt';
  ELSIF NEW.reason_code IN ('TRANSPORT_UNKNOWN','RECOVERY_UNKNOWN','PREDISPATCH_FAILURE','RUN_CANCELLED') THEN
   -- A bounded abandonment decision. This does not assert transport, usage or termination facts.
   IF f.phase_invalidation_scope<>'RUN_EXACT' OR (NEW.reason_code='PREDISPATCH_FAILURE' AND f.version<>2) THEN RAISE EXCEPTION 'Runtime abandonment cannot widen scope'; END IF;
  ELSIF NEW.reason_code='OWNER_PAUSED' THEN
   IF NOT o.execution_paused OR f.phase_invalidation_scope NOT IN ('PAUSE_ACTIVE','RUN_EXACT') THEN RAISE EXCEPTION 'Pause reason is not observed'; END IF;
  ELSIF NEW.reason_code='SESSION_REVOKED' THEN
   SELECT * INTO s FROM sessions WHERE owner_id=f.owner_id AND token_hash=f.initiating_session_hash;
   IF s.revoked_at IS NULL AND s.security_epoch=f.security_epoch AND o.security_epoch=f.security_epoch AND s.created_at>n-interval '12 hours' AND s.last_seen_at>n-interval '2 hours' THEN RAISE EXCEPTION 'Session loss is not observed'; END IF;
  ELSIF NEW.reason_code IN ('SOURCE_INVALIDATED','CONTEXT_INVALIDATED') THEN
   IF o.context_epoch IS NOT DISTINCT FROM f.context_epoch AND o.history_epoch IS NOT DISTINCT FROM f.history_epoch AND o.version IS NOT DISTINCT FROM f.profile_version AND o.security_epoch IS NOT DISTINCT FROM f.security_epoch
    AND (f.project_id IS NULL OR EXISTS(SELECT 1 FROM projects WHERE owner_id=f.owner_id AND id=f.project_id AND version=f.project_version AND active))
    THEN RAISE EXCEPTION 'Source loss is not observed'; END IF;
  ELSE RAISE EXCEPTION 'Missing feedback safety reason'; END IF;
 END IF;
 SELECT count(*) INTO remaining FROM (SELECT 1 FROM inference_contexts WHERE owner_id=f.owner_id AND conversation_id=f.conversation_id AND state<>'INVALIDATED' LIMIT 1001) b;
 IF remaining>1000 THEN RAISE EXCEPTION USING ERRCODE='54000',MESSAGE='Feedback context scope limit'; END IF;
 NEW.invalidated_contexts=CASE WHEN NEW.operation='REVOKE' THEN f.phase_initial_invalidatable_contexts-remaining ELSE 0 END;
 IF NEW.operation='REVOKE' THEN
  SELECT count(*) INTO qualifying FROM inference_contexts x WHERE x.owner_id=f.owner_id AND x.conversation_id=f.conversation_id AND x.state<>'INVALIDATED' AND
   (f.phase_invalidation_scope='OWNER_ALL' OR (f.phase_invalidation_scope='SESSION_ACTIVE' AND x.session_hash=f.phase_invalidation_session_hash AND x.state IN ('CAPTURED','PREPARED','DISPATCHED'))
    OR (f.phase_invalidation_scope='PAUSE_ACTIVE' AND x.state IN ('CAPTURED','PREPARED','DISPATCHED')) OR (f.phase_invalidation_scope='RUN_EXACT' AND x.id=f.context_id));
  IF qualifying<>0 OR NEW.invalidated_contexts IS DISTINCT FROM f.phase_initial_scope_contexts OR NEW.invalidated_contexts<1 THEN RAISE EXCEPTION 'Feedback revocation scope is incomplete'; END IF;
 END IF;
 delta=CASE NEW.operation WHEN 'REQUEST' THEN 4 WHEN 'DISPATCH' THEN 5 WHEN 'REVOKE' THEN 3+NEW.invalidated_contexts ELSE 4 END;
 IF c.content_version+1-f.phase_prior_conversation_version<>delta OR delta>16+NEW.invalidated_contexts THEN RAISE EXCEPTION 'Feedback phase source increments mismatch'; END IF;
 NEW.phase_xid=pg_current_xact_id()::text; NEW.prior_conversation_version=f.phase_prior_conversation_version; NEW.resulting_conversation_version=c.content_version+1; NEW.committed_at=n;
 RETURN NEW;
END; $$;
CREATE TRIGGER study_feedback_event_guard BEFORE INSERT OR UPDATE OR DELETE ON study_feedback_events FOR EACH ROW EXECUTE FUNCTION protect_study_feedback_event_v18();
CREATE FUNCTION commit_study_feedback_event_v18() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 UPDATE conversations SET content_version=content_version+1 WHERE owner_id=NEW.owner_id AND id=NEW.conversation_id;
 UPDATE study_feedback_requests SET updated_at=NEW.committed_at,closed_at=CASE WHEN output_authority='ACTIVE' THEN NULL ELSE NEW.committed_at END WHERE owner_id=NEW.owner_id AND id=NEW.feedback_id;
 RETURN NULL;
END; $$;
CREATE TRIGGER study_feedback_event_commit AFTER INSERT ON study_feedback_events FOR EACH ROW EXECUTE FUNCTION commit_study_feedback_event_v18();

CREATE FUNCTION verify_study_feedback_phase_v18(p_owner uuid,p_feedback uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE f study_feedback_requests%ROWTYPE; c conversations%ROWTYPE; o owners%ROWTYPE; s sessions%ROWTYPE; v record; n timestamptz; remaining integer; rows_due integer; version_due bigint;
BEGIN
 PERFORM verify_study_feedback_history_v18(p_owner,p_feedback);
 SELECT * INTO f FROM study_feedback_requests WHERE owner_id=p_owner AND id=p_feedback;
 SELECT * INTO c FROM conversations WHERE owner_id=p_owner AND id=f.conversation_id;
 IF c.deleted_at IS NOT NULL THEN RETURN; END IF;
 IF f.output_authority='ACTIVE' OR f.phase_xid=pg_current_xact_id()::text THEN
  IF c.content_version IS DISTINCT FROM (SELECT resulting_conversation_version FROM study_feedback_events WHERE owner_id=p_owner AND feedback_id=p_feedback AND version=f.version) THEN RAISE EXCEPTION 'Source write follows sealed feedback phase'; END IF;
 END IF;
 IF f.output_authority='ACTIVE' OR (f.phase_xid=pg_current_xact_id()::text AND f.state IN ('ACCEPTED','FAILED')) THEN
  SELECT * INTO o FROM owners WHERE id=p_owner FOR UPDATE;
  SELECT * INTO s FROM sessions WHERE owner_id=p_owner AND token_hash=f.initiating_session_hash FOR UPDATE;
  n=clock_timestamp();
  IF NOT isfinite(n) OR n<f.updated_at OR n<s.created_at OR n<s.last_seen_at OR s.revoked_at IS NOT NULL OR s.security_epoch IS DISTINCT FROM f.security_epoch OR o.security_epoch IS DISTINCT FROM f.security_epoch
   OR s.created_at<=n-interval '12 hours' OR s.last_seen_at<=n-interval '2 hours' OR o.execution_paused OR o.context_epoch IS DISTINCT FROM f.context_epoch OR o.history_epoch IS DISTINCT FROM f.history_epoch OR o.version IS DISTINCT FROM f.profile_version
   OR c.project_id IS DISTINCT FROM f.project_id OR (f.project_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM projects WHERE owner_id=p_owner AND id=f.project_id AND version=f.project_version AND active))
   THEN RAISE EXCEPTION 'Feedback phase retains stale initiating authority'; END IF;
 END IF;
 IF f.phase_xid=pg_current_xact_id()::text THEN
  SELECT se.* INTO s FROM study_feedback_events ev JOIN sessions se ON se.owner_id=ev.owner_id AND se.token_hash=ev.operation_session_hash
   WHERE ev.owner_id=p_owner AND ev.feedback_id=p_feedback AND ev.version=f.version AND ev.idempotency_key IS NOT NULL FOR UPDATE OF se;
  IF FOUND THEN
   SELECT * INTO o FROM owners WHERE id=p_owner FOR UPDATE; n=clock_timestamp();
   IF NOT isfinite(n) OR n<f.updated_at OR n<s.created_at OR n<s.last_seen_at OR s.revoked_at IS NOT NULL OR s.created_at<=n-interval '12 hours' OR s.last_seen_at<=n-interval '2 hours'
    OR s.security_epoch IS DISTINCT FROM o.security_epoch OR s.security_epoch IS DISTINCT FROM (SELECT operation_security_epoch FROM study_feedback_events WHERE owner_id=p_owner AND feedback_id=p_feedback AND version=f.version)
    THEN RAISE EXCEPTION 'Feedback operation authority changed before closure'; END IF;
  END IF;
 END IF;
 SELECT * INTO v FROM conversation_source_inventory_v18(p_owner,f.conversation_id);
 SELECT count(*) INTO remaining FROM (SELECT 1 FROM inference_contexts WHERE owner_id=p_owner AND conversation_id=f.conversation_id AND state<>'INVALIDATED' LIMIT 1001) b;
 IF remaining>1000 THEN RAISE EXCEPTION USING ERRCODE='54000',MESSAGE='Feedback context scope limit'; END IF;
 rows_due=CASE f.state WHEN 'QUEUED' THEN 2 WHEN 'RUNNING' THEN 1 ELSE 0 END;
 version_due=CASE WHEN f.output_authority='ACTIVE' THEN 16*rows_due+remaining ELSE 0 END;
 IF v.scoped_rows+rows_due>40000 OR v.source_bytes+(CASE WHEN f.output_authority='ACTIVE' THEN 131072 ELSE 0 END)>16777216 OR c.content_version+version_due>9007199254740990
  THEN RAISE EXCEPTION USING ERRCODE='54000',MESSAGE='Feedback reserved source capacity exhausted'; END IF;
END; $$;

CREATE FUNCTION protect_study_feedback_barrier_v18() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE n jsonb; oldrow jsonb; own uuid; target uuid; f study_feedback_requests%ROWTYPE; e study_feedback_events%ROWTYPE; allowed text[]; ok boolean:=false; key uuid; oldstate text; newstate text; observed timestamptz:=clock_timestamp();
BEGIN
 n=CASE WHEN TG_OP='DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
 oldrow=CASE WHEN TG_OP='INSERT' THEN n ELSE to_jsonb(OLD) END;
 IF TG_OP='UPDATE' AND n=oldrow THEN RETURN NEW; END IF;
 IF TG_TABLE_NAME='conversations' THEN own=(n->>'owner_id')::uuid; target=(n->>'id')::uuid;
 ELSIF TG_TABLE_NAME IN ('runs','study_sessions','study_owner_answer_inputs','study_mutations') THEN own=(n->>'owner_id')::uuid; target=(n->>'conversation_id')::uuid;
 ELSIF TG_TABLE_NAME IN ('inference_context_history','inference_context_memories') THEN SELECT owner_id,conversation_id INTO own,target FROM inference_contexts WHERE id=(n->>'context_id')::uuid;
 ELSIF TG_TABLE_NAME IN ('approvals','authorizations','action_executions','mock_effects') THEN SELECT r.owner_id,r.conversation_id INTO own,target FROM runs r JOIN action_proposals a ON a.run_id=r.id WHERE a.id=(n->>'action_id')::uuid;
 ELSE SELECT owner_id,conversation_id INTO own,target FROM runs WHERE id=(n->>'run_id')::uuid; END IF;
 IF own IS NULL OR target IS NULL THEN RAISE EXCEPTION 'Unverified feedback source relationship'; END IF;
 PERFORM 1 FROM owners WHERE id=own FOR UPDATE;
 PERFORM 1 FROM conversations WHERE owner_id=own AND id=target FOR UPDATE;
 SELECT * INTO f FROM study_feedback_requests WHERE owner_id=own AND conversation_id=target AND (output_authority='ACTIVE' OR phase_xid=pg_current_xact_id()::text) ORDER BY created_at DESC,id LIMIT 1;
 IF NOT FOUND THEN
  SELECT * INTO f FROM study_feedback_requests WHERE owner_id=own AND conversation_id=target AND
   (run_id=CASE WHEN TG_TABLE_NAME='runs' THEN (n->>'id')::uuid ELSE (n->>'run_id')::uuid END OR context_id=(n->>'context_id')::uuid) LIMIT 1;
  IF FOUND AND NOT EXISTS(SELECT 1 FROM conversations WHERE owner_id=own AND id=target AND deleted_at IS NOT NULL) THEN
   IF TG_TABLE_NAME='inference_contexts' AND TG_OP='UPDATE' AND f.state IN ('ACCEPTED','FAILED')
    AND oldrow->>'state'='SETTLED' AND n->>'state'='INVALIDATED' AND oldrow->>'invalidated_at' IS NULL
    AND (n-ARRAY['state','invalidated_at']) IS NOT DISTINCT FROM (oldrow-ARRAY['state','invalidated_at'])
    AND COALESCE(isfinite((n->>'invalidated_at')::timestamptz) AND (n->>'invalidated_at')::timestamptz>=f.closed_at AND (n->>'invalidated_at')::timestamptz<=observed,false) THEN RETURN NEW; END IF;
   RAISE EXCEPTION 'Closed feedback descendant is immutable';
  END IF;
  IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
 END IF;
 IF f.phase_xid IS DISTINCT FROM pg_current_xact_id()::text THEN RAISE EXCEPTION 'Conversation has active feedback output'; END IF;
 SELECT * INTO e FROM study_feedback_events WHERE owner_id=f.owner_id AND feedback_id=f.id AND version=f.version;
 IF TG_TABLE_NAME='conversations' THEN
  IF TG_OP='UPDATE' AND (n-'content_version') IS NOT DISTINCT FROM (oldrow-'content_version')
   AND (e.feedback_id IS NULL OR ((oldrow->>'content_version')::bigint+1=e.resulting_conversation_version AND (n->>'content_version')::bigint=e.resulting_conversation_version)) THEN RETURN NEW; END IF;
  RAISE EXCEPTION 'Feedback phase forbids conversation source mutation';
 END IF;
 IF e.feedback_id IS NOT NULL THEN RAISE EXCEPTION 'Feedback phase source is already sealed'; END IF;
 key=(n->>'id')::uuid; oldstate=oldrow->>'state'; newstate=n->>'state';
 IF f.state='QUEUED' AND f.version=1 AND TG_OP='INSERT' THEN
  IF TG_TABLE_NAME='runs' THEN ok=key=f.run_id AND n->>'status'='QUEUED' AND (n->>'version')::bigint=1 AND n->>'result_ref' IS NULL AND n->>'context_history_eligible'='false' AND n->>'cancel_requested_at' IS NULL;
  ELSIF TG_TABLE_NAME='inference_reservations' THEN ok=key=f.reservation_id AND (n->>'run_id')::uuid=f.run_id AND n->>'status'='QUEUED' AND n->>'started_at' IS NULL AND n->>'cancel_requested_at' IS NULL AND n->>'purpose'='STUDY_FEEDBACK' AND (n->>'control_revision')::integer=1;
  ELSIF TG_TABLE_NAME='inference_contexts' THEN ok=key=f.context_id AND (n->>'run_id')::uuid=f.run_id AND newstate='CAPTURED' AND n->>'profile_version' IS NULL AND n->>'invalidated_at' IS NULL;
  END IF;
 ELSIF TG_OP='UPDATE' THEN
  IF TG_TABLE_NAME='runs' AND key=f.run_id THEN
   allowed=ARRAY['status','version','cancel_requested_at'];
   ok=(n-allowed) IS NOT DISTINCT FROM (oldrow-allowed) AND (n->>'version')::bigint=(oldrow->>'version')::bigint+1 AND (n->>'version')::bigint=f.version
    AND oldrow->>'status'=CASE WHEN f.version=2 THEN 'QUEUED' ELSE 'RUNNING' END
    AND n->>'status'=CASE f.state WHEN 'RUNNING' THEN 'RUNNING' WHEN 'ACCEPTED' THEN 'SUCCEEDED' WHEN 'FAILED' THEN 'FAILED' WHEN 'REVOKED' THEN CASE WHEN f.version=2 THEN 'CANCELLED' ELSE 'UNKNOWN' END ELSE '' END
    AND oldrow->>'cancel_requested_at' IS NULL AND ((f.state='REVOKED')=(n->>'cancel_requested_at' IS NOT NULL));
  ELSIF TG_TABLE_NAME='inference_reservations' AND key=f.reservation_id THEN
   allowed=CASE WHEN f.state='RUNNING' THEN ARRAY['status','started_at','body_hash','dispatch_context_id','dispatch_cache_policy'] ELSE ARRAY['status','completed_at','cancel_requested_at'] END;
   ok=(n-allowed) IS NOT DISTINCT FROM (oldrow-allowed) AND oldrow->>'status'=CASE WHEN f.version=2 THEN 'QUEUED' ELSE 'STARTED' END
    AND n->>'status'=CASE f.state WHEN 'RUNNING' THEN 'STARTED' WHEN 'ACCEPTED' THEN 'SUCCEEDED' WHEN 'FAILED' THEN 'FAILED' WHEN 'REVOKED' THEN CASE WHEN f.version=2 THEN 'CANCELLED' ELSE 'UNKNOWN' END ELSE '' END
    AND oldrow->>'cancel_requested_at' IS NULL AND ((f.state='REVOKED')=(n->>'cancel_requested_at' IS NOT NULL))
    AND (f.state='RUNNING' OR oldrow->>'completed_at' IS NULL AND n->>'completed_at' IS NOT NULL);
  ELSIF TG_TABLE_NAME='inference_contexts' THEN
   IF f.state='RUNNING' AND key=f.context_id THEN
    allowed=ARRAY['state','profile_version']; ok=(n-allowed) IS NOT DISTINCT FROM (oldrow-allowed)
     AND ((oldstate='CAPTURED' AND newstate='PREPARED' AND (n->>'profile_version')::bigint IS NOT DISTINCT FROM f.profile_version)
       OR (oldstate='PREPARED' AND newstate='DISPATCHED' AND n->'profile_version' IS NOT DISTINCT FROM oldrow->'profile_version'));
   ELSIF f.state IN ('ACCEPTED','FAILED') AND key=f.context_id THEN ok=(n-'state') IS NOT DISTINCT FROM (oldrow-'state') AND oldstate='DISPATCHED' AND newstate='SETTLED';
   ELSIF f.state='REVOKED' THEN
    allowed=ARRAY['state','invalidated_at']; ok=(n-allowed) IS NOT DISTINCT FROM (oldrow-allowed) AND oldstate IN ('CAPTURED','PREPARED','DISPATCHED','SETTLED') AND newstate='INVALIDATED'
     AND oldrow->>'invalidated_at' IS NULL AND n->>'invalidated_at' IS NOT NULL
     AND (f.phase_invalidation_scope='OWNER_ALL' OR f.phase_invalidation_scope='RUN_EXACT' AND key=f.context_id
       OR f.phase_invalidation_scope='SESSION_ACTIVE' AND oldrow->>'session_hash'=f.phase_invalidation_session_hash AND oldstate IN ('CAPTURED','PREPARED','DISPATCHED')
       OR f.phase_invalidation_scope='PAUSE_ACTIVE' AND oldstate IN ('CAPTURED','PREPARED','DISPATCHED'));
   END IF;
  END IF;
 END IF;
 IF TG_OP='INSERT' AND TG_TABLE_NAME IN ('runs','inference_reservations','inference_contexts') THEN
  ok=ok AND COALESCE(isfinite((n->>'created_at')::timestamptz) AND (n->>'created_at')::timestamptz>=transaction_timestamp() AND (n->>'created_at')::timestamptz<=observed,false);
 END IF;
 IF TG_OP='UPDATE' THEN
  IF TG_TABLE_NAME='inference_reservations' THEN
   IF f.state='RUNNING' THEN ok=ok AND COALESCE(isfinite((n->>'started_at')::timestamptz) AND (n->>'started_at')::timestamptz>=f.phase_started_at AND (n->>'started_at')::timestamptz<=observed,false);
   ELSE ok=ok AND COALESCE(isfinite((n->>'completed_at')::timestamptz) AND (n->>'completed_at')::timestamptz>=f.phase_started_at AND (n->>'completed_at')::timestamptz<=observed,false); END IF;
  END IF;
  IF f.state='REVOKED' AND TG_TABLE_NAME IN ('runs','inference_reservations') THEN ok=ok AND COALESCE(isfinite((n->>'cancel_requested_at')::timestamptz) AND (n->>'cancel_requested_at')::timestamptz>=f.phase_started_at AND (n->>'cancel_requested_at')::timestamptz<=observed,false); END IF;
  IF f.state='REVOKED' AND TG_TABLE_NAME='inference_contexts' THEN ok=ok AND COALESCE(isfinite((n->>'invalidated_at')::timestamptz) AND (n->>'invalidated_at')::timestamptz>=f.phase_started_at AND (n->>'invalidated_at')::timestamptz<=observed,false); END IF;
 END IF;
 IF NOT COALESCE(ok,false) THEN RAISE EXCEPTION 'Source mutation is outside exact feedback phase'; END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END; $$;
DO $$ DECLARE t text; BEGIN
 FOREACH t IN ARRAY ARRAY['conversations','runs','messages','action_proposals','approvals','authorizations','jobs','action_executions','mock_effects','inference_reservations','inference_contexts','inference_context_history','inference_context_memories','study_sessions','study_owner_answer_inputs','study_mutations'] LOOP
  EXECUTE format('CREATE TRIGGER a_study_feedback_barrier BEFORE INSERT OR UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION protect_study_feedback_barrier_v18()',t);
 END LOOP;
END; $$;

CREATE FUNCTION study_feedback_execution_quiescent_v18(p_owner uuid,p_reservation uuid) RETURNS boolean LANGUAGE plpgsql AS $$
DECLARE f study_feedback_requests%ROWTYPE; q inference_reservations%ROWTYPE; k inference_control_slots%ROWTYPE; x inference_contexts%ROWTYPE; r runs%ROWTYPE;
BEGIN
 SELECT * INTO f FROM study_feedback_requests WHERE owner_id=p_owner AND reservation_id=p_reservation;
 IF NOT FOUND OR f.output_authority='ACTIVE' THEN RETURN false; END IF;
 PERFORM verify_study_feedback_history_v18(p_owner,f.id);
 SELECT * INTO q FROM inference_reservations WHERE owner_id=p_owner AND id=p_reservation;
 SELECT * INTO k FROM inference_control_slots WHERE owner_id=p_owner AND reservation_id=p_reservation;
 SELECT * INTO x FROM inference_contexts WHERE owner_id=p_owner AND id=f.context_id;
 SELECT * INTO r FROM runs WHERE owner_id=p_owner AND id=f.run_id;
 IF k.terminal_kind IS NULL OR k.recorded_at IS NULL OR k.recorded_at<k.created_at OR k.recorded_at<k.bound_at OR k.recorded_at<k.decision_at THEN RETURN false; END IF;
 IF k.terminal_kind='UNSTARTED_CORE' THEN
  RETURN COALESCE(f.state='REVOKED' AND f.version=2 AND q.status='CANCELLED' AND r.status='CANCELLED' AND q.cancel_requested_at IS NOT NULL AND r.cancel_requested_at IS NOT NULL AND q.completed_at IS NOT NULL
   AND x.state='INVALIDATED' AND x.invalidated_at IS NOT NULL AND q.started_at IS NULL AND q.dispatch_context_id IS NULL AND q.dispatch_cache_policy IS NULL
   AND k.dispatch_commitment IS NULL AND k.bound_at IS NULL AND k.decision IN ('OWNER_CANCEL','SECURITY_REVOKED','CONTEXT_INVALIDATED','RECOVERY_UNKNOWN')
   AND k.generation_id IS NULL AND k.ledger_instance_id IS NULL AND k.terminal_ordinal IS NULL,false);
 END IF;
 IF q.started_at IS NULL OR q.dispatch_context_id IS DISTINCT FROM f.context_id OR q.dispatch_cache_policy IS DISTINCT FROM 'CONTEXT_V1_NO_RESPONSE_CACHE'
  OR k.dispatch_commitment IS NULL OR k.bound_at IS DISTINCT FROM q.started_at OR k.ledger_instance_id IS NULL OR k.terminal_ordinal IS NULL OR k.terminal_ordinal<1 THEN RETURN false; END IF;
 IF k.terminal_kind='UNSTARTED_FENCED' THEN RETURN COALESCE(k.generation_id IS NULL AND f.version=3 AND f.state IN ('REVOKED','FAILED'),false); END IF;
 RETURN COALESCE(k.terminal_kind IN ('COMPLETED','WORKER_STOPPED') AND k.generation_id IS NOT NULL AND f.version=3 AND f.state IN ('ACCEPTED','FAILED','REVOKED'),false);
EXCEPTION WHEN raise_exception OR check_violation OR foreign_key_violation THEN RETURN false;
END; $$;

CREATE FUNCTION revoke_study_feedback_v18(p_owner uuid,p_feedback uuid,p_scope text,p_scope_session_hash text,p_reason text,
 p_idempotency_key uuid DEFAULT NULL,p_operation_session_hash text DEFAULT NULL,p_operation_security_epoch bigint DEFAULT NULL,p_request_digest text DEFAULT NULL)
 RETURNS void LANGUAGE plpgsql AS $$
DECLARE f study_feedback_requests%ROWTYPE; n timestamptz; terminal text;
BEGIN
 PERFORM 1 FROM owners WHERE id=p_owner FOR UPDATE;
 SELECT * INTO f FROM study_feedback_requests WHERE owner_id=p_owner AND id=p_feedback;
 IF NOT FOUND THEN RAISE EXCEPTION 'Missing feedback revocation target'; END IF;
 PERFORM 1 FROM sessions WHERE owner_id=p_owner AND token_hash=f.initiating_session_hash FOR UPDATE;
 PERFORM 1 FROM conversations WHERE owner_id=p_owner AND id=f.conversation_id FOR UPDATE;
 SELECT * INTO f FROM study_feedback_requests WHERE owner_id=p_owner AND id=p_feedback FOR UPDATE;
 IF f.output_authority<>'ACTIVE' THEN RAISE EXCEPTION 'Feedback output is already terminal'; END IF;
 IF p_scope NOT IN ('OWNER_ALL','SESSION_ACTIVE','PAUSE_ACTIVE','RUN_EXACT') OR p_reason IS NULL THEN RAISE EXCEPTION 'Unsupported feedback closure'; END IF;
 IF (p_reason='OWNER_REVOKE') IS DISTINCT FROM (p_idempotency_key IS NOT NULL) THEN RAISE EXCEPTION 'Owner revocation needs its own operation receipt'; END IF;
 IF p_reason IN ('TRANSPORT_UNKNOWN','RECOVERY_UNKNOWN','PREDISPATCH_FAILURE','RUN_CANCELLED') AND p_scope<>'RUN_EXACT' THEN RAISE EXCEPTION 'Abandonment cannot widen context scope'; END IF;
 UPDATE study_feedback_requests SET version=version+1,state='REVOKED',output_authority='REVOKED',phase_invalidation_scope=p_scope,phase_invalidation_session_hash=p_scope_session_hash WHERE owner_id=p_owner AND id=p_feedback;
 -- Lock the complete bounded scope in stable order before its first invalidation.
 PERFORM id FROM inference_contexts WHERE owner_id=p_owner AND conversation_id=f.conversation_id ORDER BY id LIMIT 1001 FOR UPDATE;
 n=clock_timestamp();
 UPDATE inference_contexts x SET state='INVALIDATED',invalidated_at=n WHERE x.owner_id=p_owner AND x.conversation_id=f.conversation_id AND x.state<>'INVALIDATED'
  AND (p_scope='OWNER_ALL' OR (p_scope='SESSION_ACTIVE' AND x.session_hash=p_scope_session_hash AND x.state IN ('CAPTURED','PREPARED','DISPATCHED'))
   OR (p_scope='PAUSE_ACTIVE' AND x.state IN ('CAPTURED','PREPARED','DISPATCHED')) OR (p_scope='RUN_EXACT' AND x.id=f.context_id));
 terminal=CASE f.state WHEN 'QUEUED' THEN 'CANCELLED' ELSE 'UNKNOWN' END;
 UPDATE inference_reservations SET status=terminal,cancel_requested_at=n,completed_at=n WHERE owner_id=p_owner AND id=f.reservation_id AND cancel_requested_at IS NULL;
 UPDATE runs SET status=terminal,version=version+1,cancel_requested_at=n WHERE owner_id=p_owner AND id=f.run_id AND cancel_requested_at IS NULL;
 INSERT INTO study_feedback_events(owner_id,feedback_id,version,conversation_id,study_session_id,lesson_run_id,question_id,input_id,attempt_id,project_id,operation,prior_version,resulting_state,output_authority,
  idempotency_key,operation_session_hash,operation_security_epoch,request_digest,reason_code,prior_conversation_version,resulting_conversation_version,committed_at,phase_xid,invalidated_contexts)
 VALUES(p_owner,f.id,f.version+1,f.conversation_id,f.study_session_id,f.lesson_run_id,f.question_id,f.input_id,f.attempt_id,f.project_id,'REVOKE',f.version,'REVOKED','REVOKED',
  p_idempotency_key,p_operation_session_hash,p_operation_security_epoch,p_request_digest,p_reason,1,2,n,'0',0);
 PERFORM verify_study_feedback_phase_v18(p_owner,p_feedback);
END; $$;

CREATE FUNCTION close_study_feedback_for_project_v18() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE f record; n integer:=0;
BEGIN
 IF ROW(NEW.version,NEW.active) IS NOT DISTINCT FROM ROW(OLD.version,OLD.active) THEN RETURN NULL; END IF;
 PERFORM 1 FROM owners WHERE id=NEW.owner_id FOR UPDATE;
 FOR f IN SELECT id FROM study_feedback_requests WHERE owner_id=NEW.owner_id AND project_id=NEW.id AND output_authority='ACTIVE' AND (project_version IS DISTINCT FROM NEW.version OR NOT NEW.active) ORDER BY conversation_id,id LIMIT 1001 LOOP
  n=n+1; IF n>1000 THEN RAISE EXCEPTION USING ERRCODE='54000',MESSAGE='Feedback closure scope limit'; END IF;
  PERFORM revoke_study_feedback_v18(NEW.owner_id,f.id,'RUN_EXACT',NULL,'SOURCE_INVALIDATED');
 END LOOP;
 RETURN NULL;
END; $$;
CREATE TRIGGER study_feedback_project_close AFTER UPDATE OF version,active ON projects FOR EACH ROW EXECUTE FUNCTION close_study_feedback_for_project_v18();

-- Existing decision precedence is retained; the final branch is proof-free.
CREATE OR REPLACE FUNCTION inference_control_decision_reason(p_owner uuid,p_reservation uuid) RETURNS text LANGUAGE plpgsql AS $$
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
 IF v_res.purpose='STUDY_FEEDBACK' AND EXISTS(SELECT 1 FROM study_feedback_requests f JOIN inference_control_slots k ON k.owner_id=f.owner_id AND k.reservation_id=f.reservation_id WHERE f.owner_id=p_owner AND f.reservation_id=p_reservation AND f.state='FAILED' AND f.output_authority='CLOSED' AND f.result_ref IS NULL AND k.dispatch_commitment IS NOT NULL) THEN
  PERFORM verify_study_feedback_history_v18(p_owner,(SELECT id FROM study_feedback_requests WHERE owner_id=p_owner AND reservation_id=p_reservation));
  RETURN 'FEEDBACK_OUTPUT_CLOSED';
 END IF;
 RETURN NULL;
END; $$;


CREATE FUNCTION study_feedback_complete_v18() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE j jsonb; own uuid; target uuid; f record; n integer:=0;
 old_session_owner uuid; new_session_owner uuid; old_session_hash text; new_session_hash text;
BEGIN
 j=CASE WHEN TG_OP='DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
 IF TG_TABLE_NAME='sessions' THEN
  IF TG_OP<>'INSERT' THEN old_session_owner=OLD.owner_id; old_session_hash=OLD.token_hash; END IF;
  IF TG_OP<>'DELETE' THEN new_session_owner=NEW.owner_id; new_session_hash=NEW.token_hash; END IF;
  IF TG_OP='UPDATE' AND old_session_owner IS NOT NULL AND new_session_owner IS DISTINCT FROM old_session_owner
   THEN RAISE EXCEPTION 'Owned session identity cannot detach or change owner'; END IF;
  -- Anonymous CSRF/bootstrap/login sessions carry no feedback authority. Preserve their
  -- normal insert/refresh/revoke/delete lifecycle without weakening any owned gate.
  IF old_session_owner IS NULL AND new_session_owner IS NULL THEN
   IF EXISTS(SELECT 1 FROM study_feedback_requests WHERE initiating_session_hash IN (old_session_hash,new_session_hash))
    OR EXISTS(SELECT 1 FROM study_feedback_requests WHERE phase_invalidation_session_hash IN (old_session_hash,new_session_hash))
    OR EXISTS(SELECT 1 FROM study_feedback_events WHERE operation_session_hash IN (old_session_hash,new_session_hash))
    THEN RAISE EXCEPTION 'Anonymous session has a feedback authority reference'; END IF;
   RETURN NULL;
  END IF;
 END IF;
 IF TG_TABLE_NAME='owners' THEN own=(j->>'id')::uuid;
 ELSIF j ? 'owner_id' THEN own=(j->>'owner_id')::uuid;
 ELSE SELECT owner_id,conversation_id INTO own,target FROM inference_contexts WHERE id=(j->>'context_id')::uuid; END IF;
 IF TG_TABLE_NAME='conversations' THEN target=(j->>'id')::uuid;
 ELSIF j ? 'conversation_id' THEN target=(j->>'conversation_id')::uuid;
 ELSIF j ? 'run_id' THEN SELECT conversation_id INTO target FROM runs WHERE id=(j->>'run_id')::uuid;
 ELSIF j ? 'action_id' THEN SELECT r.conversation_id INTO target FROM runs r JOIN action_proposals a ON a.run_id=r.id WHERE a.id=(j->>'action_id')::uuid; END IF;
 IF own IS NULL THEN RAISE EXCEPTION 'Missing feedback closure owner'; END IF;
 FOR f IN SELECT id FROM study_feedback_requests WHERE owner_id=own AND ((target IS NOT NULL AND conversation_id=target) OR (target IS NULL AND (output_authority='ACTIVE' OR phase_xid=pg_current_xact_id()::text))) ORDER BY conversation_id,id LIMIT 2001 LOOP
  n=n+1; IF n>2000 THEN RAISE EXCEPTION USING ERRCODE='54000',MESSAGE='Feedback closure scope limit'; END IF;
  PERFORM verify_study_feedback_phase_v18(own,f.id);
 END LOOP;
 IF TG_TABLE_NAME='inference_reservations' AND j->>'purpose'='STUDY_FEEDBACK' AND NOT EXISTS(SELECT 1 FROM study_feedback_requests WHERE owner_id=own AND reservation_id=(j->>'id')::uuid)
  THEN RAISE EXCEPTION 'Feedback reservation has no aggregate'; END IF;
 RETURN NULL;
END; $$;
DO $$ DECLARE t text; BEGIN
 FOREACH t IN ARRAY ARRAY['study_feedback_requests','study_feedback_events','conversations','runs','messages','action_proposals','approvals','authorizations','jobs','action_executions','mock_effects','inference_reservations','inference_contexts','inference_context_history','inference_context_memories','study_sessions','study_owner_answer_inputs','study_mutations','inference_control_slots','owners','sessions','projects'] LOOP
  EXECUTE format('CREATE CONSTRAINT TRIGGER study_feedback_complete AFTER INSERT OR UPDATE OR DELETE ON %I DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION study_feedback_complete_v18()',t);
 END LOOP;
END; $$;

-- Only exact feedback-owned lineage gains a proof-based branch; legacy refusals remain.
CREATE OR REPLACE FUNCTION verify_conversation_deletion_closure() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE c conversations%ROWTYPE;
BEGIN
 SELECT * INTO c FROM conversations WHERE id=NEW.id;
 IF c.deleted_at IS NULL THEN RETURN NULL; END IF;
 IF NOT EXISTS(SELECT 1 FROM conversation_deletion_ledger l JOIN conversation_deletion_mutations m ON m.owner_id=l.owner_id AND m.deletion_id=l.id JOIN conversation_delete_preparations p ON p.owner_id=l.owner_id AND p.id=l.preparation_id JOIN conversation_delete_approvals a ON a.owner_id=l.owner_id AND a.id=l.approval_id WHERE l.owner_id=c.owner_id AND l.conversation_id=c.id AND l.tombstone_version=c.content_version AND l.deleted_at=c.deleted_at AND l.deleted_at>=p.prepared_at AND l.deleted_at<p.expires_at AND a.consumed_at=l.deleted_at AND p.consumed_at=l.deleted_at AND p.consumed_at IS NOT NULL AND p.payload_hash_ref IS NULL AND p.expected_digest_ref IS NULL AND a.preparation_id=p.id AND a.conversation_id=c.id AND m.preparation_id=p.id AND m.session_hash=a.session_hash AND m.security_epoch=a.security_epoch) THEN RAISE EXCEPTION 'Incomplete deletion identity'; END IF;
 IF EXISTS(SELECT 1 FROM runs r WHERE r.conversation_id=c.id AND (r.owner_id<>c.owner_id OR r.content_deleted_at IS NULL OR r.result_ref IS NOT NULL OR r.context_history_eligible OR (r.status NOT IN ('SUCCEEDED','PARTIAL_SUCCESS','FAILED','CANCELLED','EXPIRED','BLOCKED','WAITING_INPUT') AND NOT EXISTS(SELECT 1 FROM study_feedback_requests z WHERE z.owner_id=r.owner_id AND z.run_id=r.id AND study_feedback_execution_quiescent_v18(z.owner_id,z.reservation_id)))))
 OR EXISTS(SELECT 1 FROM messages m JOIN runs r ON r.id=m.run_id WHERE (m.conversation_id=c.id OR r.conversation_id=c.id) AND (m.owner_id<>c.owner_id OR m.conversation_id<>r.conversation_id OR m.content_deleted_at IS NULL OR m.content_ref IS NOT NULL OR m.body_hash IS NOT NULL))
 OR EXISTS(SELECT 1 FROM action_proposals a JOIN runs r ON r.id=a.run_id WHERE r.conversation_id=c.id AND (a.tool_id<>'calendar.event.create' OR a.tool_version<>'1' OR a.content_deleted_at IS NULL OR a.payload_ref IS NOT NULL OR a.payload_hash IS NOT NULL OR a.status NOT IN ('SUCCEEDED','FAILED','CANCELLED','EXPIRED','BLOCKED')))
 OR EXISTS(SELECT 1 FROM approvals p JOIN action_proposals a ON a.id=p.action_id JOIN runs r ON r.id=a.run_id WHERE r.conversation_id=c.id AND (p.content_deleted_at IS NULL OR p.payload_hash IS NOT NULL OR p.status NOT IN ('CONSUMED','REJECTED','EXPIRED','REVOKED')))
 OR EXISTS(SELECT 1 FROM authorizations z JOIN action_proposals a ON a.id=z.action_id JOIN runs r ON r.id=a.run_id WHERE r.conversation_id=c.id AND (z.source_deleted_at IS NULL OR z.revoked_at IS NULL OR z.payload_hash IS NOT NULL))
 OR EXISTS(SELECT 1 FROM mock_effects e JOIN action_proposals a ON a.id=e.action_id JOIN runs r ON r.id=a.run_id WHERE r.conversation_id=c.id AND (e.content_deleted_at IS NULL OR e.payload_hash IS NOT NULL))
 OR EXISTS(SELECT 1 FROM jobs j JOIN runs r ON r.id=j.run_id WHERE r.conversation_id=c.id AND (j.owner_id<>c.owner_id OR j.status NOT IN ('SUCCEEDED','FAILED','CANCELLED','EXPIRED','BLOCKED') OR j.lease_owner IS NOT NULL OR j.lease_until IS NOT NULL OR (j.kind='PLAN' AND (j.action_id IS NOT NULL OR j.logical_key<>'PLAN:'||j.run_id::text OR j.started_effect)) OR (j.kind='EXECUTE' AND (j.action_id IS NULL OR j.logical_key<>'EXECUTE:'||j.action_id::text OR NOT EXISTS(SELECT 1 FROM action_proposals a WHERE a.id=j.action_id AND a.owner_id=j.owner_id AND a.run_id=j.run_id))) OR (j.started_effect AND NOT EXISTS(SELECT 1 FROM action_executions e WHERE e.action_id=j.action_id AND e.owner_id=j.owner_id AND e.status IN ('SUCCEEDED','FAILED','CANCELLED','EXPIRED','BLOCKED') AND e.finished_at IS NOT NULL AND e.lease_generation<=j.lease_generation))))
 OR EXISTS(SELECT 1 FROM action_executions e JOIN action_proposals a ON a.id=e.action_id JOIN runs r ON r.id=a.run_id WHERE r.conversation_id=c.id AND (e.status NOT IN ('SUCCEEDED','FAILED','CANCELLED','EXPIRED','BLOCKED') OR e.finished_at IS NULL))
 OR EXISTS(SELECT 1 FROM inference_contexts x JOIN runs r ON r.id=x.run_id WHERE (x.conversation_id=c.id OR r.conversation_id=c.id) AND (x.conversation_id<>r.conversation_id OR x.state<>'INVALIDATED' OR x.invalidated_at IS NULL OR NOT EXISTS(SELECT 1 FROM inference_reservations q WHERE q.owner_id=x.owner_id AND q.run_id=x.run_id AND (q.dispatch_context_id=x.id OR (q.purpose='STUDY_FEEDBACK' AND study_feedback_execution_quiescent_v18(q.owner_id,q.id)))) OR EXISTS(SELECT 1 FROM inference_context_history h WHERE h.context_id=x.id) OR EXISTS(SELECT 1 FROM inference_context_memories b WHERE b.context_id=x.id)))
 OR EXISTS(SELECT 1 FROM inference_context_history h JOIN messages m ON m.id=h.message_id WHERE m.conversation_id=c.id)
 OR EXISTS(SELECT 1 FROM inference_reservations q JOIN runs r ON r.id=q.run_id WHERE r.conversation_id=c.id AND ((q.purpose='STUDY_FEEDBACK' AND NOT study_feedback_execution_quiescent_v18(q.owner_id,q.id)) OR (q.purpose<>'STUDY_FEEDBACK' AND (q.status NOT IN ('SUCCEEDED','FAILED','CANCELLED') OR q.started_at IS NULL OR q.dispatch_context_id IS NULL OR q.dispatch_cache_policy IS DISTINCT FROM 'CONTEXT_V1_NO_RESPONSE_CACHE' OR q.accounting_version<>1))))
 OR EXISTS(SELECT 1 FROM study_feedback_requests z WHERE z.conversation_id=c.id AND (z.owner_id IS DISTINCT FROM c.owner_id OR z.source_deleted_at IS DISTINCT FROM c.deleted_at OR z.source_binding_ref IS NOT NULL OR z.result_ref IS NOT NULL OR NOT study_feedback_execution_quiescent_v18(z.owner_id,z.reservation_id)))
 OR EXISTS(SELECT 1 FROM conversation_delete_preparations p WHERE p.owner_id=c.owner_id AND p.conversation_id=c.id AND (p.payload_hash_ref IS NOT NULL OR p.expected_digest_ref IS NOT NULL OR (p.consumed_at IS NULL AND p.revoked_at IS NULL)))
 THEN RAISE EXCEPTION 'Incomplete conversation source redaction'; END IF;
 RETURN NULL;
END; $$;

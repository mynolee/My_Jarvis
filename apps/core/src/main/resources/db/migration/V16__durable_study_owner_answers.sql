-- Durable direct owner answers: three source/receipt relations, no inference or task work.
CREATE TABLE study_sessions (
 id uuid PRIMARY KEY, owner_id uuid NOT NULL, conversation_id uuid NOT NULL, project_id uuid,
 lesson_run_id uuid NOT NULL, lesson_run_version bigint NOT NULL CHECK(lesson_run_version BETWEEN 1 AND 9007199254740991),
 question_1_id uuid NOT NULL, question_2_id uuid NOT NULL CHECK(question_2_id<>question_1_id),
 lesson_binding_ref text, version bigint NOT NULL CHECK(version BETWEEN 1 AND 4),
 lifecycle text NOT NULL CHECK(lifecycle IN ('OPEN','CLOSED_BY_OWNER')),
 created_at timestamptz NOT NULL CHECK(isfinite(created_at)), updated_at timestamptz NOT NULL CHECK(isfinite(updated_at) AND updated_at>=created_at), source_deleted_at timestamptz CHECK(source_deleted_at IS NULL OR isfinite(source_deleted_at)),
 UNIQUE(owner_id,id), UNIQUE(owner_id,lesson_run_id),
 CHECK((source_deleted_at IS NULL)=(lesson_binding_ref IS NOT NULL)),
 FOREIGN KEY(owner_id,conversation_id) REFERENCES conversations(owner_id,id),
 FOREIGN KEY(owner_id,project_id) REFERENCES projects(owner_id,id),
 FOREIGN KEY(owner_id,lesson_run_id) REFERENCES runs(owner_id,id)
);
CREATE TABLE study_owner_answer_inputs (
 id uuid PRIMARY KEY, attempt_id uuid NOT NULL, owner_id uuid NOT NULL, conversation_id uuid NOT NULL, project_id uuid,
 study_session_id uuid NOT NULL, question_id uuid NOT NULL,
 expected_study_version bigint NOT NULL CHECK(expected_study_version BETWEEN 1 AND 2),
 resulting_study_version bigint NOT NULL CHECK(resulting_study_version=expected_study_version+1),
 session_hash text NOT NULL REFERENCES sessions(token_hash), security_epoch bigint NOT NULL CHECK(security_epoch BETWEEN 1 AND 9007199254740991),
 observed_conversation_version bigint NOT NULL CHECK(observed_conversation_version BETWEEN 1 AND 9007199254740989),
 created_at timestamptz NOT NULL CHECK(isfinite(created_at)), text_ref text, source_binding_ref text, content_deleted_at timestamptz CHECK(content_deleted_at IS NULL OR isfinite(content_deleted_at)),
 UNIQUE(owner_id,id), UNIQUE(owner_id,attempt_id), UNIQUE(owner_id,study_session_id,question_id),
 CHECK((content_deleted_at IS NULL AND text_ref IS NOT NULL AND source_binding_ref IS NOT NULL)
    OR (content_deleted_at IS NOT NULL AND text_ref IS NULL AND source_binding_ref IS NULL)),
 FOREIGN KEY(owner_id,conversation_id) REFERENCES conversations(owner_id,id),
 FOREIGN KEY(owner_id,project_id) REFERENCES projects(owner_id,id),
 FOREIGN KEY(owner_id,study_session_id) REFERENCES study_sessions(owner_id,id)
);
CREATE TABLE study_mutations (
 owner_id uuid NOT NULL, idempotency_key uuid NOT NULL, operation text NOT NULL CHECK(operation IN ('START','ANSWER','CLOSE')),
 session_hash text NOT NULL REFERENCES sessions(token_hash), security_epoch bigint NOT NULL CHECK(security_epoch BETWEEN 1 AND 9007199254740991),
 request_digest text NOT NULL CHECK(request_digest ~ '^[0-9a-f]{64}$'),
 conversation_id uuid NOT NULL, project_id uuid, study_session_id uuid NOT NULL, lesson_run_id uuid NOT NULL,
 question_id uuid, attempt_id uuid, input_id uuid, prior_version bigint, resulting_version bigint NOT NULL CHECK(resulting_version BETWEEN 1 AND 4),
 prior_conversation_version bigint NOT NULL CHECK(prior_conversation_version BETWEEN 1 AND 9007199254740989),
 resulting_conversation_version bigint NOT NULL CHECK(resulting_conversation_version=prior_conversation_version+1),
 committed_at timestamptz NOT NULL CHECK(isfinite(committed_at)),
 PRIMARY KEY(owner_id,idempotency_key), UNIQUE(owner_id,study_session_id,resulting_version),
 UNIQUE(owner_id,input_id), UNIQUE(owner_id,attempt_id), UNIQUE(owner_id,study_session_id,question_id),
 CHECK((operation='START' AND prior_version IS NULL AND resulting_version=1 AND question_id IS NULL AND attempt_id IS NULL AND input_id IS NULL)
    OR (operation='ANSWER' AND prior_version IS NOT NULL AND prior_version BETWEEN 1 AND 2 AND resulting_version=prior_version+1 AND question_id IS NOT NULL AND attempt_id IS NOT NULL AND input_id IS NOT NULL)
    OR (operation='CLOSE' AND prior_version IS NOT NULL AND prior_version BETWEEN 1 AND 3 AND resulting_version=prior_version+1 AND question_id IS NULL AND attempt_id IS NULL AND input_id IS NULL)),
 FOREIGN KEY(owner_id,conversation_id) REFERENCES conversations(owner_id,id),
 FOREIGN KEY(owner_id,project_id) REFERENCES projects(owner_id,id),
 FOREIGN KEY(owner_id,study_session_id) REFERENCES study_sessions(owner_id,id),
 FOREIGN KEY(owner_id,lesson_run_id) REFERENCES runs(owner_id,id),
 FOREIGN KEY(owner_id,input_id) REFERENCES study_owner_answer_inputs(owner_id,id),
 FOREIGN KEY(owner_id,attempt_id) REFERENCES study_owner_answer_inputs(owner_id,attempt_id)
);
CREATE INDEX study_sessions_conversation ON study_sessions(conversation_id,id);
CREATE INDEX study_sessions_lesson ON study_sessions(lesson_run_id,id);
CREATE INDEX study_inputs_conversation ON study_owner_answer_inputs(conversation_id,id);
CREATE INDEX study_inputs_session ON study_owner_answer_inputs(study_session_id,id);
CREATE INDEX study_mutations_conversation ON study_mutations(conversation_id,idempotency_key);
CREATE INDEX study_mutations_session ON study_mutations(study_session_id,resulting_version);
CREATE INDEX study_mutations_lesson ON study_mutations(lesson_run_id,idempotency_key);
CREATE INDEX study_mutations_input ON study_mutations(input_id,idempotency_key);

-- All metadata probes precede all source-byte reads. This is the sole capacity formula
-- used by both study receipt insertion and explicit deletion. It is not physical DB size.
CREATE FUNCTION conversation_source_inventory_v16(p_owner uuid,p_conversation uuid)
 RETURNS TABLE(scoped_rows bigint,source_bytes bigint,study_sessions bigint,study_answers bigint)
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
 tables=ARRAY['runs','messages','action_proposals','approvals','authorizations','jobs','action_executions','mock_effects','inference_reservations','inference_contexts','inference_context_history','inference_context_memories','study_sessions','study_owner_answer_inputs','study_mutations'];
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
 'x.conversation_id=$1 OR x.study_session_id IN ('||study_scope||') OR x.lesson_run_id IN ('||run_scope||') OR x.input_id IN ('||input_scope||') OR x.attempt_id IN (SELECT attempt_id FROM study_owner_answer_inputs WHERE id IN ('||input_scope||'))'];
 byte_terms=ARRAY['octet_length(x.result_ref)','COALESCE(octet_length(x.content_ref),0)+COALESCE(octet_length(x.body_hash),0)',
 'COALESCE(octet_length(x.payload_ref),0)+COALESCE(octet_length(x.payload_hash),0)','octet_length(x.payload_hash)','octet_length(x.payload_hash)',
 NULL,NULL,'octet_length(x.payload_hash)',NULL,NULL,NULL,NULL,'octet_length(x.lesson_binding_ref)',
 'COALESCE(octet_length(x.text_ref),0)+COALESCE(octet_length(x.source_binding_ref),0)','octet_length(x.request_digest)'];
 caps=ARRAY[1000,2000,1000,40000,40000,40000,40000,40000,40000,1000,40000,40000,1000,2000,40000];
 scoped_rows=0; source_bytes=0; study_sessions=0; study_answers=0;
 FOR i IN 1..array_length(tables,1) LOOP
  remaining=LEAST(caps[i],40000-scoped_rows::integer);
  EXECUTE format('SELECT count(*) FROM (SELECT 1 FROM %I x WHERE %s LIMIT %s) bounded',tables[i],conditions[i],remaining+1) INTO n USING p_conversation;
  IF n>remaining THEN RAISE EXCEPTION USING ERRCODE='54000',MESSAGE='Conversation source scope limit'; END IF;
  scoped_rows=scoped_rows+n;
  IF tables[i]='study_sessions' THEN study_sessions=n; END IF;
  IF tables[i]='study_owner_answer_inputs' THEN study_answers=n; END IF;
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
CREATE FUNCTION protect_study_source_v16() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE n jsonb; o jsonb; c conversations%ROWTYPE; s study_sessions%ROWTYPE; allowed text[]; deleted timestamptz;
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Study identity is immutable'; END IF;
 n=to_jsonb(NEW); o=CASE WHEN TG_OP='INSERT' THEN n ELSE to_jsonb(OLD) END;
 PERFORM 1 FROM owners WHERE id=(n->>'owner_id')::uuid FOR UPDATE;
 SELECT * INTO c FROM conversations WHERE owner_id=(n->>'owner_id')::uuid AND id=(n->>'conversation_id')::uuid FOR UPDATE;
 IF NOT FOUND OR c.project_id IS DISTINCT FROM (n->>'project_id')::uuid THEN RAISE EXCEPTION USING ERRCODE='23503',MESSAGE='Unverified study conversation'; END IF;
 IF TG_OP='UPDATE' AND n=o THEN RETURN NEW; END IF;
 IF TG_TABLE_NAME='study_sessions' THEN
  allowed=ARRAY['version','lifecycle','updated_at','lesson_binding_ref','source_deleted_at'];
 ELSE allowed=ARRAY['text_ref','source_binding_ref','content_deleted_at']; END IF;
 IF TG_OP='UPDATE' AND (n-allowed) IS DISTINCT FROM (o-allowed) THEN RAISE EXCEPTION 'Study identity and source are immutable'; END IF;
 IF c.deleted_at IS NOT NULL THEN
  IF TG_OP='INSERT' THEN RAISE EXCEPTION 'Study source is deleted'; END IF;
  SELECT l.deleted_at INTO deleted FROM conversation_deletion_ledger l WHERE l.owner_id=c.owner_id AND l.conversation_id=c.id AND l.deleted_at=c.deleted_at;
  IF deleted IS NULL THEN RAISE EXCEPTION 'Study redaction requires matching ledger'; END IF;
  IF TG_TABLE_NAME='study_sessions' THEN
   IF (n-ARRAY['lesson_binding_ref','source_deleted_at']) IS DISTINCT FROM (o-ARRAY['lesson_binding_ref','source_deleted_at'])
    OR OLD.source_deleted_at IS NOT NULL OR OLD.lesson_binding_ref IS NULL OR NEW.lesson_binding_ref IS NOT NULL OR NEW.source_deleted_at IS DISTINCT FROM deleted
    THEN RAISE EXCEPTION 'Invalid study source redaction'; END IF;
  ELSE
   IF OLD.content_deleted_at IS NOT NULL OR OLD.text_ref IS NULL OR OLD.source_binding_ref IS NULL
    OR NEW.text_ref IS NOT NULL OR NEW.source_binding_ref IS NOT NULL OR NEW.content_deleted_at IS DISTINCT FROM deleted
    THEN RAISE EXCEPTION 'Invalid answer source redaction'; END IF;
  END IF;
  RETURN NEW;
 END IF;
 IF n->>'source_deleted_at' IS NOT NULL OR n->>'content_deleted_at' IS NOT NULL THEN RAISE EXCEPTION 'Study redaction requires tombstone'; END IF;
 IF TG_TABLE_NAME='study_sessions' THEN
  IF TG_OP='INSERT' THEN
   IF NEW.version<>1 OR NEW.lifecycle<>'OPEN' OR NEW.created_at<>NEW.updated_at OR NOT EXISTS(
    SELECT 1 FROM runs r JOIN inference_reservations q ON q.owner_id=r.owner_id AND q.run_id=r.id
    JOIN inference_contexts x ON x.owner_id=q.owner_id AND x.id=q.dispatch_context_id AND x.run_id=r.id
    WHERE r.owner_id=NEW.owner_id AND r.id=NEW.lesson_run_id AND r.conversation_id=NEW.conversation_id AND r.version=NEW.lesson_run_version
     AND r.status='SUCCEEDED' AND r.cancel_requested_at IS NULL AND r.result_ref IS NOT NULL AND r.content_deleted_at IS NULL AND r.context_history_eligible
     AND q.purpose='STUDY_LESSON' AND q.status='SUCCEEDED' AND q.cancel_requested_at IS NULL AND q.completed_at IS NOT NULL AND q.accounting_version=1 AND q.started_at IS NOT NULL
     AND q.dispatch_cache_policy='CONTEXT_V1_NO_RESPONSE_CACHE' AND x.conversation_id=NEW.conversation_id
     AND x.project_id IS NOT DISTINCT FROM NEW.project_id AND x.security_epoch=q.security_epoch AND x.state IN ('SETTLED','INVALIDATED')
     AND (x.state<>'INVALIDATED' OR x.invalidated_at IS NOT NULL)
     AND EXISTS(SELECT 1 FROM inference_usage_receipts u WHERE u.owner_id=q.owner_id AND u.run_id=q.run_id AND u.reservation_id=q.id AND u.request_id=q.request_id AND u.receipt_kind='MEASURED' AND u.response_kind='LESSON'))
    THEN RAISE EXCEPTION 'Unverified accepted study lesson'; END IF;
  ELSE
   IF (n-ARRAY['version','lifecycle','updated_at']) IS DISTINCT FROM (o-ARRAY['version','lifecycle','updated_at'])
    OR OLD.lifecycle<>'OPEN' OR NEW.version<>OLD.version+1 OR NEW.updated_at<OLD.updated_at
    THEN RAISE EXCEPTION 'Study transition requires the exact next receipt'; END IF;
  END IF;
 ELSE
  IF TG_OP='UPDATE' THEN RAISE EXCEPTION 'Answer source is immutable'; END IF;
  SELECT * INTO s FROM study_sessions WHERE owner_id=NEW.owner_id AND id=NEW.study_session_id FOR UPDATE;
  IF NOT FOUND OR s.conversation_id<>NEW.conversation_id OR s.project_id IS DISTINCT FROM NEW.project_id OR s.source_deleted_at IS NOT NULL
   OR s.lifecycle<>'OPEN' OR s.version<>NEW.expected_study_version OR NEW.question_id NOT IN (s.question_1_id,s.question_2_id)
   OR NEW.observed_conversation_version<>c.content_version OR NOT EXISTS(SELECT 1 FROM sessions a WHERE a.token_hash=NEW.session_hash AND a.owner_id=NEW.owner_id AND a.security_epoch=NEW.security_epoch)
   THEN RAISE EXCEPTION 'Unverified answer target'; END IF;
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER study_source_guard BEFORE INSERT OR UPDATE OR DELETE ON study_sessions FOR EACH ROW EXECUTE FUNCTION protect_study_source_v16();
CREATE TRIGGER study_source_guard BEFORE INSERT OR UPDATE OR DELETE ON study_owner_answer_inputs FOR EACH ROW EXECUTE FUNCTION protect_study_source_v16();

CREATE FUNCTION protect_study_receipt_v16() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'Study receipt is immutable'; END IF;
 IF NOT EXISTS(SELECT 1 FROM sessions a WHERE a.token_hash=NEW.session_hash AND a.owner_id=NEW.owner_id AND a.security_epoch=NEW.security_epoch)
  OR NOT EXISTS(SELECT 1 FROM study_sessions s WHERE s.owner_id=NEW.owner_id AND s.id=NEW.study_session_id AND s.conversation_id=NEW.conversation_id
   AND s.project_id IS NOT DISTINCT FROM NEW.project_id AND s.lesson_run_id=NEW.lesson_run_id AND s.source_deleted_at IS NULL)
  THEN RAISE EXCEPTION 'Unverified study receipt'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER study_receipt_guard BEFORE INSERT OR UPDATE OR DELETE ON study_mutations FOR EACH ROW EXECUTE FUNCTION protect_study_receipt_v16();
CREATE FUNCTION commit_study_receipt_v16() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE c conversations%ROWTYPE; reviewed_schema text;
BEGIN
 SELECT version INTO reviewed_schema FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1;
 IF reviewed_schema IS DISTINCT FROM '16' THEN RAISE EXCEPTION 'Unreviewed study schema'; END IF;
 PERFORM 1 FROM owners WHERE id=NEW.owner_id FOR UPDATE;
 SELECT * INTO c FROM conversations WHERE owner_id=NEW.owner_id AND id=NEW.conversation_id FOR UPDATE;
 IF NOT FOUND OR c.deleted_at IS NOT NULL OR c.project_id IS DISTINCT FROM NEW.project_id
  OR c.content_version>9007199254740989 OR NEW.prior_conversation_version<>c.content_version OR NEW.resulting_conversation_version<>c.content_version+1
  THEN RAISE EXCEPTION 'Study receipt conversation precondition changed'; END IF;
 PERFORM * FROM conversation_source_inventory_v16(NEW.owner_id,NEW.conversation_id);
 UPDATE conversations SET content_version=content_version+1 WHERE owner_id=NEW.owner_id AND id=NEW.conversation_id;
 RETURN NULL;
END; $$;
CREATE TRIGGER study_receipt_commit AFTER INSERT ON study_mutations FOR EACH ROW EXECUTE FUNCTION commit_study_receipt_v16();

-- Whole-session historical closure, never a prefix and never compared to today's
-- source observation as though an old receipt represented the latest operation.
CREATE FUNCTION verify_study_history_v16(p_owner uuid,p_study uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE s study_sessions%ROWTYPE; c conversations%ROWTYPE; m study_mutations%ROWTYPE; a study_owner_answer_inputs%ROWTYPE;
 n integer; answers integer:=0; closed boolean:=false; previous_version bigint:=0; previous_c bigint:=0; previous_at timestamptz;
BEGIN
 SELECT * INTO s FROM study_sessions WHERE owner_id=p_owner AND id=p_study;
 IF NOT FOUND THEN RAISE EXCEPTION 'Missing study history'; END IF;
 SELECT * INTO c FROM conversations WHERE owner_id=s.owner_id AND id=s.conversation_id;
 IF NOT FOUND OR c.project_id IS DISTINCT FROM s.project_id THEN RAISE EXCEPTION 'Unverified study history scope'; END IF;
 SELECT count(*) INTO n FROM (SELECT 1 FROM study_mutations WHERE study_session_id=p_study ORDER BY resulting_version LIMIT 5) bounded;
 IF n<1 OR n>4 OR n<>s.version THEN RAISE EXCEPTION 'Incomplete study transition chain'; END IF;
 FOR m IN SELECT * FROM study_mutations WHERE study_session_id=p_study ORDER BY resulting_version LIMIT 5 LOOP
  IF m.owner_id<>s.owner_id OR m.conversation_id<>s.conversation_id OR m.project_id IS DISTINCT FROM s.project_id OR m.lesson_run_id<>s.lesson_run_id
   OR m.resulting_version<>previous_version+1 OR m.prior_conversation_version<previous_c
   OR (previous_at IS NOT NULL AND m.committed_at<previous_at) OR closed
   THEN RAISE EXCEPTION 'Invalid study historical transition'; END IF;
  IF previous_version=0 THEN
   IF m.operation<>'START' OR m.prior_version IS NOT NULL OR m.committed_at<>s.created_at THEN RAISE EXCEPTION 'Missing exact study start'; END IF;
  ELSE
   IF m.operation='START' OR m.prior_version IS DISTINCT FROM previous_version THEN RAISE EXCEPTION 'Invalid study predecessor'; END IF;
   IF m.operation='ANSWER' THEN
    answers=answers+1;
    SELECT * INTO a FROM study_owner_answer_inputs WHERE id=m.input_id;
    IF NOT FOUND OR a.owner_id<>m.owner_id OR a.study_session_id<>m.study_session_id OR a.conversation_id<>m.conversation_id
     OR a.project_id IS DISTINCT FROM m.project_id OR a.question_id<>m.question_id OR a.attempt_id<>m.attempt_id
     OR a.expected_study_version IS DISTINCT FROM m.prior_version OR a.resulting_study_version<>m.resulting_version
     OR a.session_hash<>m.session_hash OR a.security_epoch<>m.security_epoch OR a.observed_conversation_version<>m.prior_conversation_version
     OR a.created_at>m.committed_at OR a.question_id NOT IN (s.question_1_id,s.question_2_id)
     THEN RAISE EXCEPTION 'Study answer and receipt mismatch'; END IF;
   ELSE closed=true; END IF;
  END IF;
  previous_version=m.resulting_version; previous_c=m.resulting_conversation_version; previous_at=m.committed_at;
 END LOOP;
 SELECT count(*) INTO n FROM (SELECT 1 FROM study_owner_answer_inputs WHERE study_session_id=p_study LIMIT 3) bounded;
 IF answers>2 OR n<>answers OR s.updated_at<>previous_at OR c.content_version<previous_c
  OR s.lifecycle<>(CASE WHEN closed THEN 'CLOSED_BY_OWNER' ELSE 'OPEN' END)
  OR EXISTS(SELECT 1 FROM study_owner_answer_inputs a WHERE a.study_session_id=p_study AND NOT EXISTS(
   SELECT 1 FROM study_mutations m WHERE m.owner_id=a.owner_id AND m.study_session_id=a.study_session_id AND m.operation='ANSWER' AND m.input_id=a.id))
  THEN RAISE EXCEPTION 'Incomplete study input closure'; END IF;
 IF c.deleted_at IS NULL THEN
  IF s.source_deleted_at IS NOT NULL OR s.lesson_binding_ref IS NULL OR EXISTS(SELECT 1 FROM study_owner_answer_inputs a WHERE a.study_session_id=s.id AND (a.content_deleted_at IS NOT NULL OR a.text_ref IS NULL OR a.source_binding_ref IS NULL))
   THEN RAISE EXCEPTION 'Unexpected study source redaction'; END IF;
 ELSE
  IF s.source_deleted_at IS DISTINCT FROM c.deleted_at OR s.lesson_binding_ref IS NOT NULL
   OR NOT EXISTS(SELECT 1 FROM conversation_deletion_ledger l WHERE l.owner_id=s.owner_id AND l.conversation_id=s.conversation_id AND l.deleted_at=c.deleted_at)
   OR EXISTS(SELECT 1 FROM study_owner_answer_inputs a WHERE a.study_session_id=s.id AND (a.content_deleted_at IS DISTINCT FROM c.deleted_at OR a.text_ref IS NOT NULL OR a.source_binding_ref IS NOT NULL))
   THEN RAISE EXCEPTION 'Incomplete study source redaction'; END IF;
 END IF;
END; $$;
CREATE FUNCTION study_history_constraint_v16() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_TABLE_NAME='study_sessions' THEN PERFORM verify_study_history_v16(NEW.owner_id,NEW.id);
 ELSE PERFORM verify_study_history_v16(NEW.owner_id,NEW.study_session_id); END IF;
 RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER study_history_complete AFTER INSERT OR UPDATE ON study_sessions DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION study_history_constraint_v16();
CREATE CONSTRAINT TRIGGER study_history_complete AFTER INSERT OR UPDATE ON study_owner_answer_inputs DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION study_history_constraint_v16();
CREATE CONSTRAINT TRIGGER study_history_complete AFTER INSERT ON study_mutations DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION study_history_constraint_v16();
CREATE FUNCTION verify_study_deletion_v16() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE c conversations%ROWTYPE; s record;
BEGIN
 SELECT * INTO c FROM conversations WHERE id=NEW.id;
 IF c.deleted_at IS NULL THEN RETURN NULL; END IF;
 PERFORM * FROM conversation_source_inventory_v16(c.owner_id,c.id);
 IF EXISTS(SELECT 1 FROM study_sessions x WHERE (x.conversation_id=c.id OR x.lesson_run_id IN (SELECT id FROM runs WHERE conversation_id=c.id))
  AND (x.owner_id<>c.owner_id OR x.conversation_id<>c.id OR x.project_id IS DISTINCT FROM c.project_id OR x.source_deleted_at IS DISTINCT FROM c.deleted_at OR x.lesson_binding_ref IS NOT NULL))
  OR EXISTS(SELECT 1 FROM study_owner_answer_inputs a LEFT JOIN study_sessions x ON x.id=a.study_session_id
   WHERE (a.conversation_id=c.id OR x.conversation_id=c.id) AND (a.owner_id<>c.owner_id OR a.conversation_id<>c.id OR x.conversation_id<>c.id
    OR a.project_id IS DISTINCT FROM c.project_id OR a.content_deleted_at IS DISTINCT FROM c.deleted_at OR a.text_ref IS NOT NULL OR a.source_binding_ref IS NOT NULL))
  OR EXISTS(SELECT 1 FROM study_mutations m LEFT JOIN study_sessions x ON x.id=m.study_session_id
   WHERE (m.conversation_id=c.id OR x.conversation_id=c.id OR m.lesson_run_id IN (SELECT id FROM runs WHERE conversation_id=c.id))
   AND (m.owner_id<>c.owner_id OR m.conversation_id<>c.id OR x.conversation_id<>c.id OR m.project_id IS DISTINCT FROM c.project_id))
  THEN RAISE EXCEPTION 'Incomplete durable study deletion graph'; END IF;
 FOR s IN SELECT owner_id,id FROM study_sessions WHERE conversation_id=c.id ORDER BY id LIMIT 1001 LOOP
  PERFORM verify_study_history_v16(s.owner_id,s.id);
 END LOOP;
 RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER conversation_study_deletion_complete AFTER UPDATE ON conversations DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_study_deletion_v16();

-- Adding default-zero counts preserves all historical consumed field values.
ALTER TABLE conversation_delete_preparations ADD COLUMN study_sessions_count integer NOT NULL DEFAULT 0 CHECK(study_sessions_count BETWEEN 0 AND 1000);
ALTER TABLE conversation_delete_preparations ADD COLUMN study_answers_count integer NOT NULL DEFAULT 0 CHECK(study_answers_count BETWEEN 0 AND 2000);
-- No unconsumed r4 authority may delete this expanded source graph.
UPDATE conversation_delete_preparations SET revoked_at=COALESCE(revoked_at,clock_timestamp()),
 expected_digest_ref=NULL,payload_hash_ref=NULL WHERE consumed_at IS NULL;

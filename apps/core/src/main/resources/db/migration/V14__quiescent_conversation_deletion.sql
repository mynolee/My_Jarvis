-- Target-scoped logical source redaction. No existing inference history is upgraded.
ALTER TABLE conversations ALTER COLUMN title DROP NOT NULL;
ALTER TABLE conversations ADD COLUMN content_version bigint NOT NULL DEFAULT 1 CHECK(content_version BETWEEN 1 AND 9007199254740991);
ALTER TABLE conversations ADD COLUMN deleted_at timestamptz;
ALTER TABLE conversations ADD CONSTRAINT conversation_content_state CHECK((deleted_at IS NULL AND title IS NOT NULL) OR (deleted_at IS NOT NULL AND title IS NULL));
ALTER TABLE messages ALTER COLUMN content_ref DROP NOT NULL;
ALTER TABLE messages ALTER COLUMN body_hash DROP NOT NULL;
ALTER TABLE messages ADD COLUMN content_deleted_at timestamptz;
ALTER TABLE messages ADD CONSTRAINT message_content_state CHECK((content_deleted_at IS NULL AND content_ref IS NOT NULL AND body_hash IS NOT NULL) OR (content_deleted_at IS NOT NULL AND content_ref IS NULL AND body_hash IS NULL));
ALTER TABLE runs ADD COLUMN content_deleted_at timestamptz;
ALTER TABLE runs ADD CONSTRAINT run_content_state CHECK(content_deleted_at IS NULL OR (result_ref IS NULL AND NOT context_history_eligible));
ALTER TABLE action_proposals ALTER COLUMN payload_ref DROP NOT NULL;
ALTER TABLE action_proposals ALTER COLUMN payload_hash DROP NOT NULL;
ALTER TABLE action_proposals ADD COLUMN content_deleted_at timestamptz;
ALTER TABLE action_proposals ADD CONSTRAINT action_content_state CHECK((content_deleted_at IS NULL AND payload_ref IS NOT NULL AND payload_hash IS NOT NULL) OR (content_deleted_at IS NOT NULL AND payload_ref IS NULL AND payload_hash IS NULL));
ALTER TABLE approvals ALTER COLUMN payload_hash DROP NOT NULL;
ALTER TABLE approvals ADD COLUMN content_deleted_at timestamptz;
ALTER TABLE approvals ADD CONSTRAINT approval_content_state CHECK((content_deleted_at IS NULL)=(payload_hash IS NOT NULL));
ALTER TABLE mock_effects ALTER COLUMN payload_hash DROP NOT NULL;
ALTER TABLE mock_effects ADD COLUMN content_deleted_at timestamptz;
ALTER TABLE mock_effects ADD CONSTRAINT mock_effect_content_state CHECK((content_deleted_at IS NULL)=(payload_hash IS NOT NULL));
ALTER TABLE authorizations ALTER COLUMN payload_hash DROP NOT NULL;
ALTER TABLE authorizations ADD COLUMN source_deleted_at timestamptz;
ALTER TABLE authorizations ADD COLUMN revoked_at timestamptz;
ALTER TABLE authorizations ADD CONSTRAINT authorization_content_state CHECK((source_deleted_at IS NULL AND payload_hash IS NOT NULL) OR (source_deleted_at IS NOT NULL AND revoked_at IS NOT NULL AND payload_hash IS NULL));

CREATE TABLE conversation_delete_preparations (
 id uuid PRIMARY KEY, owner_id uuid NOT NULL, conversation_id uuid NOT NULL,
 session_hash text NOT NULL REFERENCES sessions(token_hash), security_epoch bigint NOT NULL,
 issuance_key uuid NOT NULL, issuance_digest text NOT NULL CHECK(issuance_digest ~ '^[0-9a-f]{64}$'),
 expected_version bigint NOT NULL CHECK(expected_version BETWEEN 1 AND 9007199254740990),
 expected_digest_ref text, payload_hash_ref text,
 messages_count integer NOT NULL CHECK(messages_count BETWEEN 0 AND 2000),
 runs_count integer NOT NULL CHECK(runs_count BETWEEN 0 AND 1000),
 run_results_count integer NOT NULL CHECK(run_results_count BETWEEN 0 AND runs_count),
 action_payloads_count integer NOT NULL CHECK(action_payloads_count BETWEEN 0 AND 1000),
 inference_contexts_count integer NOT NULL CHECK(inference_contexts_count BETWEEN 0 AND runs_count),
 scope_policy text NOT NULL DEFAULT 'CONVERSATION_SOURCE_V1' CHECK(scope_policy='CONVERSATION_SOURCE_V1'),
 prepared_at timestamptz NOT NULL, expires_at timestamptz NOT NULL,
 consumed_at timestamptz, revoked_at timestamptz,
 UNIQUE(owner_id,id), UNIQUE(owner_id,id,conversation_id), UNIQUE(owner_id,issuance_key),
 CHECK(expires_at=prepared_at+interval '300 seconds'),
 CHECK((consumed_at IS NULL AND revoked_at IS NULL AND expected_digest_ref IS NOT NULL AND payload_hash_ref IS NOT NULL) OR ((consumed_at IS NOT NULL OR revoked_at IS NOT NULL) AND expected_digest_ref IS NULL AND payload_hash_ref IS NULL)),
 FOREIGN KEY(owner_id,conversation_id) REFERENCES conversations(owner_id,id)
);
CREATE INDEX conversation_delete_preparations_target ON conversation_delete_preparations(owner_id,conversation_id);
CREATE INDEX conversation_delete_preparations_unconsumed ON conversation_delete_preparations(owner_id,prepared_at) WHERE consumed_at IS NULL;
CREATE TABLE conversation_delete_approvals (
 id uuid PRIMARY KEY, owner_id uuid NOT NULL, preparation_id uuid NOT NULL UNIQUE, conversation_id uuid NOT NULL,
 session_hash text NOT NULL REFERENCES sessions(token_hash), security_epoch bigint NOT NULL,
 max_executions integer NOT NULL DEFAULT 1 CHECK(max_executions=1),
 consumed_at timestamptz NOT NULL, UNIQUE(owner_id,id),
 FOREIGN KEY(owner_id,preparation_id,conversation_id) REFERENCES conversation_delete_preparations(owner_id,id,conversation_id)
);
CREATE TABLE conversation_deletion_ledger (
 id uuid PRIMARY KEY, owner_id uuid NOT NULL, conversation_id uuid NOT NULL, preparation_id uuid NOT NULL UNIQUE,
 approval_id uuid NOT NULL UNIQUE, deleted_version bigint NOT NULL CHECK(deleted_version BETWEEN 1 AND 9007199254740990),
 tombstone_version bigint NOT NULL CHECK(tombstone_version=deleted_version+1),
 scope_policy text NOT NULL DEFAULT 'CONVERSATION_SOURCE_V1' CHECK(scope_policy='CONVERSATION_SOURCE_V1'),
 deleted_at timestamptz NOT NULL, UNIQUE(owner_id,conversation_id), UNIQUE(owner_id,id),
 FOREIGN KEY(owner_id,conversation_id) REFERENCES conversations(owner_id,id),
 FOREIGN KEY(owner_id,preparation_id,conversation_id) REFERENCES conversation_delete_preparations(owner_id,id,conversation_id),
 FOREIGN KEY(owner_id,approval_id) REFERENCES conversation_delete_approvals(owner_id,id)
);
CREATE TABLE conversation_deletion_mutations (
 owner_id uuid NOT NULL, idempotency_key uuid NOT NULL, session_hash text NOT NULL REFERENCES sessions(token_hash),
 security_epoch bigint NOT NULL, request_digest text NOT NULL CHECK(request_digest ~ '^[0-9a-f]{64}$'),
 deletion_id uuid NOT NULL UNIQUE, preparation_id uuid NOT NULL UNIQUE,
 PRIMARY KEY(owner_id,idempotency_key), FOREIGN KEY(owner_id,deletion_id) REFERENCES conversation_deletion_ledger(owner_id,id),
 FOREIGN KEY(owner_id,preparation_id) REFERENCES conversation_delete_preparations(owner_id,id)
);
CREATE FUNCTION conversation_deletion_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Deletion identity is immutable'; END; $$;
CREATE TRIGGER conversation_deletion_ledger_immutable BEFORE UPDATE OR DELETE ON conversation_deletion_ledger FOR EACH ROW EXECUTE FUNCTION conversation_deletion_immutable();
CREATE TRIGGER conversation_deletion_mutations_immutable BEFORE UPDATE OR DELETE ON conversation_deletion_mutations FOR EACH ROW EXECUTE FUNCTION conversation_deletion_immutable();
CREATE TRIGGER conversation_delete_approvals_immutable BEFORE UPDATE OR DELETE ON conversation_delete_approvals FOR EACH ROW EXECUTE FUNCTION conversation_deletion_immutable();

ALTER TABLE inference_contexts ADD CONSTRAINT inference_context_dispatch_identity UNIQUE(owner_id,run_id,id);
ALTER TABLE inference_reservations ADD COLUMN dispatch_context_id uuid;
ALTER TABLE inference_reservations ADD COLUMN dispatch_cache_policy text;
ALTER TABLE inference_reservations ADD CONSTRAINT inference_dispatch_policy CHECK((dispatch_context_id IS NULL AND dispatch_cache_policy IS NULL) OR (dispatch_context_id IS NOT NULL AND dispatch_cache_policy IS NOT DISTINCT FROM 'CONTEXT_V1_NO_RESPONSE_CACHE'));
ALTER TABLE inference_reservations ADD CONSTRAINT inference_dispatch_context_fk FOREIGN KEY(owner_id,run_id,dispatch_context_id) REFERENCES inference_contexts(owner_id,run_id,id);
-- V12 identity and receipt triggers are deliberately unchanged.
CREATE FUNCTION protect_inference_dispatch_provenance() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  IF NEW.dispatch_context_id IS NOT NULL OR NEW.dispatch_cache_policy IS NOT NULL THEN RAISE EXCEPTION 'Dispatch marker cannot be inserted'; END IF;
 ELSE
  IF OLD.dispatch_context_id IS NOT NULL AND ROW(NEW.dispatch_context_id,NEW.dispatch_cache_policy) IS DISTINCT FROM ROW(OLD.dispatch_context_id,OLD.dispatch_cache_policy) THEN RAISE EXCEPTION 'Dispatch marker is immutable'; END IF;
  IF OLD.dispatch_context_id IS NULL AND NEW.dispatch_context_id IS NOT NULL THEN
   IF NOT COALESCE((OLD.status='QUEUED' AND OLD.started_at IS NULL AND NEW.status='STARTED' AND NEW.started_at IS NOT NULL AND NEW.accounting_version=1 AND NEW.dispatch_cache_policy='CONTEXT_V1_NO_RESPONSE_CACHE' AND NEW.body_hash ~ '^[0-9a-f]{64}$'),false) OR NOT EXISTS (
    SELECT 1 FROM inference_contexts c JOIN runs r ON r.owner_id=c.owner_id AND r.id=c.run_id JOIN conversations v ON v.owner_id=r.owner_id AND v.id=r.conversation_id
    WHERE c.id=NEW.dispatch_context_id AND c.owner_id=NEW.owner_id AND c.run_id=NEW.run_id AND c.conversation_id=r.conversation_id AND c.security_epoch=NEW.security_epoch AND c.state='DISPATCHED' AND v.deleted_at IS NULL AND r.content_deleted_at IS NULL
   ) THEN RAISE EXCEPTION 'Unsupported dispatch provenance transition'; END IF;
  END IF;
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER inference_dispatch_provenance_guard BEFORE INSERT OR UPDATE ON inference_reservations FOR EACH ROW EXECUTE FUNCTION protect_inference_dispatch_provenance();

CREATE INDEX messages_deletion_run ON messages(run_id,id);
CREATE INDEX action_proposals_deletion_run ON action_proposals(run_id,id);
CREATE INDEX approvals_deletion_action ON approvals(action_id,id);
CREATE INDEX jobs_deletion_run ON jobs(run_id,id);
CREATE INDEX jobs_deletion_action ON jobs(action_id,id);
CREATE INDEX inference_contexts_deletion_conversation ON inference_contexts(conversation_id,id);
CREATE INDEX inference_context_history_deletion_message ON inference_context_history(message_id,context_id);
CREATE INDEX inference_context_history_deletion_run ON inference_context_history(run_id,context_id);

CREATE FUNCTION protect_conversation_tombstone() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 PERFORM 1 FROM owners WHERE id=OLD.owner_id FOR UPDATE;
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Conversation identity cannot be deleted'; END IF;
 IF ROW(NEW.id,NEW.owner_id,NEW.created_at) IS DISTINCT FROM ROW(OLD.id,OLD.owner_id,OLD.created_at) THEN RAISE EXCEPTION 'Conversation identity is immutable'; END IF;
 IF OLD.deleted_at IS NOT NULL THEN
  IF NEW IS DISTINCT FROM OLD THEN RAISE EXCEPTION 'Conversation tombstone is immutable'; END IF;
  RETURN NEW;
 END IF;
 IF NEW IS DISTINCT FROM OLD THEN
  IF OLD.content_version>=9007199254740991 THEN RAISE EXCEPTION 'Conversation version exhausted'; END IF;
  NEW.content_version=OLD.content_version+1;
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER conversation_tombstone_guard BEFORE UPDATE OR DELETE ON conversations FOR EACH ROW EXECUTE FUNCTION protect_conversation_tombstone();

-- All supported writers take these locks before descendant locks. Direct SQL lock
-- inversions may abort, but can never commit a resurrection or mixed closure.
CREATE FUNCTION protect_conversation_source() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE n jsonb; o jsonb; owner uuid; target uuid; parent conversations%ROWTYPE; identity text[]; allowed text[]; redaction boolean := false;
BEGIN
 n=CASE WHEN TG_OP='DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
 o=CASE WHEN TG_OP='INSERT' THEN n ELSE to_jsonb(OLD) END;
 IF TG_TABLE_NAME IN ('inference_context_history','inference_context_memories') THEN
  SELECT c.owner_id,c.conversation_id INTO owner,target FROM inference_contexts c WHERE c.id=(n->>'context_id')::uuid;
  identity=ARRAY['context_id','source_id'];
 ELSIF TG_TABLE_NAME IN ('approvals','authorizations','action_executions','mock_effects') THEN
  SELECT a.owner_id,r.conversation_id INTO owner,target FROM action_proposals a JOIN runs r ON r.owner_id=a.owner_id AND r.id=a.run_id WHERE a.id=(n->>'action_id')::uuid;
  identity=ARRAY['id','owner_id','action_id'];
 ELSIF TG_TABLE_NAME='runs' THEN owner=(n->>'owner_id')::uuid; target=(n->>'conversation_id')::uuid; identity=ARRAY['id','owner_id','conversation_id'];
 ELSE
  SELECT r.owner_id,r.conversation_id INTO owner,target FROM runs r WHERE r.id=(n->>'run_id')::uuid;
  identity=ARRAY['id','owner_id','run_id'];
 END IF;
 IF owner IS NULL OR target IS NULL OR (n ? 'owner_id' AND (n->>'owner_id')::uuid IS DISTINCT FROM owner) OR (n ? 'conversation_id' AND (n->>'conversation_id')::uuid IS DISTINCT FROM target) THEN RAISE EXCEPTION USING ERRCODE='23503', MESSAGE='Unverified conversation relationship'; END IF;
 IF TG_TABLE_NAME='inference_contexts' THEN
  identity=ARRAY['id','owner_id','run_id','conversation_id','project_id','project_version','session_hash','security_epoch','context_epoch','history_epoch','created_at'];
  IF TG_OP='UPDATE' AND ((array_position(ARRAY['CAPTURED','PREPARED','DISPATCHED','SETTLED','INVALIDATED'],n->>'state')<array_position(ARRAY['CAPTURED','PREPARED','DISPATCHED','SETTLED','INVALIDATED'],o->>'state')) OR ((o->>'state' IN ('DISPATCHED','SETTLED','INVALIDATED') OR EXISTS(SELECT 1 FROM inference_reservations q WHERE q.dispatch_context_id=(o->>'id')::uuid)) AND n->'profile_version' IS DISTINCT FROM o->'profile_version') OR (o->>'invalidated_at' IS NOT NULL AND n->'invalidated_at' IS DISTINCT FROM o->'invalidated_at')) THEN RAISE EXCEPTION 'Context dispatch anchors are immutable'; END IF;
 END IF;
 IF TG_OP='UPDATE' AND EXISTS(SELECT 1 FROM unnest(identity) AS fields(k) WHERE n->fields.k IS DISTINCT FROM o->fields.k) THEN RAISE EXCEPTION 'Source identity is immutable'; END IF;
 PERFORM 1 FROM owners WHERE id=owner FOR UPDATE;
 SELECT * INTO parent FROM conversations WHERE owner_id=owner AND id=target FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='23503', MESSAGE='Missing source conversation'; END IF;
 IF TG_TABLE_NAME='jobs' AND ((n->>'kind'='PLAN' AND (n->>'action_id' IS NOT NULL OR n->>'logical_key'<>'PLAN:'||(n->>'run_id'))) OR (n->>'kind'='EXECUTE' AND (n->>'logical_key'<>'EXECUTE:'||(n->>'action_id') OR NOT EXISTS(SELECT 1 FROM action_proposals a WHERE a.id=(n->>'action_id')::uuid AND a.owner_id=owner AND a.run_id=(n->>'run_id')::uuid)))) THEN RAISE EXCEPTION USING ERRCODE='23503', MESSAGE='Unverified job relationship'; END IF;
 IF TG_TABLE_NAME='inference_context_history' AND TG_OP<>'DELETE' AND NOT EXISTS(SELECT 1 FROM messages m WHERE m.id=(n->>'message_id')::uuid AND m.owner_id=owner AND m.conversation_id=target AND m.run_id=(n->>'run_id')::uuid AND m.role=n->>'role' AND m.content_deleted_at IS NULL) THEN RAISE EXCEPTION USING ERRCODE='23503', MESSAGE='Unverified history relationship'; END IF;
 IF TG_TABLE_NAME='inference_context_memories' AND TG_OP<>'DELETE' AND NOT EXISTS(SELECT 1 FROM memories m JOIN memory_slots s ON s.id=m.slot_id AND s.owner_id=m.owner_id WHERE m.id=(n->>'memory_id')::uuid AND m.owner_id=owner AND s.id=(n->>'slot_id')::uuid) THEN RAISE EXCEPTION USING ERRCODE='23503', MESSAGE='Unverified memory relationship'; END IF;
 IF parent.deleted_at IS NULL THEN
  IF n->>'content_deleted_at' IS NOT NULL OR n->>'source_deleted_at' IS NOT NULL THEN RAISE EXCEPTION 'Redacted child requires tombstone'; END IF;
  IF TG_OP='DELETE' AND TG_TABLE_NAME NOT IN ('inference_context_history','inference_context_memories') THEN RAISE EXCEPTION 'Source identity cannot be deleted'; END IF;
  IF TG_OP<>'UPDATE' OR n IS DISTINCT FROM o THEN UPDATE conversations SET content_version=content_version+1 WHERE id=target; END IF;
 ELSE
  IF TG_OP='INSERT' THEN RAISE EXCEPTION 'Conversation source is deleted'; END IF;
  IF TG_OP='DELETE' THEN
   IF TG_TABLE_NAME NOT IN ('inference_context_history','inference_context_memories') THEN RAISE EXCEPTION 'Source identity cannot be deleted'; END IF;
   RETURN OLD;
  END IF;
  IF n=o THEN RETURN NEW; END IF;
  CASE TG_TABLE_NAME
   WHEN 'messages' THEN allowed=ARRAY['content_ref','body_hash','content_deleted_at']; redaction=n->>'content_ref' IS NULL AND n->>'body_hash' IS NULL AND n->>'content_deleted_at' IS NOT NULL AND o->>'content_deleted_at' IS NULL;
   WHEN 'runs' THEN allowed=ARRAY['result_ref','context_history_eligible','content_deleted_at']; redaction=n->>'result_ref' IS NULL AND n->>'context_history_eligible'='false' AND n->>'content_deleted_at' IS NOT NULL AND o->>'content_deleted_at' IS NULL;
   WHEN 'action_proposals' THEN allowed=ARRAY['payload_ref','payload_hash','content_deleted_at']; redaction=n->>'payload_ref' IS NULL AND n->>'payload_hash' IS NULL AND n->>'content_deleted_at' IS NOT NULL AND o->>'content_deleted_at' IS NULL;
   WHEN 'approvals','mock_effects' THEN allowed=ARRAY['payload_hash','content_deleted_at']; redaction=n->>'payload_hash' IS NULL AND n->>'content_deleted_at' IS NOT NULL AND o->>'content_deleted_at' IS NULL;
   WHEN 'authorizations' THEN allowed=ARRAY['payload_hash','source_deleted_at','revoked_at']; redaction=n->>'payload_hash' IS NULL AND n->>'source_deleted_at' IS NOT NULL AND n->>'revoked_at' IS NOT NULL AND o->>'source_deleted_at' IS NULL;
   WHEN 'inference_contexts' THEN allowed=ARRAY['state','invalidated_at']; redaction=n->>'state'='INVALIDATED' AND n->>'invalidated_at' IS NOT NULL AND (o->>'invalidated_at' IS NULL OR n->>'invalidated_at'=o->>'invalidated_at');
   WHEN 'jobs' THEN allowed=ARRAY['lease_owner','lease_until','lease_generation']; redaction=n->>'lease_owner' IS NULL AND n->>'lease_until' IS NULL AND (n->>'lease_generation')::bigint=(o->>'lease_generation')::bigint+1;
   ELSE redaction=false;
  END CASE;
  IF NOT redaction OR (n-allowed) IS DISTINCT FROM (o-allowed) THEN RAISE EXCEPTION 'Conversation source is deleted'; END IF;
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END; $$;
DO $$ DECLARE t text; BEGIN
 FOREACH t IN ARRAY ARRAY['runs','messages','action_proposals','approvals','authorizations','jobs','action_executions','mock_effects','inference_reservations','inference_contexts','inference_context_history','inference_context_memories'] LOOP
  EXECUTE format('CREATE TRIGGER conversation_source_guard BEFORE INSERT OR UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION protect_conversation_source()',t);
 END LOOP;
END; $$;

CREATE FUNCTION verify_conversation_deletion_closure() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE c conversations%ROWTYPE;
BEGIN
 SELECT * INTO c FROM conversations WHERE id=NEW.id;
 IF c.deleted_at IS NULL THEN RETURN NULL; END IF;
 IF NOT EXISTS(SELECT 1 FROM conversation_deletion_ledger l JOIN conversation_deletion_mutations m ON m.owner_id=l.owner_id AND m.deletion_id=l.id JOIN conversation_delete_preparations p ON p.owner_id=l.owner_id AND p.id=l.preparation_id JOIN conversation_delete_approvals a ON a.owner_id=l.owner_id AND a.id=l.approval_id WHERE l.owner_id=c.owner_id AND l.conversation_id=c.id AND l.tombstone_version=c.content_version AND l.deleted_at=c.deleted_at AND l.deleted_at>=p.prepared_at AND l.deleted_at<p.expires_at AND a.consumed_at=l.deleted_at AND p.consumed_at=l.deleted_at AND p.consumed_at IS NOT NULL AND p.payload_hash_ref IS NULL AND p.expected_digest_ref IS NULL AND a.preparation_id=p.id AND a.conversation_id=c.id AND m.preparation_id=p.id AND m.session_hash=a.session_hash AND m.security_epoch=a.security_epoch) THEN RAISE EXCEPTION 'Incomplete deletion identity'; END IF;
 IF EXISTS(SELECT 1 FROM runs r WHERE r.conversation_id=c.id AND (r.owner_id<>c.owner_id OR r.content_deleted_at IS NULL OR r.result_ref IS NOT NULL OR r.context_history_eligible OR r.status NOT IN ('SUCCEEDED','PARTIAL_SUCCESS','FAILED','CANCELLED','EXPIRED','BLOCKED','WAITING_INPUT')))
 OR EXISTS(SELECT 1 FROM messages m JOIN runs r ON r.id=m.run_id WHERE (m.conversation_id=c.id OR r.conversation_id=c.id) AND (m.owner_id<>c.owner_id OR m.conversation_id<>r.conversation_id OR m.content_deleted_at IS NULL OR m.content_ref IS NOT NULL OR m.body_hash IS NOT NULL))
 OR EXISTS(SELECT 1 FROM action_proposals a JOIN runs r ON r.id=a.run_id WHERE r.conversation_id=c.id AND (a.tool_id<>'calendar.event.create' OR a.tool_version<>'1' OR a.content_deleted_at IS NULL OR a.payload_ref IS NOT NULL OR a.payload_hash IS NOT NULL OR a.status NOT IN ('SUCCEEDED','FAILED','CANCELLED','EXPIRED','BLOCKED')))
 OR EXISTS(SELECT 1 FROM approvals p JOIN action_proposals a ON a.id=p.action_id JOIN runs r ON r.id=a.run_id WHERE r.conversation_id=c.id AND (p.content_deleted_at IS NULL OR p.payload_hash IS NOT NULL OR p.status NOT IN ('CONSUMED','REJECTED','EXPIRED','REVOKED')))
 OR EXISTS(SELECT 1 FROM authorizations z JOIN action_proposals a ON a.id=z.action_id JOIN runs r ON r.id=a.run_id WHERE r.conversation_id=c.id AND (z.source_deleted_at IS NULL OR z.revoked_at IS NULL OR z.payload_hash IS NOT NULL))
 OR EXISTS(SELECT 1 FROM mock_effects e JOIN action_proposals a ON a.id=e.action_id JOIN runs r ON r.id=a.run_id WHERE r.conversation_id=c.id AND (e.content_deleted_at IS NULL OR e.payload_hash IS NOT NULL))
 OR EXISTS(SELECT 1 FROM jobs j JOIN runs r ON r.id=j.run_id WHERE r.conversation_id=c.id AND (j.owner_id<>c.owner_id OR j.status NOT IN ('SUCCEEDED','FAILED','CANCELLED','EXPIRED','BLOCKED') OR j.lease_owner IS NOT NULL OR j.lease_until IS NOT NULL OR (j.kind='PLAN' AND (j.action_id IS NOT NULL OR j.logical_key<>'PLAN:'||j.run_id::text OR j.started_effect)) OR (j.kind='EXECUTE' AND (j.action_id IS NULL OR j.logical_key<>'EXECUTE:'||j.action_id::text OR NOT EXISTS(SELECT 1 FROM action_proposals a WHERE a.id=j.action_id AND a.owner_id=j.owner_id AND a.run_id=j.run_id))) OR (j.started_effect AND NOT EXISTS(SELECT 1 FROM action_executions e WHERE e.action_id=j.action_id AND e.owner_id=j.owner_id AND e.status IN ('SUCCEEDED','FAILED','CANCELLED','EXPIRED','BLOCKED') AND e.finished_at IS NOT NULL AND e.lease_generation<=j.lease_generation))))
 OR EXISTS(SELECT 1 FROM action_executions e JOIN action_proposals a ON a.id=e.action_id JOIN runs r ON r.id=a.run_id WHERE r.conversation_id=c.id AND (e.status NOT IN ('SUCCEEDED','FAILED','CANCELLED','EXPIRED','BLOCKED') OR e.finished_at IS NULL))
 OR EXISTS(SELECT 1 FROM inference_contexts x JOIN runs r ON r.id=x.run_id WHERE (x.conversation_id=c.id OR r.conversation_id=c.id) AND (x.conversation_id<>r.conversation_id OR x.state<>'INVALIDATED' OR x.invalidated_at IS NULL OR NOT EXISTS(SELECT 1 FROM inference_reservations q WHERE q.owner_id=x.owner_id AND q.run_id=x.run_id AND q.dispatch_context_id=x.id) OR EXISTS(SELECT 1 FROM inference_context_history h WHERE h.context_id=x.id) OR EXISTS(SELECT 1 FROM inference_context_memories b WHERE b.context_id=x.id)))
 OR EXISTS(SELECT 1 FROM inference_context_history h JOIN messages m ON m.id=h.message_id WHERE m.conversation_id=c.id)
 OR EXISTS(SELECT 1 FROM inference_reservations q JOIN runs r ON r.id=q.run_id WHERE r.conversation_id=c.id AND (q.status NOT IN ('SUCCEEDED','FAILED','CANCELLED') OR q.started_at IS NULL OR q.dispatch_context_id IS NULL OR q.dispatch_cache_policy IS DISTINCT FROM 'CONTEXT_V1_NO_RESPONSE_CACHE' OR q.accounting_version<>1))
 OR EXISTS(SELECT 1 FROM conversation_delete_preparations p WHERE p.owner_id=c.owner_id AND p.conversation_id=c.id AND (p.payload_hash_ref IS NOT NULL OR p.expected_digest_ref IS NOT NULL OR (p.consumed_at IS NULL AND p.revoked_at IS NULL)))
 THEN RAISE EXCEPTION 'Incomplete conversation source redaction'; END IF;
 RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER conversation_deletion_complete AFTER UPDATE ON conversations DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_conversation_deletion_closure();

CREATE FUNCTION protect_conversation_preparation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN
  IF OLD.consumed_at IS NOT NULL THEN RAISE EXCEPTION 'Consumed deletion identity is permanent'; END IF;
  RETURN OLD;
 END IF;
 IF (to_jsonb(NEW)-ARRAY['expected_digest_ref','payload_hash_ref','consumed_at','revoked_at']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['expected_digest_ref','payload_hash_ref','consumed_at','revoked_at'])
 OR (OLD.consumed_at IS NOT NULL AND NEW.consumed_at IS DISTINCT FROM OLD.consumed_at)
 OR (OLD.revoked_at IS NOT NULL AND NEW.revoked_at IS DISTINCT FROM OLD.revoked_at)
 OR (NEW.expected_digest_ref IS NOT NULL AND NEW.expected_digest_ref IS DISTINCT FROM OLD.expected_digest_ref)
 OR (NEW.payload_hash_ref IS NOT NULL AND NEW.payload_hash_ref IS DISTINCT FROM OLD.payload_hash_ref)
 THEN RAISE EXCEPTION 'Deletion preparation binding is immutable'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER conversation_preparation_guard BEFORE UPDATE OR DELETE ON conversation_delete_preparations FOR EACH ROW EXECUTE FUNCTION protect_conversation_preparation();

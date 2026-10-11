package dev.myjarvis.core;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** One saved target, one lifetime request, one dedicated retained result. */
@Service
final class StudyFeedbackService {
    private final JdbcTemplate db;
    private final MemoryService memories;
    private final ConversationAccess access;
    private final StudySessionService studies;
    private final StudyFeedbackSource sources;
    private final StudyFeedbackLifecycle lifecycle;
    private final InferenceContextService contexts;
    private final InferenceContextFreshness freshness;
    private final InferenceControlService controls;
    private final LocalInferenceClient client;
    private final TransactionTemplate tx;
    StudyFeedbackService(JdbcTemplate db,MemoryService memories,ConversationAccess access,StudySessionService studies,
            StudyFeedbackSource sources,StudyFeedbackLifecycle lifecycle,InferenceContextService contexts,
            InferenceContextFreshness freshness,InferenceControlService controls,LocalInferenceClient client,
            PlatformTransactionManager manager) {
        this.db=db;this.memories=memories;this.access=access;this.studies=studies;this.sources=sources;
        this.lifecycle=lifecycle;this.contexts=contexts;this.freshness=freshness;this.controls=controls;this.client=client;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(5);
    }
    private <T> T transaction(Supplier<T> work) {
        try { return tx.execute(status->{
            db.execute("SET LOCAL statement_timeout='5s'");db.execute("SET LOCAL lock_timeout='5s'");db.execute("SET LOCAL idle_in_transaction_session_timeout='5s'");
            long started=System.nanoTime();T result=work.get();
            if(System.nanoTime()-started>=5_000_000_000L)throw new ApiException(503,"FEEDBACK_TIME_LIMIT");return result;
        }); }catch(TransactionTimedOutException invalid){throw new ApiException(503,"FEEDBACK_TIME_LIMIT");}
        catch(DataAccessException invalid){
            for(Throwable cause=invalid;cause!=null;cause=cause.getCause())if(cause instanceof java.sql.SQLException sql){
                if(Set.of("57014","55P03","25P03").contains(sql.getSQLState()))throw new ApiException(503,"FEEDBACK_TIME_LIMIT");
                if("54000".equals(sql.getSQLState()))throw new ApiException(413,"FEEDBACK_CAPACITY_LIMIT");
                if(Set.of("23505","23514","23503","P0001").contains(sql.getSQLState()))throw new ApiException(409,"FEEDBACK_SOURCE_UNAVAILABLE");
            }throw invalid;
        }
    }
    private void schema(){if(!"18".equals(db.queryForObject("SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1",String.class)))throw new ApiException(409,"CONVERSATION_GRAPH_UNVERIFIED");}
    private Instant now(){return memories.databaseNow();}
    private static UUID id(Map<String,Object> row,String field){return StudyFeedbackSource.id(row,field);}
    private static long number(Map<String,Object> row,String field){return StudyFeedbackSource.number(row,field);}
    private static void require(boolean value){if(!value)throw new ApiException(409,"FEEDBACK_SOURCE_UNAVAILABLE");}
    private static void body(JsonNode body,String... fields){InferenceControlResult.exact(body,fields);if(Json.canonical(body).getBytes(StandardCharsets.UTF_8).length>8192)throw new ApiException(413,"BODY_TOO_LARGE");}
    private String digest(AuthService.Session session,String operation,UUID target,JsonNode body){return memories.keys.digest(session.owner(),"study-feedback:request-v1",Json.canonical(Map.of("operation",operation,"target",target,"body",body)));}
    private Map<String,Object> existing(UUID owner,UUID key){var rows=db.queryForList("SELECT * FROM study_feedback_events WHERE owner_id=? AND idempotency_key=?",owner,key);return rows.isEmpty()?null:rows.getFirst();}
    private void binding(AuthService.Session session,Map<String,Object> row){
        if(!session.hash().equals(row.get("operation_session_hash"))||session.epoch()!=number(row,"operation_security_epoch"))throw new ApiException(403,"AUTHORITY_NOT_VALID");
    }
    private Instant finish(AuthService.Session session,Instant observed,Instant committed){
        access.lockSession(session);
        // One final observation is the last database statement, after every wait and source read.
        var authority=controls.finalObservation(session.owner(),session.hash());Instant checked=AuthService.instant(authority.get("observed_at"));
        Instant created=AuthService.instant(authority.get("created_at")),seen=AuthService.instant(authority.get("last_seen_at"));
        if(authority.get("revoked_at")!=null||number(authority,"owner_epoch")!=session.epoch()||number(authority,"session_epoch")!=session.epoch()
                ||!created.isAfter(checked.minusSeconds(43200))||!seen.isAfter(checked.minusSeconds(7200)))throw new ApiException(401,"SESSION_REVOKED");
        if(checked.isBefore(observed)||(committed!=null&&checked.isBefore(committed))||checked.isBefore(created)||checked.isBefore(seen))throw new ApiException(403,"AUTHORITY_NOT_VALID");return checked;
    }
    private Map<String,Object> replay(AuthService.Session session,UUID key,String digest,Instant observed){
        var event=existing(session.owner(),key);if(event==null)return null;
        access.requireLive(session,id(event,"conversation_id"));binding(session,event);
        if(!Json.equal(digest,(String)event.get("request_digest")))throw new ApiException(409,"IDEMPOTENCY_CONFLICT");
        return outcome(event,finish(session,observed,AuthService.instant(event.get("committed_at"))));
    }
    private static Map<String,Object> receipt(Map<String,Object> event){
        var result=new LinkedHashMap<String,Object>();result.put("mutation_id",event.get("idempotency_key"));
        result.put("operation","REQUEST".equals(event.get("operation"))?"REQUEST_FEEDBACK":"REVOKE_FEEDBACK");
        result.put("feedback_request_id",event.get("feedback_id"));
        for(String field:List.of("conversation_id","study_session_id","lesson_run_id","question_id","input_id","attempt_id","prior_conversation_version","resulting_conversation_version"))result.put(field,event.get(field));
        result.put("prior_feedback_version",event.get("prior_version"));result.put("resulting_feedback_version",event.get("version"));result.put("study_version",3);
        result.put("committed_at",AuthService.instant(event.get("committed_at")).toString());return result;
    }
    private static Map<String,Object> outcome(Map<String,Object> event,Instant checked){var result=new LinkedHashMap<String,Object>();result.put("status",event==null?"NOT_OBSERVED":"COMMITTED");result.put("receipt",event==null?null:receipt(event));result.put("checked_at",checked.toString());return result;}
    Map<String,Object> mutation(AuthService.Session session,UUID key){return transaction(()->{
        access.lockSession(session);schema();Instant observed=now();var event=existing(session.owner(),key);if(event!=null)binding(session,event);
        return outcome(event,finish(session,observed,event==null?null:AuthService.instant(event.get("committed_at"))));
    });}
    Map<String,Object> request(AuthService.Session session,UUID key,UUID studyId,JsonNode body){
        body(body,"expected_study_version","expected_conversation_version","question_id","input_id","attempt_id");
        if(Json.integer(body,"expected_study_version")!=3)throw ApiException.bad();
        long expected=MemoryService.number(body,"expected_conversation_version",1);
        var target=new StudyFeedbackSource.Target(Json.uuid(body,"question_id"),Json.uuid(body,"input_id"),Json.uuid(body,"attempt_id"));
        AuthService.requireOwner(session);String digest=digest(session,"REQUEST_FEEDBACK",studyId,body);
        var prior=transaction(()->{access.lockSession(session);schema();var replay=replay(session,key,digest,now());if(replay!=null)return replay;var targets=db.queryForList("SELECT conversation_id FROM study_sessions WHERE owner_id=? AND id=?",session.owner(),studyId);if(targets.isEmpty())throw ApiException.missing();access.requireLive(session,id(targets.getFirst(),"conversation_id"));return null;});
        if(prior!=null)return prior;
        // Readiness performs no generation and is deliberately outside all database locks.
        if(!client.ready()||!client.feedbackReady())throw new ApiException(503,"FEEDBACK_MODEL_UNAVAILABLE");
        return transaction(()->{
            var owner=access.lockSession(session);schema();Instant observed=now();var again=replay(session,key,digest,observed);if(again!=null)return again;
            var source=sources.loadForAdmission(session,studyId,target);UUID conversation=id(source.conversation(),"id");
            if(number(source.conversation(),"content_version")!=expected)throw new ApiException(409,"PRECONDITION_CHANGED");
            if(!db.queryForList("SELECT id FROM study_feedback_requests WHERE owner_id=? AND input_id=?",session.owner(),target.input()).isEmpty())throw new ApiException(409,"FEEDBACK_ALREADY_REQUESTED");
            lifecycle.requireWritableConversation(session.owner(),conversation);capacity(session.owner());
            UUID feedback=UUID.randomUUID(),run=UUID.randomUUID(),reservation=UUID.randomUUID(),request=UUID.randomUUID(),context=UUID.randomUUID();
            var row=new LinkedHashMap<String,Object>();row.put("id",feedback);row.put("owner_id",session.owner());row.put("conversation_id",conversation);
            row.put("study_session_id",studyId);row.put("lesson_run_id",source.study().get("lesson_run_id"));row.put("question_id",target.question());row.put("input_id",target.input());row.put("attempt_id",target.attempt());
            row.put("run_id",run);row.put("reservation_id",reservation);row.put("request_id",request);row.put("context_id",context);
            row.put("project_id",source.conversation().get("project_id"));row.put("project_version",row.get("project_id")==null?null:db.queryForObject("SELECT version FROM projects WHERE owner_id=? AND id=?",Long.class,session.owner(),row.get("project_id")));
            row.put("initiating_session_hash",session.hash());row.put("security_epoch",session.epoch());row.put("admitted_study_version",3);row.put("lesson_run_version",source.study().get("lesson_run_version"));
            row.put("admission_conversation_version",expected);row.put("context_epoch",owner.get("context_epoch"));row.put("history_epoch",owner.get("history_epoch"));row.put("profile_version",owner.get("version"));row.put("timezone","UTC");
            String sourceRef=sources.sealBinding(session.owner(),row,source);
            Timestamp created=Timestamp.from(now());
            db.update("INSERT INTO study_feedback_requests(id,owner_id,conversation_id,study_session_id,lesson_run_id,question_id,input_id,attempt_id,run_id,reservation_id,request_id,context_id,project_id,project_version,initiating_session_hash,security_epoch,admitted_study_version,lesson_run_version,admission_conversation_version,context_epoch,history_epoch,profile_version,timezone,created_at,version,state,output_authority,updated_at,source_binding_ref) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,3,?,?,?,?,?,'UTC',?,1,'QUEUED','ACTIVE',?,?)",
                    feedback,session.owner(),conversation,studyId,row.get("lesson_run_id"),target.question(),target.input(),target.attempt(),run,reservation,request,context,row.get("project_id"),row.get("project_version"),session.hash(),session.epoch(),row.get("lesson_run_version"),expected,owner.get("context_epoch"),owner.get("history_epoch"),owner.get("version"),created,created,sourceRef);
            db.update("INSERT INTO runs(id,owner_id,conversation_id,status,history_epoch,context_history_eligible,result_ref) VALUES (?,?,?,'QUEUED',?,false,NULL)",run,session.owner(),conversation,owner.get("history_epoch"));
            db.update("INSERT INTO inference_reservations(id,owner_id,run_id,request_id,body_hash,model_manifest_hash,status,max_compute_ms,reserved_output_tokens,security_epoch,purpose,control_revision) VALUES (?,?,?,?,?,?,'QUEUED',60000,512,?,'STUDY_FEEDBACK',1)",reservation,session.owner(),run,request,digest,client.manifestHash,session.epoch());
            contexts.captureFeedback(session,conversation,run,context);controls.allocate(session,conversation,run,reservation,request,context,StudyFeedbackResult.PURPOSE);
            event(session.owner(),feedback,"REQUEST",null,key,session,digest,null);
            controls.finishAdmission(session,conversation,run,reservation,observed);sources.loadForExecution(session.owner(),feedback);
            var committed=existing(session.owner(),key);controls.kick();
            return outcome(committed,finish(session,observed,AuthService.instant(committed.get("committed_at"))));
        });
    }
    private void capacity(UUID owner){
        if(lifecycle.activeCapacityCount(owner)>=2)throw new ApiException(429,"INFERENCE_CAPACITY_RESERVED");
        long day=db.queryForObject("SELECT COALESCE(sum(max_compute_ms),0) FROM inference_reservations WHERE owner_id=? AND created_at>=date_trunc('day',now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC' AND status<>'CANCELLED'",Long.class,owner);
        long month=db.queryForObject("SELECT COALESCE(sum(max_compute_ms),0) FROM inference_reservations WHERE owner_id=? AND created_at>=date_trunc('month',now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC' AND status<>'CANCELLED'",Long.class,owner);
        if(day+60000>3600000||month+60000>36000000)throw new ApiException(429,"INFERENCE_BUDGET_EXCEEDED");
    }
    private void event(UUID owner,UUID feedback,String operation,Integer prior,UUID key,AuthService.Session session,String digest,String reason){
        var row=db.queryForMap("SELECT * FROM study_feedback_requests WHERE owner_id=? AND id=?",owner,feedback);
        long current=db.queryForObject("SELECT content_version FROM conversations WHERE owner_id=? AND id=?",Long.class,owner,row.get("conversation_id"));
        db.update("INSERT INTO study_feedback_events(owner_id,feedback_id,version,conversation_id,study_session_id,lesson_run_id,question_id,input_id,attempt_id,project_id,operation,prior_version,resulting_state,output_authority,idempotency_key,operation_session_hash,operation_security_epoch,request_digest,reason_code,prior_conversation_version,resulting_conversation_version,committed_at,phase_xid,invalidated_contexts) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,clock_timestamp(),?,0)",
                owner,feedback,row.get("version"),row.get("conversation_id"),row.get("study_session_id"),row.get("lesson_run_id"),row.get("question_id"),row.get("input_id"),row.get("attempt_id"),row.get("project_id"),operation,prior,row.get("state"),row.get("output_authority"),key,session==null?null:session.hash(),session==null?null:session.epoch(),digest,reason,row.get("phase_prior_conversation_version"),current+1,row.get("phase_xid"));
    }
    Map<String,Object> revoke(AuthService.Session session,UUID key,UUID feedback,JsonNode body){
        body(body,"expected_feedback_version","expected_conversation_version");long version=Json.integer(body,"expected_feedback_version"),expected=MemoryService.number(body,"expected_conversation_version",1);
        if(version!=1&&version!=2)throw ApiException.bad();String digest=digest(session,"REVOKE_FEEDBACK",feedback,body);
        return transaction(()->{
            access.lockSession(session);schema();Instant observed=now();var previous=replay(session,key,digest,observed);if(previous!=null)return previous;
            var row=sources.request(session.owner(),feedback);var conversation=access.requireLive(session,id(row,"conversation_id"));
            if(number(row,"version")!=version||number(conversation,"content_version")!=expected)throw new ApiException(409,"PRECONDITION_CHANGED");
            if(!"ACTIVE".equals(row.get("output_authority")))throw new ApiException(409,"FEEDBACK_TERMINAL");
            db.queryForObject("SELECT revoke_study_feedback_v18(?,?,?,?,?,?,?,?,?)",Object.class,session.owner(),feedback,"RUN_EXACT",null,"OWNER_REVOKE",key,session.hash(),session.epoch(),digest);
            db.execute("SET CONSTRAINTS ALL IMMEDIATE");var committed=existing(session.owner(),key);controls.kick();
            return outcome(committed,finish(session,observed,AuthService.instant(committed.get("committed_at"))));
        });
    }
    LocalInferenceService.Dispatch prepareDispatch(UUID owner,UUID reservation){return transaction(()->{
        access.lockOwner(owner);schema();Instant observed=now();
        var rows=db.queryForList("SELECT id FROM study_feedback_requests WHERE owner_id=? AND reservation_id=? AND state='QUEUED' AND output_authority='ACTIVE'",owner,reservation);if(rows.isEmpty())return null;
        UUID feedback=id(rows.getFirst(),"id");var source=sources.loadForExecution(owner,feedback);var row=sources.request(owner,feedback);UUID run=id(row,"run_id");
        require(db.update("UPDATE study_feedback_requests SET version=2,state='RUNNING' WHERE owner_id=? AND id=? AND version=1 AND state='QUEUED'",owner,feedback)==1);
        var selected=contexts.prepareFeedback(owner,run);var stored=db.queryForMap("SELECT * FROM inference_reservations WHERE owner_id=? AND id=? FOR UPDATE",owner,reservation);
        JsonNode request=transport(stored,source.wire(feedback),selected);String digest=contexts.requestDigest(owner,Json.canonical(request));
        contexts.markDispatched(owner,run);
        require(db.update("UPDATE inference_reservations SET status='STARTED',started_at=clock_timestamp(),body_hash=?,dispatch_context_id=?,dispatch_cache_policy='CONTEXT_V1_NO_RESPONSE_CACHE' WHERE owner_id=? AND id=? AND status='QUEUED' AND started_at IS NULL AND dispatch_context_id IS NULL AND dispatch_cache_policy IS NULL",digest,row.get("context_id"),owner,reservation)==1);
        require(db.update("UPDATE runs SET status='RUNNING',version=version+1 WHERE owner_id=? AND id=? AND status='QUEUED' AND result_ref IS NULL AND NOT context_history_eligible",owner,run)==1);
        controls.bind(owner,reservation,request);event(owner,feedback,"DISPATCH",1,null,null,null,null);
        sources.loadForExecution(owner,feedback);controls.finishDispatch(owner,run,reservation,observed);
        var committed=db.queryForMap("SELECT committed_at FROM study_feedback_events WHERE owner_id=? AND feedback_id=? AND version=2",owner,feedback);
        finish(StudyFeedbackSource.initiator(row),observed,AuthService.instant(committed.get("committed_at")));
        return new LocalInferenceService.Dispatch(owner,run,reservation,request);
    });}
    boolean beforeTransport(LocalInferenceService.Dispatch dispatch){return transaction(()->{
        access.lockOwner(dispatch.owner());schema();Instant observed=now();
        var rows=db.queryForList("SELECT * FROM study_feedback_requests WHERE owner_id=? AND run_id=? AND reservation_id=? AND state='RUNNING' AND output_authority='ACTIVE'",dispatch.owner(),dispatch.run(),dispatch.reservation());
        if(rows.isEmpty())return false;var row=rows.getFirst();UUID feedback=id(row,"id");
        sources.loadForExecution(dispatch.owner(),feedback);
        require(Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM inference_reservations WHERE owner_id=? AND id=? AND run_id=? AND request_id=? AND status='STARTED' AND body_hash=? AND dispatch_context_id=? AND dispatch_cache_policy='CONTEXT_V1_NO_RESPONSE_CACHE' AND cancel_requested_at IS NULL)",Boolean.class,dispatch.owner(),dispatch.reservation(),dispatch.run(),Json.uuid(dispatch.body(),"request_id"),contexts.requestDigest(dispatch.owner(),Json.canonical(dispatch.body())),row.get("context_id"))));
        controls.finishDispatch(dispatch.owner(),dispatch.run(),dispatch.reservation(),observed);
        var event=db.queryForMap("SELECT committed_at FROM study_feedback_events WHERE owner_id=? AND feedback_id=? AND version=2",dispatch.owner(),feedback);
        finish(StudyFeedbackSource.initiator(row),observed,AuthService.instant(event.get("committed_at")));return true;
    });}
    private JsonNode transport(Map<String,Object> reservation,Map<String,Object> source,Map<String,Object> selected){
        var body=new LinkedHashMap<String,Object>();body.put("request_id",reservation.get("request_id").toString());body.put("run_id",reservation.get("run_id").toString());
        body.put("purpose",StudyFeedbackResult.PURPOSE);body.put("feedback_source",source);body.putAll(selected);
        for(String field:List.of("evidence","tool_results","allowed_tool_ids"))body.put(field,List.of());
        body.put("budget",Map.of("reservation_id",reservation.get("id").toString(),"max_compute_ms",60000,"max_input_tokens",3584,"max_output_tokens",512,"max_model_calls",1));
        body.put("model_id","local-mlx-v1");body.put("model_manifest_hash",reservation.get("model_manifest_hash"));body.put("prompt_revision","local-v1");
        if(Json.canonical(body).getBytes(StandardCharsets.UTF_8).length>196608)throw new ApiException(413,"CONTEXT_TOO_LARGE");
        body.put("control",controls.descriptor(reservation,body));JsonNode result=Json.MAPPER.valueToTree(body);
        if(Json.canonical(result).getBytes(StandardCharsets.UTF_8).length>262144)throw new ApiException(413,"CONTEXT_TOO_LARGE");return result;
    }
    void settle(LocalInferenceService.Dispatch dispatch,JsonNode response){
        transaction(()->{
            access.lockOwner(dispatch.owner());schema();Instant observed=now();var rows=db.queryForList("SELECT id FROM study_feedback_requests WHERE owner_id=? AND reservation_id=? AND output_authority='ACTIVE'",dispatch.owner(),dispatch.reservation());if(rows.isEmpty())return null;
            UUID feedback=id(rows.getFirst(),"id");sources.loadForExecution(dispatch.owner(),feedback);var row=sources.request(dispatch.owner(),feedback);
            LocalInferenceService.validate(response,dispatch.body());String kind=Json.string(response,"kind",30);boolean accepted="FEEDBACK".equals(kind);
            require(accepted||"ERROR".equals(kind));
            require(Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM inference_usage_receipts WHERE owner_id=? AND run_id=? AND reservation_id=? AND request_id=? AND request_digest=? AND receipt_kind=? AND response_kind=?)",Boolean.class,dispatch.owner(),dispatch.run(),dispatch.reservation(),Json.uuid(dispatch.body(),"request_id"),contexts.requestDigest(dispatch.owner(),Json.canonical(dispatch.body())),accepted?"MEASURED":"ERROR",kind)));
            String result=accepted?memories.vault.seal(dispatch.owner(),feedback,"study-feedback-result",StudyFeedbackResult.encodeAccepted(StudyFeedbackSource.targetEnvelope(row),response)):null;
            sources.loadForExecution(dispatch.owner(),feedback);
            require(db.update("UPDATE study_feedback_requests SET version=3,state=?,output_authority='CLOSED',result_ref=? WHERE owner_id=? AND id=? AND version=2 AND state='RUNNING' AND output_authority='ACTIVE'",accepted?"ACCEPTED":"FAILED",result,dispatch.owner(),feedback)==1);
            contexts.markSettled(dispatch.owner(),dispatch.run());
            require(db.update("UPDATE inference_reservations SET status=?,completed_at=clock_timestamp() WHERE owner_id=? AND id=? AND status='STARTED' AND cancel_requested_at IS NULL",accepted?"SUCCEEDED":"FAILED",dispatch.owner(),dispatch.reservation())==1);
            require(db.update("UPDATE runs SET status=?,version=version+1 WHERE owner_id=? AND id=? AND status='RUNNING' AND result_ref IS NULL AND NOT context_history_eligible AND cancel_requested_at IS NULL",accepted?"SUCCEEDED":"FAILED",dispatch.owner(),dispatch.run())==1);
            event(dispatch.owner(),feedback,accepted?"ACCEPT":"FAIL",2,null,null,null,accepted?null:"MODEL_ERROR");
            db.execute("SET CONSTRAINTS ALL IMMEDIATE");var committed=db.queryForMap("SELECT committed_at FROM study_feedback_events WHERE owner_id=? AND feedback_id=? AND version=3",dispatch.owner(),feedback);
            controls.kick();finish(StudyFeedbackSource.initiator(row),observed,AuthService.instant(committed.get("committed_at")));return null;
        });
    }
    void revokeUncertain(UUID owner,UUID reservation,String reason){transaction(()->{
        access.lockOwner(owner);var rows=db.queryForList("SELECT run_id FROM study_feedback_requests WHERE owner_id=? AND reservation_id=? AND output_authority='ACTIVE'",owner,reservation);
        if(!rows.isEmpty())lifecycle.closeAffected(owner,"RUN_EXACT",null,id(rows.getFirst(),"run_id"),reason);controls.kick();return null;
    });}
    Map<String,Object> read(AuthService.Session session,UUID studyId){
        // Availability transport is outside locks; failure is a closed unavailable state, never a new admission.
        boolean available=client.ready()&&client.feedbackReady();
        return transaction(()->{
            access.lockSession(session);schema();Instant observed=now();var study=studies.get(session,studyId);UUID conversation=(UUID)study.get("conversation_id");
            var requests=db.queryForList("SELECT * FROM study_feedback_requests WHERE owner_id=? AND study_session_id=? ORDER BY id LIMIT 3",session.owner(),studyId);require(requests.size()<=2);
            var inputs=db.queryForList("SELECT id,question_id,attempt_id FROM study_owner_answer_inputs WHERE owner_id=? AND study_session_id=? ORDER BY resulting_study_version LIMIT 3",session.owner(),studyId);require(inputs.size()<=2);
            boolean busy=!sources.quiescentForAdmission(session.owner(),conversation);
            JsonNode lesson=Json.MAPPER.valueToTree(study.get("lesson"));var targets=new ArrayList<Map<String,Object>>();
            for(JsonNode question:lesson.get("questions")){
                UUID questionId=Json.uuid(question,"id");var input=inputs.stream().filter(r->questionId.equals(r.get("question_id"))).findFirst().orElse(null);
                var request=requests.stream().filter(r->questionId.equals(r.get("question_id"))).findFirst().orElse(null);
                var target=new LinkedHashMap<String,Object>();target.put("question_id",questionId);target.put("input_id",input==null?null:input.get("id"));target.put("attempt_id",input==null?null:input.get("attempt_id"));
                target.put("availability",request!=null?"ALREADY_REQUESTED":!"OPEN".equals(study.get("lifecycle"))?"STUDY_CLOSED":inputs.size()!=2?"ANSWERS_INCOMPLETE":busy?"CONVERSATION_BUSY":!available?"MODEL_UNAVAILABLE":"AVAILABLE");
                target.put("request",request==null?null:project(session,request));targets.add(target);
            }
            var result=new LinkedHashMap<String,Object>();result.put("revision",1);result.put("study_session_id",studyId);result.put("conversation_id",conversation);result.put("study_version",study.get("version"));result.put("conversation_version",study.get("conversation_content_version"));result.put("lifecycle",study.get("lifecycle"));result.put("targets",targets);result.put("checked_at",finish(session,observed,null).toString());
            if(Json.canonical(result).getBytes(StandardCharsets.UTF_8).length>261120)throw new ApiException(409,"FEEDBACK_SOURCE_UNAVAILABLE");return result;
        });
    }
    private Map<String,Object> project(AuthService.Session session,Map<String,Object> row){
        UUID feedback=id(row,"id");db.queryForObject("SELECT verify_study_feedback_history_v18(?,?)",Object.class,session.owner(),feedback);
        var result=new LinkedHashMap<String,Object>();result.put("id",feedback);result.put("feedback_version",row.get("version"));result.put("status",row.get("state"));result.put("output_authority",row.get("output_authority"));
        var slot=db.queryForMap("SELECT terminal_kind FROM inference_control_slots WHERE owner_id=? AND reservation_id=?",session.owner(),row.get("reservation_id"));
        result.put("execution_evidence",slot.get("terminal_kind")==null?"UNCONFIRMED":slot.get("terminal_kind"));
        String accounting=db.queryForObject("SELECT CASE WHEN EXISTS(SELECT 1 FROM inference_usage_receipts u WHERE u.owner_id=i.owner_id AND u.reservation_id=i.id AND u.receipt_kind='MEASURED') THEN 'MEASURED' WHEN EXISTS(SELECT 1 FROM inference_usage_receipts e WHERE e.owner_id=i.owner_id AND e.reservation_id=i.id AND e.receipt_kind='ERROR') THEN 'UNCERTAIN' WHEN i.status='CANCELLED' AND i.started_at IS NULL THEN 'UNSTARTED' WHEN i.status IN ('QUEUED','STARTED') THEN 'PENDING' ELSE 'UNCERTAIN' END FROM inference_reservations i WHERE i.owner_id=? AND i.id=?",String.class,session.owner(),row.get("reservation_id"));result.put("accounting",accounting);
        for(String field:List.of("created_at","updated_at"))result.put(field,AuthService.instant(row.get(field)).toString());
        result.put("result","ACCEPTED".equals(row.get("state"))?sources.loadForRead(session,feedback).result():null);return result;
    }
}

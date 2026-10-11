package dev.myjarvis.core;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Stored historical answers and current output authority are deliberately separate checks. */
@Service
final class StudyFeedbackSource {
    record Target(UUID question, UUID input, UUID attempt) {}
    record ExecutionSource(Map<String,Object> conversation, Map<String,Object> study,
            StudySessionService.Lesson lesson, Map<String,Object> input, String text,
            String originalBinding, Map<String,Object> owner) {
        Map<String,Object> wire(UUID feedback) {
            var value = new LinkedHashMap<String,Object>();
            value.put("revision",1); value.put("feedback_id",feedback); value.put("study_session_id",study.get("id"));
            value.put("study_version",3); value.put("lesson_run_id",study.get("lesson_run_id"));
            value.put("lesson_run_version",study.get("lesson_run_version"));
            for(String field:List.of("question_id","attempt_id")) value.put(field,input.get(field));
            value.put("input_id",input.get("id")); value.put("lesson",StudyLessonResult.project(lesson.lesson()));
            value.put("answer",Map.of("source_kind","OWNER_STUDY_ANSWER_INPUT","role","USER","trust","USER_DIRECT","text",text));
            return value;
        }
    }
    record RetainedFeedback(Map<String,Object> request, Map<String,Object> result) {}
    private final JdbcTemplate db;
    private final StudySessionService study;
    private final MemoryService memories;
    private final ConversationAccess access;
    private final ConversationDeletionService deletion;
    StudyFeedbackSource(JdbcTemplate db, StudySessionService study, MemoryService memories,
            ConversationAccess access, ConversationDeletionService deletion) {
        this.db=db; this.study=study; this.memories=memories; this.access=access; this.deletion=deletion;
    }
    private static void require(boolean value) { if(!value) throw new ApiException(409,"FEEDBACK_SOURCE_UNAVAILABLE"); }
    static long number(Map<String,Object> row,String field) { return ((Number)row.get(field)).longValue(); }
    static UUID id(Map<String,Object> row,String field) { return (UUID)row.get(field); }
    private Map<String,Object> identity(UUID owner,UUID studyId) {
        var rows=db.queryForList("SELECT * FROM study_sessions WHERE owner_id=? AND id=?",owner,studyId);
        if(rows.size()!=1) throw ApiException.missing(); return rows.getFirst();
    }
    ExecutionSource loadForAdmission(AuthService.Session session,UUID studyId,Target target) {
        var owner=access.lockSession(session); MemoryService.unpaused(owner);
        var identity=identity(session.owner(),studyId); var conversation=access.requireLive(session,id(identity,"conversation_id"));
        deletion.validateFeedbackGraph(session.owner(),id(conversation,"id"));
        return historical(session,conversation,studyId,target,true,owner);
    }
    /** Reads the independently selected row; no current-C or old submission-session shortcut. */
    private ExecutionSource historical(AuthService.Session session,Map<String,Object> conversation,UUID studyId,
            Target target,boolean executing,Map<String,Object> owner) {
        db.queryForObject("SELECT verify_study_history_v16(?,?)",Object.class,session.owner(),studyId);
        StudySessionService.Hydrated hydrated=study.hydrate(session.owner(),conversation,studyId);
        var row=hydrated.row();
        boolean open=number(row,"version")==3 && "OPEN".equals(row.get("lifecycle"));
        boolean closed=number(row,"version")==4 && "CLOSED_BY_OWNER".equals(row.get("lifecycle"));
        require(hydrated.inputs().size()==2 && (open || (!executing && closed)));
        require(!Objects.equals(row.get("question_1_id"),row.get("question_2_id")));
        var observed=db.queryForList("SELECT * FROM study_owner_answer_inputs WHERE owner_id=? AND id=? FOR UPDATE",session.owner(),target.input());
        require(observed.size()==1); var input=observed.getFirst();
        require(studyId.equals(input.get("study_session_id")) && conversation.get("id").equals(input.get("conversation_id"))
                && Objects.equals(conversation.get("project_id"),input.get("project_id")) && target.question().equals(input.get("question_id"))
                && target.attempt().equals(input.get("attempt_id"))
                && Set.of(row.get("question_1_id"),row.get("question_2_id")).contains(target.question()));
        require(new HashSet<>(List.of(conversation.get("id"),studyId,row.get("lesson_run_id"),input.get("id"),input.get("attempt_id"),row.get("question_1_id"),row.get("question_2_id"))).size()==7);
        require(hydrated.inputs().stream().filter(saved->saved.get("id").equals(input.get("id"))).count()==1);
        require(hydrated.inputs().stream().map(saved->saved.get("question_id")).distinct().count()==2);
        // Both originals have their own genuine ANSWER receipt; author-session liveness is historical data only.
        for(var saved:hydrated.inputs()) {
            require(Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM study_mutations WHERE owner_id=? AND study_session_id=? AND operation='ANSWER' AND input_id=? AND question_id=? AND attempt_id=? AND session_hash=? AND security_epoch=? AND prior_version=? AND resulting_version=? AND prior_conversation_version=?)",
                    Boolean.class,session.owner(),studyId,saved.get("id"),saved.get("question_id"),saved.get("attempt_id"),saved.get("session_hash"),saved.get("security_epoch"),saved.get("expected_study_version"),saved.get("resulting_study_version"),saved.get("observed_conversation_version"))));
            study.inputText(session.owner(),saved);
        }
        String text=study.inputText(session.owner(),input);
        StudySessionService.Lesson lesson=study.lesson(session.owner(),id(conversation,"id"),id(conversation,"project_id"),id(row,"lesson_run_id"));
        require(number(row,"lesson_run_version")==number(lesson.run(),"version"));
        access.lockSession(session);
        return new ExecutionSource(conversation,row,lesson,input,text,study.inputBinding(session.owner(),input,text),owner);
    }
    boolean quiescentForAdmission(UUID owner,UUID conversation) {
        try { deletion.validateFeedbackGraph(owner,conversation); return true; }
        catch(ApiException blocked) { if(blocked.status==409)return false; throw blocked; }
    }
    Map<String,Object> request(UUID owner,UUID feedback) {
        access.lockOwner(owner); var rows=db.queryForList("SELECT * FROM study_feedback_requests WHERE owner_id=? AND id=?",owner,feedback);
        if(rows.size()!=1) throw ApiException.missing(); return rows.getFirst();
    }
    ExecutionSource loadForExecution(UUID owner,UUID feedback) {
        var request=request(owner,feedback); var session=initiator(request);
        var current=access.lockSession(session); MemoryService.unpaused(current);
        var conversation=access.requireLive(session,id(request,"conversation_id"));
        require("ACTIVE".equals(request.get("output_authority")) && request.get("source_deleted_at")==null
                && Objects.equals(request.get("profile_version"),current.get("version"))
                && Objects.equals(request.get("context_epoch"),current.get("context_epoch"))
                && Objects.equals(request.get("history_epoch"),current.get("history_epoch")));
        if(request.get("project_id")!=null) require(Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM projects WHERE owner_id=? AND id=? AND version=? AND active)",Boolean.class,owner,request.get("project_id"),request.get("project_version"))));
        var source=historical(session,conversation,id(request,"study_session_id"),target(request),true,current);
        verifyBinding(owner,request,source); access.lockSession(session); return source;
    }
    RetainedFeedback loadForRead(AuthService.Session currentSession,UUID feedback) {
        var current=access.lockSession(currentSession); var request=request(currentSession.owner(),feedback);
        var conversation=access.requireLive(currentSession,id(request,"conversation_id"));
        deletion.validateStudyGraph(currentSession.owner(),id(conversation,"id"));
        require("ACCEPTED".equals(request.get("state")) && "CLOSED".equals(request.get("output_authority"))
                && request.get("source_deleted_at")==null && request.get("result_ref")!=null);
        var source=historical(currentSession,conversation,id(request,"study_session_id"),target(request),false,current);
        verifyBinding(currentSession.owner(),request,source);
        db.queryForObject("SELECT verify_study_feedback_history_v18(?,?)",Object.class,currentSession.owner(),feedback);
        String encoded=memories.vault.open(currentSession.owner(),feedback,"study-feedback-result",(String)request.get("result_ref"));
        var result=StudyFeedbackResult.projectStored(targetEnvelope(request),encoded);
        access.lockSession(currentSession); return new RetainedFeedback(request,result);
    }
    static AuthService.Session initiator(Map<String,Object> request) {
        return new AuthService.Session((String)request.get("initiating_session_hash"),id(request,"owner_id"),null,number(request,"security_epoch"));
    }
    static Target target(Map<String,Object> request) { return new Target(id(request,"question_id"),id(request,"input_id"),id(request,"attempt_id")); }
    static Map<String,Object> targetEnvelope(Map<String,Object> request) {
        var result=new LinkedHashMap<String,Object>(); result.put("feedback_id",request.get("id"));
        for(String field:List.of("study_session_id","lesson_run_id","question_id","input_id","attempt_id"))result.put(field,request.get(field));
        return result;
    }
    String binding(UUID owner,Map<String,Object> request,ExecutionSource source) {
        var identities=new ArrayList<Object>();
        for(String field:List.of("id","request_id","run_id","reservation_id","context_id","conversation_id","study_session_id","lesson_run_id","input_id","attempt_id"))identities.add(request.get(field));
        identities.add(source.study().get("question_1_id"));identities.add(source.study().get("question_2_id"));require(!identities.contains(null)&&new HashSet<>(identities).size()==identities.size());
        var value=new LinkedHashMap<String,Object>();
        for(String field:List.of("id","request_id","run_id","reservation_id","context_id","owner_id","conversation_id","project_id","project_version","study_session_id","admitted_study_version","lesson_run_id","lesson_run_version","question_id","input_id","attempt_id","initiating_session_hash","security_epoch","context_epoch","history_epoch","profile_version","timezone","admission_conversation_version")) value.put(field,request.get(field));
        value.put("lesson_envelope",source.lesson().encoded());
        var historical=new LinkedHashMap<String,Object>();
        for(String field:List.of("id","attempt_id","owner_id","conversation_id","project_id","study_session_id","question_id","expected_study_version","resulting_study_version","observed_conversation_version","session_hash","security_epoch")) historical.put(field,source.input().get(field));
        historical.put("created_at",AuthService.instant(source.input().get("created_at")).toString());
        historical.put("text",source.text()); historical.put("input_binding",source.originalBinding()); value.put("historical_input",historical);
        require("UTC".equals(request.get("timezone")) && number(request,"admitted_study_version")==3
                && Objects.equals(request.get("lesson_run_version"),source.study().get("lesson_run_version")));
        return memories.keys.digest(owner,"study-feedback:source-v1",Json.canonical(value));
    }
    String sealBinding(UUID owner,Map<String,Object> request,ExecutionSource source) {
        return memories.vault.seal(owner,id(request,"id"),"study-feedback-source-binding",binding(owner,request,source));
    }
    private void verifyBinding(UUID owner,Map<String,Object> request,ExecutionSource source) {
        require(request.get("source_binding_ref")!=null);
        try { require(Json.equal(binding(owner,request,source),memories.vault.open(owner,id(request,"id"),"study-feedback-source-binding",(String)request.get("source_binding_ref")))); }
        catch(RuntimeException invalid) { throw new ApiException(409,"FEEDBACK_SOURCE_UNAVAILABLE"); }
    }
}

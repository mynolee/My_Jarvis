package dev.myjarvis.core;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;

@Service
final class WorkflowService {
    final JdbcTemplate db; final TransactionTemplate tx;final Vault vault; final ConversationReplyWriter replies;
    static final String POLICY="m0-v1";
    WorkflowService(JdbcTemplate db,TransactionTemplate tx,Vault vault,ConversationReplyWriter replies){this.db=db;this.tx=tx;this.vault=vault;this.replies=replies;}
    Map<String,Object> createConversation(UUID owner,JsonNode body){
        Json.fields(body,"title","project_id");String title=Json.string(body,"title",200);UUID project=body.hasNonNull("project_id")?Json.uuid(body,"project_id"):null;
        return tx.execute(s->{if(project!=null)owned("projects",owner,project);UUID id=UUID.randomUUID();db.update("INSERT INTO conversations(id,owner_id,project_id,title) VALUES (?,?,?,?)",id,owner,project,title);return conversation(owned("conversations",owner,id));});
    }
    List<Map<String,Object>> conversations(UUID owner){return db.queryForList("SELECT * FROM conversations WHERE owner_id=? ORDER BY created_at DESC,id LIMIT 100",owner).stream().map(this::conversation).toList();}
    private Map<String,Object> conversation(Map<String,Object> row){Map<String,Object> m=new LinkedHashMap<>();for(String k:List.of("id","title","project_id"))m.put(k,row.get(k));m.put("created_at",AuthService.instant(row.get("created_at")).toString());return m;}
    /** Authenticated HTTP admission cannot survive an account epoch change while waiting. */
    Map<String,Object> accept(AuthService.Session session,UUID conversation,JsonNode body){
        AuthService.requireOwner(session);
        return tx.execute(s->{
            var owners=db.queryForList("SELECT security_epoch FROM owners WHERE id=? FOR UPDATE",session.owner());
            if(owners.isEmpty()||session.epoch()!=((Number)owners.getFirst().get("security_epoch")).longValue())throw new ApiException(401,"SESSION_REVOKED");
            if(db.queryForList("SELECT token_hash FROM sessions WHERE token_hash=? AND owner_id=? FOR UPDATE",session.hash(),session.owner()).isEmpty())throw new ApiException(401,"SESSION_REVOKED");
            executionStatus(session);return accept(session.owner(),conversation,body);
        });
    }
    Map<String,Object> accept(UUID owner,UUID conversation,JsonNode body){
        Json.fields(body,"client_message_id","text","attachment_ids");UUID client=Json.uuid(body,"client_message_id");String text=Json.string(body,"text",8000);
        if(!body.has("attachment_ids")||!body.get("attachment_ids").isArray()||!body.get("attachment_ids").isEmpty())throw new ApiException(400,"ATTACHMENTS_DISABLED");
        String hash=Json.hash(Json.canonical(Map.of("conversation_id",conversation.toString(),"body",body)));
        return tx.execute(s->{
            lockOwner(owner);owned("conversations",owner,conversation);
            var prior=db.queryForList("SELECT body_hash,run_id FROM messages WHERE owner_id=? AND client_message_id=?",owner,client);
            if(!prior.isEmpty()){
                if(!Json.equal(hash,(String)prior.getFirst().get("body_hash")))throw new ApiException(409,"IDEMPOTENCY_CONFLICT");
                UUID run=(UUID)prior.getFirst().get("run_id");return Map.of("run_id",run,"status",owned("runs",owner,run).get("status"));
            }
            var ownerRow=owned("owners",owner,owner);if(Boolean.TRUE.equals(ownerRow.get("execution_paused")))throw new ApiException(409,"EXECUTION_PAUSED");
            UUID run=UUID.randomUUID(),message=UUID.randomUUID();
            db.update("INSERT INTO runs(id,owner_id,conversation_id,status) VALUES (?,?,?,'ACCEPTED')",run,owner,conversation);
            db.update("UPDATE runs SET history_epoch=? WHERE owner_id=? AND id=?",ownerRow.get("history_epoch"),owner,run);
            db.update("INSERT INTO messages(id,owner_id,conversation_id,run_id,client_message_id,body_hash,content_ref,role,trust) VALUES (?,?,?,?,?,?,?,'USER','USER_DIRECT')",message,owner,conversation,run,client,hash,vault.seal(owner,message,"message",text));
            job(owner,run,null,"PLAN");event(owner,run,"RUN_ACCEPTED");return Map.of("run_id",run,"status","ACCEPTED");
        });
    }
    List<Map<String,Object>> messages(UUID owner,UUID conversation){
        owned("conversations",owner,conversation);
        return db.queryForList("SELECT * FROM messages WHERE owner_id=? AND conversation_id=? ORDER BY created_at,id LIMIT 100",owner,conversation).stream().map(r->{Map<String,Object> m=new LinkedHashMap<>();for(String key:List.of("id","run_id","role"))m.put(key,r.get(key));m.put("text",vault.open(owner,(UUID)r.get("id"),"message",(String)r.get("content_ref")));m.put("created_at",AuthService.instant(r.get("created_at")).toString());return m;}).toList();
    }
    Map<String,Object> run(UUID owner,UUID id){
        var r=owned("runs",owner,id);Map<String,Object> result=new LinkedHashMap<>();for(String k:List.of("id","status","version"))result.put(k,r.get(k));
        result.put("execution_mode",Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM inference_reservations WHERE owner_id=? AND run_id=?)",Boolean.class,owner,id))?"LOCAL_INFERENCE":"MOCK");
        result.put("result",r.get("result_ref")==null?null:vault.open(owner,id,"result",(String)r.get("result_ref")));
        result.put("approval_id",db.queryForList("SELECT p.id FROM approvals p JOIN action_proposals a ON a.id=p.action_id AND a.owner_id=p.owner_id WHERE a.owner_id=? AND a.run_id=? ORDER BY p.id LIMIT 1",owner,id).stream().findFirst().map(x->x.get("id")).orElse(null));
        result.put("job_id",db.queryForList("SELECT id FROM jobs WHERE owner_id=? AND run_id=? ORDER BY created_at DESC LIMIT 1",owner,id).stream().findFirst().map(x->x.get("id")).orElse(null));return result;
    }
    Map<String,Object> approval(UUID owner,UUID id){
        var r=owned("approvals",owner,id);var a=owned("action_proposals",owner,(UUID)r.get("action_id"));
        Map<String,Object> out=new LinkedHashMap<>();for(String k:List.of("id","action_id","action_version","payload_hash","status"))out.put(k,r.get(k));out.put("expires_at",AuthService.instant(r.get("expires_at")).toString());
        out.put("payload",Json.parse(vault.open(owner,(UUID)a.get("id"),"action",(String)a.get("payload_ref")).getBytes(java.nio.charset.StandardCharsets.UTF_8)));return out;
    }
    Map<String,Object> decision(AuthService.Session session,UUID id,JsonNode body){
        Json.fields(body,"decision","expected_action_version","payload_hash");String decision=Json.string(body,"decision",7),hash=Json.string(body,"payload_hash",64);long version=Json.integer(body,"expected_action_version");
        if(!Set.of("APPROVE","REJECT").contains(decision)||!hash.matches("[a-f0-9]{64}"))throw ApiException.bad();
        AuthService.requireRecent(session);UUID owner=session.owner();
        return tx.execute(s->{
            lockOwner(owner);var ownerRow=owned("owners",owner,owner);
            validateSession(session,ownerRow);
            var r=locked("approvals",owner,id);var a=locked("action_proposals",owner,(UUID)r.get("action_id"));UUID action=(UUID)a.get("id"),run=(UUID)a.get("run_id");
            if(!"PENDING".equals(r.get("status")))throw new ApiException(409,"APPROVAL_NOT_PENDING");
            if(!"WAITING_APPROVAL".equals(a.get("status")))throw new ApiException(409,"PRECONDITION_CHANGED");
            if(Boolean.TRUE.equals(ownerRow.get("execution_paused"))||((Number)ownerRow.get("security_epoch")).longValue()!=((Number)a.get("security_epoch")).longValue())throw new ApiException(409,"PRECONDITION_CHANGED");
            if(!AuthService.instant(r.get("expires_at")).isAfter(Instant.now()))throw new ApiException(409,"APPROVAL_EXPIRED");
            String snapshot=vault.open(owner,action,"action",(String)a.get("payload_ref"));
            if(version!=((Number)a.get("version")).longValue()||version!=((Number)r.get("action_version")).longValue()||!Json.equal(hash,(String)a.get("payload_hash"))||!Json.equal(hash,(String)r.get("payload_hash"))||!Json.equal(hash,Json.hash(snapshot)))throw new ApiException(409,"PAYLOAD_CHANGED");
            if(decision.equals("REJECT")){
                db.update("UPDATE approvals SET status='REJECTED' WHERE id=?",id);db.update("UPDATE action_proposals SET status='CANCELLED',version=version+1 WHERE id=?",action);setRun(owner,run,"CANCELLED",null);event(owner,run,"APPROVAL_REJECTED");return Map.of("status","REJECTED");
            }
            db.update("UPDATE approvals SET status='CONSUMED',consumed_at=now(),approved_session_hash=? WHERE id=?",session.hash(),id);
            db.update("INSERT INTO authorizations(id,owner_id,action_id,approval_id,payload_hash,security_epoch,valid_until) VALUES (?,?,?,?,?,?,?)",UUID.randomUUID(),owner,action,id,hash,a.get("security_epoch"),a.get("valid_until"));
            UUID job=job(owner,run,action,"EXECUTE");db.update("UPDATE action_proposals SET status='QUEUED',version=version+1 WHERE id=?",action);setRun(owner,run,"QUEUED",null);event(owner,run,"APPROVAL_CONSUMED");return Map.of("status","CONSUMED","job_id",job);
        });
    }
    Map<String,Object> cancel(UUID owner,UUID run,long version){return tx.execute(s->{lockOwner(owner);var r=locked("runs",owner,run);if(((Number)r.get("version")).longValue()!=version)throw new ApiException(409,"PRECONDITION_CHANGED");
        if(Set.of("SUCCEEDED","PARTIAL_SUCCESS","FAILED","CANCELLED","EXPIRED","BLOCKED").contains(r.get("status")))return run(owner,run);
        boolean inFlight=Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM jobs WHERE owner_id=? AND run_id=? AND started_effect=true AND status IN ('RUNNING','UNKNOWN'))",Boolean.class,owner,run));
        db.update("UPDATE jobs SET status='CANCELLED',lease_generation=lease_generation+1 WHERE owner_id=? AND run_id=? AND started_effect=false AND status IN ('QUEUED','RUNNING')",owner,run);
        db.update("UPDATE approvals SET status='REVOKED' WHERE owner_id=? AND action_id IN (SELECT id FROM action_proposals WHERE owner_id=? AND run_id=?) AND status='PENDING'",owner,owner,run);
        inFlight=inFlight||Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM inference_reservations WHERE owner_id=? AND run_id=? AND status IN ('STARTED','UNKNOWN'))",Boolean.class,owner,run));
        db.update("UPDATE inference_reservations SET cancel_requested_at=now(),status=CASE WHEN status='QUEUED' THEN 'CANCELLED' ELSE 'UNKNOWN' END WHERE owner_id=? AND run_id=? AND status IN ('QUEUED','STARTED','UNKNOWN')",owner,run);
        db.update("UPDATE runs SET cancel_requested_at=now() WHERE id=?",run);setRun(owner,run,inFlight?"UNKNOWN":"CANCELLED",null);event(owner,run,"CANCEL_REQUESTED");return run(owner,run);
    });}
    static final long EXECUTION_MAX=9007199254740991L;

    /** One database observation; no profile, session, epoch, work payload, or mutation attribution. */
    Map<String,Object> executionStatus(AuthService.Session session){
        AuthService.requireOwner(session);
        var rows=db.queryForList("WITH checked AS MATERIALIZED (SELECT clock_timestamp() AS at) SELECT o.execution_paused,o.version,checked.at AS checked_at FROM owners o JOIN sessions s ON s.owner_id=o.id CROSS JOIN checked WHERE o.id=? AND o.security_epoch=? AND s.token_hash=? AND s.security_epoch=o.security_epoch AND s.revoked_at IS NULL AND s.created_at>checked.at-interval '12 hours' AND s.last_seen_at>checked.at-interval '2 hours'",session.owner(),session.epoch(),session.hash());
        if(rows.isEmpty())throw new ApiException(401,"SESSION_REVOKED");return executionView(rows.getFirst());
    }
    private Map<String,Object> executionView(Map<String,Object> row){
        long version=((Number)row.get("version")).longValue();if(version<1||version>EXECUTION_MAX)throw new ApiException(409,"VERSION_EXHAUSTED");
        return Map.of("execution_paused",row.get("execution_paused"),"version",version,"checked_at",AuthService.instant(row.get("checked_at")).toString());
    }
    Map<String,Object> executionTransition(AuthService.Session session,boolean paused,JsonNode body){
        if(body==null)throw ApiException.bad();Json.fields(body,"expected_version");long expected=Json.integer(body,"expected_version");
        if(expected<1||expected>EXECUTION_MAX)throw ApiException.bad();AuthService.requireOwner(session);
        return tx.execute(s->executionTransitionLocked(session,paused,expected));
    }
    /** Internal fixture compatibility only. HTTP callers must supply their displayed expected_version. */
    void pause(AuthService.Session session,boolean paused){
        AuthService.requireOwner(session);tx.executeWithoutResult(s->{
            var owners=db.queryForList("SELECT version FROM owners WHERE id=? FOR UPDATE",session.owner());
            if(owners.isEmpty())throw new ApiException(401,"SESSION_REVOKED");
            executionTransitionLocked(session,paused,((Number)owners.getFirst().get("version")).longValue());
        });
    }
    private Map<String,Object> executionTransitionLocked(AuthService.Session session,boolean paused,long expected){
        // Owner first, then session. Capture the database time only after all lock waits.
        var owners=db.queryForList("SELECT execution_paused,version,security_epoch FROM owners WHERE id=? FOR UPDATE",session.owner());
        if(owners.isEmpty()||session.epoch()!=((Number)owners.getFirst().get("security_epoch")).longValue())throw new ApiException(401,"SESSION_REVOKED");
        var sessions=db.queryForList("SELECT * FROM sessions WHERE token_hash=? AND owner_id=? FOR UPDATE",session.hash(),session.owner());
        Instant now=AuthService.instant(db.queryForObject("SELECT clock_timestamp()",Object.class));
        if(sessions.isEmpty())throw new ApiException(401,"SESSION_REVOKED");var live=sessions.getFirst();
        if(live.get("revoked_at")!=null||live.get("security_epoch")==null||((Number)live.get("security_epoch")).longValue()!=session.epoch()
                ||!AuthService.instant(live.get("created_at")).isAfter(now.minusSeconds(43200))||!AuthService.instant(live.get("last_seen_at")).isAfter(now.minusSeconds(7200)))throw new ApiException(401,"SESSION_REVOKED");
        Instant reauth=AuthService.instant(live.get("reauthenticated_at"));
        if(reauth==null||reauth.isBefore(now.minusSeconds(300))||reauth.isAfter(now))throw new ApiException(403,"REAUTHENTICATION_REQUIRED");
        var owner=owners.getFirst();long version=((Number)owner.get("version")).longValue(),epoch=((Number)owner.get("security_epoch")).longValue();
        if(version!=expected)throw new ApiException(409,"PRECONDITION_CHANGED");
        // A repeated snapshot is stale even if its desired state happens to match again.
        if(Boolean.TRUE.equals(owner.get("execution_paused"))==paused)return executionSnapshot(session.owner());
        if(version<1||version>=EXECUTION_MAX||epoch<1||epoch>=EXECUTION_MAX)throw new ApiException(409,"VERSION_EXHAUSTED");
        int changed=db.update("UPDATE owners SET execution_paused=?,security_epoch=security_epoch+1,version=version+1 WHERE id=? AND version=? AND security_epoch=?",paused,session.owner(),expected,session.epoch());
        if(changed!=1)throw new ApiException(409,"PRECONDITION_CHANGED");
        if(paused)invalidateExecution(session.owner());
        db.update("UPDATE sessions SET security_epoch=? WHERE token_hash=? AND owner_id=?",epoch+1,session.hash(),session.owner());
        db.update("INSERT INTO audit_events(id,owner_id,event_type,resource_id) VALUES (?,?,?,?)",UUID.randomUUID(),session.owner(),paused?"EXECUTION_PAUSED":"EXECUTION_RESUMED",session.owner());
        return executionSnapshot(session.owner());
    }
    private Map<String,Object> executionSnapshot(UUID owner){return executionView(db.queryForMap("SELECT execution_paused,version,clock_timestamp() AS checked_at FROM owners WHERE id=?",owner));}
    private void invalidateExecution(UUID owner){
        // Fence every active lease. Started work remains uncertain; this is not external cancellation.
        db.update("UPDATE jobs SET status=CASE WHEN started_effect THEN 'UNKNOWN' ELSE 'CANCELLED' END,lease_generation=lease_generation+1,lease_owner=NULL,lease_until=NULL WHERE owner_id=? AND status IN ('QUEUED','RUNNING')",owner);
        db.update("UPDATE approvals SET status='REVOKED' WHERE owner_id=? AND status='PENDING'",owner);
        db.update("UPDATE action_executions SET status='UNKNOWN' WHERE owner_id=? AND status='STARTED'",owner);
        db.update("UPDATE action_proposals SET status=CASE WHEN status='EXECUTING' THEN 'UNKNOWN' ELSE 'CANCELLED' END,version=version+1 WHERE owner_id=? AND status IN ('WAITING_APPROVAL','QUEUED','EXECUTING')",owner);
        db.update("UPDATE inference_reservations SET cancel_requested_at=clock_timestamp(),status=CASE WHEN status='QUEUED' THEN 'CANCELLED' ELSE 'UNKNOWN' END WHERE owner_id=? AND status IN ('QUEUED','STARTED')",owner);
        db.update("UPDATE inference_contexts SET state='INVALIDATED',invalidated_at=clock_timestamp() WHERE owner_id=? AND state IN ('CAPTURED','PREPARED','DISPATCHED')",owner);
        db.update("UPDATE runs r SET status=CASE WHEN r.status='RUNNING' OR EXISTS(SELECT 1 FROM jobs j WHERE j.owner_id=r.owner_id AND j.run_id=r.id AND j.started_effect AND j.status='UNKNOWN') OR EXISTS(SELECT 1 FROM inference_reservations i WHERE i.owner_id=r.owner_id AND i.run_id=r.id AND i.status='UNKNOWN') THEN 'UNKNOWN' ELSE 'CANCELLED' END,cancel_requested_at=clock_timestamp(),version=version+1 WHERE r.owner_id=? AND r.status IN ('ACCEPTED','PLANNING','WAITING_APPROVAL','QUEUED','RUNNING','CANCEL_REQUESTED')",owner);
    }
    private void validateSession(AuthService.Session session,Map<String,Object> ownerRow){
        AuthService.requireRecent(session);
        var rows=db.queryForList("SELECT token_hash FROM sessions WHERE token_hash=? AND owner_id=? AND revoked_at IS NULL AND security_epoch=? AND created_at>now()-interval '12 hours' AND last_seen_at>now()-interval '2 hours' AND reauthenticated_at>now()-interval '5 minutes' FOR UPDATE",session.hash(),session.owner(),ownerRow.get("security_epoch"));
        if(rows.isEmpty()||session.epoch()!=((Number)ownerRow.get("security_epoch")).longValue())throw new ApiException(401,"SESSION_REVOKED");
    }
    UUID job(UUID owner,UUID run,UUID action,String kind){UUID id=UUID.randomUUID();db.update("INSERT INTO jobs(id,owner_id,kind,logical_key,run_id,action_id,status) VALUES (?,?,?,?,?,?,'QUEUED')",id,owner,kind,kind+":"+(action==null?run:action),run,action);return id;}
    void setRun(UUID owner,UUID run,String status,String result){
        int changed=db.update("UPDATE runs SET status=?,version=version+1,result_ref=COALESCE(?,result_ref) WHERE owner_id=? AND id=?",status,result==null?null:vault.seal(owner,run,"result",result),owner,run);
        if(changed==1 && result!=null && Set.of("SUCCEEDED","WAITING_INPUT").contains(status))replies.appendAccepted(owner,run);
    }
    void event(UUID owner,UUID run,String type){db.update("INSERT INTO audit_events(id,owner_id,event_type,resource_id) VALUES (?,?,?,?)",UUID.randomUUID(),owner,type,run);db.update("INSERT INTO outbox_events(id,owner_id,aggregate_id,event_type) VALUES (?,?,?,?)",UUID.randomUUID(),owner,run,type);db.update("INSERT INTO stream_events(owner_id,conversation_id,run_id,event_type) SELECT owner_id,conversation_id,id,? FROM runs WHERE owner_id=? AND id=?",type,owner,run);}
    void lockOwner(UUID owner){db.queryForObject("SELECT id FROM owners WHERE id=? FOR UPDATE",UUID.class,owner);}
    Map<String,Object> owned(String table,UUID owner,UUID id){return fetch(table,owner,id,false);}
    Map<String,Object> locked(String table,UUID owner,UUID id){return fetch(table,owner,id,true);}
    private Map<String,Object> fetch(String table,UUID owner,UUID id,boolean lock){if(!Set.of("owners","projects","conversations","runs","approvals","action_proposals").contains(table))throw new IllegalArgumentException();var rows=db.queryForList("SELECT * FROM "+table+" WHERE "+(table.equals("owners")?"id":"owner_id")+"=? AND id=?"+(lock?" FOR UPDATE":""),owner,id);if(rows.isEmpty())throw ApiException.missing();return rows.getFirst();}
}

package dev.myjarvis.core;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Narrow explicit-UI task authority. Never accepts model messages, grants or tool proposals. */
@Service
final class TaskIntentService {
    final JdbcTemplate db;
    final TransactionTemplate tx;
    final Vault vault;
    TaskIntentService(JdbcTemplate db,TransactionTemplate tx,Vault vault){this.db=db;this.tx=new TransactionTemplate(Objects.requireNonNull(tx.getTransactionManager()));this.tx.setTimeout(15);this.vault=vault;}

    Map<String,Object> issue(AuthService.Session session,UUID key,JsonNode body){
        Json.fields(body,"tool_id","target_id","arguments");
        if(body.size()!=3)throw ApiException.bad();
        String tool=Json.string(body,"tool_id",32);
        boolean create="task.create".equals(tool);
        if(!create&&!"task.update".equals(tool))throw ApiException.bad();
        if(create&&!body.get("target_id").isNull())throw ApiException.bad();
        UUID suppliedTarget=create?null:Json.uuid(body,"target_id");
        JsonNode args=body.get("arguments");TaskService.validateArguments(args,create);
        String requestHash=Json.hash(Json.canonical(body));
        return tx.execute(transaction->{
            var owner=lockSession(session);
            var prior=db.queryForList("SELECT * FROM task_user_intents WHERE owner_id=? AND issuance_key=?",session.owner(),key);
            if(!prior.isEmpty()){
                var row=prior.getFirst();validateBinding(session,row);
                if(!Json.equal(requestHash,(String)row.get("issuance_hash")))throw new ApiException(409,"IDEMPOTENCY_CONFLICT");
                return readResponse(session.owner(),(UUID)row.get("id"),"task-intent",(String)row.get("response_ref"));
            }
            requireUnpaused(owner);
            TaskService.validateProject(db,session.owner(),args);
            if(!create){var current=TaskService.owned(db,session.owner(),suppliedTarget);TaskService.checkVersion(vault,current,Json.integer(args,"expected_version"));}
            UUID id=UUID.randomUUID(),target=create?UUID.randomUUID():suppliedTarget;
            String hash=payloadHash(tool,target,args);Instant expiry=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS).plusSeconds(300);
            Map<String,Object> response=new LinkedHashMap<>();
            response.put("id",id);response.put("tool_id",tool);response.put("target_id",target);
            response.put("arguments",args);response.put("payload_hash",hash);response.put("expires_at",expiry.toString());response.put("status","ISSUED");
            db.update("INSERT INTO task_user_intents(id,owner_id,session_hash,security_epoch,issuance_key,issuance_hash,response_ref,tool_id,target_id,expected_version,payload_hash,expires_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                id,session.owner(),session.hash(),session.epoch(),key,requestHash,
                vault.seal(session.owner(),id,"task-intent",Json.MAPPER.writeValueAsString(response)),tool,target,
                create?null:Json.integer(args,"expected_version"),hash,java.sql.Timestamp.from(expiry));
            audit(session.owner(),"TASK_INTENT_ISSUED",id);
            return response;
        });
    }

    /** Caller must remain in this owner-locked transaction through audit and mutation commit. */
    Map<String,Object> lockSession(AuthService.Session session){
        AuthService.requireOwner(session);
        var owners=db.queryForList("SELECT * FROM owners WHERE id=? FOR UPDATE",session.owner());
        if(owners.isEmpty())throw new ApiException(401,"SESSION_REVOKED");
        var owner=owners.getFirst();
        if(session.epoch()!=((Number)owner.get("security_epoch")).longValue())throw new ApiException(401,"SESSION_REVOKED");
        var sessions=db.queryForList("SELECT token_hash FROM sessions WHERE token_hash=? AND owner_id=? AND security_epoch=? AND revoked_at IS NULL AND created_at>clock_timestamp()-interval '12 hours' AND last_seen_at>clock_timestamp()-interval '2 hours' FOR UPDATE",session.hash(),session.owner(),session.epoch());
        if(sessions.isEmpty())throw new ApiException(401,"SESSION_REVOKED");
        return owner;
    }
    static void requireUnpaused(Map<String,Object> owner){if(Boolean.TRUE.equals(owner.get("execution_paused")))throw new ApiException(409,"EXECUTION_PAUSED");}
    static void validateBinding(AuthService.Session session,Map<String,Object> row){
        if(!Json.equal(session.hash(),(String)row.get("session_hash"))||session.epoch()!=((Number)row.get("security_epoch")).longValue())throw new ApiException(403,"INTENT_NOT_VALID");
    }
    Map<String,Object> intent(AuthService.Session session,UUID id){
        var rows=db.queryForList("SELECT * FROM task_user_intents WHERE owner_id=? AND id=? FOR UPDATE",session.owner(),id);
        if(rows.isEmpty())throw ApiException.missing();
        var row=rows.getFirst();validateBinding(session,row);return row;
    }
    void verify(Map<String,Object> intent,String tool,UUID target,JsonNode args){
        if(intent.get("consumed_at")!=null)throw new ApiException(409,"INTENT_CONSUMED");
        if(!AuthService.instant(intent.get("expires_at")).isAfter(Instant.now()))throw new ApiException(409,"INTENT_EXPIRED");
        if(!tool.equals(intent.get("tool_id"))||!target.equals(intent.get("target_id"))||!Json.equal(payloadHash(tool,target,args),(String)intent.get("payload_hash")))throw new ApiException(403,"INTENT_NOT_VALID");
    }
    static String payloadHash(String tool,UUID target,JsonNode args){return Json.hash(Json.canonical(Map.of("tool_id",tool,"target_id",target.toString(),"arguments",args)));}
    @SuppressWarnings("unchecked")
    Map<String,Object> readResponse(UUID owner,UUID id,String purpose,String encrypted){
        return Json.MAPPER.convertValue(Json.parse(vault.open(owner,id,purpose,encrypted).getBytes(StandardCharsets.UTF_8)),Map.class);
    }
    void audit(UUID owner,String type,UUID id){db.update("INSERT INTO audit_events(id,owner_id,event_type,resource_id) VALUES (?,?,?,?)",UUID.randomUUID(),owner,type,id);}
}

package dev.myjarvis.core;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Data-only selections. Plaintext exists only in the returned in-process request. */
@Service
final class InferenceContextService {
    private final JdbcTemplate db;private final MemoryService memories;private final OwnerProfileService profiles;
    private final Vault vault;private final InferenceContextFreshness freshness;
    InferenceContextService(JdbcTemplate db,MemoryService memories,OwnerProfileService profiles,Vault vault,InferenceContextFreshness freshness){this.db=db;this.memories=memories;this.profiles=profiles;this.vault=vault;this.freshness=freshness;}
    String requestDigest(UUID owner,String canonical){return memories.keys.digest(owner,"inference-context-request",canonical);}
    String controlDigest(UUID owner,String domain,String canonical){
        if(!Set.of("inference-control-origin-v1","inference-control-dispatch-v1").contains(domain))throw new IllegalArgumentException("Unsupported control digest domain");
        return memories.keys.digest(owner,domain,canonical);
    }
    void authorize(AuthService.Session session,UUID conversation){
        InferenceContextFreshness.transaction();var access=new ConversationAccess(db);var owner=access.lockSession(session);
        var row=access.requireLive(session,conversation);MemoryService.unpaused(owner);
        memories.project(session.owner(),(UUID)row.get("project_id"));if(row.get("project_id")!=null&&db.queryForList("SELECT id FROM projects WHERE owner_id=? AND id=? AND active",session.owner(),row.get("project_id")).isEmpty())throw ApiException.missing();
    }
    UUID capture(AuthService.Session session,UUID conversation,UUID run){
        authorize(session,conversation);var owner=db.queryForMap("SELECT * FROM owners WHERE id=?",session.owner());
        var runs=db.queryForList("SELECT id FROM runs WHERE owner_id=? AND id=? AND conversation_id=? AND status='QUEUED'",session.owner(),run,conversation);if(runs.isEmpty())throw ApiException.missing();
        UUID project=db.queryForObject("SELECT project_id FROM conversations WHERE owner_id=? AND id=?",UUID.class,session.owner(),conversation);
        Object version=project==null?null:db.queryForObject("SELECT version FROM projects WHERE owner_id=? AND id=?",Long.class,session.owner(),project);UUID id=UUID.randomUUID();
        db.update("INSERT INTO inference_contexts(id,owner_id,run_id,conversation_id,project_id,project_version,session_hash,security_epoch,context_epoch,history_epoch,state) VALUES (?,?,?,?,?,?,?,?,?,?,'CAPTURED')",id,session.owner(),run,conversation,project,version,session.hash(),session.epoch(),owner.get("context_epoch"),owner.get("history_epoch"));
        db.update("UPDATE runs SET history_epoch=? WHERE owner_id=? AND id=?",owner.get("history_epoch"),session.owner(),run);
        // Capture actual accepted transcript pairs now. Later completions cannot enter this anchor.
        var pairs=db.queryForList("SELECT r.id,u.id AS user_id,a.id AS assistant_id,u.causal_sequence FROM runs r JOIN messages u ON u.owner_id=r.owner_id AND u.run_id=r.id AND u.conversation_id=r.conversation_id AND u.role='USER' AND u.trust='USER_DIRECT' JOIN messages a ON a.owner_id=r.owner_id AND a.run_id=r.id AND a.conversation_id=r.conversation_id AND a.role='ASSISTANT' AND a.trust='MODEL_PROPOSAL' WHERE r.owner_id=? AND r.conversation_id=? AND r.id<>? AND u.causal_sequence<(SELECT min(current_message.causal_sequence) FROM messages current_message WHERE current_message.owner_id=r.owner_id AND current_message.run_id=? AND current_message.role='USER') AND r.status IN ('SUCCEEDED','WAITING_INPUT') AND r.cancel_requested_at IS NULL AND r.history_epoch=? AND r.context_history_eligible AND r.content_deleted_at IS NULL AND u.content_deleted_at IS NULL AND a.content_deleted_at IS NULL AND NOT EXISTS (SELECT 1 FROM inference_contexts previous WHERE previous.owner_id=r.owner_id AND previous.run_id=r.id AND previous.state<>'SETTLED') AND (SELECT count(*) FROM messages x WHERE x.owner_id=r.owner_id AND x.run_id=r.id AND x.role='USER')=1 ORDER BY u.causal_sequence DESC LIMIT 8",session.owner(),conversation,run,run,owner.get("history_epoch"));
        Collections.reverse(pairs);int ordinal=0;for(var pair:pairs)for(String role:List.of("USER","ASSISTANT"))db.update("INSERT INTO inference_context_history(context_id,ordinal,source_id,message_id,run_id,role) VALUES (?,?,?,?,?,?)",id,ordinal++,UUID.randomUUID(),pair.get(role.equals("USER")?"user_id":"assistant_id"),pair.get("id"),role);
        return id;
    }
    Map<String,Object> prepare(UUID owner,UUID run){
        if(!freshness.valid(owner,run))throw new ApiException(409,"STALE_INFERENCE_CONTEXT");var c=db.queryForMap("SELECT * FROM inference_contexts WHERE owner_id=? AND run_id=?",owner,run);
        if(!"CAPTURED".equals(c.get("state")))throw new ApiException(409,"CONTEXT_ALREADY_PREPARED");
        var session=new AuthService.Session((String)c.get("session_hash"),owner,null,((Number)c.get("security_epoch")).longValue());authorize(session,(UUID)c.get("conversation_id"));UUID project=(UUID)c.get("project_id");
        var profile=profiles.get(session);String timezone=Objects.toString(profile.get("timezone"),"");
        if(!timezone.equals("UTC")&&(!timezone.matches("[A-Za-z0-9._+-]+(?:/[A-Za-z0-9._+-]+)+")||timezone.startsWith("SystemV/")||timezone.length()>80))throw new IllegalStateException("Invalid profile timezone");ZoneId.of(timezone).getRules();
        Object profileVersion=db.queryForObject("SELECT CASE WHEN profile_ref IS NULL THEN NULL ELSE version END FROM owners WHERE id=?",Long.class,owner);
        // Keep the explicit selector's project override and tombstone masking before its cap.
        var rows=db.queryForList("SELECT m.*,s.scope,s.project_id FROM memories m JOIN memory_slots s ON s.owner_id=m.owner_id AND s.id=m.slot_id WHERE m.owner_id=? AND m.deleted_at IS NULL AND s.current_memory_id=m.id AND NOT s.tombstone AND s.generation=m.generation AND ((s.scope='PROJECT' AND s.project_id=?) OR (s.scope='GLOBAL' AND NOT EXISTS (SELECT 1 FROM memory_slots p WHERE p.owner_id=s.owner_id AND p.scope='PROJECT' AND p.project_id=? AND p.key_digest=s.key_digest AND (p.tombstone OR p.current_memory_id IS NOT NULL)))) ORDER BY CASE WHEN s.scope='PROJECT' THEN 0 ELSE 1 END,m.updated_at DESC,m.id ASC LIMIT 20",owner,project,project);
        List<Map<String,Object>> selected=new ArrayList<>();for(var row:rows){
            UUID source=UUID.randomUUID();var content=memories.payload(row);Map<String,Object> item=new LinkedHashMap<>();item.put("source_id",source);for(String f:List.of("id","version","generation","scope","project_id","kind"))item.put(f,row.get(f));item.put("key",content.get("key"));item.put("value",content.get("value"));for(String f:List.of("confirmed_at","updated_at"))item.put(f,AuthService.instant(row.get(f)).toString());selected.add(item);
            db.update("INSERT INTO inference_context_memories(context_id,source_id,memory_id,slot_id,version,generation) VALUES (?,?,?,?,?,?)",c.get("id"),source,row.get("id"),row.get("slot_id"),row.get("version"),row.get("generation"));
        }
        List<Map<String,Object>> history=new ArrayList<>();for(var row:db.queryForList("SELECT h.source_id,m.* FROM inference_context_history h JOIN messages m ON m.id=h.message_id AND m.owner_id=? WHERE h.context_id=? ORDER BY h.ordinal",owner,c.get("id"))){Map<String,Object> item=new LinkedHashMap<>();for(String f:List.of("source_id","id","run_id","role","trust"))item.put(f,row.get(f));item.put("text",vault.open(owner,(UUID)row.get("id"),"message",(String)row.get("content_ref")));item.put("created_at",AuthService.instant(row.get("created_at")).toString());history.add(item);}
        Map<String,Object> descriptor=new LinkedHashMap<>();descriptor.put("id",c.get("id"));descriptor.put("revision",1);for(String f:List.of("conversation_id","context_epoch","history_epoch"))descriptor.put(f,c.get(f));descriptor.put("profile_version",profileVersion);
        Map<String,Object> result=new LinkedHashMap<>();result.put("context",descriptor);result.put("project_context",project==null?null:Map.of("id",project,"version",c.get("project_version")));result.put("memories",selected);result.put("history",history);result.put("timezone",timezone);result.put("current_time",AuthService.instant(db.queryForObject("SELECT clock_timestamp()",Object.class)).toString());
        if(Json.canonical(selected).getBytes(StandardCharsets.UTF_8).length>131072||Json.canonical(history).getBytes(StandardCharsets.UTF_8).length>65536||Json.canonical(result).getBytes(StandardCharsets.UTF_8).length>196608)throw new ApiException(413,"CONTEXT_TOO_LARGE");
        db.update("UPDATE inference_contexts SET state='PREPARED',profile_version=? WHERE owner_id=? AND run_id=?",profileVersion,owner,run);if(!freshness.valid(owner,run))throw new ApiException(409,"STALE_INFERENCE_CONTEXT");return result;
    }
    /** Dedicated capture has no transcript or memory selections and no hidden run UPDATE. */
    void captureFeedback(AuthService.Session session,UUID conversation,UUID run,UUID context) {
        authorize(session,conversation);
        var request=db.queryForMap("SELECT * FROM study_feedback_requests WHERE owner_id=? AND run_id=? AND context_id=? AND state='QUEUED'",session.owner(),run,context);
        db.update("INSERT INTO inference_contexts(id,owner_id,run_id,conversation_id,project_id,project_version,session_hash,security_epoch,context_epoch,history_epoch,state) VALUES (?,?,?,?,?,?,?,?,?,?,'CAPTURED')",context,session.owner(),run,conversation,request.get("project_id"),request.get("project_version"),session.hash(),session.epoch(),request.get("context_epoch"),request.get("history_epoch"));
    }
    Map<String,Object> prepareFeedback(UUID owner,UUID run) {
        if(!freshness.valid(owner,run))throw new ApiException(409,"STALE_INFERENCE_CONTEXT");
        var c=db.queryForMap("SELECT * FROM inference_contexts WHERE owner_id=? AND run_id=? AND state='CAPTURED'",owner,run);
        var request=db.queryForMap("SELECT * FROM study_feedback_requests WHERE owner_id=? AND run_id=? AND output_authority='ACTIVE'",owner,run);
        Long version=db.queryForObject("SELECT version FROM owners WHERE id=?",Long.class,owner);
        if(!Objects.equals(version,request.get("profile_version")))throw new ApiException(409,"STALE_INFERENCE_CONTEXT");
        if(db.queryForObject("SELECT (SELECT count(*) FROM inference_context_history WHERE context_id=?)+(SELECT count(*) FROM inference_context_memories WHERE context_id=?)",Long.class,c.get("id"),c.get("id"))!=0)throw new ApiException(409,"FEEDBACK_SOURCE_UNAVAILABLE");
        var descriptor=new LinkedHashMap<String,Object>();descriptor.put("id",c.get("id"));descriptor.put("revision",1);
        for(String f:List.of("conversation_id","context_epoch","history_epoch"))descriptor.put(f,c.get(f));descriptor.put("profile_version",version);
        var selected=new LinkedHashMap<String,Object>();selected.put("context",descriptor);
        selected.put("project_context",c.get("project_id")==null?null:Map.of("id",c.get("project_id"),"version",c.get("project_version")));
        selected.put("memories",List.of());selected.put("history",List.of());selected.put("timezone","UTC");
        selected.put("current_time",AuthService.instant(db.queryForObject("SELECT clock_timestamp()",Object.class)).toString());
        if(db.update("UPDATE inference_contexts SET state='PREPARED',profile_version=? WHERE owner_id=? AND run_id=? AND state='CAPTURED'",version,owner,run)!=1)throw new ApiException(409,"STALE_INFERENCE_CONTEXT");
        if(!freshness.valid(owner,run))throw new ApiException(409,"STALE_INFERENCE_CONTEXT");return selected;
    }
    void markDispatched(UUID owner,UUID run){if(!freshness.valid(owner,run))throw new ApiException(409,"STALE_INFERENCE_CONTEXT");if(db.update("UPDATE inference_contexts SET state='DISPATCHED' WHERE owner_id=? AND run_id=? AND state='PREPARED'",owner,run)!=1)throw new ApiException(409,"CONTEXT_NOT_PREPARED");}
    void markSettled(UUID owner,UUID run){if(!freshness.valid(owner,run))throw new ApiException(409,"STALE_INFERENCE_CONTEXT");if(db.update("UPDATE inference_contexts SET state='SETTLED' WHERE owner_id=? AND run_id=? AND state='DISPATCHED'",owner,run)!=1)throw new ApiException(409,"STALE_INFERENCE_CONTEXT");}
}

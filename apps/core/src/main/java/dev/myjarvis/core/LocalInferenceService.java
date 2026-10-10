package dev.myjarvis.core;

import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Durable single-call text-only inference. Unknown attempts are never automatically resubmitted. */
@Service
final class LocalInferenceService {
    private final WorkflowService flow;
    private final LocalInferenceClient client;
    private final InferenceContextService contexts;private final InferenceContextFreshness freshness;
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicBoolean busy=new java.util.concurrent.atomic.AtomicBoolean();
    private final ConcurrentMap<UUID,Thread> active=new ConcurrentHashMap<>();
    LocalInferenceService(WorkflowService flow,LocalInferenceClient client,InferenceContextService contexts,InferenceContextFreshness freshness){this.flow=flow;this.client=client;this.contexts=contexts;this.freshness=freshness;}
    Map<String,Object> status(){boolean ready=client.ready();return Map.of("mode","LOCAL_MLX","model_id","local-mlx-v1","ready",ready,"status",ready?"READY":client.enabled?"UNAVAILABLE":"DISABLED");}
    Map<String,Object> accept(AuthService.Session session,UUID conversation,JsonNode body){
        AuthService.requireOwner(session);UUID owner=session.owner();
        Json.fields(body,"client_message_id","text");UUID messageId=Json.uuid(body,"client_message_id");String text=Json.string(body,"text",8000);
        String canonical=Json.canonical(Map.of("conversation_id",conversation.toString(),"mode","LOCAL_INFERENCE","body",body));
        String hash=contexts.requestDigest(owner,canonical);
        // Idempotent repeats can inspect an existing run even if the model is currently unavailable.
        var existing=flow.tx.execute(s->{contexts.authorize(session,conversation);var prior=flow.db.queryForList("SELECT body_hash,run_id FROM messages WHERE owner_id=? AND client_message_id=?",owner,messageId);return prior.isEmpty()?null:prior(owner,hash,canonical,prior.getFirst());});
        if(existing!=null)return existing;
        if(!client.ready())throw new ApiException(503,"LOCAL_MODEL_UNAVAILABLE");
        return flow.tx.execute(s->{
            contexts.authorize(session,conversation);
            var again=flow.db.queryForList("SELECT body_hash,run_id FROM messages WHERE owner_id=? AND client_message_id=?",owner,messageId);
            if(!again.isEmpty())return prior(owner,hash,canonical,again.getFirst());
            var ownerRow=flow.owned("owners",owner,owner);if(Boolean.TRUE.equals(ownerRow.get("execution_paused")))throw new ApiException(409,"EXECUTION_PAUSED");
            long running=flow.db.queryForObject("SELECT count(*) FROM inference_reservations WHERE owner_id=? AND status IN ('QUEUED','STARTED','UNKNOWN')",Long.class,owner);
            if(running>=2)throw new ApiException(429,"INFERENCE_CAPACITY_RESERVED");
            // Conservative local compute ceilings. UNKNOWN keeps its full reservation; no cloud billing is claimed.
            long day=flow.db.queryForObject("SELECT COALESCE(sum(max_compute_ms),0) FROM inference_reservations WHERE owner_id=? AND created_at>=date_trunc('day',now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC' AND status<>'CANCELLED'",Long.class,owner);
            long month=flow.db.queryForObject("SELECT COALESCE(sum(max_compute_ms),0) FROM inference_reservations WHERE owner_id=? AND created_at>=date_trunc('month',now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC' AND status<>'CANCELLED'",Long.class,owner);
            if(day+60000>3600000||month+60000>36000000)throw new ApiException(429,"INFERENCE_BUDGET_EXCEEDED");
            UUID run=UUID.randomUUID(),message=UUID.randomUUID(),request=UUID.randomUUID(),reservation=UUID.randomUUID();
            flow.db.update("INSERT INTO runs(id,owner_id,conversation_id,status) VALUES (?,?,?,'QUEUED')",run,owner,conversation);
            flow.db.update("INSERT INTO messages(id,owner_id,conversation_id,run_id,client_message_id,body_hash,content_ref,role,trust) VALUES (?,?,?,?,?,?,?,'USER','USER_DIRECT')",message,owner,conversation,run,messageId,hash,flow.vault.seal(owner,message,"message",text));
            flow.db.update("INSERT INTO inference_reservations(id,owner_id,run_id,request_id,body_hash,model_manifest_hash,status,max_compute_ms,reserved_output_tokens,security_epoch) VALUES (?,?,?,?,?,?,'QUEUED',60000,512,?)",reservation,owner,run,request,hash,client.manifestHash,ownerRow.get("security_epoch"));
            contexts.capture(session,conversation,run);flow.event(owner,run,"LOCAL_INFERENCE_RESERVED");return Map.of("run_id",run,"status","QUEUED");
        });
    }
    private Map<String,Object> prior(UUID owner,String hash,String canonical,Map<String,Object> row){
        UUID run=(UUID)row.get("run_id");var existing=flow.owned("runs",owner,run);
        boolean matches=Json.equal(hash,(String)row.get("body_hash"));
        // Read-only recovery of pre-context requests; never enqueue or write a legacy digest.
        if(!matches&&Boolean.FALSE.equals(existing.get("context_history_eligible"))
                &&!flow.db.queryForList("SELECT id FROM inference_reservations WHERE owner_id=? AND run_id=?",owner,run).isEmpty())
            matches=Json.equal(Json.hash(canonical),(String)row.get("body_hash"));
        if(!matches)throw new ApiException(409,"IDEMPOTENCY_CONFLICT");
        return Map.of("run_id",run,"status",existing.get("status"));
    }
    @Scheduled(fixedDelay=500) void tick(){
        interruptInvalidContexts();recover();if(!client.configured()||!busy.compareAndSet(false,true))return;
        executor.submit(()->{try{runOne();}finally{busy.set(false);}});
    }
    void interruptInvalidContexts(){
        for(UUID run:active.keySet()){
            boolean invalid=Boolean.TRUE.equals(flow.tx.execute(s->{var rows=flow.db.queryForList("SELECT owner_id,status,cancel_requested_at FROM inference_reservations WHERE run_id=?",run);if(rows.isEmpty())return true;var row=rows.getFirst();UUID owner=(UUID)row.get("owner_id");if(!"STARTED".equals(row.get("status"))||row.get("cancel_requested_at")!=null)return true;if(!freshness.valid(owner,run)){freshness.invalidateRun(owner,run);return true;}return false;}));
            if(invalid)interrupt(run);
        }
    }
    void recover(){
        for(var row:flow.db.queryForList("SELECT owner_id,run_id FROM inference_reservations WHERE status='STARTED' AND started_at<clock_timestamp()-interval '90 seconds'")){
            UUID owner=(UUID)row.get("owner_id"),run=(UUID)row.get("run_id");
            flow.tx.executeWithoutResult(tx->{flow.lockOwner(owner);if(flow.db.queryForList("SELECT id FROM inference_reservations WHERE owner_id=? AND run_id=? AND status='STARTED' AND started_at<clock_timestamp()-interval '90 seconds' FOR UPDATE",owner,run).isEmpty())return;freshness.invalidateRun(owner,run);flow.event(owner,run,"LOCAL_INFERENCE_UNKNOWN");});
        }
    }
    boolean runOne(){
        var candidates=flow.db.queryForList("SELECT owner_id,id FROM inference_reservations WHERE status='QUEUED' ORDER BY created_at LIMIT 1");if(candidates.isEmpty())return false;
        UUID owner=(UUID)candidates.getFirst().get("owner_id"),id=(UUID)candidates.getFirst().get("id");
        Dispatch dispatch;
        try { dispatch=flow.tx.execute(s->{
            flow.lockOwner(owner);var rows=flow.db.queryForList("SELECT * FROM inference_reservations WHERE id=? AND status='QUEUED' FOR UPDATE",id);if(rows.isEmpty())return null;
            var row=rows.getFirst();UUID run=(UUID)row.get("run_id");var o=flow.owned("owners",owner,owner);var r=flow.locked("runs",owner,run);
            if(Boolean.TRUE.equals(o.get("execution_paused"))||r.get("cancel_requested_at")!=null||!o.get("security_epoch").equals(row.get("security_epoch"))){flow.db.update("UPDATE inference_reservations SET status='CANCELLED',completed_at=now() WHERE id=?",id);flow.setRun(owner,run,"CANCELLED",null);return null;}
            if(!freshness.valid(owner,run)){freshness.invalidateRun(owner,run);return null;}
            var m=flow.db.queryForMap("SELECT id,content_ref FROM messages WHERE owner_id=? AND run_id=? AND role='USER'",owner,run);
            String text=flow.vault.open(owner,(UUID)m.get("id"),"message",(String)m.get("content_ref"));
            JsonNode request=request(row,text,contexts.prepare(owner,run));contexts.markDispatched(owner,run);flow.db.update("UPDATE inference_reservations SET status='STARTED',started_at=now(),body_hash=? WHERE id=?",contexts.requestDigest(owner,Json.canonical(request)),id);
            flow.setRun(owner,run,"RUNNING",null);flow.event(owner,run,"LOCAL_INFERENCE_STARTED");return new Dispatch(owner,run,id,request);
        });
        } catch(ApiException|IllegalStateException|java.time.DateTimeException invalidContext){
            flow.tx.executeWithoutResult(tx->{flow.lockOwner(owner);var pending=flow.db.queryForList("SELECT run_id FROM inference_reservations WHERE owner_id=? AND id=? AND status='QUEUED' FOR UPDATE",owner,id);if(!pending.isEmpty()){UUID run=(UUID)pending.getFirst().get("run_id");freshness.invalidateRun(owner,run);flow.setRun(owner,run,"BLOCKED",null);flow.event(owner,run,"LOCAL_CONTEXT_BLOCKED");}});
            return true;
        }
        if(dispatch==null)return true;
        active.put(dispatch.run(),Thread.currentThread());
        try {
            // Last check after registration closes the cancel-before-registration race.
            if(!Boolean.TRUE.equals(flow.tx.execute(s->{flow.lockOwner(owner);if(!"STARTED".equals(flow.db.queryForObject("SELECT status FROM inference_reservations WHERE id=?",String.class,id)))return false;if(!freshness.valid(owner,dispatch.run())){freshness.invalidateRun(owner,dispatch.run());return false;}return true;})))return true;
            JsonNode response=client.infer(owner,dispatch.body());String result=validate(response,dispatch.body());
            settle(dispatch,switch(response.get("kind").asText()){case "ANSWER" -> "SUCCEEDED";case "NEED_INPUT" -> "WAITING_INPUT";default -> "FAILED";},result);
        }catch(Exception uncertain){settle(dispatch,"UNKNOWN",null);}
        finally{active.remove(dispatch.run());Thread.interrupted();}
        return true;
    }
    record Dispatch(UUID owner,UUID run,UUID reservation,JsonNode body){}
    JsonNode request(Map<String,Object> row,String text,Map<String,Object> selected){
        Map<String,Object> body=new LinkedHashMap<>();body.put("request_id",row.get("request_id").toString());body.put("run_id",row.get("run_id").toString());body.put("user_input",text);body.putAll(selected);
        for(String field:List.of("evidence","tool_results","allowed_tool_ids"))body.put(field,List.of());
        body.put("budget",Map.of("reservation_id",row.get("id").toString(),"max_compute_ms",60000,"max_input_tokens",3584,"max_output_tokens",512,"max_model_calls",1));body.put("model_id","local-mlx-v1");body.put("model_manifest_hash",row.get("model_manifest_hash"));body.put("prompt_revision","local-v1");JsonNode result=Json.MAPPER.valueToTree(body);if(Json.canonical(result).getBytes(java.nio.charset.StandardCharsets.UTF_8).length>262144)throw new ApiException(413,"CONTEXT_TOO_LARGE");return result;
    }
    static String validate(JsonNode response,JsonNode request){
        String kind=Json.string(response,"kind",30);if(!Set.of("ANSWER","NEED_INPUT","ERROR").contains(kind))throw ApiException.bad();
        if(kind.equals("ANSWER")){Json.fields(response,"kind","reply_text","cited_source_ids","usage");if(!response.path("cited_source_ids").isArray()||!response.path("cited_source_ids").isEmpty())throw ApiException.bad();}
        else if(kind.equals("NEED_INPUT")){Json.fields(response,"kind","question","missing_fields","usage");var missing=response.path("missing_fields");if(!missing.isArray()||missing.size()>32)throw ApiException.bad();for(var field:missing)if(!field.isTextual()||field.asText().isEmpty()||field.asText().codePointCount(0,field.asText().length())>80)throw ApiException.bad();}
        else{Json.fields(response,"kind","error_code","retryable","usage");if(!Set.of("MODEL_UNAVAILABLE","CONTRACT_VIOLATION","RESOURCE_LIMIT","CONTEXT_LIMIT","MODEL_OUTPUT_INVALID","GENERATION_TIMEOUT","CANCELLED").contains(Json.string(response,"error_code",40))||!response.path("retryable").isBoolean()||response.path("retryable").asBoolean())throw ApiException.bad();}
        JsonNode usage=response.get("usage");if(usage==null)throw ApiException.bad();Json.fields(usage,"inference_request_id","model_manifest_hash","input_tokens","output_tokens","compute_ms","peak_memory_bytes","reservation_id");
        if(!Json.string(usage,"inference_request_id",36).equals(request.get("request_id").asText())||!Json.string(usage,"reservation_id",36).equals(request.get("budget").get("reservation_id").asText())||!Json.equal(Json.string(usage,"model_manifest_hash",64),request.get("model_manifest_hash").asText()))throw ApiException.bad();
        for(String field:List.of("input_tokens","output_tokens","compute_ms","peak_memory_bytes"))if(Json.integer(usage,field)<0)throw ApiException.bad();
        if(Json.integer(usage,"input_tokens")>3584||Json.integer(usage,"output_tokens")>512||Json.integer(usage,"compute_ms")>60000)throw ApiException.bad();
        if(kind.equals("NEED_INPUT"))return Json.string(response,"question",1000);
        if(kind.equals("ERROR"))return "Local inference stopped: "+response.get("error_code").asText()+". No automatic retry was made.";return Json.string(response,"reply_text",16000);
    }
    private void settle(Dispatch d,String status,String text){flow.tx.executeWithoutResult(s->{flow.lockOwner(d.owner());if(!freshness.valid(d.owner(),d.run())){freshness.invalidateRun(d.owner(),d.run());return;}contexts.markSettled(d.owner(),d.run());int changed=flow.db.update("UPDATE inference_reservations SET status=?,completed_at=now() WHERE id=? AND status='STARTED' AND cancel_requested_at IS NULL",(status.equals("WAITING_INPUT")?"SUCCEEDED":status),d.reservation());if(changed==1){flow.setRun(d.owner(),d.run(),status,text);flow.event(d.owner(),d.run(),"LOCAL_INFERENCE_"+status);}});}
    void interruptOwner(UUID owner){for(var row:flow.db.queryForList("SELECT run_id FROM inference_reservations WHERE owner_id=? AND cancel_requested_at IS NOT NULL",owner))interrupt((UUID)row.get("run_id"));}
    void interrupt(UUID run){Thread t=active.get(run);if(t!=null)t.interrupt();}
    @PreDestroy void close(){executor.shutdownNow();}
}

package dev.myjarvis.core;

import jakarta.annotation.PreDestroy;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** Forward-only safety metadata. Never decrypts source, retries generation, settles runs, or records usage. */
@Service
final class InferenceControlService {
    static final int BATCH=16, MAX_BACKOFF=1024;
    static final long BACKOFF_NANOS=5_000_000_000L;
    private final WorkflowService flow;
    private final LocalInferenceClient client;
    private final InferenceContextService contexts;
    private final InferenceContextFreshness freshness;
    private final TransactionTemplate tx;
    private final ExecutorService worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1),new ThreadPoolExecutor.AbortPolicy());
    private final AtomicBoolean busy=new AtomicBoolean();
    private final Map<UUID,Long> attempted=new HashMap<>();
    private UUID cursor;

    InferenceControlService(WorkflowService flow,LocalInferenceClient client,InferenceContextService contexts,InferenceContextFreshness freshness){
        this.flow=flow;this.client=client;this.contexts=contexts;this.freshness=freshness;
        tx=new TransactionTemplate(Objects.requireNonNull(flow.tx.getTransactionManager()));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(5);
    }
    <T> T boundary(Supplier<T> work){
        try{return tx.execute(status->{
            flow.db.execute("SET LOCAL statement_timeout='5s'");flow.db.execute("SET LOCAL lock_timeout='5s'");
            flow.db.execute("SET LOCAL idle_in_transaction_session_timeout='5s'");
            long started=System.nanoTime();T result=work.get();
            if(System.nanoTime()-started>=BACKOFF_NANOS)throw new ApiException(503,"LOCAL_TIME_LIMIT");
            return result;
        });}catch(TransactionTimedOutException invalid){throw new ApiException(503,"LOCAL_TIME_LIMIT");}
        catch(DataAccessException invalid){
            for(Throwable cause=invalid;cause!=null;cause=cause.getCause())if(cause instanceof java.sql.SQLException sql){
                if(Set.of("57014","55P03","25P03").contains(sql.getSQLState()))throw new ApiException(503,"LOCAL_TIME_LIMIT");
                if("54000".equals(sql.getSQLState()))throw new ApiException(413,"CONTEXT_TOO_LARGE");
            }
            throw invalid;
        }
    }
    Instant now(){return AuthService.instant(flow.db.queryForObject("SELECT clock_timestamp()",Object.class));}
    private static void chronology(Instant earlier,Instant later){if(later.isBefore(earlier))throw new ApiException(409,"CONTROL_CLOCK_INVALID");}
    private void chronology(Map<String,Object> slot,Instant at){
        for(String field:List.of("created_at","bound_at","decision_at"))if(slot.get(field)!=null)chronology(AuthService.instant(slot.get(field)),at);
    }
    private Map<String,Object> slot(UUID owner,UUID reservation){
        var rows=flow.db.queryForList("SELECT * FROM inference_control_slots WHERE owner_id=? AND reservation_id=?",owner,reservation);
        if(rows.size()!=1)throw new ApiException(409,"CONTROL_IDENTITY_CONFLICT");return rows.getFirst();
    }
    void allocate(AuthService.Session session,UUID conversation,UUID run,UUID reservation,UUID request,UUID context,String purpose){
        InferenceContextFreshness.transaction();
        var stored=flow.db.queryForMap("SELECT session_hash,security_epoch FROM inference_contexts WHERE owner_id=? AND id=? AND run_id=? AND conversation_id=? AND state='CAPTURED'",session.owner(),context,run,conversation);
        if(!session.hash().equals(stored.get("session_hash"))||session.epoch()!=((Number)stored.get("security_epoch")).longValue())throw new ApiException(409,"CONTROL_IDENTITY_CONFLICT");
        Map<String,Object> identity=new LinkedHashMap<>();identity.put("owner_id",session.owner());identity.put("reservation_id",reservation);identity.put("request_id",request);identity.put("run_id",run);identity.put("conversation_id",conversation);identity.put("context_id",context);identity.put("session_hash",stored.get("session_hash"));identity.put("security_epoch",stored.get("security_epoch"));identity.put("purpose",purpose);
        String origin=contexts.controlDigest(session.owner(),"inference-control-origin-v1",Json.canonical(identity));
        flow.db.update("INSERT INTO inference_control_slots(reservation_id,owner_id,run_id,request_id,conversation_id,context_id,revision,purpose,origin_binding,security_epoch,created_at) VALUES (?,?,?,?,?,?,1,?,?,?,clock_timestamp())",reservation,session.owner(),run,request,conversation,context,purpose,origin,stored.get("security_epoch"));
    }
    Map<String,Object> descriptor(Map<String,Object> reservation,Map<String,Object> body){
        InferenceContextFreshness.transaction();
        UUID owner=(UUID)reservation.get("owner_id"),id=(UUID)reservation.get("id");var slot=slot(owner,id);
        if(slot.get("dispatch_commitment")!=null||slot.get("terminal_kind")!=null||slot.get("decision")!=null||body.containsKey("control"))throw new ApiException(409,"CONTROL_IDENTITY_CONFLICT");
        if(!slot.get("run_id").toString().equals(body.get("run_id"))||!slot.get("request_id").toString().equals(body.get("request_id"))||!slot.get("purpose").equals(reservation.get("purpose"))||!Objects.equals(slot.get("security_epoch"),reservation.get("security_epoch")))throw new ApiException(409,"CONTROL_IDENTITY_CONFLICT");
        String commitment=contexts.controlDigest(owner,"inference-control-dispatch-v1",Json.canonical(body));
        return Map.of("revision",1,"origin_binding",slot.get("origin_binding"),"dispatch_commitment",commitment,"security_epoch",slot.get("security_epoch"));
    }
    void bind(UUID owner,UUID reservation,JsonNode body){
        InferenceContextFreshness.transaction();var slot=slot(owner,reservation);JsonNode descriptor=body.path("control");
        InferenceControlResult.exact(descriptor,"revision","origin_binding","dispatch_commitment","security_epoch");
        if(Json.integer(descriptor,"revision")!=1||!Json.equal((String)slot.get("origin_binding"),Json.string(descriptor,"origin_binding",64))||Json.integer(descriptor,"security_epoch")!=((Number)slot.get("security_epoch")).longValue()||!slot.get("context_id").toString().equals(body.path("context").path("id").asText()))throw new ApiException(409,"CONTROL_IDENTITY_CONFLICT");
        String commitment=Json.string(descriptor,"dispatch_commitment",64);
        var unadorned=body.deepCopy();((tools.jackson.databind.node.ObjectNode)unadorned).remove("control");
        if(!Json.equal(commitment,contexts.controlDigest(owner,"inference-control-dispatch-v1",Json.canonical(unadorned))))throw new ApiException(409,"CONTROL_IDENTITY_CONFLICT");
        if(flow.db.update("UPDATE inference_control_slots SET dispatch_commitment=?,bound_at=(SELECT started_at FROM inference_reservations WHERE owner_id=? AND id=?) WHERE owner_id=? AND reservation_id=? AND dispatch_commitment IS NULL AND terminal_kind IS NULL AND decision IS NULL",commitment,owner,reservation,owner,reservation)!=1)throw new ApiException(409,"CONTROL_IDENTITY_CONFLICT");
    }
    void finishAdmission(AuthService.Session session,UUID conversation,UUID run,UUID reservation,Instant entry){
        validateClosure(session.owner(),conversation,reservation);
        contexts.authorize(session,conversation);
        if(!freshness.valid(session.owner(),run))throw new ApiException(409,"STALE_INFERENCE_CONTEXT");
        finishClock(session.owner(),reservation,entry);
    }
    void finishDispatch(UUID owner,UUID run,UUID reservation,Instant entry){
        var slot=slot(owner,reservation);validateClosure(owner,(UUID)slot.get("conversation_id"),reservation);
        if(!freshness.valid(owner,run))throw new ApiException(409,"STALE_INFERENCE_CONTEXT");
        finishClock(owner,reservation,entry);
    }
    private void validateClosure(UUID owner,UUID conversation,UUID reservation){
        flow.db.queryForMap("SELECT * FROM conversation_source_inventory_v17(?,?)",owner,conversation);
        flow.db.queryForObject("SELECT verify_inference_control_slot(?,?)",Object.class,owner,reservation);
        flow.db.execute("SET CONSTRAINTS ALL IMMEDIATE");
    }
    private void finishClock(UUID owner,UUID reservation,Instant entry){
        var slot=slot(owner,reservation);
        var original=flow.db.queryForMap("SELECT session_hash,security_epoch FROM inference_contexts WHERE owner_id=? AND id=?",owner,slot.get("context_id"));
        flow.access.lockSession(new AuthService.Session((String)original.get("session_hash"),owner,null,((Number)original.get("security_epoch")).longValue()));
        // This is the final database statement. Its one observed instant governs both
        // chronology and expiry, so an expiry during the preceding waits cannot commit.
        var authority=finalObservation(owner,(String)original.get("session_hash"));
        Instant finalTime=AuthService.instant(authority.get("observed_at"));long epoch=((Number)original.get("security_epoch")).longValue();
        if(authority.get("revoked_at")!=null||((Number)authority.get("owner_epoch")).longValue()!=epoch||((Number)authority.get("session_epoch")).longValue()!=epoch
                ||!AuthService.instant(authority.get("created_at")).isAfter(finalTime.minusSeconds(43200))
                ||!AuthService.instant(authority.get("last_seen_at")).isAfter(finalTime.minusSeconds(7200)))throw new ApiException(401,"SESSION_REVOKED");
        chronology(entry,finalTime);chronology(slot,finalTime);
        chronology(AuthService.instant(authority.get("created_at")),finalTime);chronology(AuthService.instant(authority.get("last_seen_at")),finalTime);
    }
    Map<String,Object> finalObservation(UUID owner,String hash){
        var rows=flow.db.queryForList("SELECT o.security_epoch AS owner_epoch,s.security_epoch AS session_epoch,s.revoked_at,s.created_at,s.last_seen_at,clock_timestamp() AS observed_at FROM owners o JOIN sessions s ON s.owner_id=o.id WHERE o.id=? AND s.token_hash=?",owner,hash);
        if(rows.size()!=1)throw new ApiException(401,"SESSION_REVOKED");return rows.getFirst();
    }

    /** Coalesced wake-up only. Durable flags, not callback delivery, are the work queue. */
    void kick(){
        if(TransactionSynchronizationManager.isActualTransactionActive()){
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){kick();}});return;
        }
        if(!busy.compareAndSet(false,true))return;
        try{worker.submit(()->{try{reconcileBatch();}finally{busy.set(false);}});}catch(RejectedExecutionException closed){busy.set(false);}
    }
    synchronized int reconcileBatch(){
        var rows=boundary(()->cursor==null?flow.db.queryForList("SELECT owner_id,reservation_id FROM inference_control_slots WHERE terminal_kind IS NULL ORDER BY reservation_id LIMIT 16"):
            flow.db.queryForList("SELECT owner_id,reservation_id FROM inference_control_slots WHERE terminal_kind IS NULL AND reservation_id>? ORDER BY reservation_id LIMIT 16",cursor));
        if(rows.isEmpty()){cursor=null;return 0;}
        int processed=0;
        for(var row:rows){
            UUID id=(UUID)row.get("reservation_id");cursor=id;long now=backoffNow();attempted.entrySet().removeIf(e->now-e.getValue()>=BACKOFF_NANOS);
            if(attempted.containsKey(id)||attempted.size()>=MAX_BACKOFF)continue;
            attempted.put(id,now);processed++;
            try{reconcileOne((UUID)row.get("owner_id"),id);}catch(Exception unavailable){/* No producer error, timeout, or clock rollback supplies terminal proof. */}
        }
        if(rows.size()<BATCH)cursor=null;return processed;
    }
    long backoffNow(){return System.nanoTime();}
    void reconcileOne(UUID owner,UUID reservation)throws Exception {
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Control transport must not own database locks");
        JsonNode request=boundary(()->{
            Instant entry=now();flow.access.lockOwner(owner);
            String decision=flow.db.queryForObject("SELECT inference_control_observe_decision(?,?)",String.class,owner,reservation);
            var slot=slot(owner,reservation);
            if(slot.get("terminal_kind")!=null)return null;
            Instant observed=now();chronology(entry,observed);chronology(slot,observed);
            if(slot.get("dispatch_commitment")==null){
                if(decision!=null){
                    if(!localUnstarted(owner,reservation))throw new ApiException(409,"CONTROL_UNSTARTED_CONFLICT");
                    flow.db.update("UPDATE inference_control_slots SET terminal_kind='UNSTARTED_CORE',recorded_at=? WHERE owner_id=? AND reservation_id=? AND terminal_kind IS NULL AND dispatch_commitment IS NULL AND decision IS NOT NULL",Timestamp.from(observed),owner,reservation);
                    flow.db.execute("SET CONSTRAINTS ALL IMMEDIATE");chronology(observed,now());
                }
                return null;
            }
            return controlRequest(slot,decision==null?"OBSERVE":"FENCE_OR_STOP");
        });
        if(request==null||!client.configured())return;
        InferenceControlResult result=client.control(request);
        if(result.terminal())record(request,result);
    }
    private boolean localUnstarted(UUID owner,UUID reservation){
        return Boolean.TRUE.equals(flow.db.queryForObject("SELECT EXISTS(SELECT 1 FROM inference_control_slots s JOIN inference_reservations i ON i.owner_id=s.owner_id AND i.id=s.reservation_id AND i.run_id=s.run_id AND i.request_id=s.request_id JOIN runs r ON r.owner_id=s.owner_id AND r.id=s.run_id AND r.conversation_id=s.conversation_id JOIN inference_contexts c ON c.owner_id=s.owner_id AND c.id=s.context_id AND c.run_id=s.run_id AND c.conversation_id=s.conversation_id WHERE s.owner_id=? AND s.reservation_id=? AND s.dispatch_commitment IS NULL AND s.bound_at IS NULL AND s.decision IS NOT NULL AND i.control_revision=1 AND i.status='CANCELLED' AND i.cancel_requested_at IS NOT NULL AND i.started_at IS NULL AND i.dispatch_context_id IS NULL AND i.dispatch_cache_policy IS NULL AND r.cancel_requested_at IS NOT NULL AND (r.status='CANCELLED' OR (r.status='BLOCKED' AND c.state='INVALIDATED' AND c.invalidated_at IS NOT NULL)))",Boolean.class,owner,reservation));
    }
    static JsonNode controlRequest(Map<String,Object> slot,String operation){
        Map<String,Object> body=new LinkedHashMap<>();body.put("revision",1);body.put("operation",operation);
        for(String field:InferenceControlResult.ID_FIELDS)body.put(field,slot.get(field));
        for(String field:List.of("purpose","security_epoch","origin_binding","dispatch_commitment"))body.put(field,slot.get(field));
        JsonNode request=Json.MAPPER.valueToTree(body);InferenceControlResult.request(request);return request;
    }
    void record(JsonNode request,InferenceControlResult result){
        InferenceControlResult.request(request);if(!result.terminal())return;
        if(!Json.equal(result.requestBinding(),Json.hash(Json.canonical(request))))throw new ApiException(409,"CONTROL_IDENTITY_CONFLICT");
        UUID owner=Json.uuid(request,"owner_id"),reservation=Json.uuid(request,"reservation_id");
        boundary(()->{
            Instant entry=now();flow.access.lockOwner(owner);
            // The same lock/order helper may observe a new decision, but it does not require
            // the original user session still live and never opens any source ciphertext.
            flow.db.queryForObject("SELECT inference_control_observe_decision(?,?)",String.class,owner,reservation);
            var slot=slot(owner,reservation);
            if(!Json.canonical(controlRequest(slot,request.get("operation").asText())).equals(Json.canonical(request)))throw new ApiException(409,"CONTROL_IDENTITY_CONFLICT");
            if(slot.get("terminal_kind")!=null){
                if(!result.kind().equals(slot.get("terminal_kind"))||!Objects.equals(result.generation(),slot.get("generation_id"))||!Objects.equals(result.ledger(),slot.get("ledger_instance_id"))||!Objects.equals(result.ordinal(),slot.get("terminal_ordinal")))throw new ApiException(409,"CONTROL_FACT_CONFLICT");
                return null; // Exact replay preserves the first Core observation time.
            }
            Instant observed=now();chronology(entry,observed);chronology(slot,observed);
            flow.db.update("UPDATE inference_control_slots SET terminal_kind=?,generation_id=?,ledger_instance_id=?,terminal_ordinal=?,recorded_at=? WHERE owner_id=? AND reservation_id=? AND terminal_kind IS NULL",result.kind(),result.generation(),result.ledger(),result.ordinal(),Timestamp.from(observed),owner,reservation);
            flow.db.execute("SET CONSTRAINTS ALL IMMEDIATE");chronology(observed,now());return null;
        });
    }
    @PreDestroy void close(){worker.shutdownNow();}
}

package dev.myjarvis.core;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
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

/** Atomic, exact-snapshot redaction of one closed source graph. Never calls a model or provider. */
@Service
final class ConversationDeletionService {
    static final String SCOPE="CONVERSATION_SOURCE_V1", POLICY="conversation-source-v1-r3";
    static final int RUN_LIMIT=1000, MESSAGE_LIMIT=2000, ACTION_LIMIT=1000, ROW_LIMIT=40000, PREPARATION_LIMIT=100;
    static final long BYTE_LIMIT=16L*1024*1024, BUDGET_NANOS=5_000_000_000L;
    private static final Set<String> RUN_CLOSED=Set.of("SUCCEEDED","PARTIAL_SUCCESS","FAILED","CANCELLED","EXPIRED","BLOCKED","WAITING_INPUT");
    private static final Set<String> CLOSED=Set.of("SUCCEEDED","FAILED","CANCELLED","EXPIRED","BLOCKED");
    private static final Set<String> ACTIVE=Set.of("ACCEPTED","PLANNING","WAITING_APPROVAL","QUEUED","RUNNING","CANCEL_REQUESTED","EXECUTING","STARTED","CAPTURED","PREPARED","DISPATCHED");
    private final JdbcTemplate db; private final MemoryService memories; private final ConversationAccess access;
    private final TransactionTemplate tx;
    ConversationDeletionService(JdbcTemplate db,MemoryService memories,ConversationAccess access,PlatformTransactionManager manager){
        this.db=db;this.memories=memories;this.access=access;tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(5);
    }
    private static void budget(long start){if(System.nanoTime()-start>=BUDGET_NANOS)throw new ApiException(503,"CONVERSATION_DELETION_TIME_LIMIT");}
    private <T> T transaction(Supplier<T> work){
        try{return tx.execute(status->{db.execute("SET LOCAL statement_timeout='5s'");db.execute("SET LOCAL lock_timeout='5s'");db.execute("SET LOCAL idle_in_transaction_session_timeout='5s'");long start=System.nanoTime();T value=work.get();budget(start);return value;});}
        catch(TransactionTimedOutException e){throw new ApiException(503,"CONVERSATION_DELETION_TIME_LIMIT");}
        catch(DataAccessException e){for(Throwable t=e;t!=null;t=t.getCause())if(t instanceof java.sql.SQLException sql && Set.of("57014","55P03","25P03").contains(sql.getSQLState()))throw new ApiException(503,"CONVERSATION_DELETION_TIME_LIMIT");throw e;}
    }
    private Instant now(){return memories.databaseNow();}
    private static void authorityTime(Instant prepared,Instant expires,Instant observed){
        if(observed.isBefore(prepared))throw new ApiException(403,"AUTHORITY_NOT_VALID");
        if(!observed.isBefore(expires))throw new ApiException(409,"APPROVAL_EXPIRED");
    }
    private static void observationTime(Instant deleted,Instant observed){if(observed.isBefore(deleted))throw new ApiException(403,"AUTHORITY_NOT_VALID");}
    private Map<String,Object> session(AuthService.Session s,boolean recent){var owner=access.lockSession(s);if(recent){
        var times=db.queryForMap("SELECT reauthenticated_at,clock_timestamp() AS observed_at FROM sessions WHERE token_hash=? AND owner_id=?",s.hash(),s.owner());
        Instant observed=AuthService.instant(times.get("observed_at")),reauth=times.get("reauthenticated_at")==null?null:AuthService.instant(times.get("reauthenticated_at"));
        if(reauth==null||reauth.isAfter(observed)||reauth.isBefore(observed.minusSeconds(300)))throw new ApiException(403,"REAUTHENTICATION_REQUIRED");
    }return owner;}
    private Map<String,Object> conversation(UUID owner,UUID id){var rows=db.queryForList("SELECT * FROM conversations WHERE owner_id=? AND id=? FOR UPDATE",owner,id);if(rows.isEmpty())throw ApiException.missing();return rows.getFirst();}
    private static void live(Map<String,Object> conversation){if(conversation.get("deleted_at")!=null)throw new ApiException(410,"CONVERSATION_DELETED");}
    private static long number(Map<String,Object> row,String field){return ((Number)row.get(field)).longValue();}
    private static String digest(JsonNode body,String field){String value=Json.string(body,field,64);if(!value.matches("[0-9a-f]{64}"))throw ApiException.bad();return value;}
    private String keyed(UUID owner,String purpose,Object object){return memories.keys.digest(owner,"conversation-delete:"+purpose,Json.canonical(object));}
    private String open(UUID owner,UUID id,String purpose,Object ref){if(!(ref instanceof String value))throw new ApiException(403,"AUTHORITY_NOT_VALID");return memories.vault.open(owner,id,"conversation-delete-"+purpose,value);}
    private String seal(UUID owner,UUID id,String purpose,String value){return memories.vault.seal(owner,id,"conversation-delete-"+purpose,value);}
    private static void expected(Map<String,Object> c,long version,String digest,Graph graph){if(number(c,"content_version")!=version||!Json.equal(digest,graph.digest))throw new ApiException(409,"PRECONDITION_CHANGED");}

    Map<String,Object> state(AuthService.Session s,UUID id){return transaction(()->{
        session(s,false);var c=conversation(s.owner(),id);Map<String,Object> result=new LinkedHashMap<>();result.put("conversation_id",id);
        if(c.get("deleted_at")!=null){var ledger=db.queryForList("SELECT id,deleted_at FROM conversation_deletion_ledger WHERE owner_id=? AND conversation_id=?",s.owner(),id);if(ledger.size()!=1)throw new ApiException(409,"CONVERSATION_GRAPH_UNVERIFIED");result.put("status","DELETED");result.put("deletion_id",ledger.getFirst().get("id"));result.put("deleted_at",AuthService.instant(ledger.getFirst().get("deleted_at")).toString());}
        else {Graph graph=graph(s.owner(),c);result.put("status","ACTIVE");result.put("content_version",c.get("content_version"));result.put("content_digest",graph.blockers.isEmpty()?graph.digest:null);result.put("eligibility",Map.of("status",graph.blockers.isEmpty()?"ELIGIBLE":"BLOCKED","blockers",List.copyOf(graph.blockers)));}
        session(s,false);Instant observed=now();if(c.get("deleted_at")!=null)observationTime(AuthService.instant(c.get("deleted_at")),observed);result.put("checked_at",observed.toString());return result;
    });}
    Map<String,Object> prepare(AuthService.Session s,UUID key,UUID id,JsonNode body){
        MemoryService.exact(body,"expected_version","expected_digest");long version=MemoryService.number(body,"expected_version",1);String submitted=digest(body,"expected_digest");AuthService.requireOwner(s);
        String request=keyed(s.owner(),"prepare-request",Map.of("conversation_id",id,"request",body));
        return transaction(()->{
            MemoryService.unpaused(session(s,true));var c=conversation(s.owner(),id);
            var prior=db.queryForList("SELECT * FROM conversation_delete_preparations WHERE owner_id=? AND issuance_key=? FOR UPDATE",s.owner(),key);
            if(!prior.isEmpty()){
                var p=prior.getFirst();MemoryService.binding(s,p);if(!Json.equal(request,(String)p.get("issuance_digest")))throw new ApiException(409,"IDEMPOTENCY_CONFLICT");
                if(p.get("consumed_at")!=null)throw new ApiException(409,"APPROVAL_CONSUMED");live(c);if(p.get("revoked_at")!=null)throw new ApiException(403,"AUTHORITY_NOT_VALID");authorityTime(AuthService.instant(p.get("prepared_at")),AuthService.instant(p.get("expires_at")),now());
                Graph graph=graph(s.owner(),c);eligible(graph);expected(c,version,submitted,graph);if(number(p,"expected_version")!=version||!Json.equal(submitted,open(s.owner(),(UUID)p.get("id"),"snapshot",p.get("expected_digest_ref"))))throw new ApiException(409,"PRECONDITION_CHANGED");
                var response=preparation(s,p,c,graph);MemoryService.unpaused(session(s,true));authorityTime(AuthService.instant(p.get("prepared_at")),AuthService.instant(p.get("expires_at")),now());return response;
            }
            live(c);Graph graph=graph(s.owner(),c);eligible(graph);expected(c,version,submitted,graph);MemoryService.advance(version);for(var job:graph.rows.get("jobs"))MemoryService.advance(number(job,"lease_generation"));
            // Only expired, unconsumed, unreferenced temporary preparations are reclaimable.
            db.update("DELETE FROM conversation_delete_preparations p WHERE p.owner_id=? AND p.consumed_at IS NULL AND p.prepared_at<clock_timestamp()-interval '24 hours' AND NOT EXISTS(SELECT 1 FROM conversation_delete_approvals a WHERE a.preparation_id=p.id) AND NOT EXISTS(SELECT 1 FROM conversation_deletion_mutations m WHERE m.preparation_id=p.id)",s.owner());
            if(db.queryForList("SELECT id FROM conversation_delete_preparations WHERE owner_id=? AND consumed_at IS NULL ORDER BY id LIMIT 100",s.owner()).size()>=PREPARATION_LIMIT)throw new ApiException(429,"CONVERSATION_DELETION_PREPARATION_LIMIT");
            MemoryService.unpaused(session(s,true));Instant prepared=now(),expires=prepared.plusSeconds(300);UUID preparation=UUID.randomUUID();String hash=payloadHash(s,preparation,id,version,submitted,prepared,expires);
            db.update("INSERT INTO conversation_delete_preparations(id,owner_id,conversation_id,session_hash,security_epoch,issuance_key,issuance_digest,expected_version,expected_digest_ref,payload_hash_ref,messages_count,runs_count,run_results_count,action_payloads_count,inference_contexts_count,prepared_at,expires_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",preparation,s.owner(),id,s.hash(),s.epoch(),key,request,version,seal(s.owner(),preparation,"snapshot",submitted),seal(s.owner(),preparation,"payload",hash),graph.counts.get("messages"),graph.counts.get("runs"),graph.counts.get("run_results"),graph.counts.get("action_payloads"),graph.counts.get("inference_contexts"),Timestamp.from(prepared),Timestamp.from(expires));
            audit(s.owner(),"CONVERSATION_DELETE_PREPARED",preparation);
            var response=preparation(s,db.queryForMap("SELECT * FROM conversation_delete_preparations WHERE id=?",preparation),c,graph);MemoryService.unpaused(session(s,true));authorityTime(prepared,expires,now());return response;
        });
    }
    private String payloadHash(AuthService.Session s,UUID preparation,UUID target,long version,String snapshot,Instant prepared,Instant expires){
        Map<String,Object> data=new LinkedHashMap<>();data.put("owner_id",s.owner());data.put("session_hash",s.hash());data.put("security_epoch",s.epoch());data.put("preparation_id",preparation);data.put("conversation_id",target);data.put("tool_id","conversation.source.delete");data.put("tool_version","1");data.put("policy_version",POLICY);data.put("scope_policy",SCOPE);data.put("expected_version",version);data.put("expected_digest",snapshot);data.put("manifest_revision",1);data.put("prepared_at",prepared.toString());data.put("expires_at",expires.toString());data.put("max_executions",1);return keyed(s.owner(),"confirmation-authority",data);
    }
    private Map<String,Object> preparation(AuthService.Session s,Map<String,Object> p,Map<String,Object> c,Graph graph){
        Map<String,Object> target=new LinkedHashMap<>();for(String f:List.of("id","title","project_id"))target.put(f,c.get(f));target.put("created_at",AuthService.instant(c.get("created_at")).toString());
        Map<String,Object> result=new LinkedHashMap<>();result.put("id",p.get("id"));result.put("status","PREPARED");result.put("scope_policy",SCOPE);result.put("conversation",target);result.put("expected_version",p.get("expected_version"));result.put("expected_digest",graph.digest);result.put("source_counts",graph.counts);result.put("payload_hash",open(s.owner(),(UUID)p.get("id"),"payload",p.get("payload_hash_ref")));for(String f:List.of("prepared_at","expires_at"))result.put(f,AuthService.instant(p.get(f)).toString());return result;
    }
    Map<String,Object> confirm(AuthService.Session s,UUID key,UUID preparation,JsonNode body){
        MemoryService.exact(body,"payload_hash","expected_version","expected_digest","confirm");String submittedHash=digest(body,"payload_hash"),submittedDigest=digest(body,"expected_digest");long version=MemoryService.number(body,"expected_version",1);if(!body.get("confirm").isBoolean()||!body.get("confirm").asBoolean())throw ApiException.bad();AuthService.requireOwner(s);
        String request=keyed(s.owner(),"confirm-request",Map.of("preparation_id",preparation,"request",body));
        return transaction(()->{
            session(s,false);var receipt=prior(s,key,request);if(receipt!=null)return receipt;
            MemoryService.unpaused(session(s,true));
            // Owner lock serializes preparation lookup; lock the conversation before descendants.
            var rows=db.queryForList("SELECT * FROM conversation_delete_preparations WHERE owner_id=? AND id=?",s.owner(),preparation);if(rows.isEmpty())throw ApiException.missing();var p=rows.getFirst();MemoryService.binding(s,p);
            if(key.equals(p.get("issuance_key")))throw new ApiException(409,"IDEMPOTENCY_CONFLICT");
            if(p.get("consumed_at")!=null)throw new ApiException(409,"APPROVAL_CONSUMED");
            UUID id=(UUID)p.get("conversation_id");var c=conversation(s.owner(),id);live(c);
            if(p.get("revoked_at")!=null)throw new ApiException(403,"AUTHORITY_NOT_VALID");Instant prepared=AuthService.instant(p.get("prepared_at")),expires=AuthService.instant(p.get("expires_at"));authorityTime(prepared,expires,now());
            if(number(p,"expected_version")!=version||!Json.equal(submittedDigest,open(s.owner(),preparation,"snapshot",p.get("expected_digest_ref")))||!Json.equal(submittedHash,open(s.owner(),preparation,"payload",p.get("payload_hash_ref")))||!Json.equal(submittedHash,payloadHash(s,preparation,id,version,submittedDigest,AuthService.instant(p.get("prepared_at")),expires)))throw new ApiException(409,"PRECONDITION_CHANGED");
            Graph graph=graph(s.owner(),c);eligible(graph);expected(c,version,submittedDigest,graph);long next=MemoryService.advance(version);
            for(var job:graph.rows.get("jobs"))MemoryService.advance(number(job,"lease_generation"));
            MemoryService.unpaused(session(s,true));Instant deleted=now();authorityTime(prepared,expires,deleted);
            UUID deletion=UUID.randomUUID(),approval=UUID.randomUUID();Timestamp at=Timestamp.from(deleted);
            db.update("INSERT INTO conversation_delete_approvals(id,owner_id,preparation_id,conversation_id,session_hash,security_epoch,consumed_at) VALUES (?,?,?,?,?,?,?)",approval,s.owner(),preparation,id,s.hash(),s.epoch(),at);
            db.update("INSERT INTO conversation_deletion_ledger(id,owner_id,conversation_id,preparation_id,approval_id,deleted_version,tombstone_version,deleted_at) VALUES (?,?,?,?,?,?,?,?)",deletion,s.owner(),id,preparation,approval,version,next,at);
            if(db.update("UPDATE conversations SET title=NULL,deleted_at=?,content_version=? WHERE owner_id=? AND id=? AND deleted_at IS NULL AND content_version=?",at,next,s.owner(),id,version)!=1)throw new ApiException(409,"PRECONDITION_CHANGED");
            redact(s.owner(),id,at);
            db.update("UPDATE conversation_delete_preparations SET expected_digest_ref=NULL,payload_hash_ref=NULL,revoked_at=?,consumed_at=CASE WHEN id=? THEN ? ELSE consumed_at END WHERE owner_id=? AND conversation_id=? AND consumed_at IS NULL",at,preparation,at,s.owner(),id);
            db.update("INSERT INTO conversation_deletion_mutations(owner_id,idempotency_key,session_hash,security_epoch,request_digest,deletion_id,preparation_id) VALUES (?,?,?,?,?,?,?)",s.owner(),key,s.hash(),s.epoch(),request,deletion,preparation);
            audit(s.owner(),"CONVERSATION_SOURCE_DELETED",id);db.update("INSERT INTO outbox_events(id,owner_id,aggregate_id,event_type) VALUES (?,?,?,'CONVERSATION_SOURCE_DELETED')",UUID.randomUUID(),s.owner(),id);
            db.execute("SET CONSTRAINTS conversation_deletion_complete IMMEDIATE");var committed=prior(s,key,request);
            // Recheck after constraints and receipt reads, including a possible wall-clock rollback.
            MemoryService.unpaused(session(s,true));Instant observed=now();authorityTime(prepared,expires,observed);observationTime(deleted,observed);
            committed.put("checked_at",observed.toString());return committed;
        });
    }
    private void redact(UUID owner,UUID id,Timestamp at){
        db.update("UPDATE messages SET content_ref=NULL,body_hash=NULL,content_deleted_at=? WHERE owner_id=? AND conversation_id=?",at,owner,id);
        db.update("UPDATE runs SET result_ref=NULL,context_history_eligible=false,content_deleted_at=? WHERE owner_id=? AND conversation_id=?",at,owner,id);
        for(String table:List.of("approvals","mock_effects"))db.update("UPDATE "+table+" SET payload_hash=NULL,content_deleted_at=? WHERE owner_id=? AND action_id IN (SELECT a.id FROM action_proposals a JOIN runs r ON r.id=a.run_id WHERE r.owner_id=? AND r.conversation_id=?)",at,owner,owner,id);
        db.update("UPDATE authorizations SET payload_hash=NULL,source_deleted_at=?,revoked_at=COALESCE(revoked_at,?) WHERE owner_id=? AND action_id IN (SELECT a.id FROM action_proposals a JOIN runs r ON r.id=a.run_id WHERE r.owner_id=? AND r.conversation_id=?)",at,at,owner,owner,id);
        db.update("UPDATE action_proposals SET payload_ref=NULL,payload_hash=NULL,content_deleted_at=? WHERE owner_id=? AND run_id IN (SELECT id FROM runs WHERE owner_id=? AND conversation_id=?)",at,owner,owner,id);
        db.update("UPDATE jobs SET lease_owner=NULL,lease_until=NULL,lease_generation=lease_generation+1 WHERE owner_id=? AND run_id IN (SELECT id FROM runs WHERE owner_id=? AND conversation_id=?)",owner,owner,id);
        for(String table:List.of("inference_context_history","inference_context_memories"))db.update("DELETE FROM "+table+" WHERE context_id IN (SELECT id FROM inference_contexts WHERE owner_id=? AND conversation_id=?)",owner,id);
        db.update("UPDATE inference_contexts SET state='INVALIDATED',invalidated_at=COALESCE(invalidated_at,?) WHERE owner_id=? AND conversation_id=?",at,owner,id);
    }
    private void audit(UUID owner,String type,UUID resource){db.update("INSERT INTO audit_events(id,owner_id,event_type,resource_id) VALUES (?,?,?,?)",UUID.randomUUID(),owner,type,resource);}
    private Map<String,Object> receipt(Map<String,Object> row){Map<String,Object> result=new LinkedHashMap<>();result.put("deletion_id",row.get("id"));for(String f:List.of("conversation_id","preparation_id","deleted_version","tombstone_version"))result.put(f,row.get(f));result.put("scope_policy",SCOPE);result.put("deleted_at",AuthService.instant(row.get("deleted_at")).toString());return result;}
    private Map<String,Object> outcome(Map<String,Object> ledger){Instant observed=now();if(ledger!=null)observationTime(AuthService.instant(ledger.get("deleted_at")),observed);Map<String,Object> result=new LinkedHashMap<>();result.put("status",ledger==null?"NOT_OBSERVED":"COMMITTED");result.put("receipt",ledger==null?null:receipt(ledger));result.put("checked_at",observed.toString());return result;}
    private Map<String,Object> prior(AuthService.Session s,UUID key,String request){var rows=db.queryForList("SELECT * FROM conversation_deletion_mutations WHERE owner_id=? AND idempotency_key=?",s.owner(),key);if(rows.isEmpty())return null;var m=rows.getFirst();MemoryService.binding(s,m);if(request!=null&&!Json.equal(request,(String)m.get("request_digest")))throw new ApiException(409,"IDEMPOTENCY_CONFLICT");return outcome(db.queryForMap("SELECT * FROM conversation_deletion_ledger WHERE owner_id=? AND id=?",s.owner(),m.get("deletion_id")));}
    Map<String,Object> reconcile(AuthService.Session s,UUID key){return transaction(()->{session(s,false);var result=prior(s,key,null);session(s,false);return result==null?outcome(null):result;});}

    private record Scope(String table,String condition,String order,int limit,String bytes){}
    private static final String RUNS="SELECT id FROM runs WHERE conversation_id=?", ACTIONS="SELECT a.id FROM action_proposals a JOIN runs r ON r.id=a.run_id WHERE r.conversation_id=?", CONTEXTS="SELECT id FROM inference_contexts WHERE conversation_id=? OR run_id IN ("+RUNS+")";
    private static final List<Scope> SCOPES=List.of(
        new Scope("runs","x.conversation_id=?","x.id",RUN_LIMIT,"octet_length(x.result_ref)"),
        new Scope("messages","x.conversation_id=? OR x.run_id IN ("+RUNS+")","x.id",MESSAGE_LIMIT,"COALESCE(octet_length(x.content_ref),0)+COALESCE(octet_length(x.body_hash),0)"),
        new Scope("action_proposals","x.run_id IN ("+RUNS+")","x.id",ACTION_LIMIT,"COALESCE(octet_length(x.payload_ref),0)+COALESCE(octet_length(x.payload_hash),0)"),
        new Scope("approvals","x.action_id IN ("+ACTIONS+")","x.id",ROW_LIMIT,"octet_length(x.payload_hash)"),
        new Scope("authorizations","x.action_id IN ("+ACTIONS+")","x.id",ROW_LIMIT,"octet_length(x.payload_hash)"),
        new Scope("jobs","x.run_id IN ("+RUNS+") OR x.action_id IN ("+ACTIONS+")","x.id",ROW_LIMIT,null),
        new Scope("action_executions","x.action_id IN ("+ACTIONS+")","x.action_id",ROW_LIMIT,null),
        new Scope("mock_effects","x.action_id IN ("+ACTIONS+")","x.action_id",ROW_LIMIT,"octet_length(x.payload_hash)"),
        new Scope("inference_reservations","x.run_id IN ("+RUNS+")","x.id",ROW_LIMIT,null),
        new Scope("inference_contexts","x.conversation_id=? OR x.run_id IN ("+RUNS+")","x.id",RUN_LIMIT,null),
        new Scope("inference_context_history","x.context_id IN ("+CONTEXTS+") OR x.message_id IN (SELECT id FROM messages WHERE conversation_id=?) OR x.run_id IN ("+RUNS+")","x.context_id,x.ordinal",ROW_LIMIT,null),
        new Scope("inference_context_memories","x.context_id IN ("+CONTEXTS+")","x.context_id,x.memory_id",ROW_LIMIT,null)
    );
    private static Object[] parameters(String sql,UUID target){return Collections.nCopies((int)sql.chars().filter(c->c=='?').count(),target).toArray();}
    private static Object normalized(Object value){if(value instanceof Timestamp t)return t.toInstant().toString();if(value instanceof OffsetDateTime t)return t.toInstant().toString();if(value instanceof UUID u)return u.toString();return value;}
    private static Map<String,Object> canonicalRow(Map<String,Object> row){Map<String,Object> result=new TreeMap<>();row.forEach((k,v)->result.put(k,normalized(v)));return result;}
    private final class Graph {
        final Map<String,List<Map<String,Object>>> rows=new LinkedHashMap<>();final SortedSet<String> blockers=new TreeSet<>();final Map<String,Object> counts=new LinkedHashMap<>();String digest;
    }
    private Graph graph(UUID owner,Map<String,Object> conversation){
        long start=System.nanoTime();UUID target=(UUID)conversation.get("id");Graph graph=new Graph();long total=0,bytes=Objects.toString(conversation.get("title"),"").getBytes(StandardCharsets.UTF_8).length;
        // Metadata-only cap+1 probes happen before source/ciphertext materialization.
        for(Scope scope:SCOPES){budget(start);String from=" FROM "+scope.table+" x WHERE "+scope.condition;int limit=(int)Math.min(scope.limit,ROW_LIMIT-total);int count=db.queryForList("SELECT 1"+from+" LIMIT "+(limit+1),parameters(from,target)).size();if(count>limit)throw new ApiException(413,"CONVERSATION_DELETION_SCOPE_LIMIT");total+=count;if(scope.bytes!=null){Long size=db.queryForObject("SELECT COALESCE(sum("+scope.bytes+"),0)"+from,Long.class,parameters(from,target));bytes+=size==null?0:size;if(bytes>BYTE_LIMIT)throw new ApiException(413,"CONVERSATION_DELETION_SCOPE_LIMIT");}}
        for(Scope scope:SCOPES){budget(start);String sql="SELECT x.* FROM "+scope.table+" x WHERE "+scope.condition+" ORDER BY "+scope.order+" LIMIT "+(scope.limit+1)+" FOR UPDATE OF x";graph.rows.put(scope.table,db.queryForList(sql,parameters(sql,target)));}
        validateGraph(owner,target,graph);
        if(!"14".equals(db.queryForObject("SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1",String.class)))graph.blockers.add("INTEGRITY_UNVERIFIED");
        Object title=conversation.get("title");if(!(title instanceof String t)||t.isEmpty()||t.codePointCount(0,t.length())>200||t.indexOf('\0')>=0)graph.blockers.add("INTEGRITY_UNVERIFIED");
        graph.counts.put("messages",graph.rows.get("messages").size());graph.counts.put("runs",graph.rows.get("runs").size());graph.counts.put("run_results",graph.rows.get("runs").stream().filter(r->r.get("result_ref")!=null).count());graph.counts.put("action_payloads",graph.rows.get("action_proposals").stream().filter(r->r.get("payload_ref")!=null).count());graph.counts.put("inference_contexts",graph.rows.get("inference_contexts").size());
        if(graph.blockers.isEmpty()){
            // Each row has its own keyed commitment; no transcript manifest is persisted or returned.
            StringBuilder manifest=new StringBuilder(Json.canonical(Map.of("revision",1,"scope_policy",SCOPE,"policy_version",POLICY,"conversation",canonicalRow(conversation),"counts",graph.counts)));
            for(var table:graph.rows.entrySet()){manifest.append('|').append(table.getKey()).append(':');for(var row:table.getValue()){budget(start);manifest.append(keyed(owner,"manifest-row:"+table.getKey(),canonicalRow(row))).append(';');}}
            graph.digest=memories.keys.digest(owner,"conversation-delete:manifest",manifest.toString());
        }budget(start);return graph;
    }
    private static Map<UUID,Map<String,Object>> index(List<Map<String,Object>> rows,String field){Map<UUID,Map<String,Object>> indexed=new HashMap<>();for(var row:rows)indexed.put((UUID)row.get(field),row);return indexed;}
    private static void check(Graph g,boolean condition){if(!condition)g.blockers.add("INTEGRITY_UNVERIFIED");}
    private static void lifecycle(Graph g,Object value,Set<String> allowed){String state=Objects.toString(value,"");if(allowed.contains(state))return;if(state.equals("UNKNOWN"))g.blockers.add("UNKNOWN_WORK");else if(state.equals("PENDING"))g.blockers.add("PENDING_APPROVAL");else if(ACTIVE.contains(state))g.blockers.add("ACTIVE_WORK");else g.blockers.add("INTEGRITY_UNVERIFIED");}
    private void validateGraph(UUID owner,UUID target,Graph g){
        var runs=index(g.rows.get("runs"),"id");var messages=index(g.rows.get("messages"),"id");var actions=index(g.rows.get("action_proposals"),"id");var approvals=index(g.rows.get("approvals"),"id");var contexts=index(g.rows.get("inference_contexts"),"id");var executions=index(g.rows.get("action_executions"),"action_id");
        for(var table:g.rows.entrySet())for(var row:table.getValue()){if(row.containsKey("owner_id"))check(g,owner.equals(row.get("owner_id")));if(row.containsKey("content_deleted_at"))check(g,row.get("content_deleted_at")==null);if(row.containsKey("source_deleted_at"))check(g,row.get("source_deleted_at")==null);
            for(String field:List.of("version","lease_generation","action_version","context_epoch","history_epoch","security_epoch"))if(row.get(field) instanceof Number n)check(g,n.longValue()>=(field.equals("lease_generation")?0:1)&&n.longValue()<=MemoryService.MAX);}
        for(var r:runs.values()){check(g,target.equals(r.get("conversation_id")));lifecycle(g,r.get("status"),RUN_CLOSED);}
        for(var m:messages.values())check(g,target.equals(m.get("conversation_id"))&&runs.containsKey(m.get("run_id"))&&m.get("content_ref")!=null&&m.get("body_hash")!=null);
        for(var a:actions.values()){check(g,runs.containsKey(a.get("run_id"))&&a.get("payload_ref")!=null&&a.get("payload_hash")!=null&&"calendar.event.create".equals(a.get("tool_id"))&&"1".equals(a.get("tool_version")));lifecycle(g,a.get("status"),CLOSED);}
        for(var a:approvals.values()){var action=actions.get(a.get("action_id"));check(g,action!=null&&Objects.equals(a.get("payload_hash"),action.get("payload_hash")));lifecycle(g,a.get("status"),Set.of("CONSUMED","REJECTED","EXPIRED","REVOKED"));if("CONSUMED".equals(a.get("status")))check(g,a.get("consumed_at")!=null&&a.get("approved_session_hash")!=null);if(action!=null)check(g,number(a,"action_version")<=number(action,"version"));}
        for(var z:g.rows.get("authorizations")){var action=actions.get(z.get("action_id"));var approval=approvals.get(z.get("approval_id"));check(g,action!=null&&approval!=null&&Objects.equals(approval.get("action_id"),z.get("action_id"))&&Objects.equals(z.get("payload_hash"),action.get("payload_hash"))&&"CONSUMED".equals(approval.get("status"))&&Objects.equals(z.get("security_epoch"),action.get("security_epoch"))&&number(z,"max_executions")==1&&z.get("grant_id")==null&&z.get("user_intent_id")==null);}
        for(var e:executions.values()){check(g,actions.containsKey(e.get("action_id")));lifecycle(g,e.get("status"),CLOSED);check(g,e.get("finished_at")!=null&&e.get("started_at")!=null&&(e.get("finished_at")==null||!AuthService.instant(e.get("finished_at")).isBefore(AuthService.instant(e.get("started_at")))));}
        for(var e:g.rows.get("mock_effects")){var a=actions.get(e.get("action_id"));check(g,a!=null&&Objects.equals(e.get("payload_hash"),a.get("payload_hash"))&&Objects.equals(e.get("tool_id"),a.get("tool_id"))&&executions.containsKey(e.get("action_id")));}
        for(var j:g.rows.get("jobs")){check(g,runs.containsKey(j.get("run_id")));lifecycle(g,j.get("status"),CLOSED);if("PLAN".equals(j.get("kind")))check(g,j.get("action_id")==null&&("PLAN:"+j.get("run_id")).equals(j.get("logical_key"))&&!Boolean.TRUE.equals(j.get("started_effect")));else if("EXECUTE".equals(j.get("kind"))){var a=actions.get(j.get("action_id"));check(g,a!=null&&Objects.equals(a.get("run_id"),j.get("run_id"))&&("EXECUTE:"+j.get("action_id")).equals(j.get("logical_key")));if(Boolean.TRUE.equals(j.get("started_effect"))){var e=executions.get(j.get("action_id"));check(g,e!=null&&number(e,"lease_generation")<=number(j,"lease_generation"));}}else check(g,false);}
        var reservationsByRun=index(g.rows.get("inference_reservations"),"run_id");
        Map<UUID,List<Map<String,Object>>> byRun=new HashMap<>();for(var c:contexts.values()){check(g,runs.containsKey(c.get("run_id"))&&target.equals(c.get("conversation_id"))&&reservationsByRun.containsKey(c.get("run_id")));lifecycle(g,c.get("state"),Set.of("SETTLED","INVALIDATED"));if("INVALIDATED".equals(c.get("state")))check(g,c.get("invalidated_at")!=null);byRun.computeIfAbsent((UUID)c.get("run_id"),id->new ArrayList<>()).add(c);}
        for(var q:g.rows.get("inference_reservations")){var r=runs.get(q.get("run_id"));lifecycle(g,q.get("status"),Set.of("SUCCEEDED","FAILED","CANCELLED"));var cs=byRun.getOrDefault(q.get("run_id"),List.of());if(number(q,"accounting_version")!=1||q.get("dispatch_context_id")==null||!"CONTEXT_V1_NO_RESPONSE_CACHE".equals(q.get("dispatch_cache_policy"))||q.get("started_at")==null)g.blockers.add("UNSUPPORTED_LEGACY_INFERENCE");check(g,r!=null&&Boolean.TRUE.equals(r.get("context_history_eligible"))&&cs.size()==1);if(cs.size()==1){var c=cs.getFirst();if(q.get("dispatch_context_id")!=null)check(g,q.get("dispatch_context_id").equals(c.get("id"))&&Objects.equals(q.get("security_epoch"),c.get("security_epoch")));}check(g,"local-mlx-v1".equals(q.get("model_id"))&&number(q,"budget_revision")==1&&Objects.toString(q.get("body_hash"),"").matches("[0-9a-f]{64}")&&Objects.toString(q.get("model_manifest_hash"),"").matches("[0-9a-f]{64}"));}
        for(var h:g.rows.get("inference_context_history")){var c=contexts.get(h.get("context_id"));var m=messages.get(h.get("message_id"));check(g,c!=null&&m!=null&&Objects.equals(m.get("run_id"),h.get("run_id"))&&Objects.equals(m.get("role"),h.get("role"))&&runs.containsKey(h.get("run_id")));}
        for(var b:g.rows.get("inference_context_memories"))check(g,contexts.containsKey(b.get("context_id")));
        check(g,db.queryForList("SELECT 1 FROM inference_contexts c LEFT JOIN sessions s ON s.token_hash=c.session_hash WHERE c.conversation_id=? AND (s.token_hash IS NULL OR s.owner_id IS DISTINCT FROM c.owner_id) LIMIT 1",target).isEmpty());
        check(g,db.queryForList("SELECT 1 FROM approvals p JOIN action_proposals a ON a.id=p.action_id JOIN runs r ON r.id=a.run_id LEFT JOIN sessions s ON s.token_hash=p.approved_session_hash WHERE r.conversation_id=? AND p.status='CONSUMED' AND (s.token_hash IS NULL OR s.owner_id IS DISTINCT FROM p.owner_id) LIMIT 1",target).isEmpty());
        check(g,db.queryForList("SELECT 1 FROM inference_context_memories b JOIN inference_contexts c ON c.id=b.context_id LEFT JOIN memories m ON m.id=b.memory_id AND m.owner_id=c.owner_id LEFT JOIN memory_slots s ON s.id=b.slot_id AND s.owner_id=c.owner_id WHERE c.conversation_id=? AND (m.id IS NULL OR s.id IS NULL OR m.slot_id<>s.id) LIMIT 1",target).isEmpty());
    }
    private static void eligible(Graph graph){if(graph.blockers.isEmpty())return;if(graph.blockers.contains("INTEGRITY_UNVERIFIED"))throw new ApiException(409,"CONVERSATION_GRAPH_UNVERIFIED");if(graph.blockers.contains("ACTIVE_WORK")||graph.blockers.contains("UNKNOWN_WORK")||graph.blockers.contains("PENDING_APPROVAL"))throw new ApiException(409,"CONVERSATION_NOT_QUIESCENT");throw new ApiException(409,"CONVERSATION_DELETION_UNSUPPORTED");}
}

package dev.myjarvis.core;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** Explicit owner data only. Neither a tool executor nor a model authority issuer. */
@Service
final class MemoryService {
    static final long MAX=9007199254740991L;
    final JdbcTemplate db; final Vault vault; final MemoryKeyCodec keys;
    final TransactionTemplate writeTx,readTx;
    MemoryService(JdbcTemplate db,Vault vault,MemoryKeyCodec keys,PlatformTransactionManager manager){
        this.db=db;this.vault=vault;this.keys=keys;writeTx=new TransactionTemplate(manager);writeTx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);writeTx.setTimeout(15);
        readTx=new TransactionTemplate(manager);readTx.setReadOnly(true);readTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);readTx.setTimeout(15);
    }
    static void exact(JsonNode body,String... fields){if(body==null)throw ApiException.bad();Json.validate(body);Json.fields(body,fields);if(body.size()!=fields.length)throw ApiException.bad();if(Json.canonical(body).getBytes(StandardCharsets.UTF_8).length>16384)throw new ApiException(413,"BODY_TOO_LARGE");}
    static long number(JsonNode body,String field,long min){long n=Json.integer(body,field);if(n<min||n>MAX)throw ApiException.bad();return n;}
    static UUID uuid(String value){try{UUID id=UUID.fromString(value);if(!id.toString().equals(value))throw ApiException.bad();return id;}catch(IllegalArgumentException e){throw ApiException.bad();}}
    static String text(JsonNode body,String field,int points,int bytes){String value=Json.string(body,field,points);if(value.codePoints().allMatch(MemoryService::blankCodePoint)||value.indexOf('\0')>=0||value.getBytes(StandardCharsets.UTF_8).length>bytes)throw ApiException.bad();return value;}
    // Same explicit Unicode White_Space + FEFF convention as the wire contract.
    static boolean blankCodePoint(int cp){return cp>=0x09&&cp<=0x0D||cp==0x20||cp==0x85||cp==0xA0||cp==0x1680||cp>=0x2000&&cp<=0x200A||cp==0x2028||cp==0x2029||cp==0x202F||cp==0x205F||cp==0x3000||cp==0xFEFF;}
    Instant databaseNow(){return AuthService.instant(db.queryForObject("SELECT clock_timestamp()",Object.class));}
    static void identity(JsonNode args){String scope=Json.string(args,"scope",7);if(!Set.of("GLOBAL","PROJECT").contains(scope))throw ApiException.bad();JsonNode project=args.get("project_id");if(project==null)throw ApiException.bad();if(scope.equals("GLOBAL")){if(!project.isNull())throw ApiException.bad();}else Json.uuid(args,"project_id");String key=Json.string(args,"key",96);if(!key.matches("[a-z][a-z0-9_.-]{0,95}"))throw ApiException.bad();}
    static void arguments(JsonNode args,boolean create){
        if(create){exact(args,"scope","project_id","key","kind","value","change_reason","expected_generation","revive_deleted");identity(args);if(!Set.of("PROFILE","PREFERENCE").contains(Json.string(args,"kind",10))||!args.get("revive_deleted").isBoolean())throw ApiException.bad();number(args,"expected_generation",0);}
        else {exact(args,"value","change_reason","expected_version","expected_generation");number(args,"expected_version",1);number(args,"expected_generation",1);}
        text(args,"value",2000,8000);text(args,"change_reason",300,1200);
    }
    Map<String,Object> session(AuthService.Session s,boolean lock,boolean recent){
        AuthService.requireOwner(s);
        var owners=db.queryForList("SELECT * FROM owners WHERE id=?"+(lock?" FOR UPDATE":""),s.owner());
        if(owners.isEmpty()||s.epoch()!=((Number)owners.getFirst().get("security_epoch")).longValue())throw new ApiException(401,"SESSION_REVOKED");
        var rows=db.queryForList("SELECT *, (reauthenticated_at >= clock_timestamp()-interval '5 minutes' AND reauthenticated_at <= clock_timestamp()) AS recent_reauth FROM sessions WHERE token_hash=? AND owner_id=? AND security_epoch=? AND revoked_at IS NULL AND created_at>clock_timestamp()-interval '12 hours' AND last_seen_at>clock_timestamp()-interval '2 hours'"+(lock?" FOR UPDATE":""),s.hash(),s.owner(),s.epoch());
        if(rows.isEmpty())throw new ApiException(401,"SESSION_REVOKED");
        if(recent&&!Boolean.TRUE.equals(rows.getFirst().get("recent_reauth")))throw new ApiException(403,"REAUTHENTICATION_REQUIRED");return owners.getFirst();
    }
    static void unpaused(Map<String,Object> owner){if(Boolean.TRUE.equals(owner.get("execution_paused")))throw new ApiException(409,"EXECUTION_PAUSED");}
    static void binding(AuthService.Session s,Map<String,Object> row){if(!Json.equal(s.hash(),(String)row.get("session_hash"))||s.epoch()!=((Number)row.get("security_epoch")).longValue())throw new ApiException(403,"AUTHORITY_NOT_VALID");}
    void project(UUID owner,UUID project){if(project!=null&&db.queryForList("SELECT id FROM projects WHERE owner_id=? AND id=?",owner,project).isEmpty())throw ApiException.missing();}
    static UUID projectId(JsonNode args){return args.get("project_id").isNull()?null:Json.uuid(args,"project_id");}
    Map<String,Object> findSlot(UUID owner,JsonNode args,boolean lock){
        UUID project=projectId(args);project(owner,project);
        var rows=db.queryForList("SELECT * FROM memory_slots WHERE owner_id=? AND scope=? AND project_id IS NOT DISTINCT FROM ? AND key_digest=?"+(lock?" FOR UPDATE":""),owner,args.get("scope").asText(),project,keys.slot(owner,args.get("key").asText()));return rows.isEmpty()?null:rows.getFirst();
    }
    Map<String,Object> slot(UUID owner,UUID id,boolean lock){var rows=db.queryForList("SELECT * FROM memory_slots WHERE owner_id=? AND id=?"+(lock?" FOR UPDATE":""),owner,id);if(rows.isEmpty())throw ApiException.missing();project(owner,(UUID)rows.getFirst().get("project_id"));return rows.getFirst();}
    Map<String,Object> record(UUID owner,UUID id,boolean active){var rows=db.queryForList("SELECT m.*,s.scope,s.project_id,s.key_digest FROM memories m JOIN memory_slots s ON s.owner_id=m.owner_id AND s.id=m.slot_id WHERE m.owner_id=? AND m.id=?"+(active?" AND m.deleted_at IS NULL AND s.current_memory_id=m.id":""),owner,id);if(rows.isEmpty())throw ApiException.missing();project(owner,(UUID)rows.getFirst().get("project_id"));return rows.getFirst();}
    static void expected(Map<String,Object> row,long version,long generation){if(((Number)row.get("version")).longValue()!=version||((Number)row.get("generation")).longValue()!=generation)throw new ApiException(409,"PRECONDITION_CHANGED");}
    static long advance(long value){if(value>=MAX)throw new ApiException(409,"VERSION_EXHAUSTED");return value+1;}
    <T> T read(AuthService.Session s,Supplier<T> supplier){return readTx.execute(tx->{session(s,false,false);return supplier.get();});}
    @SuppressWarnings("unchecked") Map<String,Object> payload(Map<String,Object> row){UUID owner=(UUID)row.get("owner_id"),id=(UUID)row.get("id");return Json.MAPPER.convertValue(Json.parse(vault.open(owner,id,"memory-current",(String)row.get("payload_ref")).getBytes(StandardCharsets.UTF_8)),Map.class);}
    Map<String,Object> view(Map<String,Object> row){
        Map<String,Object> result=new LinkedHashMap<>(payload(row));for(String field:List.of("id","scope","project_id","kind","version","generation"))result.put(field,row.get(field));
        for(String field:List.of("confirmed_at","created_at","updated_at"))result.put(field,AuthService.instant(row.get(field)).toString());return result;
    }
    Map<String,Object> get(AuthService.Session s,UUID id){return read(s,()->view(record(s.owner(),id,true)));}
    Map<String,Object> keyState(AuthService.Session s,JsonNode args){exact(args,"scope","project_id","key");identity(args);return read(s,()->{
        var slot=findSlot(s.owner(),args,false);Map<String,Object> response=new LinkedHashMap<>();response.put("status",slot==null||((Number)slot.get("generation")).longValue()==0?"ABSENT":Boolean.TRUE.equals(slot.get("tombstone"))?"DELETED":"ACTIVE");response.put("generation",slot==null?0L:slot.get("generation"));response.put("memory_id",slot==null?null:slot.get("current_memory_id"));response.put("version",slot==null||slot.get("current_memory_id")==null?null:record(s.owner(),(UUID)slot.get("current_memory_id"),true).get("version"));return response;});}
    Map<String,Object> list(AuthService.Session s,String scope,UUID project,String cursor,int limit){
        if(!Set.of("GLOBAL","PROJECT").contains(scope)||scope.equals("GLOBAL")!=(project==null)||limit<1||limit>50)throw ApiException.bad();
        return read(s,()->{project(s.owner(),project);String context="list|"+scope+"|"+project;
            List<Object> params=new ArrayList<>(Arrays.asList(s.owner(),scope,project));String boundary="";
            if(cursor!=null){String[] b=keys.boundary(s.owner(),context,cursor).split("\\|",-1);if(b.length!=2)throw ApiException.bad();try{params.add(Timestamp.from(Instant.parse(b[0])));params.add(uuid(b[1]));}catch(Exception e){throw ApiException.bad();}boundary=" AND (m.created_at,m.id)<(?,?)";}
            params.add(limit+1);var rows=db.queryForList("SELECT m.*,s.scope,s.project_id FROM memories m JOIN memory_slots s ON s.owner_id=m.owner_id AND s.id=m.slot_id WHERE m.owner_id=? AND s.scope=? AND s.project_id IS NOT DISTINCT FROM ? AND m.deleted_at IS NULL AND s.current_memory_id=m.id"+boundary+" ORDER BY m.created_at DESC,m.id DESC LIMIT ?",params.toArray());
            List<Map<String,Object>> items=rows.stream().limit(limit).map(this::view).toList();Map<String,Object> result=new LinkedHashMap<>();result.put("items",items);result.put("next_cursor",rows.size()>limit?keys.cursor(s.owner(),context,AuthService.instant(rows.get(limit-1).get("created_at"))+"|"+rows.get(limit-1).get("id")):null);return result;});
    }
    @SuppressWarnings("unchecked") Map<String,Object> revisions(AuthService.Session s,UUID id,String cursor,int limit){if(limit<1||limit>50)throw ApiException.bad();return read(s,()->{
        record(s.owner(),id,true);String context="revisions|"+id;long before=MAX;
        if(cursor!=null){try{before=Long.parseLong(keys.boundary(s.owner(),context,cursor));if(before<1||before>MAX)throw ApiException.bad();}catch(NumberFormatException e){throw ApiException.bad();}}
        var rows=db.queryForList("SELECT * FROM memory_revisions WHERE owner_id=? AND memory_id=? AND version"+(cursor==null?"<=":"<")+"? ORDER BY version DESC LIMIT ?",s.owner(),id,before,limit+1);List<Map<String,Object>> items=new ArrayList<>();
        for(var row:rows.stream().limit(limit).toList()){long version=((Number)row.get("version")).longValue();Map<String,Object> content=Json.MAPPER.convertValue(Json.parse(vault.open(s.owner(),id,"memory-revision:"+version,(String)row.get("payload_ref")).getBytes(StandardCharsets.UTF_8)),Map.class);content.remove("key");content.put("memory_id",id);content.put("version",version);content.put("confirmed_at",AuthService.instant(row.get("confirmed_at")).toString());items.add(content);}
        Map<String,Object> result=new LinkedHashMap<>();result.put("items",items);result.put("next_cursor",rows.size()>limit?keys.cursor(s.owner(),context,rows.get(limit-1).get("version").toString()):null);return result;});}
    Map<String,Object> receipt(UUID owner,Map<String,Object> row){Map<String,Object> result=new LinkedHashMap<>();if(row==null){result.put("status","NOT_OBSERVED");for(String f:List.of("memory_id","version","generation","operation"))result.put(f,null);return result;}var memory=record(owner,(UUID)row.get("memory_id"),false);result.put("status",memory.get("deleted_at")==null?"COMMITTED":"REDACTED_DELETED");for(String f:List.of("memory_id","version","generation","operation"))result.put(f,row.get(f));return result;}
    Map<String,Object> prior(AuthService.Session s,UUID key,String digest){var rows=db.queryForList("SELECT * FROM memory_mutations WHERE owner_id=? AND idempotency_key=?",s.owner(),key);if(rows.isEmpty())return null;var row=rows.getFirst();binding(s,row);if(!Json.equal(digest,(String)row.get("request_digest")))throw new ApiException(409,"IDEMPOTENCY_CONFLICT");return receipt(s.owner(),row);}
    Map<String,Object> reconcile(AuthService.Session s,UUID key){return read(s,()->{var rows=db.queryForList("SELECT * FROM memory_mutations WHERE owner_id=? AND idempotency_key=?",s.owner(),key);return receipt(s.owner(),rows.isEmpty()?null:rows.getFirst());});}
    void mutation(AuthService.Session s,UUID key,String digest,UUID authority,UUID id,long version,long generation,String operation){db.update("INSERT INTO memory_mutations(owner_id,idempotency_key,session_hash,security_epoch,request_digest,authority_id,memory_id,version,generation,operation) VALUES (?,?,?,?,?,?,?,?,?,?)",s.owner(),key,s.hash(),s.epoch(),digest,authority,id,version,generation,operation);}
    void event(UUID owner,UUID memory,long version,String type){db.update("INSERT INTO memory_invalidation_outbox(id,owner_id,memory_id,version,event_type) VALUES (?,?,?,?,?)",UUID.randomUUID(),owner,memory,version,type);audit(owner,"MEMORY_"+type,memory);}
    void audit(UUID owner,String type,UUID id){db.update("INSERT INTO audit_events(id,owner_id,event_type,resource_id) VALUES (?,?,?,?)",UUID.randomUUID(),owner,type,id);}
    Map<String,Object> save(AuthService.Session s,UUID key,UUID target,JsonNode body,boolean create){
        exact(body,"intent_id","arguments");UUID intentId=Json.uuid(body,"intent_id");JsonNode args=body.get("arguments");arguments(args,create);String operation=create?"CREATE":"REVISE";
        AuthService.requireOwner(s);String digest=keys.digest(s.owner(),"mutation",operation+"|"+target+"|"+Json.canonical(body));
        return writeTx.execute(tx->{var owner=session(s,true,false);var prior=prior(s,key,digest);if(prior!=null)return prior;unpaused(owner);
            var intents=db.queryForList("SELECT * FROM memory_write_intents WHERE owner_id=? AND id=?",s.owner(),intentId);if(intents.isEmpty())throw ApiException.missing();var intent=intents.getFirst();binding(s,intent);
            var slot=slot(s.owner(),(UUID)intent.get("slot_id"),true);UUID id=(UUID)intent.get("target_id");
            if(intent.get("revoked_at")!=null)throw new ApiException(410,"MEMORY_DELETED");if(intent.get("consumed_at")!=null)throw new ApiException(409,"INTENT_CONSUMED");
            if(!AuthService.instant(intent.get("expires_at")).isAfter(databaseNow()))throw new ApiException(409,"INTENT_EXPIRED");
            if(!operation.equals(intent.get("operation"))||(!create&&!id.equals(target))||!Json.equal(vault.open(s.owner(),intentId,"memory-intent-hash",(String)intent.get("payload_hash_ref")),MemoryIntentService.hash(s,intentId,id,operation,args,AuthService.instant(intent.get("expires_at")))))throw new ApiException(403,"AUTHORITY_NOT_VALID");
            long generation=((Number)slot.get("generation")).longValue();if(generation!=number(args,"expected_generation",create?0:1))throw new ApiException(409,"PRECONDITION_CHANGED");
            long version;String memoryKey;Instant now=databaseNow();
            if(create){MemoryIntentService.checkCreate(slot,args);var resolved=findSlot(s.owner(),args,false);if(resolved==null||!resolved.get("id").equals(slot.get("id")))throw new ApiException(403,"AUTHORITY_NOT_VALID");generation=advance(generation);version=1;memoryKey=args.get("key").asText();}
            else{var current=record(s.owner(),id,true);if(!id.equals(slot.get("current_memory_id")))throw new ApiException(409,"PRECONDITION_CHANGED");expected(current,number(args,"expected_version",1),generation);version=advance(((Number)current.get("version")).longValue());memoryKey=(String)payload(current).get("key");}
            Map<String,Object> source=Map.of("type","EXPLICIT_OWNER_FORM","submission_id",intentId,"submitted_at",now.toString(),"source_hash",Json.hash(Json.canonical(args)));
            Map<String,Object> content=Map.of("key",memoryKey,"value",args.get("value").asText(),"change_reason",args.get("change_reason").asText(),"source",source);String serialized=Json.MAPPER.writeValueAsString(content);
            String currentRef=vault.seal(s.owner(),id,"memory-current",serialized),revisionRef=vault.seal(s.owner(),id,"memory-revision:"+version,serialized);
            if(create){db.update("INSERT INTO memories(id,owner_id,slot_id,kind,generation,version,payload_ref,confirmed_at,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)",id,s.owner(),slot.get("id"),args.get("kind").asText(),generation,version,currentRef,Timestamp.from(now),Timestamp.from(now),Timestamp.from(now));db.update("UPDATE memory_slots SET generation=?,current_memory_id=?,tombstone=false WHERE owner_id=? AND id=?",generation,id,s.owner(),slot.get("id"));}
            else db.update("UPDATE memories SET version=?,payload_ref=?,confirmed_at=?,updated_at=? WHERE owner_id=? AND id=?",version,currentRef,Timestamp.from(now),Timestamp.from(now),s.owner(),id);
            db.update("INSERT INTO memory_revisions(owner_id,memory_id,version,payload_ref,confirmed_at) VALUES (?,?,?,?,?)",s.owner(),id,version,revisionRef,Timestamp.from(now));
            db.update("UPDATE memory_write_intents SET consumed_at=now() WHERE owner_id=? AND id=?",s.owner(),intentId);mutation(s,key,digest,intentId,id,version,generation,operation);event(s.owner(),id,version,create?"CREATED":"REVISED");return prior(s,key,digest);
        });
    }
}

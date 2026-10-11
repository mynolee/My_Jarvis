package dev.myjarvis.core;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** Narrow R2 memory.write intent. Does not extend Task or model authority. */
@Service
final class MemoryIntentService {
    private final MemoryService m;
    MemoryIntentService(MemoryService memories){m=memories;}
    static void checkCreate(Map<String,Object> slot,JsonNode args){
        if(slot.get("current_memory_id")!=null)throw new ApiException(409,"MEMORY_ALREADY_EXISTS");
        if(Boolean.TRUE.equals(slot.get("tombstone"))!=args.get("revive_deleted").asBoolean())throw new ApiException(409,"PRECONDITION_CHANGED");
        if(((Number)slot.get("generation")).longValue()!=MemoryService.number(args,"expected_generation",0))throw new ApiException(409,"PRECONDITION_CHANGED");
        MemoryService.advance(((Number)slot.get("generation")).longValue());
    }
    static String hash(AuthService.Session s,UUID intent,UUID target,String operation,JsonNode args,Instant expires){
        Map<String,Object> payload=new LinkedHashMap<>();payload.put("owner_id",s.owner());payload.put("session_hash",s.hash());payload.put("security_epoch",s.epoch());payload.put("intent_id",intent);payload.put("tool_id","memory.write");payload.put("tool_version","1");payload.put("policy_version","memory-v1");payload.put("operation",operation);payload.put("target_id",target);payload.put("arguments",args);payload.put("expires_at",expires.toString());payload.put("max_executions",1);return Json.hash(Json.canonical(payload));
    }
    Map<String,Object> issue(AuthService.Session s,UUID key,JsonNode body){
        MemoryService.exact(body,"tool_id","operation","target_id","arguments");if(!"memory.write".equals(Json.string(body,"tool_id",32)))throw ApiException.bad();
        String operation=Json.string(body,"operation",6);if(!Set.of("CREATE","REVISE").contains(operation))throw ApiException.bad();boolean create=operation.equals("CREATE");
        if(create&&!body.get("target_id").isNull())throw ApiException.bad();UUID target=create?null:Json.uuid(body,"target_id");JsonNode args=body.get("arguments");MemoryService.arguments(args,create);
        AuthService.requireOwner(s);String digest=m.keys.digest(s.owner(),"intent",Json.canonical(body));
        return m.writeTx.execute(tx->{var owner=m.session(s,true,false);
            var prior=m.db.queryForList("SELECT * FROM memory_write_intents WHERE owner_id=? AND issuance_key=?",s.owner(),key);
            if(!prior.isEmpty()){var row=prior.getFirst();MemoryService.binding(s,row);if(!Json.equal(digest,(String)row.get("issuance_digest")))throw new ApiException(409,"IDEMPOTENCY_CONFLICT");if(row.get("revoked_at")!=null)throw new ApiException(410,"MEMORY_DELETED");if(!AuthService.instant(row.get("expires_at")).isAfter(m.databaseNow()))throw new ApiException(409,"INTENT_EXPIRED");if(row.get("consumed_at")!=null)throw new ApiException(409,"INTENT_CONSUMED");return response(row);}
            MemoryService.unpaused(owner);Map<String,Object> slot;UUID resolved;
            if(create){slot=m.findSlot(s.owner(),args,true);if(slot==null){if(MemoryService.number(args,"expected_generation",0)!=0||args.get("revive_deleted").asBoolean())throw new ApiException(409,"PRECONDITION_CHANGED");UUID slotId=UUID.randomUUID();m.db.update("INSERT INTO memory_slots(id,owner_id,scope,project_id,key_digest) VALUES (?,?,?,?,?)",slotId,s.owner(),args.get("scope").asText(),MemoryService.projectId(args),m.keys.slot(s.owner(),args.get("key").asText()));slot=m.slot(s.owner(),slotId,true);}checkCreate(slot,args);resolved=UUID.randomUUID();}
            else{var current=m.record(s.owner(),target,true);slot=m.slot(s.owner(),(UUID)current.get("slot_id"),true);MemoryService.expected(current,MemoryService.number(args,"expected_version",1),MemoryService.number(args,"expected_generation",1));MemoryService.advance(((Number)current.get("version")).longValue());resolved=target;}
            UUID id=UUID.randomUUID();Instant expires=m.databaseNow().plusSeconds(300);String hash=hash(s,id,resolved,operation,args,expires);
            m.db.update("INSERT INTO memory_write_intents(id,owner_id,slot_id,session_hash,security_epoch,issuance_key,issuance_digest,operation,target_id,expected_generation,expected_version,payload_hash_ref,arguments_ref,expires_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",id,s.owner(),slot.get("id"),s.hash(),s.epoch(),key,digest,operation,resolved,MemoryService.number(args,"expected_generation",0),create?null:MemoryService.number(args,"expected_version",1),m.vault.seal(s.owner(),id,"memory-intent-hash",hash),m.vault.seal(s.owner(),id,"memory-intent",Json.canonical(args)),Timestamp.from(expires));
            m.audit(s.owner(),"MEMORY_INTENT_ISSUED",id);return response(m.db.queryForMap("SELECT * FROM memory_write_intents WHERE owner_id=? AND id=?",s.owner(),id));
        });
    }
    private Map<String,Object> response(Map<String,Object> row){Map<String,Object> result=new LinkedHashMap<>();for(String field:List.of("id","operation","target_id","expected_generation"))result.put(field,row.get(field));result.put("payload_hash",m.vault.open((UUID)row.get("owner_id"),(UUID)row.get("id"),"memory-intent-hash",(String)row.get("payload_hash_ref")));result.put("tool_id","memory.write");result.put("expires_at",AuthService.instant(row.get("expires_at")).toString());result.put("status","ISSUED");return result;}
}

package dev.myjarvis.core;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** Exact-snapshot, recent-reauthenticated one-off R3 deletion, never a write intent. */
@Service
final class MemoryDeletionService {
    private final MemoryService m;
    MemoryDeletionService(MemoryService memories){m=memories;}
    private String hash(AuthService.Session s,UUID preparation,Map<String,Object> memory,Instant expires){
        return payloadHash(s,preparation,(UUID)memory.get("id"),(UUID)memory.get("slot_id"),((Number)memory.get("version")).longValue(),((Number)memory.get("generation")).longValue(),m.view(memory),expires);
    }
    static String payloadHash(AuthService.Session s,UUID preparation,UUID memory,UUID slot,long version,long generation,Map<String,Object> snapshot,Instant expires){
        Map<String,Object> payload=new LinkedHashMap<>();payload.put("owner_id",s.owner());payload.put("session_hash",s.hash());payload.put("security_epoch",s.epoch());payload.put("preparation_id",preparation);payload.put("tool_id","memory.delete");payload.put("tool_version","1");payload.put("policy_version","memory-v1-r3");payload.put("memory_id",memory);payload.put("slot_id",slot);payload.put("expected_version",version);payload.put("expected_generation",generation);payload.put("snapshot_hash",Json.hash(Json.canonical(snapshot)));payload.put("expires_at",expires.toString());payload.put("max_executions",1);return Json.hash(Json.canonical(payload));
    }
    Map<String,Object> prepare(AuthService.Session s,UUID key,UUID target,JsonNode body){
        MemoryService.exact(body,"expected_version","expected_generation");long version=MemoryService.number(body,"expected_version",1),generation=MemoryService.number(body,"expected_generation",1);AuthService.requireOwner(s);
        String digest=m.keys.digest(s.owner(),"delete-prepare",target+"|"+Json.canonical(body));
        return m.writeTx.execute(tx->{var owner=m.session(s,true,true);MemoryService.unpaused(owner);
            var prior=m.db.queryForList("SELECT * FROM memory_delete_preparations WHERE owner_id=? AND issuance_key=?",s.owner(),key);
            if(!prior.isEmpty()){var row=prior.getFirst();MemoryService.binding(s,row);if(!Json.equal(digest,(String)row.get("issuance_digest")))throw new ApiException(409,"IDEMPOTENCY_CONFLICT");if(row.get("revoked_at")!=null||row.get("consumed_at")!=null)throw new ApiException(410,"MEMORY_DELETED");if(!AuthService.instant(row.get("expires_at")).isAfter(m.databaseNow()))throw new ApiException(409,"APPROVAL_EXPIRED");var memory=m.record(s.owner(),target,true);MemoryService.expected(memory,version,generation);return response(row,memory);}
            var memory=m.record(s.owner(),target,true);m.slot(s.owner(),(UUID)memory.get("slot_id"),true);MemoryService.expected(memory,version,generation);MemoryService.advance(generation);
            UUID id=UUID.randomUUID();Instant expires=m.databaseNow().plusSeconds(300);String hash=hash(s,id,memory,expires);
            m.db.update("INSERT INTO memory_delete_preparations(id,owner_id,memory_id,slot_id,session_hash,security_epoch,issuance_key,issuance_digest,expected_version,expected_generation,payload_hash_ref,expires_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",id,s.owner(),target,memory.get("slot_id"),s.hash(),s.epoch(),key,digest,version,generation,m.vault.seal(s.owner(),id,"memory-delete-hash",hash),Timestamp.from(expires));m.audit(s.owner(),"MEMORY_DELETE_PREPARED",id);
            return response(m.db.queryForMap("SELECT * FROM memory_delete_preparations WHERE owner_id=? AND id=?",s.owner(),id),memory);
        });
    }
    private Map<String,Object> response(Map<String,Object> row,Map<String,Object> memory){Map<String,Object> result=new LinkedHashMap<>();for(String f:List.of("id","expected_version","expected_generation"))result.put(f,row.get(f));result.put("payload_hash",m.vault.open((UUID)row.get("owner_id"),(UUID)row.get("id"),"memory-delete-hash",(String)row.get("payload_hash_ref")));result.put("memory",m.view(memory));result.put("expires_at",AuthService.instant(row.get("expires_at")).toString());result.put("status","PREPARED");return result;}
    Map<String,Object> confirm(AuthService.Session s,UUID key,UUID preparation,JsonNode body){
        MemoryService.exact(body,"payload_hash","expected_version","expected_generation","confirm");String submittedHash=Json.string(body,"payload_hash",64);if(!submittedHash.matches("[0-9a-f]{64}")||!body.get("confirm").isBoolean()||!body.get("confirm").asBoolean())throw ApiException.bad();
        long version=MemoryService.number(body,"expected_version",1),generation=MemoryService.number(body,"expected_generation",1);AuthService.requireOwner(s);String digest=m.keys.digest(s.owner(),"mutation","DELETE|"+preparation+"|"+Json.canonical(body));
        return m.writeTx.execute(tx->{var owner=m.session(s,true,false);var receipt=m.prior(s,key,digest);if(receipt!=null)return receipt;m.session(s,true,true);MemoryService.unpaused(owner);
            var rows=m.db.queryForList("SELECT * FROM memory_delete_preparations WHERE owner_id=? AND id=?",s.owner(),preparation);if(rows.isEmpty())throw ApiException.missing();var approval=rows.getFirst();MemoryService.binding(s,approval);
            if(approval.get("consumed_at")!=null)throw new ApiException(409,"APPROVAL_CONSUMED");if(approval.get("revoked_at")!=null)throw new ApiException(410,"MEMORY_DELETED");Instant expires=AuthService.instant(approval.get("expires_at"));if(!expires.isAfter(m.databaseNow()))throw new ApiException(409,"APPROVAL_EXPIRED");
            UUID id=(UUID)approval.get("memory_id");var slot=m.slot(s.owner(),(UUID)approval.get("slot_id"),true);var memory=m.record(s.owner(),id,true);MemoryService.expected(memory,version,generation);
            if(!id.equals(slot.get("current_memory_id"))||((Number)slot.get("generation")).longValue()!=generation||((Number)approval.get("expected_version")).longValue()!=version||((Number)approval.get("expected_generation")).longValue()!=generation||!Json.equal(submittedHash,m.vault.open(s.owner(),preparation,"memory-delete-hash",(String)approval.get("payload_hash_ref")))||!Json.equal(submittedHash,hash(s,preparation,memory,expires)))throw new ApiException(409,"PRECONDITION_CHANGED");
            long next=MemoryService.advance(generation);UUID approvalId=UUID.randomUUID();
            m.db.update("INSERT INTO memory_delete_approvals(id,owner_id,preparation_id,session_hash,security_epoch) VALUES (?,?,?,?,?)",approvalId,s.owner(),preparation,s.hash(),s.epoch());
            m.db.update("UPDATE memories SET payload_ref=NULL,deleted_at=now(),updated_at=now() WHERE owner_id=? AND id=?",s.owner(),id);
            m.db.update("DELETE FROM memory_revisions WHERE owner_id=? AND memory_id=?",s.owner(),id);
            m.db.update("UPDATE memory_write_intents SET arguments_ref=NULL,payload_hash_ref=NULL,revoked_at=now() WHERE owner_id=? AND slot_id=? AND revoked_at IS NULL",s.owner(),slot.get("id"));
            m.db.update("UPDATE memory_delete_preparations SET payload_hash_ref=NULL,revoked_at=now() WHERE owner_id=? AND slot_id=? AND revoked_at IS NULL",s.owner(),slot.get("id"));
            m.db.update("UPDATE memory_delete_preparations SET consumed_at=now() WHERE owner_id=? AND id=?",s.owner(),preparation);
            m.db.update("UPDATE memory_slots SET current_memory_id=NULL,tombstone=true,generation=? WHERE owner_id=? AND id=?",next,s.owner(),slot.get("id"));
            m.db.update("INSERT INTO memory_deletion_ledger(id,owner_id,slot_id,memory_id,generation,deleted_version) VALUES (?,?,?,?,?,?)",UUID.randomUUID(),s.owner(),slot.get("id"),id,generation,version);
            m.mutation(s,key,digest,approvalId,id,version,generation,"DELETE");m.event(s.owner(),id,version,"DELETED");return m.prior(s,key,digest);
        });
    }
}

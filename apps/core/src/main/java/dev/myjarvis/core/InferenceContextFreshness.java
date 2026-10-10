package dev.myjarvis.core;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Transaction-local revocation gates. Callers serialize using the owner row, never a model-call lock. */
@Service
final class InferenceContextFreshness {
    private final JdbcTemplate db;
    InferenceContextFreshness(JdbcTemplate db){this.db=db;}
    static void transaction(){if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw new IllegalStateException("Context operation requires a write transaction");}
    void lock(UUID owner){transaction();if(db.queryForList("SELECT id FROM owners WHERE id=? FOR UPDATE",owner).isEmpty())throw ApiException.missing();}
    boolean valid(UUID owner,UUID run){
        lock(owner);
        var rows=db.queryForList("SELECT c.* FROM inference_contexts c JOIN owners o ON o.id=c.owner_id JOIN sessions s ON s.token_hash=c.session_hash AND s.owner_id=c.owner_id JOIN runs r ON r.owner_id=c.owner_id AND r.id=c.run_id JOIN conversations v ON v.owner_id=c.owner_id AND v.id=c.conversation_id WHERE c.owner_id=? AND c.run_id=? AND c.state IN ('CAPTURED','PREPARED','DISPATCHED') AND c.context_epoch=o.context_epoch AND c.history_epoch=o.history_epoch AND c.security_epoch=o.security_epoch AND s.security_epoch=o.security_epoch AND NOT o.execution_paused AND s.revoked_at IS NULL AND s.created_at>clock_timestamp()-interval '12 hours' AND s.last_seen_at>clock_timestamp()-interval '2 hours' AND r.cancel_requested_at IS NULL AND r.status IN ('QUEUED','RUNNING') AND v.project_id IS NOT DISTINCT FROM c.project_id AND (c.profile_version IS NULL OR c.profile_version=o.version)",owner,run);
        if(rows.isEmpty())return false;var c=rows.getFirst();
        if(c.get("project_id")!=null&&db.queryForList("SELECT id FROM projects WHERE owner_id=? AND id=? AND version=? AND active",owner,c.get("project_id"),c.get("project_version")).isEmpty())return false;
        long stale=db.queryForObject("SELECT count(*) FROM inference_context_memories b LEFT JOIN memories m ON m.id=b.memory_id AND m.owner_id=? LEFT JOIN memory_slots s ON s.id=b.slot_id AND s.owner_id=? WHERE b.context_id=? AND (m.id IS NULL OR m.slot_id<>b.slot_id OR m.deleted_at IS NOT NULL OR m.version<>b.version OR m.generation<>b.generation OR s.current_memory_id IS DISTINCT FROM m.id OR s.tombstone OR s.generation<>b.generation OR NOT (s.scope='GLOBAL' OR (s.scope='PROJECT' AND s.project_id IS NOT DISTINCT FROM ?)))",Long.class,owner,owner,c.get("id"),c.get("project_id"));
        if(stale!=0)return false;
        return db.queryForObject("SELECT count(*) FROM inference_context_history h LEFT JOIN messages m ON m.id=h.message_id AND m.owner_id=? AND m.conversation_id=? AND m.run_id=h.run_id LEFT JOIN runs r ON r.id=h.run_id AND r.owner_id=? AND r.conversation_id=? WHERE h.context_id=? AND (m.id IS NULL OR m.role<>h.role OR (m.role='USER' AND m.trust<>'USER_DIRECT') OR (m.role='ASSISTANT' AND m.trust<>'MODEL_PROPOSAL') OR r.id IS NULL OR r.status NOT IN ('SUCCEEDED','WAITING_INPUT') OR r.history_epoch<>? OR r.cancel_requested_at IS NOT NULL OR NOT r.context_history_eligible OR EXISTS (SELECT 1 FROM inference_contexts previous WHERE previous.owner_id=r.owner_id AND previous.run_id=r.id AND previous.state<>'SETTLED'))",Long.class,owner,c.get("conversation_id"),owner,c.get("conversation_id"),c.get("id"),c.get("history_epoch"))==0;
    }
    void invalidateOwner(UUID owner,boolean deletion){
        lock(owner);int changed=db.update("UPDATE owners SET context_epoch=context_epoch+1,history_epoch=history_epoch+? WHERE id=? AND context_epoch<9007199254740991 AND history_epoch<9007199254740991",deletion?1:0,owner);if(changed!=1)throw new ApiException(409,"VERSION_EXHAUSTED");invalidate(owner,null);
    }
    void invalidateSession(UUID owner,String sessionHash){lock(owner);invalidate(owner,sessionHash);}
    private void invalidate(UUID owner,String sessionHash){
        String filter=sessionHash==null?"":" AND session_hash=? AND state IN ('CAPTURED','PREPARED','DISPATCHED')";Object[] args=sessionHash==null?new Object[]{owner}:new Object[]{owner,sessionHash};
        for(var row:db.queryForList("UPDATE inference_contexts SET state='INVALIDATED',invalidated_at=clock_timestamp() WHERE owner_id=? AND state<>'INVALIDATED'"+filter+" RETURNING run_id",args))cancelReservation(owner,(UUID)row.get("run_id"));
    }
    void invalidateRun(UUID owner,UUID run){
        lock(owner);db.update("UPDATE inference_contexts SET state='INVALIDATED',invalidated_at=COALESCE(invalidated_at,clock_timestamp()) WHERE owner_id=? AND run_id=?",owner,run);cancelReservation(owner,run);
    }
    private void cancelReservation(UUID owner,UUID run){
        var reservations=db.queryForList("UPDATE inference_reservations SET status=CASE WHEN status='QUEUED' THEN 'CANCELLED' ELSE 'UNKNOWN' END,cancel_requested_at=clock_timestamp(),completed_at=clock_timestamp() WHERE owner_id=? AND run_id=? AND status IN ('QUEUED','STARTED') RETURNING status",owner,run);
        if(!reservations.isEmpty())db.update("UPDATE runs SET status=?,cancel_requested_at=clock_timestamp(),version=version+1 WHERE owner_id=? AND id=? AND status IN ('QUEUED','RUNNING','CANCEL_REQUESTED')",reservations.getFirst().get("status"),owner,run);
    }
}

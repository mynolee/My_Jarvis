package dev.myjarvis.core;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Current-committed, owner-first serialization for every conversation content boundary. */
@Service
final class ConversationAccess {
    private final JdbcTemplate db;

    ConversationAccess(JdbcTemplate db) { this.db = db; }

    private void transaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalStateException("Conversation access requires a locking transaction");
        Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        if ((isolation != null && isolation != TransactionDefinition.ISOLATION_READ_COMMITTED)
                || (isolation == null && !"read committed".equals(db.queryForObject("SHOW transaction_isolation", String.class))))
            throw new IllegalStateException("Conversation access requires current committed observations");
    }

    Map<String,Object> lockOwner(UUID owner) {
        transaction();
        var rows = db.queryForList("SELECT * FROM owners WHERE id=? FOR UPDATE", owner);
        if (rows.isEmpty()) throw ApiException.missing();
        return rows.getFirst();
    }

    /** Locks owner then session; observes time after waiting and never refreshes authentication. */
    Map<String,Object> lockSession(AuthService.Session session) {
        AuthService.requireOwner(session);
        transaction();
        var owners = db.queryForList("SELECT * FROM owners WHERE id=? FOR UPDATE", session.owner());
        if (owners.isEmpty() || session.epoch() != ((Number) owners.getFirst().get("security_epoch")).longValue())
            throw new ApiException(401, "SESSION_REVOKED");
        var rows = db.queryForList("SELECT * FROM sessions WHERE token_hash=? AND owner_id=? FOR UPDATE", session.hash(), session.owner());
        Instant now = AuthService.instant(db.queryForObject("SELECT clock_timestamp()", Object.class));
        if (rows.isEmpty()) throw new ApiException(401, "SESSION_REVOKED");
        var row = rows.getFirst();
        if (row.get("revoked_at") != null || row.get("security_epoch") == null
                || ((Number) row.get("security_epoch")).longValue() != session.epoch()
                || !AuthService.instant(row.get("created_at")).isAfter(now.minusSeconds(43200))
                || !AuthService.instant(row.get("last_seen_at")).isAfter(now.minusSeconds(7200)))
            throw new ApiException(401, "SESSION_REVOKED");
        return owners.getFirst();
    }

    Map<String,Object> requireLive(UUID owner, UUID conversation) {
        lockOwner(owner);
        var rows = db.queryForList("SELECT * FROM conversations WHERE owner_id=? AND id=? FOR UPDATE", owner, conversation);
        if (rows.isEmpty()) throw ApiException.missing();
        var row = rows.getFirst();
        if (row.get("deleted_at") != null) throw new ApiException(410, "CONVERSATION_DELETED");
        return row;
    }

    Map<String,Object> requireLive(AuthService.Session session, UUID conversation) {
        lockSession(session);
        var row = requireLive(session.owner(), conversation);
        lockSession(session); // Recheck the clock after the final target lock wait.
        return row;
    }

    /** Resolve identity under the owner lock before acquiring any descendant lock. */
    Map<String,Object> requireRun(UUID owner, UUID run) {
        lockOwner(owner);
        var rows = db.queryForList("SELECT conversation_id FROM runs WHERE owner_id=? AND id=?", owner, run);
        if (rows.isEmpty()) throw ApiException.missing();
        requireLive(owner, (UUID) rows.getFirst().get("conversation_id"));
        return db.queryForMap("SELECT * FROM runs WHERE owner_id=? AND id=? FOR UPDATE", owner, run);
    }

    Map<String,Object> requireRun(AuthService.Session session, UUID run) {
        lockSession(session);
        var row = requireRun(session.owner(), run);
        lockSession(session);
        return row;
    }

    void requireApproval(UUID owner, UUID approval) {
        lockOwner(owner);
        var rows = db.queryForList("SELECT a.run_id FROM approvals p JOIN action_proposals a ON a.owner_id=p.owner_id AND a.id=p.action_id WHERE p.owner_id=? AND p.id=?", owner, approval);
        if (rows.isEmpty()) throw ApiException.missing();
        requireRun(owner, (UUID) rows.getFirst().get("run_id"));
    }

    void requireApproval(AuthService.Session session, UUID approval) {
        lockSession(session);
        requireApproval(session.owner(), approval);
        lockSession(session);
    }

    /** A background callback is allowed to disappear quietly, but may never reopen a tombstone. */
    boolean liveRun(UUID owner, UUID run) {
        lockOwner(owner);
        var rows = db.queryForList("SELECT conversation_id FROM runs WHERE owner_id=? AND id=?", owner, run);
        if (rows.isEmpty()) return false;
        var conversations = db.queryForList("SELECT deleted_at FROM conversations WHERE owner_id=? AND id=? FOR UPDATE", owner, rows.getFirst().get("conversation_id"));
        return !conversations.isEmpty() && conversations.getFirst().get("deleted_at") == null;
    }
}

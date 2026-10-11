package dev.myjarvis.core;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Copies only accepted canonical Run output into the encrypted transcript. */
@Service
final class ConversationReplyWriter {
    private static final Set<String> RESPONDED = Set.of("SUCCEEDED", "WAITING_INPUT");
    private final JdbcTemplate db;
    private final Vault vault;

    ConversationReplyWriter(JdbcTemplate db, Vault vault) { this.db = db; this.vault = vault; }

    void appendAccepted(UUID owner, UUID run) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalStateException("Reply settlement requires a write transaction");
        var row = new ConversationAccess(db).requireRun(owner, run);
        if (!RESPONDED.contains(row.get("status")) || row.get("result_ref") == null) return;
        if (Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM messages WHERE owner_id=? AND run_id=? AND role='ASSISTANT')", Boolean.class, owner, run))) return;
        String text = vault.open(owner, run, "result", (String) row.get("result_ref"));
        UUID message = UUID.randomUUID();
        db.update("INSERT INTO messages(id,owner_id,conversation_id,run_id,client_message_id,body_hash,content_ref,role,trust) VALUES (?,?,?,?,NULL,?,?,'ASSISTANT','MODEL_PROPOSAL')",
                message, owner, row.get("conversation_id"), run, Json.hash(text), vault.seal(owner, message, "message", text));
    }
}

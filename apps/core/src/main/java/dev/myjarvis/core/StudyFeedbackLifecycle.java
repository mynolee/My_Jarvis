package dev.myjarvis.core;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Metadata-only irreversible closure. It neither infers termination nor discounts usage. */
@Service
final class StudyFeedbackLifecycle {
    private final JdbcTemplate db;
    StudyFeedbackLifecycle(JdbcTemplate db) { this.db=db; }
    void closeAffected(UUID owner,String scope,String sessionHash,UUID run,String reason) {
        InferenceContextFreshness.transaction(); new ConversationAccess(db).lockOwner(owner);
        if(!Set.of("OWNER_ALL","SESSION_ACTIVE","PAUSE_ACTIVE","RUN_EXACT").contains(scope))throw ApiException.bad();
        String filter=switch(scope) {
            case "SESSION_ACTIVE" -> " AND initiating_session_hash=?";
            case "RUN_EXACT" -> " AND run_id=?";
            default -> "";
        };
        Object[] args=scope.equals("SESSION_ACTIVE")?new Object[]{owner,Objects.requireNonNull(sessionHash)}:
                scope.equals("RUN_EXACT")?new Object[]{owner,Objects.requireNonNull(run)}:new Object[]{owner};
        var rows=db.queryForList("SELECT id,initiating_session_hash,conversation_id FROM study_feedback_requests WHERE owner_id=? AND output_authority='ACTIVE'"+filter+" ORDER BY conversation_id,id LIMIT 1001",args);
        if(rows.size()>1000)throw new ApiException(413,"FEEDBACK_CAPACITY_LIMIT");
        for(var row:rows) {
            // Safety routes preserve the original identity even when this session is no longer live.
            if(db.queryForList("SELECT token_hash FROM sessions WHERE owner_id=? AND token_hash=? FOR UPDATE",owner,row.get("initiating_session_hash")).size()!=1)throw new ApiException(409,"FEEDBACK_SOURCE_UNAVAILABLE");
            db.queryForObject("SELECT id FROM conversations WHERE owner_id=? AND id=? FOR UPDATE",UUID.class,owner,row.get("conversation_id"));
            db.queryForObject("SELECT revoke_study_feedback_v18(?,?,?,?,?,NULL,NULL,NULL,NULL)",Object.class,owner,row.get("id"),scope,sessionHash,reason);
        }
    }
    boolean feedbackRun(UUID owner,UUID run) {
        return Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM study_feedback_requests WHERE owner_id=? AND run_id=?)",Boolean.class,owner,run));
    }
    void requireWritableConversation(UUID owner,UUID conversation) {
        if(Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM study_feedback_requests WHERE owner_id=? AND conversation_id=? AND output_authority='ACTIVE')",Boolean.class,owner,conversation)))throw new ApiException(409,"FEEDBACK_IN_PROGRESS");
    }
    long activeCapacityCount(UUID owner) {
        return db.queryForObject("SELECT count(*) FROM inference_reservations i WHERE owner_id=? AND status IN ('QUEUED','STARTED','UNKNOWN') AND NOT (i.purpose='STUDY_FEEDBACK' AND study_feedback_execution_quiescent_v18(i.owner_id,i.id))",Long.class,owner);
    }
}

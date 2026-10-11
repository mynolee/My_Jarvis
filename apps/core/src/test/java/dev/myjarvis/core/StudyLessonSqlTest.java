package dev.myjarvis.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StudyLessonSqlTest extends ConversationDeletionFixtures {
    private UUID reservation(String purpose) {
        UUID target = conversation("SQL purpose"), run = answered(target, "synthetic"), id = UUID.randomUUID();
        db.update("INSERT INTO inference_reservations(id,owner_id,run_id,request_id,body_hash,model_manifest_hash,status,max_compute_ms,reserved_output_tokens,security_epoch,purpose) VALUES (?,?,?,?,?,?,'QUEUED',1000,10,1,?)", id, owner, run, UUID.randomUUID(), "a".repeat(64), "b".repeat(64), purpose);
        return id;
    }
    private void receipt(UUID reservation, String kind) {
        db.update("INSERT INTO inference_usage_receipts(owner_id,run_id,reservation_id,request_id,model_id,model_manifest_hash,request_digest,budget_revision,max_compute_ms,reserved_output_tokens,reserved_at,receipt_kind,response_kind,input_tokens,output_tokens,compute_ms,peak_memory_bytes) SELECT owner_id,run_id,id,request_id,model_id,model_manifest_hash,body_hash,budget_revision,max_compute_ms,reserved_output_tokens,created_at,'MEASURED',?,2,3,4,5 FROM inference_reservations WHERE id=?", kind, reservation);
    }
    @Test void directReceiptWritesCannotSubstituteOrdinaryAndStudyPurpose() {
        UUID ordinary = reservation("CONVERSATION"), study = reservation("STUDY_LESSON");
        assertThrows(org.springframework.dao.DataAccessException.class, () -> receipt(ordinary, "LESSON"));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> receipt(study, "ANSWER"));
        receipt(ordinary, "ANSWER"); receipt(study, "LESSON"); assertEquals(2, count("inference_usage_receipts"));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> receipt(study, "LESSON"));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> db.update("UPDATE inference_usage_receipts SET compute_ms=5 WHERE reservation_id=?", study));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> db.update("DELETE FROM inference_usage_receipts WHERE reservation_id=?", ordinary));
    }
    @Test void sharedClarificationAndUnknownPurposeRemainStrictlyConstrained() {
        receipt(reservation("CONVERSATION"), "NEED_INPUT"); receipt(reservation("STUDY_LESSON"), "NEED_INPUT");
        assertEquals(2, count("inference_usage_receipts"));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> reservation("UNREVIEWED"));
    }
}

package dev.myjarvis.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import static org.junit.jupiter.api.Assertions.*;

/** Actual owner input, encrypted storage, recovery and deletion with synthetic lesson generation. */
class DurableStudyServiceTest extends DurableStudyFixtures {
    @Test void acceptedLessonTwoExactInputsRestartCloseAndDeletionHaveNoExtraModelWork() {
        UUID conversation = conversation("durable lifecycle"), run = lesson(conversation);
        var usage = db.queryForList("SELECT * FROM inference_usage_receipts ORDER BY reservation_id,receipt_kind");
        long reservations = count("inference_reservations"), messages = count("messages"), jobs = count("jobs"), before = conversationVersion(conversation);
        UUID id = start(conversation, run); assertEquals(before + 1, conversationVersion(conversation));
        var original = view(id); var typed = local.lesson(session, run).get("lesson"); assertEquals(typed, original.get("lesson"));
        String text = "  e\u0301\n😀 <script>synthetic</script>  "; UUID firstKey = UUID.randomUUID(); var firstBody = answerBody(id, 0, text);
        var first = study.answer(session, firstKey, id, firstBody); assertEquals(before + 2, conversationVersion(conversation));
        assertEquals(receipt(first), receipt(study.answer(session, firstKey, id, firstBody)));
        UUID firstInput = (UUID) receipt(first).get("input_id"), firstAttempt = (UUID) receipt(first).get("attempt_id"); assertNotEquals(firstInput, firstAttempt);
        assertFalse(db.queryForObject("SELECT text_ref FROM study_owner_answer_inputs WHERE id=?", String.class, firstInput).contains(text));
        local.close(); context.close(); openContext(); study = context.getBean(StudySessionService.class);
        assertEquals(receipt(first), receipt(study.reconcile(session, firstKey))); assertEquals(text, Json.MAPPER.valueToTree(view(id)).path("attempts").get(0).path("text").asText());
        var second = study.answer(session, UUID.randomUUID(), id, answerBody(id, 1, "Second exact answer")); assertNotEquals(firstInput, receipt(second).get("input_id"));
        var pending = view(id); assertEquals("FEEDBACK_PENDING", pending.get("stage")); assertEquals("UNAVAILABLE", pending.get("feedback_availability"));
        for (var attempt : Json.MAPPER.valueToTree(pending).path("attempts")) assertTrue(attempt.path("feedback").isNull());
        study.close(session, UUID.randomUUID(), id, closeBody(id)); assertEquals("CLOSED_BY_OWNER", view(id).get("stage")); assertEquals(before + 4, conversationVersion(conversation));
        assertEquals(1, providerCalls); assertEquals(reservations, count("inference_reservations")); assertEquals(messages, count("messages")); assertEquals(jobs, count("jobs")); assertEquals(usage, db.queryForList("SELECT * FROM inference_usage_receipts ORDER BY reservation_id,receipt_kind"));
        var preparation = prepare(conversation); var counts = (Map<?,?>) preparation.get("source_counts");
        assertEquals(1L, ((Number) counts.get("study_sessions")).longValue()); assertEquals(2L, ((Number) counts.get("study_answers")).longValue());
        commit(preparation, UUID.randomUUID());
        assertEquals(0L, db.queryForObject("SELECT count(*) FROM study_owner_answer_inputs WHERE text_ref IS NOT NULL OR source_binding_ref IS NOT NULL", Long.class));
        assertEquals(0L, db.queryForObject("SELECT count(*) FROM study_sessions WHERE lesson_binding_ref IS NOT NULL", Long.class));
        assertEquals(4L, count("study_mutations")); assertEquals(receipt(first), receipt(study.reconcile(session, firstKey)));
        error(() -> view(id), 410, "CONVERSATION_DELETED"); error(() -> study.answer(session, firstKey, id, firstBody), 410, "CONVERSATION_DELETED");
        var changedAfterDeletion = firstBody.deepCopy(); ((tools.jackson.databind.node.ObjectNode) changedAfterDeletion).put("text", "Changed after deletion");
        error(() -> study.answer(session, firstKey, id, changedAfterDeletion), 410, "CONVERSATION_DELETED");
        assertEquals(usage, db.queryForList("SELECT * FROM inference_usage_receipts ORDER BY reservation_id,receipt_kind"));
    }
    @Test void duplicateKeysChangedRequestsQuestionConflictsAndStaleSnapshotsDoNotWrite() {
        UUID conversation = conversation("conflicts"), run = lesson(conversation), key = UUID.randomUUID(); long version = conversationVersion(conversation);
        var body = json(Map.of("lesson_run_id", run, "expected_conversation_version", version));
        var started = study.start(session, key, conversation, body); UUID id = (UUID) receipt(started).get("study_session_id");
        assertEquals(receipt(started), receipt(study.start(session, key, conversation, body)));
        error(() -> study.start(session, UUID.randomUUID(), conversation, json(Map.of("lesson_run_id", run, "expected_conversation_version", conversationVersion(conversation)))), 409, "STUDY_SESSION_ALREADY_EXISTS");
        UUID answerKey = UUID.randomUUID(); var answer = answerBody(id, 0, "First"); study.answer(session, answerKey, id, answer);
        var changed = answer.deepCopy(); ((tools.jackson.databind.node.ObjectNode) changed).put("text", "Changed");
        error(() -> study.answer(session, answerKey, id, changed), 409, "IDEMPOTENCY_CONFLICT");
        error(() -> study.answer(session, UUID.randomUUID(), id, answer), 409, "PRECONDITION_CHANGED");
        error(() -> study.answer(session, UUID.randomUUID(), id, answerBody(id, 0, "Overwrite")), 409, "ANSWER_ALREADY_RECORDED");
        assertEquals(1L, count("study_owner_answer_inputs")); assertEquals(2L, count("study_mutations"));
        study.close(session, UUID.randomUUID(), id, closeBody(id));
        error(() -> study.answer(session, UUID.randomUUID(), id, answerBody(id, 1, "Late")), 409, "SESSION_NOT_OPEN");
    }
    @Test void canonicalStateSurvivesNewLoginButOriginalMutationAuthorityDoesNot() {
        UUID conversation = conversation("new login"), run = lesson(conversation), id = start(conversation, run), key = UUID.randomUUID();
        var body = answerBody(id, 0, "Private answer"); var answer = study.answer(session, key, id, body);
        AuthService auth = context.getBean(AuthService.class); session = auth.find(auth.issue(owner, true, session.hash()).token());
        assertNotNull(session); assertEquals(2L, ((Number) view(id).get("version")).longValue());
        error(() -> study.reconcile(session, key), 403, "AUTHORITY_NOT_VALID");
        error(() -> study.answer(session, key, id, body), 403, "AUTHORITY_NOT_VALID");
        assertEquals(1L, count("study_owner_answer_inputs")); assertNotNull(receipt(answer).get("input_id"));
    }
    @Test void ordinaryMessagesAndContextChangesPreserveHistoricalInputEvidence() {
        UUID conversation = conversation("historical input"), run = lesson(conversation), id = start(conversation, run);
        study.answer(session, UUID.randomUUID(), id, answerBody(id, 0, "Historical"));
        long observed = db.queryForObject("SELECT observed_conversation_version FROM study_owner_answer_inputs", Long.class);
        answered(conversation, "Intervening ordinary message"); db.update("UPDATE owners SET context_epoch=context_epoch+1 WHERE id=?", owner);
        assertTrue(conversationVersion(conversation) > observed); assertEquals("Historical", Json.MAPPER.valueToTree(view(id)).path("attempts").get(0).path("text").asText());
        study.answer(session, UUID.randomUUID(), id, answerBody(id, 1, "Fresh explicit input"));
        assertEquals("FEEDBACK_PENDING", view(id).get("stage")); assertEquals(1, providerCalls);
    }
    @Test void pausedExecutionBlocksWritesButDoesNotHideRecordedInputs() {
        UUID conversation = conversation("paused"), id = start(conversation, lesson(conversation)); var body = answerBody(id, 0, "Blocked");
        db.update("UPDATE owners SET execution_paused=true WHERE id=?", owner);
        assertEquals("WAITING_ANSWERS", view(id).get("stage"));
        error(() -> study.answer(session, UUID.randomUUID(), id, body), 409, "EXECUTION_PAUSED"); assertEquals(0L, count("study_owner_answer_inputs"));
    }
    @Test void rejectedMockClarificationOrSubstitutedSourcesNeverStart() {
        UUID conversation = conversation("invalid sources"), mock = answered(conversation, "Synthetic mock");
        error(() -> start(conversation, mock), 409, "STUDY_SOURCE_UNAVAILABLE");
        responseKind = "NEED_INPUT"; UUID clarification = lesson(conversation);
        error(() -> start(conversation, clarification), 409, "STUDY_SOURCE_UNAVAILABLE"); responseKind = "LESSON";
        UUID accepted = lesson(conversation); String original = db.queryForObject("SELECT result_ref FROM runs WHERE id=?", String.class, accepted);
        db.update("UPDATE runs SET result_ref=? WHERE id=?", flow.vault.seal(owner, accepted, "result", "Synthetic invalid retained bytes"), accepted);
        error(() -> start(conversation, accepted), 500, "STUDY_LESSON_UNAVAILABLE");
        db.update("UPDATE runs SET result_ref=? WHERE id=?", original, accepted); UUID id = start(conversation, accepted);
        var parsed = StudyLessonResult.stored(flow.vault.open(owner, accepted, "result", original));
        var changed = new StudySessionPolicy.Lesson(List.of("Changed concept", "Second", "Third"), parsed.questions());
        db.update("UPDATE runs SET result_ref=? WHERE id=?", flow.vault.seal(owner, accepted, "result", StudyLessonResult.encode(changed)), accepted);
        error(() -> view(id), 409, "STUDY_SOURCE_UNAVAILABLE"); assertEquals(1L, count("study_sessions")); assertEquals(0L, count("study_owner_answer_inputs"));
    }
    @Test void cancelledOrMeasuredClarificationSubstitutedAsSuccessCannotSupplyLessonAuthority() {
        for (String table : List.of("runs", "inference_reservations")) {
            UUID conversation = conversation("cancelled " + table), run = lesson(conversation);
            db.update("UPDATE " + table + " SET cancel_requested_at=clock_timestamp() WHERE " + (table.equals("runs") ? "id" : "run_id") + "=?", run);
            error(() -> start(conversation, run), 409, "STUDY_SOURCE_UNAVAILABLE");
        }
        UUID conversation = conversation("measured clarification"); responseKind = "NEED_INPUT"; UUID run = lesson(conversation); responseKind = "LESSON";
        var fake = new StudySessionPolicy.Lesson(List.of("Synthetic one", "Synthetic two", "Synthetic three"), List.of(
                new StudySessionPolicy.Question(UUID.randomUUID(), "Synthetic first?"), new StudySessionPolicy.Question(UUID.randomUUID(), "Synthetic second?")));
        db.update("UPDATE runs SET status='SUCCEEDED',version=version+1,result_ref=? WHERE id=?", flow.vault.seal(owner, run, "result", StudyLessonResult.encode(fake)), run);
        assertEquals("NEED_INPUT", db.queryForObject("SELECT response_kind FROM inference_usage_receipts WHERE run_id=? AND receipt_kind='MEASURED'", String.class, run));
        error(() -> start(conversation, run), 409, "STUDY_SOURCE_UNAVAILABLE"); assertEquals(0L, count("study_sessions"));
    }

    @Test void maximumUnicodeInputAndUnknownFieldsRespectDedicatedRequestCap() {
        UUID conversation = conversation("Unicode"), id = start(conversation, lesson(conversation)); String text = "😀".repeat(8000);
        study.answer(session, UUID.randomUUID(), id, answerBody(id, 0, text)); assertEquals(text, Json.MAPPER.valueToTree(view(id)).path("attempts").get(0).path("text").asText());
        for (String field : List.of("source_id", "attempt_id", "owner_id", "feedback", "verified", "purpose")) {
            var body = (tools.jackson.databind.node.ObjectNode) answerBody(id, 1, "Synthetic"); body.put(field, "untrusted");
            error(() -> study.answer(session, UUID.randomUUID(), id, body), 400, "INVALID_REQUEST");
        }
        assertEquals(1L, count("study_owner_answer_inputs"));
    }
    @Test void deletionHeadroomAndUnknownSchemaRefuseBeforeAnyInputAllocation() {
        UUID conversation = conversation("headroom"), id = start(conversation, lesson(conversation));
        flow.tx.executeWithoutResult(tx -> {
            // Synthetic version-boundary seed; restore the guard before committing the fixture.
            db.execute("ALTER TABLE conversations DISABLE TRIGGER conversation_tombstone_guard");
            db.update("UPDATE conversations SET content_version=? WHERE id=?", MemoryService.MAX - 1, conversation);
            db.execute("SET CONSTRAINTS ALL IMMEDIATE");
            db.execute("ALTER TABLE conversations ENABLE TRIGGER conversation_tombstone_guard");
        });
        error(() -> study.answer(session, UUID.randomUUID(), id, answerBody(id, 0, "No room")), 409, "VERSION_EXHAUSTED");
        error(() -> study.close(session, UUID.randomUUID(), id, closeBody(id)), 409, "VERSION_EXHAUSTED"); assertEquals(0L, count("study_owner_answer_inputs"));
        flow.tx.executeWithoutResult(tx -> { tx.setRollbackOnly(); db.update("UPDATE flyway_schema_history SET version='17' WHERE version='16'");
            error(() -> view(id), 409, "CONVERSATION_GRAPH_UNVERIFIED"); });
    }
    @Test void contentFreeIndexAndNotObservedReadDoNotClaimAWriteOutcome() {
        UUID conversation = conversation("index"), run = lesson(conversation); var before = study.index(session, conversation, null, 1);
        assertEquals(conversationVersion(conversation), ((Number) before.get("conversation_content_version")).longValue());
        assertNull(Json.MAPPER.valueToTree(before).path("items").get(0).get("study_session_id").textValue());
        UUID id = start(conversation, run); var after = study.index(session, conversation, null, 1);
        assertEquals(id.toString(), Json.MAPPER.valueToTree(after).path("items").get(0).path("study_session_id").asText());
        assertFalse(Json.canonical(after).contains("Synthetic first concept")); assertFalse(Json.canonical(after).contains("session_hash"));
        var absent = study.reconcile(session, UUID.randomUUID()); assertEquals("NOT_OBSERVED", absent.get("status")); assertNull(absent.get("receipt"));
    }
}

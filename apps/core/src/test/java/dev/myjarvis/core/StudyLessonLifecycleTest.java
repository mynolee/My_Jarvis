package dev.myjarvis.core;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

/** Real service/storage transactions; the provider is an in-process synthetic boundary only. */
class StudyLessonLifecycleTest extends ConversationDeletionFixtures {
    LocalInferenceService local; CapturingClient client;
    final class CapturingClient extends LocalInferenceClient {
        int calls; JsonNode captured; Runnable during = () -> {}; String kind = "LESSON"; boolean malformed;
        CapturingClient() { super(new MockEnvironment().withProperty("jarvis.local.manifest-hash", "a".repeat(64))); }
        @Override boolean ready() { return true; }
        @Override boolean configured() { return true; }
        @Override JsonNode infer(UUID owner, JsonNode request) {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            calls++; captured = request; during.run();
            ObjectNode result = StudyLessonContractTest.response(request);
            if (kind.equals("ANSWER")) result = LocalInferenceContractTest.response(request);
            if (kind.equals("NEED_INPUT")) result = (ObjectNode) json(Map.of("kind", kind, "question", "Synthetic clarification?", "missing_fields", List.of("topic"), "usage", result.get("usage")));
            if (kind.equals("ERROR")) result = (ObjectNode) json(Map.of("kind", kind, "error_code", "MODEL_UNAVAILABLE", "retryable", false, "usage", result.get("usage")));
            if (malformed) result.put("verified", true);
            return result;
        }
    }
    @BeforeEach void localBoundary() {
        client = new CapturingClient();
        local = new LocalInferenceService(flow, client, context.getBean(InferenceContextService.class), context.getBean(InferenceContextFreshness.class));
    }
    @AfterEach void closeLocal() { local.close(); Thread.interrupted(); }
    JsonNode input(UUID id) { return json(Map.of("client_message_id", id, "text", "Synthetic lesson topic")); }
    UUID accept(UUID conversation) { return (UUID) local.acceptLesson(session, conversation, input(UUID.randomUUID())).get("run_id"); }
    String status(UUID run) { return db.queryForObject("SELECT status FROM runs WHERE id=?", String.class, run); }
    long measured(UUID run) { return db.queryForObject("SELECT count(*) FROM inference_usage_receipts WHERE run_id=? AND receipt_kind='MEASURED'", Long.class, run); }
    void noResult(UUID run) { assertNull(db.queryForObject("SELECT result_ref FROM runs WHERE id=?", String.class, run)); assertEquals(0L, db.queryForObject("SELECT count(*) FROM messages WHERE run_id=? AND role='ASSISTANT' AND content_ref IS NOT NULL", Long.class, run)); }

    @Test void reservedPurposeAndTypedOutputFlowThroughRealSettlementAndPlainReaders() {
        UUID conversation = conversation("lesson"), run = accept(conversation);
        assertEquals("STUDY_LESSON", db.queryForObject("SELECT purpose FROM inference_reservations WHERE run_id=?", String.class, run));
        assertTrue(local.runOne()); assertEquals("SUCCEEDED", status(run)); assertEquals(1, client.calls);
        assertEquals("STUDY_LESSON", client.captured.path("purpose").asText());
        assertEquals(1L, measured(run));
        String cipher = db.queryForObject("SELECT result_ref FROM runs WHERE id=?", String.class, run);
        assertFalse(cipher.contains("Synthetic first concept"));
        String stored = flow.vault.open(owner, run, "result", cipher);
        var lesson = StudyLessonResult.stored(stored); String rendered = StudyLessonResult.render(lesson);
        assertEquals(rendered, flow.run(session, run).get("result"));
        assertEquals(rendered, flow.messages(owner, conversation).stream().filter(row -> "ASSISTANT".equals(row.get("role"))).findFirst().orElseThrow().get("text"));
        var typed = local.lesson(session, run); assertEquals(StudyLessonResult.project(lesson), typed.get("lesson"));
        assertTrue(db.queryForObject("SELECT content_version>1 FROM conversations WHERE id=?", Boolean.class, conversation));
        assertEquals("MODEL_PROPOSAL", db.queryForObject("SELECT trust FROM messages WHERE run_id=? AND role='ASSISTANT'", String.class, run));
    }
    @Test void ordinaryHashesAndOmittedPurposeRemainByteCompatible() {
        UUID conversation = conversation("ordinary"), message = UUID.randomUUID(); JsonNode body = input(message);
        String canonical = Json.canonical(Map.of("conversation_id", conversation.toString(), "mode", "LOCAL_INFERENCE", "body", body));
        UUID run = (UUID) local.accept(session, conversation, body).get("run_id");
        assertEquals(context.getBean(InferenceContextService.class).requestDigest(owner, canonical), db.queryForObject("SELECT body_hash FROM messages WHERE run_id=?", String.class, run));
        client.kind = "ANSWER"; assertTrue(local.runOne()); assertFalse(client.captured.has("purpose"));
        assertEquals("Synthetic answer", flow.run(session, run).get("result")); assertEquals(1L, measured(run));
        assertEquals(run, local.accept(session, conversation, body).get("run_id")); assertEquals(1, client.calls);
    }
    @Test void idempotencyCannotSwitchPurposeInEitherDirectionOrResubmitCompletedWork() {
        UUID conversation = conversation("replay"), key = UUID.randomUUID(); JsonNode body = input(key);
        UUID run = (UUID) local.acceptLesson(session, conversation, body).get("run_id");
        assertEquals(run, local.acceptLesson(session, conversation, body).get("run_id"));
        error(() -> local.accept(session, conversation, body), 409, "IDEMPOTENCY_CONFLICT");
        assertTrue(local.runOne()); assertEquals(run, local.acceptLesson(session, conversation, body).get("run_id"));
        UUID ordinaryKey = UUID.randomUUID(); JsonNode ordinary = input(ordinaryKey);
        local.accept(session, conversation, ordinary);
        error(() -> local.acceptLesson(session, conversation, ordinary), 409, "IDEMPOTENCY_CONFLICT");
        assertEquals(1, client.calls);
    }
    @Test void purposeMismatchAndMalformedOutputKeepUnknownReservedUsage() {
        for (boolean malformed : List.of(false, true)) {
            UUID run = accept(conversation("untrusted")); client.kind = malformed ? "LESSON" : "ANSWER"; client.malformed = malformed;
            assertTrue(local.runOne()); assertEquals("UNKNOWN", status(run)); assertEquals(0L, measured(run)); noResult(run);
            assertEquals(60000L, db.queryForObject("SELECT max_compute_ms FROM inference_reservations WHERE run_id=? AND status='UNKNOWN'", Long.class, run));
        }
    }
    @Test void ordinaryPurposeRejectsLessonWithoutCreatingTypedHistory() {
        UUID conversation = conversation("ordinary mismatch"); UUID run = (UUID) local.accept(session, conversation, input(UUID.randomUUID())).get("run_id");
        assertTrue(local.runOne()); assertEquals("UNKNOWN", status(run)); assertEquals(0L, measured(run)); noResult(run);
        error(() -> local.lesson(session, run), 404, "NOT_FOUND");
    }
    @Test void needInputAndUnavailableRemainOrdinaryDataAndErrorObservation() {
        UUID clarification = accept(conversation("clarification")); client.kind = "NEED_INPUT"; local.runOne();
        assertEquals("WAITING_INPUT", status(clarification)); assertEquals(1L, measured(clarification)); assertNull(local.lesson(session, clarification).get("lesson"));
        assertEquals("Synthetic clarification?", flow.run(session, clarification).get("result"));
        UUID unavailable = accept(conversation("unavailable")); client.kind = "ERROR"; local.runOne();
        assertEquals("FAILED", status(unavailable)); assertEquals(0L, measured(unavailable)); assertNull(local.lesson(session, unavailable).get("lesson"));
        assertEquals(1L, db.queryForObject("SELECT count(*) FROM inference_usage_receipts WHERE run_id=? AND receipt_kind='ERROR'", Long.class, unavailable));
    }
    @Test void pauseAfterDispatchRecordsFactsButNeverMintsOrStoresLesson() {
        UUID run = accept(conversation("pause")); client.during = () -> db.update("UPDATE owners SET execution_paused=true WHERE id=?", owner);
        local.runOne(); assertEquals("UNKNOWN", status(run)); assertEquals(1L, measured(run)); noResult(run);
    }
    @Test void logoutAfterDispatchRecordsFactsButRejectsOutputAndTypedRead() {
        UUID run = accept(conversation("logout")); client.during = () -> db.update("UPDATE sessions SET revoked_at=clock_timestamp() WHERE token_hash=?", session.hash());
        local.runOne(); assertEquals("UNKNOWN", status(run)); assertEquals(1L, measured(run)); noResult(run);
        error(() -> local.lesson(session, run), 401, "SESSION_REVOKED");
    }
    @Test void contextRotationAfterDispatchRecordsFactsButNeverAcceptsStaleLesson() {
        UUID run = accept(conversation("rotation")); client.during = () -> db.update("UPDATE owners SET context_epoch=context_epoch+1 WHERE id=?", owner);
        local.runOne(); assertEquals("UNKNOWN", status(run)); assertEquals(1L, measured(run)); noResult(run);
    }
    @Test void crossOwnerNonLessonAndCorruptReadsNeverExposeSourceText() {
        UUID conversation = conversation("private"), run = accept(conversation); local.runOne();
        String singleton = db.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='owners'::regclass AND conname='owners_singleton_key'", String.class);
        var ownersBefore = db.queryForList("SELECT * FROM owners ORDER BY id");
        var sessionsBefore = db.queryForList("SELECT * FROM sessions ORDER BY token_hash");
        try {
            flow.tx.executeWithoutResult(transaction -> {
                transaction.setRollbackOnly();
                // Isolated synthetic cross-owner defense in depth, not supported multi-owner operation.
                // The second principal and this one constraint change never leave the rollback fixture.
                db.execute("ALTER TABLE owners DROP CONSTRAINT owners_singleton_key");
                UUID foreignOwner = UUID.randomUUID(); db.update("INSERT INTO owners(id,login_name,password_hash) VALUES (?,'foreign-study-owner','unused')", foreignOwner);
                var auth = context.getBean(AuthService.class); AuthService.Session other = auth.find(auth.issue(foreignOwner, true, null).token());
                assertNotNull(other); assertEquals(foreignOwner, other.owner());
                ApiException foreign = error(() -> local.lesson(other, run), 404, "NOT_FOUND");
                assertFalse(foreign.getMessage().contains("Synthetic"));
            });
        } finally {
            assertEquals(singleton, db.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='owners'::regclass AND conname='owners_singleton_key'", String.class), "Rollback restores the production singleton constraint");
            assertEquals(ownersBefore, db.queryForList("SELECT * FROM owners ORDER BY id"), "Fixture principals must roll back");
            assertEquals(sessionsBefore, db.queryForList("SELECT * FROM sessions ORDER BY token_hash"), "Fixture sessions must roll back");
        }
        UUID ordinary = answered(conversation, "unrelated"); error(() -> local.lesson(session, ordinary), 404, "NOT_FOUND");
        db.update("UPDATE runs SET result_ref=? WHERE id=?", flow.vault.seal(owner, run, "result", "private-corrupt-sentinel"), run);
        ApiException failure = error(() -> local.lesson(session, run), 500, "STUDY_LESSON_UNAVAILABLE");
        assertFalse(failure.getMessage().contains("private-corrupt-sentinel"));
    }
    @Test void acceptedHistoricalLessonSurvivesContextChangeWithoutCreatingAuthority() {
        UUID run = accept(conversation("historical")); local.runOne(); var before = local.lesson(session, run);
        db.update("UPDATE owners SET context_epoch=context_epoch+1 WHERE id=?", owner);
        assertEquals(before, local.lesson(session, run)); assertEquals(1, client.calls);
    }
    @Test void deletionErasesBothTypedResultAndRenderedTranscriptAndPreventsResurrection() {
        UUID conversation = conversation("delete"), run = accept(conversation); local.runOne();
        UUID ordinary = (UUID) local.accept(session, conversation, input(UUID.randomUUID())).get("run_id"); client.kind = "ANSWER"; local.runOne(); assertEquals("SUCCEEDED", status(ordinary));
        var prepared = prepare(conversation); var counts = (Map<?,?>) prepared.get("source_counts");
        assertEquals(2L, ((Number) counts.get("run_results")).longValue()); assertEquals(4L, ((Number) counts.get("messages")).longValue());
        commit(prepared, UUID.randomUUID()); noResult(run); noResult(ordinary);
        assertEquals(0L, db.queryForObject("SELECT count(*) FROM messages WHERE conversation_id=? AND content_ref IS NOT NULL", Long.class, conversation));
        error(() -> local.lesson(session, run), 410, "CONVERSATION_DELETED");
        assertThrows(org.springframework.dao.DataAccessException.class, () -> db.update("UPDATE runs SET result_ref='resurrect' WHERE id=?", run));
        assertEquals(1L, measured(run));
    }
    @Test void reservationPurposeIsImmutableAndCommittedByDeletionSnapshot() {
        UUID conversation = conversation("purpose commitment"), run = accept(conversation); local.runOne();
        var before = deletion.state(session, conversation);
        assertThrows(org.springframework.dao.DataAccessException.class, () -> db.update("UPDATE inference_reservations SET purpose='CONVERSATION' WHERE run_id=?", run));
        assertEquals(before.get("content_digest"), deletion.state(session, conversation).get("content_digest"));
        assertEquals("15", db.queryForObject("SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1", String.class));
    }
    @Test void queuedPauseAndLogoutCancelWithoutCallingTheProvider() {
        UUID paused = accept(conversation("queued pause")); flow.pause(session, true);
        assertFalse(local.runOne()); assertEquals("CANCELLED", status(paused)); noResult(paused); assertEquals(0, client.calls);
        session = new AuthService.Session(session.hash(), owner, java.time.Instant.now(), 2); flow.pause(session, false);
        session = new AuthService.Session(session.hash(), owner, java.time.Instant.now(), 3);
        UUID loggedOut = accept(conversation("queued logout")); context.getBean(AuthService.class).logout(session);
        assertFalse(local.runOne()); assertEquals("CANCELLED", status(loggedOut)); noResult(loggedOut); assertEquals(0, client.calls);
    }
    @Test void realSessionRotationAndIdleExpiryDiscardLateTypedResponses() {
        UUID rotated = accept(conversation("rotate actual")); client.during = () -> context.getBean(AuthService.class).issue(owner, true, session.hash());
        local.runOne(); assertEquals("UNKNOWN", status(rotated)); assertEquals(1L, measured(rotated)); noResult(rotated);
        session = context.getBean(AuthService.class).find(context.getBean(AuthService.class).issue(owner, true, null).token());
        UUID expired = accept(conversation("expire actual")); client.during = () -> db.update("UPDATE sessions SET last_seen_at=clock_timestamp()-interval '3 hours' WHERE token_hash=?", session.hash());
        local.runOne(); assertEquals("UNKNOWN", status(expired)); assertEquals(1L, measured(expired)); noResult(expired);
    }
    @Test void deletingASelectedMemoryInterruptsAndDiscardsLateLessonButKeepsBoundUsage() {
        Map<String,Object> fields = new LinkedHashMap<>(); fields.put("scope", "GLOBAL"); fields.put("project_id", null); fields.put("key", "synthetic.study.source");
        fields.put("kind", "PREFERENCE"); fields.put("value", "Synthetic selected source"); fields.put("change_reason", "Synthetic explicit form"); fields.put("expected_generation", 0); fields.put("revive_deleted", false);
        JsonNode arguments = json(fields); Map<String,Object> intentBody = new LinkedHashMap<>(); intentBody.put("tool_id", "memory.write"); intentBody.put("operation", "CREATE"); intentBody.put("target_id", null); intentBody.put("arguments", arguments);
        var intent = context.getBean(MemoryIntentService.class).issue(session, UUID.randomUUID(), json(intentBody));
        var saved = memories.save(session, UUID.randomUUID(), null, json(Map.of("intent_id", intent.get("id"), "arguments", arguments)), true);
        UUID memoryId = UUID.fromString(saved.get("memory_id").toString()); var memory = memories.get(session, memoryId);
        UUID run = accept(conversation("in-flight source deletion"));
        client.during = () -> {
            assertEquals("Synthetic selected source", client.captured.path("memories").get(0).path("value").asText());
            var service = context.getBean(MemoryDeletionService.class);
            var prepared = service.prepare(session, UUID.randomUUID(), memoryId, json(Map.of("expected_version", memory.get("version"), "expected_generation", memory.get("generation"))));
            service.confirm(session, UUID.randomUUID(), UUID.fromString(prepared.get("id").toString()), json(Map.of("payload_hash", prepared.get("payload_hash"), "expected_version", prepared.get("expected_version"), "expected_generation", prepared.get("expected_generation"), "confirm", true)));
            local.interruptInvalidContexts(); assertTrue(Thread.currentThread().isInterrupted());
        };
        local.runOne(); assertEquals("UNKNOWN", status(run)); assertEquals(1L, measured(run)); noResult(run);
        assertNull(db.queryForObject("SELECT payload_ref FROM memories WHERE id=?", String.class, memoryId)); assertFalse(Thread.currentThread().isInterrupted());
    }
    @Test void activeConversationDeletionIsBlockedAndCompletedReplayNeverRemintsQuestionIds() {
        UUID conversation = conversation("active delete"), key = UUID.randomUUID(); JsonNode body = input(key);
        UUID run = (UUID) local.acceptLesson(session, conversation, body).get("run_id");
        client.during = () -> { var state = deletion.state(session, conversation); assertEquals("BLOCKED", ((Map<?,?>)state.get("eligibility")).get("status")); assertNull(state.get("content_digest")); };
        local.runOne(); var accepted = local.lesson(session, run);
        assertEquals(run, local.acceptLesson(session, conversation, body).get("run_id")); assertFalse(local.runOne());
        assertEquals(accepted, local.lesson(session, run)); assertEquals(1, client.calls);
        commit(prepare(conversation), UUID.randomUUID()); error(() -> local.acceptLesson(session, conversation, body), 410, "CONVERSATION_DELETED");
        assertFalse(local.runOne()); assertEquals(1, client.calls); noResult(run);
    }

    @Test void descendantLockWaitRechecksSessionClockBeforeLessonIdsOrContentCommit() throws Exception {
        for (String table : List.of("runs", "inference_contexts")) {
            var expired = new java.util.concurrent.atomic.AtomicBoolean();
            var waiting = new java.util.concurrent.CountDownLatch(1);
            var provider = new java.util.concurrent.CountDownLatch(1);
            var releaseProvider = new java.util.concurrent.CountDownLatch(1);
            var watch = new java.util.concurrent.atomic.AtomicBoolean();
            var guardedDb = org.mockito.Mockito.spy(db);
            org.mockito.Mockito.doAnswer(call -> expired.get() ? java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(8000)) : call.callRealMethod())
                    .when(guardedDb).queryForObject(org.mockito.ArgumentMatchers.eq("SELECT clock_timestamp()"), org.mockito.ArgumentMatchers.eq(Object.class));
            if (table.equals("runs")) {
                org.mockito.Mockito.doAnswer(call -> { if (watch.get()) waiting.countDown(); return call.callRealMethod(); })
                        .when(guardedDb).queryForMap(org.mockito.ArgumentMatchers.eq("SELECT * FROM runs WHERE owner_id=? AND id=? FOR UPDATE"), org.mockito.ArgumentMatchers.eq(owner), org.mockito.ArgumentMatchers.any(UUID.class));
            } else {
                org.mockito.Mockito.doAnswer(call -> { if (watch.get()) waiting.countDown(); return call.callRealMethod(); })
                        .when(guardedDb).queryForList(org.mockito.ArgumentMatchers.eq("SELECT session_hash,security_epoch FROM inference_contexts WHERE owner_id=? AND run_id=? AND id=? FOR UPDATE"), org.mockito.ArgumentMatchers.eq(owner), org.mockito.ArgumentMatchers.any(UUID.class), org.mockito.ArgumentMatchers.any(UUID.class));
            }
            WorkflowService guardedFlow = new WorkflowService(guardedDb, flow.tx, flow.vault, new ConversationReplyWriter(guardedDb, flow.vault));
            LocalInferenceService guarded = new LocalInferenceService(guardedFlow, client, context.getBean(InferenceContextService.class), new InferenceContextFreshness(guardedDb), context.getBean(UsageService.class));
            var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
            UUID run = (UUID) guarded.acceptLesson(session, conversation("descendant clock " + table), input(UUID.randomUUID())).get("run_id");
            client.during = () -> { provider.countDown(); try { assertTrue(releaseProvider.await(5, java.util.concurrent.TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new AssertionError(e); } };
            try (var connection = Objects.requireNonNull(db.getDataSource()).getConnection()) {
                connection.setAutoCommit(false);
                var future = executor.submit(guarded::runOne);
                assertTrue(provider.await(5, java.util.concurrent.TimeUnit.SECONDS));
                try (var statement = connection.prepareStatement("SELECT id FROM " + table + " WHERE " + (table.equals("runs") ? "id" : "run_id") + "=? FOR UPDATE")) {
                    statement.setObject(1, run); try (var result = statement.executeQuery()) { assertTrue(result.next()); }
                }
                watch.set(true); releaseProvider.countDown();
                assertTrue(waiting.await(5, java.util.concurrent.TimeUnit.SECONDS));
                long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
                boolean blocked = false;
                while (System.nanoTime() < until && !blocked) {
                    blocked = Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE ?)", Boolean.class, "%FROM " + table + " WHERE%"));
                    if (!blocked) Thread.sleep(5);
                }
                assertTrue(blocked, "Settlement must actually wait on the held descendant lock");
                // Sample an expired database clock after the wait; no OS or PostgreSQL clock is changed.
                expired.set(true); connection.commit(); assertTrue(future.get(5, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals("UNKNOWN", status(run)); assertEquals(1L, measured(run)); noResult(run);
            } finally { releaseProvider.countDown(); guarded.close(); executor.shutdownNow(); expired.set(false); }
        }
    }

}

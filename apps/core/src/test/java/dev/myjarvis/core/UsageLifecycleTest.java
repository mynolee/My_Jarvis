package dev.myjarvis.core;

import java.util.*;
import java.util.concurrent.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import static org.junit.jupiter.api.Assertions.*;

/** End-to-end Core orchestration with a synthetic in-process inference boundary, never native inference. */
class UsageLifecycleTest extends ReminderTestSupport {
    LocalInferenceService local;
    SyntheticClient client;
    UUID conversation;
    @BeforeEach void createLocal() {
        conversation = UUID.randomUUID(); db.update("INSERT INTO conversations(id,owner_id,title) VALUES (?,?,'Synthetic usage')", conversation, owner);
        client = new SyntheticClient();
        local = new LocalInferenceService(flow, client, context.getBean(InferenceContextService.class), context.getBean(InferenceContextFreshness.class), context.getBean(UsageService.class));
    }
    @AfterEach void closeLocal() { if (local != null) local.close(); Thread.interrupted(); }
    static final class SyntheticClient extends LocalInferenceClient {
        int calls; JsonNode captured; Runnable during = () -> {};
        Function<JsonNode,JsonNode> response = LocalInferenceContractTest::response;
        SyntheticClient() { super(new MockEnvironment().withProperty("jarvis.local.manifest-hash", "a".repeat(64))); }
        @Override boolean ready() { return true; }
        @Override boolean configured() { return true; }
        @Override JsonNode infer(UUID owner, JsonNode request) {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); calls++; captured = request; during.run(); return response.apply(request);
        }
    }
    UUID accept() { return (UUID)local.accept(session, conversation, body(Map.of("client_message_id", UUID.randomUUID(), "text", "Synthetic usage question"))).get("run_id"); }
    String status(UUID run) { return db.queryForObject("SELECT status FROM runs WHERE id=?", String.class, run); }
    JsonNode summary() { return body(context.getBean(UsageService.class).get(session)).path("day"); }
    void noRevival(UUID run) {
        assertEquals("UNKNOWN", status(run)); assertEquals("UNKNOWN", db.queryForObject("SELECT status FROM inference_reservations WHERE run_id=?", String.class, run));
        assertNull(db.queryForObject("SELECT result_ref FROM runs WHERE id=?", String.class, run));
        assertEquals(0L, db.queryForObject("SELECT count(*) FROM messages WHERE run_id=? AND role='ASSISTANT'", Long.class, run));
        assertEquals(0, count("authorizations")); assertEquals(0, count("action_proposals")); assertEquals(0, count("jobs"));
        assertFalse(local.runOne()); assertEquals(1, client.calls);
    }

    @Test void normalAnswerPersistsMeasuredUsageBeforeAcceptedReply() {
        UUID run = accept(); assertEquals(1, summary().path("counts").path("pending").asLong());
        assertTrue(local.runOne()); assertEquals("SUCCEEDED", status(run)); assertEquals(1, count("inference_usage_receipts"));
        assertEquals(1, summary().path("measured").path("request_count").asLong()); assertEquals(1, summary().path("measured").path("compute_ms").asLong());
        assertEquals(60000, summary().path("conservative_reserved").path("max_compute_ms").asLong());
    }

    @Test void clarificationIsMeasuredWithoutIssuingAuthority() {
        client.response = request -> body(Map.of("kind", "NEED_INPUT", "question", "Which detail?", "missing_fields", List.of("detail"), "usage", LocalInferenceContractTest.response(request).get("usage")));
        UUID run = accept(); local.runOne(); assertEquals("WAITING_INPUT", status(run));
        assertEquals(1, summary().path("counts").path("measured").asLong()); assertEquals(0, count("authorizations"));
    }

    @Test void errorZerosRemainConservativeUncertaintyRatherThanMeasuredZero() {
        client.response = request -> {
            var usage = LocalInferenceContractTest.response(request).withObject("usage");
            for (String field : List.of("input_tokens", "output_tokens", "compute_ms", "peak_memory_bytes")) usage.put(field, 0);
            return body(Map.of("kind", "ERROR", "error_code", "GENERATION_TIMEOUT", "retryable", false, "usage", usage));
        };
        UUID run = accept(); local.runOne(); assertEquals("FAILED", status(run));
        assertEquals(1, summary().path("counts").path("uncertain").asLong()); assertEquals(0, summary().path("measured").path("request_count").asLong());
        assertTrue(summary().path("measured").path("peak_memory_bytes").isNull()); assertEquals(60000, summary().path("conservative_reserved").path("max_compute_ms").asLong());
    }

    @Test void invalidResponseDoesNotPersistCountersOrReleaseReservation() {
        client.response = request -> { var response = LocalInferenceContractTest.response(request); response.withObject("usage").put("output_tokens", 513); return response; };
        UUID run = accept(); local.runOne(); assertEquals("UNKNOWN", status(run)); assertEquals(0, count("inference_usage_receipts"));
        assertEquals(1, summary().path("counts").path("uncertain").asLong()); assertEquals(60000, summary().path("conservative_reserved").path("max_compute_ms").asLong());
        assertFalse(local.runOne()); assertEquals(1, client.calls);
    }

    @Test void transportFailureRetainsUnknownAcrossRestartWithoutRetry() {
        client.response = request -> { throw new IllegalStateException("Synthetic disconnected transport"); };
        UUID run = accept(); local.runOne(); noRevival(run); context.close(); open();
        assertEquals("UNKNOWN", status(run)); assertEquals(1, summary().path("counts").path("uncertain").asLong()); assertEquals(0, count("inference_usage_receipts"));
    }

    @Test void lateAnswerAfterPauseIsRecordedWithoutRunOutputOrAuthorityRevival() {
        UUID run = accept(); client.during = () -> { flow.pause(session, true); local.interruptOwner(owner); assertTrue(Thread.currentThread().isInterrupted()); };
        local.runOne(); noRevival(run); assertEquals(1, count("inference_usage_receipts")); assertFalse(Thread.currentThread().isInterrupted());
        session = auth.find(token); assertEquals(1, summary().path("counts").path("measured").asLong());
        assertEquals(60000, summary().path("conservative_reserved").path("max_compute_ms").asLong());
    }

    @Test void lateAnswerAfterLogoutIsRetainedButRevokedSessionCannotReadUsage() {
        UUID run = accept(); client.during = () -> auth.logout(session); local.runOne(); noRevival(run);
        assertEquals(1, count("inference_usage_receipts")); code("SESSION_REVOKED", () -> context.getBean(UsageService.class).get(session));
        session = auth.find(auth.issue(owner, true, null).token()); assertEquals(1, summary().path("counts").path("measured").asLong());
    }

    @Test void lateAnswerAfterContextDeletionIsRetainedWithoutReenteringHistory() {
        UUID run = accept(); client.during = () -> flow.tx.executeWithoutResult(tx -> context.getBean(InferenceContextFreshness.class).invalidateOwner(owner, true));
        local.runOne(); noRevival(run); assertEquals(1, count("inference_usage_receipts"));
        assertEquals("INVALIDATED", db.queryForObject("SELECT state FROM inference_contexts WHERE run_id=?", String.class, run));
    }

    @Test void cancellationBeforeDispatchHasNoMeasurementAndReleasesOnlyUnstartedCeiling() {
        UUID run = accept(); long version = db.queryForObject("SELECT version FROM runs WHERE id=?", Long.class, run);
        flow.cancel(owner, run, version); assertFalse(local.runOne()); assertEquals(0, client.calls);
        assertEquals(1, summary().path("counts").path("unstarted").asLong()); assertEquals(0, summary().path("conservative_reserved").path("max_compute_ms").asLong());
        assertEquals(0, count("inference_usage_receipts"));
    }

    @Test void resultSettlementRollbackCannotEraseAlreadyCommittedActualCounters() {
        UUID run = accept(); db.execute("ALTER TABLE audit_events ADD CONSTRAINT usage_settlement_failure CHECK(event_type<>'LOCAL_INFERENCE_SUCCEEDED')");
        try { local.runOne(); } finally { db.execute("ALTER TABLE audit_events DROP CONSTRAINT usage_settlement_failure"); }
        noRevival(run); assertEquals(1, count("inference_usage_receipts")); assertEquals(1, summary().path("counts").path("measured").asLong());
    }

    @Test void receiptFailureKeepsConservativeUnknownRatherThanReturningUnaccountedSuccess() {
        UUID run = accept(); db.execute("ALTER TABLE inference_usage_receipts ADD CONSTRAINT usage_record_failure CHECK(compute_ms<0)");
        try { local.runOne(); } finally { db.execute("ALTER TABLE inference_usage_receipts DROP CONSTRAINT usage_record_failure"); }
        noRevival(run); assertEquals(0, count("inference_usage_receipts")); assertEquals(1, summary().path("counts").path("uncertain").asLong());
        assertEquals(60000, summary().path("conservative_reserved").path("max_compute_ms").asLong());
    }

    @Test void staleStartedRecoveryRemainsUnknownAndIsNeverAutomaticallyResubmitted() {
        UUID run = accept();
        db.update("UPDATE inference_reservations SET status='STARTED',started_at=clock_timestamp()-interval '2 minutes' WHERE run_id=?", run);
        db.update("UPDATE runs SET status='RUNNING' WHERE id=?", run);
        local.recover(); assertEquals("UNKNOWN", status(run)); assertFalse(local.runOne()); assertEquals(0, client.calls);
        assertEquals(1, summary().path("counts").path("uncertain").asLong()); assertEquals(0, count("inference_usage_receipts"));
    }

    @Test void identicalMessageAdmissionDoesNotReserveOrMeasureTwice() {
        UUID key = UUID.randomUUID(); JsonNode input = body(Map.of("client_message_id", key, "text", "Synthetic identical request"));
        var first = local.accept(session, conversation, input); local.runOne(); var second = local.accept(session, conversation, input);
        assertEquals(first.get("run_id"), second.get("run_id")); assertEquals(1, count("inference_reservations")); assertEquals(1, count("inference_usage_receipts")); assertEquals(1, client.calls);
    }
    @Test void cancellationDuringReceiptPersistenceCannotInterruptValidatedAccounting() throws Exception {
        CountDownLatch recording = new CountDownLatch(1), release = new CountDownLatch(1);
        JdbcTemplate intercepted = new JdbcTemplate(Objects.requireNonNull(db.getDataSource())) {
            @Override public List<Map<String,Object>> queryForList(String sql, Object... args) {
                if (sql.equals("SELECT id FROM owners WHERE id=? FOR UPDATE")) {
                    assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                    assertFalse(Thread.currentThread().isInterrupted()); recording.countDown();
                    try { assertTrue(release.await(15, TimeUnit.SECONDS)); }
                    catch (InterruptedException interrupted) { throw new AssertionError("Validated accounting was interrupted by transport cancellation", interrupted); }
                    assertFalse(Thread.currentThread().isInterrupted());
                }
                return super.queryForList(sql, args);
            }
        };
        var usage = new UsageService(intercepted, vault, context.getBean(InferenceContextService.class), context.getBean(PlatformTransactionManager.class));
        local.close(); local = new LocalInferenceService(flow, client, context.getBean(InferenceContextService.class), context.getBean(InferenceContextFreshness.class), usage);
        UUID run = accept();
        try (var pool = Executors.newSingleThreadExecutor()) {
            Future<Boolean> result = pool.submit(() -> local.runOne());
            try {
                assertTrue(recording.await(15, TimeUnit.SECONDS));
                flow.pause(session, true);
                for (int i = 0; i < 5; i++) { local.interruptOwner(owner); local.interruptInvalidContexts(); local.interrupt(run); }
            } finally { release.countDown(); }
            assertTrue(result.get(15, TimeUnit.SECONDS));
        }
        noRevival(run); assertEquals(1, count("inference_usage_receipts"));
        session = auth.find(token); assertEquals(1, summary().path("counts").path("measured").asLong());
    }

}

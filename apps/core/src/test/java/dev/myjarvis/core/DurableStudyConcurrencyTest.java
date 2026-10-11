package dev.myjarvis.core;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real PostgreSQL races and lock waits; clock observations are explicitly synthetic. */
class DurableStudyConcurrencyTest extends DurableStudyFixtures {
    private List<Object> race(Callable<Object> left, Callable<Object> right) throws Exception {
        var ready = new CountDownLatch(2); var release = new CountDownLatch(1); var executor = Executors.newFixedThreadPool(2);
        try {
            var a = executor.submit(() -> { ready.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)); try { return left.call(); } catch (ApiException e) { return e; } });
            var b = executor.submit(() -> { ready.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)); try { return right.call(); } catch (ApiException e) { return e; } });
            assertTrue(ready.await(3, TimeUnit.SECONDS)); release.countDown(); return List.of(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS));
        } finally { release.countDown(); executor.shutdownNow(); }
    }
    @Test void simultaneousSameKeyCreatesOneSourceReceiptAuditAndVersionIncrement() throws Exception {
        UUID conversation = conversation("same-key race"), id = start(conversation, lesson(conversation)), key = UUID.randomUUID();
        JsonNode body = answerBody(id, 0, "Same bytes"); long before = conversationVersion(conversation);
        var outcomes = race(() -> study.answer(session, key, id, body), () -> study.answer(session, key, id, body));
        assertTrue(outcomes.stream().allMatch(x -> x instanceof Map));
        assertEquals(((Map<?,?>) outcomes.get(0)).get("receipt"), ((Map<?,?>) outcomes.get(1)).get("receipt"));
        assertEquals(1L, count("study_owner_answer_inputs")); assertEquals(2L, count("study_mutations"));
        assertEquals(1L, db.queryForObject("SELECT count(*) FROM audit_events WHERE event_type='STUDY_ANSWER'", Long.class));
        assertEquals(before + 1, conversationVersion(conversation)); assertEquals(1, providerCalls);
    }
    @Test void twoQuestionsAtOneSnapshotHaveOneWinnerAndNeverSilentlyRebase() throws Exception {
        UUID conversation = conversation("question race"), id = start(conversation, lesson(conversation));
        JsonNode first = answerBody(id, 0, "First"), second = answerBody(id, 1, "Second");
        var outcomes = race(() -> study.answer(session, UUID.randomUUID(), id, first), () -> study.answer(session, UUID.randomUUID(), id, second));
        assertEquals(1, outcomes.stream().filter(x -> x instanceof Map).count());
        ApiException failure = (ApiException) outcomes.stream().filter(x -> x instanceof ApiException).findFirst().orElseThrow();
        assertEquals("PRECONDITION_CHANGED", failure.code); assertEquals(1L, count("study_owner_answer_inputs")); assertEquals(2L, count("study_mutations"));
    }
    @Test void answerAndCloseSerializeAndBothExplicitOrderingsRemainLegal() throws Exception {
        UUID conversation = conversation("answer-close race"), id = start(conversation, lesson(conversation));
        JsonNode answer = answerBody(id, 0, "Racing"), close = closeBody(id);
        var outcomes = race(() -> study.answer(session, UUID.randomUUID(), id, answer), () -> study.close(session, UUID.randomUUID(), id, close));
        assertEquals(1, outcomes.stream().filter(x -> x instanceof Map).count());
        assertEquals("PRECONDITION_CHANGED", ((ApiException) outcomes.stream().filter(x -> x instanceof ApiException).findFirst().orElseThrow()).code);
        if ("OPEN".equals(view(id).get("lifecycle"))) study.close(session, UUID.randomUUID(), id, closeBody(id));
        error(() -> study.answer(session, UUID.randomUUID(), id, answerBody(id, 1, "After close")), 409, "SESSION_NOT_OPEN");
        UUID other = conversation("answer-first"), second = start(other, lesson(other));
        study.answer(session, UUID.randomUUID(), second, answerBody(second, 0, "Before close")); study.close(session, UUID.randomUUID(), second, closeBody(second));
        assertEquals("CLOSED_BY_OWNER", view(second).get("stage"));
    }
    @Test void ownerAndDescendantLockWaitsRecheckIdleAndAbsoluteSessionExpiry() throws Exception {
        for (String table : List.of("owners", "study_sessions")) for (long expiry : new long[] {7200, 43200}) {
            UUID conversation = conversation("expiry " + table + expiry), id = start(conversation, lesson(conversation)); JsonNode body = answerBody(id, 0, "Must roll back");
            var advanced = new AtomicBoolean(); var guardedDb = spy(db);
            Instant boundary = db.queryForObject("SELECT " + (expiry == 7200 ? "last_seen_at" : "created_at") + " FROM sessions WHERE token_hash=?", Timestamp.class, session.hash()).toInstant().plusSeconds(expiry);
            doAnswer(call -> advanced.get() ? Timestamp.from(boundary) : call.callRealMethod()).when(guardedDb).queryForObject(eq("SELECT clock_timestamp()"), eq(Object.class));
            var guarded = new StudySessionService(guardedDb, memories, new ConversationAccess(guardedDb), deletion, context.getBean(PlatformTransactionManager.class));
            var executor = Executors.newSingleThreadExecutor(); long before = count("study_owner_answer_inputs");
            try (var connection = Objects.requireNonNull(db.getDataSource()).getConnection()) {
                connection.setAutoCommit(false);
                try (var lock = connection.prepareStatement("SELECT id FROM " + table + " WHERE id=? FOR UPDATE")) {
                    lock.setObject(1, table.equals("owners") ? owner : id); try (var rows = lock.executeQuery()) { assertTrue(rows.next()); }
                }
                var future = executor.submit(() -> assertThrows(ApiException.class, () -> guarded.answer(session, UUID.randomUUID(), id, body)));
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3); boolean blocked = false;
                while (System.nanoTime() < until && !blocked) {
                    blocked = Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE ?)", Boolean.class, "%FROM " + table + "%"));
                    if (!blocked) Thread.sleep(5);
                }
                assertTrue(blocked, "Must observe an actual PostgreSQL lock wait"); advanced.set(true); connection.commit();
                assertEquals("SESSION_REVOKED", future.get(5, TimeUnit.SECONDS).code); assertEquals(before, count("study_owner_answer_inputs"));
                assertEquals(1L, db.queryForObject("SELECT count(*) FROM study_mutations WHERE study_session_id=?", Long.class, id));
            } finally { advanced.set(false); executor.shutdownNow(); }
        }
    }
    @Test void blockedReceiptConstraintRechecksExpiryAfterAllBusinessWrites() throws Exception {
        UUID conversation = conversation("final constraint expiry"), id = start(conversation, lesson(conversation)); JsonNode body = answerBody(id, 0, "Must roll back after receipt");
        long before = conversationVersion(conversation); var advanced = new AtomicBoolean(); var guardedDb = spy(db);
        Instant expired = db.queryForObject("SELECT last_seen_at FROM sessions WHERE token_hash=?", Timestamp.class, session.hash()).toInstant().plusSeconds(7200);
        doAnswer(call -> advanced.get() ? Timestamp.from(expired) : call.callRealMethod()).when(guardedDb).queryForObject(eq("SELECT clock_timestamp()"), eq(Object.class));
        var guarded = new StudySessionService(guardedDb, memories, new ConversationAccess(guardedDb), deletion, context.getBean(PlatformTransactionManager.class));
        db.execute("CREATE FUNCTION synthetic_study_wait() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_advisory_xact_lock(78123987); RETURN NULL; END; $$");
        db.execute("CREATE CONSTRAINT TRIGGER synthetic_study_wait AFTER INSERT ON study_mutations DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION synthetic_study_wait()");
        var executor = Executors.newSingleThreadExecutor();
        try (var connection = Objects.requireNonNull(db.getDataSource()).getConnection()) {
            connection.setAutoCommit(false); try (var lock = connection.createStatement()) { lock.execute("SELECT pg_advisory_xact_lock(78123987)"); }
            var future = executor.submit(() -> assertThrows(ApiException.class, () -> guarded.answer(session, UUID.randomUUID(), id, body)));
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3); boolean blocked = false;
            while (System.nanoTime() < until && !blocked) {
                blocked = Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query='SET CONSTRAINTS ALL IMMEDIATE')", Boolean.class));
                if (!blocked) Thread.sleep(5);
            }
            assertTrue(blocked, "Receipt and state writes must reach an actual deferred-constraint wait");
            advanced.set(true); connection.commit(); assertEquals("SESSION_REVOKED", future.get(5, TimeUnit.SECONDS).code);
            assertEquals(0L, count("study_owner_answer_inputs")); assertEquals(1L, count("study_mutations")); assertEquals(before, conversationVersion(conversation));
        } finally {
            executor.shutdownNow(); advanced.set(false); db.execute("DROP TRIGGER synthetic_study_wait ON study_mutations"); db.execute("DROP FUNCTION synthetic_study_wait()");
        }
    }
    @Test void blockedNonceReservationCannotCommitAnExpiredOwnerAnswer() throws Exception {
        UUID conversation = conversation("nonce expiry"), id = start(conversation, lesson(conversation)); JsonNode body = answerBody(id, 0, "No late source");
        long before = conversationVersion(conversation), nonces = count("crypto_nonces"); var advanced = new AtomicBoolean(); var guardedDb = spy(db);
        doAnswer(call -> advanced.get() ? Timestamp.from(Instant.now().plusSeconds(8000)) : call.callRealMethod()).when(guardedDb).queryForObject(eq("SELECT clock_timestamp()"), eq(Object.class));
        var guarded = new StudySessionService(guardedDb, memories, new ConversationAccess(guardedDb), deletion, context.getBean(PlatformTransactionManager.class));
        String keyId = db.queryForObject("SELECT lesson_binding_ref FROM study_sessions WHERE id=?", String.class, id).split("\\.", 3)[0];
        var executor = Executors.newSingleThreadExecutor();
        try (var connection = Objects.requireNonNull(db.getDataSource()).getConnection()) {
            connection.setAutoCommit(false); try (var lock = connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")) { lock.setString(1, keyId); lock.execute(); }
            var future = executor.submit(() -> assertThrows(ApiException.class, () -> guarded.answer(session, UUID.randomUUID(), id, body)));
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3); boolean blocked = false;
            while (System.nanoTime() < until && !blocked) {
                blocked = Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%pg_advisory_xact_lock(hashtextextended%')", Boolean.class));
                if (!blocked) Thread.sleep(5);
            }
            assertTrue(blocked, "Independent encryption nonce transaction must actually wait"); advanced.set(true); connection.commit();
            assertEquals("SESSION_REVOKED", future.get(5, TimeUnit.SECONDS).code); assertEquals(0L, count("study_owner_answer_inputs"));
            assertEquals(1L, count("study_mutations")); assertEquals(before, conversationVersion(conversation)); assertTrue(count("crypto_nonces") > nonces);
        } finally { advanced.set(false); executor.shutdownNow(); }
    }
    @Test void backwardFinalClockAfterReceiptForcesCompleteRollback() {
        UUID conversation = conversation("backward final clock"), id = start(conversation, lesson(conversation)); JsonNode body = answerBody(id, 0, "Rollback");
        long before = conversationVersion(conversation); var backward = new AtomicBoolean(); var guardedDb = spy(db); var guardedMemories = spy(memories);
        doAnswer(call -> backward.get() ? Instant.now().minusSeconds(3600) : call.callRealMethod()).when(guardedMemories).databaseNow();
        doAnswer(call -> { Object result = call.callRealMethod(); backward.set(true); return result; }).when(guardedDb).execute("SET CONSTRAINTS ALL IMMEDIATE");
        var guarded = new StudySessionService(guardedDb, guardedMemories, context.getBean(ConversationAccess.class), deletion, context.getBean(PlatformTransactionManager.class));
        error(() -> guarded.answer(session, UUID.randomUUID(), id, body), 403, "AUTHORITY_NOT_VALID");
        assertEquals(0L, count("study_owner_answer_inputs")); assertEquals(1L, count("study_mutations")); assertEquals(before, conversationVersion(conversation));
    }

    @Test void failuresAfterInputTransitionReceiptAndAuditRollBackEveryBusinessRow() {
        for (String stage : List.of("input", "transition", "receipt", "audit")) {
            UUID conversation = conversation("fault " + stage), id = start(conversation, lesson(conversation)); JsonNode body = answerBody(id, 0, "Rollback sentinel");
            long version = conversationVersion(conversation), inputs = count("study_owner_answer_inputs"), receipts = count("study_mutations"), nonces = count("crypto_nonces");
            String table = switch (stage) { case "input" -> "study_owner_answer_inputs"; case "transition" -> "study_sessions"; case "receipt" -> "study_mutations"; default -> "audit_events"; };
            String event = stage.equals("transition") ? "UPDATE" : "INSERT";
            db.execute("CREATE FUNCTION synthetic_study_fault() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Synthetic transaction fault'; END; $$");
            try {
                db.execute("CREATE TRIGGER synthetic_study_fault AFTER " + event + " ON " + table + " FOR EACH ROW EXECUTE FUNCTION synthetic_study_fault()");
                assertThrows(org.springframework.dao.DataAccessException.class, () -> study.answer(session, UUID.randomUUID(), id, body));
                assertEquals(inputs, count("study_owner_answer_inputs")); assertEquals(receipts, count("study_mutations")); assertEquals(version, conversationVersion(conversation));
                assertEquals(1L, ((Number) view(id).get("version")).longValue()); assertTrue(count("crypto_nonces") > nonces, "Independent nonce reservations are not reused after rollback");
            } finally { db.execute("DROP TRIGGER IF EXISTS synthetic_study_fault ON " + table); db.execute("DROP FUNCTION synthetic_study_fault()"); }
        }
    }
    @Test void answerBeforeDeleteInvalidatesReviewAndDeleteBeforeAnswerNeverRehydrates() {
        UUID conversation = conversation("delete ordering"), id = start(conversation, lesson(conversation)); var preparation = prepare(conversation); JsonNode body = answerBody(id, 0, "Earlier answer");
        study.answer(session, UUID.randomUUID(), id, body); error(() -> commit(preparation, UUID.randomUUID()), 409, "PRECONDITION_CHANGED");
        JsonNode later = answerBody(id, 1, "Must not survive"); commit(prepare(conversation), UUID.randomUUID());
        error(() -> study.answer(session, UUID.randomUUID(), id, later), 410, "CONVERSATION_DELETED");
        assertEquals(0L, db.queryForObject("SELECT count(*) FROM study_owner_answer_inputs WHERE text_ref IS NOT NULL", Long.class));
    }
}

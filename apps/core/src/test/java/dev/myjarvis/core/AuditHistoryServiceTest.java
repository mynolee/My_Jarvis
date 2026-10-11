package dev.myjarvis.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL, synthetic metadata and strict read-only invariants. No inference or providers. */
class AuditHistoryServiceTest extends ReminderTestSupport {
    AuditHistoryService history() { return context.getBean(AuditHistoryService.class); }
    Instant now() { return AuthService.instant(db.queryForObject("SELECT clock_timestamp()", Object.class)); }
    UUID run(String status) {
        UUID conversation = UUID.randomUUID(), run = UUID.randomUUID();
        db.update("INSERT INTO conversations(id,owner_id,title) VALUES (?,?,'private-conversation-sentinel')", conversation, owner);
        db.update("INSERT INTO runs(id,owner_id,conversation_id,status,result_ref) VALUES (?,?,?,?,'private-result-reference-sentinel')", run, owner, conversation, status);
        return run;
    }
    UUID event(String type, UUID resource, Instant time) { return event(UUID.randomUUID(), type, resource, time); }
    UUID event(UUID id, String type, UUID resource, Instant time) {
        db.update("INSERT INTO audit_events(id,owner_id,event_type,resource_id,happened_at) VALUES (?,?,?,?,?)", id, owner, type, resource, Timestamp.from(time));
        return id;
    }
    JsonNode page(String scope, String cursor, int limit) { return body(history().get(session, scope, cursor, limit)); }
    JsonNode page() { return page("ALL", null, 100); }
    List<String> ids(JsonNode rows) { List<String> ids = new ArrayList<>(); rows.forEach(row -> ids.add(row.path("id").asText())); return ids; }
    String next(JsonNode page) { return page.path("next_cursor").isNull() ? null : page.path("next_cursor").asText(); }
    AuditHistoryService service(JdbcTemplate template) throws Exception {
        return new AuditHistoryService(template, context.getBean(PlatformTransactionManager.class), key.toString());
    }

    @Test void emptyPageIsClosedAndUsesOneDatabaseObservationTime() {
        String index = db.queryForObject("SELECT indexdef FROM pg_indexes WHERE schemaname=current_schema() AND tablename='audit_events' AND indexname='audit_events_owner_history'", String.class);
        assertNotNull(index); assertTrue(index.contains("USING btree (owner_id, happened_at DESC, id DESC)"));
        Instant before = now(); JsonNode page = page(); Instant after = now();
        assertEquals(Set.of("checked_at", "scope", "items", "next_cursor"), Set.copyOf(page.propertyNames()));
        Instant checked = Instant.parse(page.path("checked_at").asText());
        assertFalse(checked.isBefore(before)); assertFalse(checked.isAfter(after)); assertEquals(0, checked.getNano() % 1000);
        assertEquals("ALL", page.path("scope").asText()); assertTrue(page.path("items").isEmpty()); assertTrue(page.path("next_cursor").isNull());
    }

    @Test void everyKnownEventHasAnExplicitKindAndOnlyRunEventsHydrateRuns() {
        UUID run = run("UNKNOWN"); Instant time = now().minusSeconds(1);
        AuditHistoryService.EVENT_KINDS.forEach((type, kind) -> event(type, run, time));
        JsonNode page = page(); assertEquals(AuditHistoryService.EVENT_KINDS.size(), page.path("items").size());
        for (JsonNode item : page.path("items")) {
            assertEquals(Set.of("id", "event_type", "happened_at", "resource_kind", "resource_id", "run"), Set.copyOf(item.propertyNames()));
            String type = item.path("event_type").asText(), kind = AuditHistoryService.EVENT_KINDS.get(type);
            assertEquals(kind, item.path("resource_kind").asText()); assertEquals(run.toString(), item.path("resource_id").asText());
            if (kind.equals("RUN")) {
                assertEquals(Set.of("id", "current_status"), Set.copyOf(item.path("run").propertyNames()));
                assertEquals(run.toString(), item.path("run").path("id").asText()); assertEquals("UNKNOWN", item.path("run").path("current_status").asText());
            } else assertTrue(item.path("run").isNull(), type);
        }
        assertEquals(AuditHistoryService.RUN_EVENTS.size(), page("RUNS", null, 100).path("items").size());
    }

    @Test void unknownEventsAreSanitizedWithoutPrefixClassification() {
        UUID run = run("SUCCEEDED");
        for (String type : List.of("RUN_SECRET_PAYLOAD_SENTINEL", "RUN_FAILED", "LOCAL_INFERENCE_EVIL", "<script>secret-event</script>", "")) event(type, run, now());
        JsonNode response = page(); assertEquals(5, response.path("items").size());
        for (JsonNode item : response.path("items")) {
            assertEquals("OTHER", item.path("event_type").asText()); assertEquals("OTHER", item.path("resource_kind").asText());
            assertTrue(item.path("resource_id").isNull()); assertTrue(item.path("run").isNull());
        }
        assertFalse(response.toString().contains("SECRET")); assertFalse(response.toString().contains("script"));
        assertTrue(page("RUNS", null, 100).path("items").isEmpty());
    }

    @Test void currentRunStateIsExplicitAndUnknownIsNeverConvertedToSuccess() {
        UUID run = run("UNKNOWN"); event("MOCK_RECONCILED", run, now().minusSeconds(10));
        JsonNode item = page().path("items").get(0);
        assertEquals("UNKNOWN", item.path("run").path("current_status").asText());
        assertFalse(item.has("status")); assertFalse(item.path("run").has("execution_mode")); assertFalse(item.path("run").has("verified"));
        db.update("UPDATE runs SET status='FAILED',version=version+1 WHERE id=?", run);
        assertEquals("FAILED", page().path("items").get(0).path("run").path("current_status").asText());
    }

    @Test void missingRunKeepsOnlyHistoricalReferenceAndCannotBecomeADeadLink() {
        UUID absent = UUID.randomUUID(); event("RUN_ACCEPTED", absent, now());
        JsonNode item = page("RUNS", null, 20).path("items").get(0);
        assertEquals("RUN", item.path("resource_kind").asText()); assertEquals(absent.toString(), item.path("resource_id").asText());
        assertTrue(item.path("run").isNull());
    }

    @Test void otherOwnerEventsAndCurrentStateCannotLeakThroughPolymorphicReferences() throws Exception {
        UUID other = UUID.randomUUID(), conversation = UUID.randomUUID(), foreignRun = UUID.randomUUID();
        var manager = context.getBean(PlatformTransactionManager.class);
        var fixture = new TransactionTemplate(manager);
        fixture.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        String singleton = db.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='owners'::regclass AND conname='owners_singleton_key'", String.class);
        Map<String,String> before = databaseContents();
        AuditHistoryService isolated = service(db);
        try {
            // Only this synthetic projection fixture joins its uncommitted setup. The real
            // service keeps REQUIRES_NEW/read-only snapshots, covered independently below.
            var read = (TransactionTemplate) Objects.requireNonNull(ReflectionTestUtils.getField(isolated, "readTx"));
            read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
            fixture.executeWithoutResult(transaction -> {
                transaction.setRollbackOnly();
                // A temporary second principal is local to this rollback-only transaction.
                // Neither a source DELETE nor a disabled resurrection guard is needed.
                db.execute("ALTER TABLE owners DROP CONSTRAINT owners_singleton_key");
                db.update("INSERT INTO owners(id,login_name,password_hash) VALUES (?,'other-audit-owner','unused')", other);
                db.update("INSERT INTO conversations(id,owner_id,title) VALUES (?,?,'foreign-conversation-secret')", conversation, other);
                db.update("INSERT INTO runs(id,owner_id,conversation_id,status) VALUES (?,?,?,'PARTIAL_SUCCESS')", foreignRun, other, conversation);
                db.update("INSERT INTO audit_events(id,owner_id,event_type,resource_id) VALUES (?,?,'RUN_SUCCEEDED',?)", UUID.randomUUID(), other, foreignRun);
                event("RUN_ACCEPTED", foreignRun, now());
                JsonNode response = body(isolated.get(session, "ALL", null, 100)); assertEquals(1, response.path("items").size());
                JsonNode item = response.path("items").get(0); assertTrue(item.path("run").isNull());
                assertFalse(response.toString().contains("PARTIAL_SUCCESS")); assertFalse(response.toString().contains("foreign-conversation-secret"));
            });
        } finally { isolated.close(); }
        assertEquals(before, databaseContents(), "Fixture rows and source versions must all roll back");
        assertEquals(singleton, db.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='owners'::regclass AND conname='owners_singleton_key'", String.class), "Rollback must restore the singleton constraint");
    }

    @Test void secretsContentAndAuthenticationHashesNeverEnterProjectionOrCursor() {
        UUID run = run("WAITING_APPROVAL"); event("APPROVAL_CONSUMED", run, now()); event("OWNER_PROFILE_UPDATED", owner, now());
        db.update("UPDATE owners SET profile_ref='private-profile-reference-sentinel',profile_updated_at=clock_timestamp(),password_hash='private-password-sentinel' WHERE id=?", owner);
        JsonNode response = page("ALL", null, 1); String cursor = next(response);
        assertNotNull(cursor); String decoded = new String(Base64.getUrlDecoder().decode(cursor.split("\\.")[0]), StandardCharsets.UTF_8);
        for (String value : List.of("private-conversation-sentinel", "private-result-reference-sentinel", "private-profile-reference-sentinel", "private-password-sentinel", session.hash(), token)) {
            assertFalse(response.toString().contains(value)); assertFalse(decoded.contains(value));
        }
        for (String key : List.of("result", "result_ref", "payload", "payload_hash", "content_ref", "session_hash", "error", "execution_mode")) assertFalse(response.toString().contains('"' + key + '"'));
    }

    @Test void descendingKeysetHandlesTimestampTiesAndExactPageEndsWithoutDuplicates() {
        Instant tied = Instant.parse("2026-01-01T00:00:00.123456Z"); List<String> expected = new ArrayList<>();
        for (int i = 1; i <= 5; i++) expected.add(event(UUID.fromString("00000000-0000-4000-8000-" + String.format("%012d", i)), "OWNER_CREATED", owner, tied).toString());
        Collections.reverse(expected); List<String> actual = new ArrayList<>(); String cursor = null;
        do { JsonNode page = page("ALL", cursor, 2); actual.addAll(ids(page.path("items"))); cursor = next(page); } while (cursor != null);
        assertEquals(expected, actual); assertEquals(actual.size(), new HashSet<>(actual).size());
        JsonNode full = page("ALL", null, 5); assertEquals(5, full.path("items").size()); assertNull(next(full));
    }

    @Test void concurrentNewerRowsDoNotRepeatExistingRowsAndBackdatedRowsCanAppearOnFreshPages() {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        UUID oldest = event("OWNER_CREATED", owner, base), middle = event("OWNER_CREATED", owner, base.plusSeconds(10));
        UUID newest = event("OWNER_CREATED", owner, base.plusSeconds(20));
        JsonNode first = page("ALL", null, 1); assertEquals(List.of(newest.toString()), ids(first.path("items")));
        UUID insertedNewer = event("OWNER_CREATED", owner, base.plusSeconds(30));
        UUID insertedBackdated = event("OWNER_CREATED", owner, base.plusSeconds(5));
        JsonNode rest = page("ALL", next(first), 100);
        assertEquals(List.of(middle.toString(), insertedBackdated.toString(), oldest.toString()), ids(rest.path("items")));
        assertEquals(insertedNewer.toString(), page().path("items").get(0).path("id").asText());
    }

    @Test void cursorRejectsTamperScopeSessionAndEpochReuse() {
        UUID run = run("UNKNOWN"); event("RUN_ACCEPTED", run, now()); event("RUN_UNKNOWN", run, now());
        String cursor = next(page("ALL", null, 1)); assertNotNull(cursor);
        code("INVALID_CURSOR", () -> page("RUNS", cursor, 1));
        for (String invalid : List.of("", " ", "x", cursor + "=", cursor + ".extra", "x".repeat(1025), (cursor.charAt(0) == 'A' ? "B" : "A") + cursor.substring(1)))
            code("INVALID_CURSOR", () -> page("ALL", invalid, 1));
        String otherToken = auth.issue(owner, true, null).token(); AuthService.Session otherSession = auth.find(otherToken);
        code("INVALID_CURSOR", () -> history().get(otherSession, "ALL", cursor, 1));
        db.update("UPDATE owners SET security_epoch=security_epoch+1 WHERE id=?", owner);
        db.update("UPDATE sessions SET security_epoch=security_epoch+1 WHERE owner_id=?", owner);
        AuthService.Session changed = auth.find(token);
        code("INVALID_CURSOR", () -> history().get(changed, "ALL", cursor, 1));
    }

    @Test void cursorCannotReplayIntoAnotherLiveOwnerSession() {
        event("OWNER_CREATED", owner, now()); event("OWNER_CREATED", owner, now());
        String cursor = next(page("ALL", null, 1)); UUID other = UUID.randomUUID();
        db.execute("ALTER TABLE owners DROP CONSTRAINT owners_singleton_key");
        try {
            db.update("INSERT INTO owners(id,login_name,password_hash) VALUES (?,'audit-cursor-other-owner','unused')", other);
            String otherToken = auth.issue(other, true, null).token(); AuthService.Session otherSession = auth.find(otherToken);
            code("INVALID_CURSOR", () -> history().get(otherSession, "ALL", cursor, 1));
        } finally {
            db.update("DELETE FROM sessions WHERE owner_id=?", other); db.update("DELETE FROM owners WHERE id=?", other);
            db.execute("ALTER TABLE owners ADD CONSTRAINT owners_singleton_key UNIQUE(singleton)");
        }
    }

    @Test void concurrentEqualTimestampInsertsUseTheStrictUuidBoundary() {
        Instant tied = Instant.parse("2026-01-01T00:00:00.123456Z");
        UUID low = UUID.fromString("00000000-0000-4000-8000-000000000001");
        UUID middle = UUID.fromString("00000000-0000-4000-8000-000000000003");
        UUID first = UUID.fromString("00000000-0000-4000-8000-000000000005");
        UUID below = UUID.fromString("00000000-0000-4000-8000-000000000004");
        UUID above = UUID.fromString("00000000-0000-4000-8000-000000000006");
        for (UUID id : List.of(low, middle, first)) event(id, "OWNER_CREATED", owner, tied);
        JsonNode page = page("ALL", null, 1); assertEquals(first.toString(), page.path("items").get(0).path("id").asText());
        event(below, "OWNER_CREATED", owner, tied); event(above, "OWNER_CREATED", owner, tied);
        assertEquals(List.of(below.toString(), middle.toString(), low.toString()), ids(page("ALL", next(page), 100).path("items")));
    }

    @Test void malformedStoredRunStateFailsClosedWithoutReturningTheRawValue() {
        UUID run = run("UNKNOWN"); event("RUN_UNKNOWN", run, now());
        db.execute("ALTER TABLE runs DROP CONSTRAINT runs_status_check");
        try {
            db.update("UPDATE runs SET status='PRIVATE_INVALID_STATUS_SENTINEL' WHERE id=?", run);
            assertThrows(IllegalStateException.class, () -> page());
        } finally {
            db.update("UPDATE runs SET status='UNKNOWN' WHERE id=?", run);
            db.execute("ALTER TABLE runs ADD CONSTRAINT runs_status_check CHECK(status IN ('ACCEPTED','PLANNING','WAITING_INPUT','WAITING_APPROVAL','QUEUED','RUNNING','SUCCEEDED','PARTIAL_SUCCESS','FAILED','CANCEL_REQUESTED','CANCELLED','UNKNOWN','EXPIRED','BLOCKED'))");
        }
    }

    @Test void sessionAndOwnerAreRevalidatedBeforeCursorAccess() {
        code("AUTHENTICATION_REQUIRED", () -> history().get(null, "ALL", null, 20));
        code("SESSION_REVOKED", () -> history().get(new AuthService.Session(session.hash(), UUID.randomUUID(), session.reauth(), session.epoch()), "ALL", null, 20));
        code("SESSION_REVOKED", () -> history().get(new AuthService.Session("different-session", owner, session.reauth(), session.epoch()), "ALL", null, 20));
        db.update("UPDATE sessions SET last_seen_at=now()-interval '3 hours'"); code("SESSION_REVOKED", () -> page());
        db.update("UPDATE sessions SET last_seen_at=now(),created_at=now()-interval '13 hours'"); code("SESSION_REVOKED", () -> page());
        db.update("UPDATE sessions SET created_at=now(),revoked_at=now()"); code("SESSION_REVOKED", () -> page());
        db.update("UPDATE sessions SET revoked_at=NULL"); db.update("UPDATE owners SET security_epoch=security_epoch+1"); code("SESSION_REVOKED", () -> page());
    }

    @Test void invalidScopeAndPageBoundsFailClosed() {
        code("INVALID_REQUEST", () -> page(null, null, 20));
        for (String scope : List.of("", "all", "RUN", "OTHER", "RUNS ")) code("INVALID_REQUEST", () -> page(scope, null, 20));
        for (int limit : List.of(-1, 0, 101, Integer.MAX_VALUE)) code("INVALID_REQUEST", () -> page("ALL", null, limit));
    }

    Map<String,String> databaseContents() {
        Map<String,String> snapshot = new TreeMap<>();
        for (String table : db.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename", String.class)) {
            if (!table.matches("[a-z_][a-z0-9_]*")) throw new AssertionError("Unexpected table identifier");
            snapshot.put(table, db.queryForObject("SELECT COALESCE(jsonb_agg(value ORDER BY value::text),'[]'::jsonb)::text FROM (SELECT to_jsonb(t) AS value FROM " + table + " t) rows", String.class));
        }
        return snapshot;
    }

    @Test void everyTableIncludingSessionsAuditAndNoncesRemainsByteEquivalentWhilePaused() {
        UUID run = run("UNKNOWN"); event("RUN_UNKNOWN", run, now()); event("EXECUTION_PAUSED", owner, now());
        db.update("UPDATE owners SET execution_paused=true WHERE id=?", owner);
        Map<String,String> before = databaseContents();
        JsonNode first = page("ALL", null, 1); page("ALL", next(first), 100); page("RUNS", null, 100);
        assertEquals(before, databaseContents());
    }

    @Test void concurrentCommitCannotSplitEventAndCurrentStateWithinOneReadOnlyPage() throws Exception {
        UUID run = run("UNKNOWN"); event("RUN_UNKNOWN", run, now().minusSeconds(10));
        JdbcTemplate intercepted = new JdbcTemplate(Objects.requireNonNull(db.getDataSource())) {
            boolean changed;
            @Override public List<Map<String,Object>> queryForList(String sql, Object... args) {
                if (!changed && sql.startsWith("SELECT e.id,e.event_type")) {
                    changed = true;
                    assertEquals("on", super.queryForObject("SHOW transaction_read_only", String.class));
                    assertEquals("repeatable read", super.queryForObject("SHOW transaction_isolation", String.class));
                    try (var executor = Executors.newSingleThreadExecutor()) {
                        executor.submit(() -> {
                            db.update("UPDATE runs SET status='SUCCEEDED',version=version+1 WHERE id=?", run);
                            event("RUN_SUCCEEDED", run, now());
                        }).get(5, TimeUnit.SECONDS);
                    } catch (Exception error) { throw new AssertionError(error); }
                }
                return super.queryForList(sql, args);
            }
        };
        AuditHistoryService service = service(intercepted);
        try {
            JsonNode response = body(service.get(session, "ALL", null, 100));
            assertEquals(1, response.path("items").size()); assertEquals("UNKNOWN", response.path("items").get(0).path("run").path("current_status").asText());
            assertEquals(2, page().path("items").size()); assertEquals("SUCCEEDED", page().path("items").get(0).path("run").path("current_status").asText());
        } finally { service.close(); }
    }

    @Test void malformedStoredTimestampsFailClosedRatherThanProducingEmptyOrSuccessfulData() {
        UUID id = event("OWNER_CREATED", owner, now());
        for (String timestamp : List.of("infinity", "-infinity", "10000-01-01 00:00:00+00", "0001-01-01 00:00:00+00 BC")) {
            db.update("UPDATE audit_events SET happened_at=?::timestamptz WHERE id=?", timestamp, id);
            assertThrows(RuntimeException.class, () -> page());
        }
    }

    @Test void databaseFailureIsNotTreatedAsAnEmptyHistory() {
        db.execute("ALTER TABLE audit_events RENAME TO audit_test_hidden_events");
        try { assertThrows(org.springframework.dao.DataAccessException.class, () -> page()); }
        finally { db.execute("ALTER TABLE audit_test_hidden_events RENAME TO audit_events"); }
    }

    String signed(String body) throws Exception {
        byte[] root = Files.readAllBytes(key), derived = hmac(root, "jarvis:audit-history:cursor:v1");
        try {
            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(body.getBytes(StandardCharsets.UTF_8));
            return encoded + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(derived, encoded));
        } finally { Arrays.fill(root, (byte) 0); Arrays.fill(derived, (byte) 0); }
    }
    byte[] hmac(byte[] key, String text) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256")); return mac.doFinal(text.getBytes(StandardCharsets.UTF_8));
    }
    String[] fields(String cursor) { return new String(Base64.getUrlDecoder().decode(cursor.split("\\.")[0]), StandardCharsets.UTF_8).split("\\|", -1); }

    @Test void authenticButUnknownCursorShapesAndNoncanonicalBoundariesAreRejected() throws Exception {
        event("OWNER_CREATED", owner, now()); event("OWNER_CREATED", owner, now()); String cursor = next(page("ALL", null, 1));
        for (int index : List.of(0, 4, 5, 6, 7, 8)) {
            String[] fields = fields(cursor);
            fields[index] = switch (index) { case 0 -> "2"; case 4 -> "01"; case 5 -> "9999-01-01T00:00:00Z"; case 6 -> "2000-01-01T00:00:00Z"; case 7 -> "2026-01-01T00:00:00.000000001Z"; default -> "1-1-1-1-1"; };
            String invalid = signed(String.join("|", fields)); code("INVALID_CURSOR", () -> page("ALL", invalid, 1));
        }
        String extra = signed(String.join("|", fields(cursor)) + "|extra"); code("INVALID_CURSOR", () -> page("ALL", extra, 1));
    }

    @Test void cursorExpiresAtFifteenMinutesOfDatabaseTimeWithoutRenewalAcrossPages() throws Exception {
        for (int i = 0; i < 3; i++) event("OWNER_CREATED", owner, now().minusSeconds(i));
        String first = next(page("ALL", null, 1)), second = next(page("ALL", first, 1));
        assertEquals(fields(first)[5], fields(second)[5]); assertEquals(fields(first)[6], fields(second)[6]);
        Instant issued = Instant.parse(fields(first)[5]), expires = Instant.parse(fields(first)[6]); assertEquals(issued.plusSeconds(900), expires);
        JdbcTemplate clock = new JdbcTemplate(Objects.requireNonNull(db.getDataSource())) {
            @Override public List<Map<String,Object>> queryForList(String sql, Object... args) {
                var rows = super.queryForList(sql, args);
                if (sql.startsWith("WITH checked AS MATERIALIZED") && !rows.isEmpty()) rows.getFirst().put("checked_at", Timestamp.from(expires));
                return rows;
            }
        };
        AuditHistoryService service = service(clock);
        try { code("AUDIT_CURSOR_EXPIRED", () -> service.get(session, "ALL", first, 1)); }
        finally { service.close(); }
    }
}

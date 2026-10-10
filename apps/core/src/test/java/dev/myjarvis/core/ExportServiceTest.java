package dev.myjarvis.core;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** PostgreSQL-backed synthetic privacy tests. No real owner records, providers or stored artifacts. */
class ExportServiceTest extends ReminderTestSupport {
    ExportService exports() { return context.getBean(ExportService.class); }
    MemoryService memories() { return context.getBean(MemoryService.class); }
    MemoryIntentService intents() { return context.getBean(MemoryIntentService.class); }
    MemoryDeletionService deletion() { return context.getBean(MemoryDeletionService.class); }
    Instant now() { return memories().databaseNow(); }
    UUID id(Map<String,Object> value) { return UUID.fromString(value.get("id").toString()); }
    JsonNode request(UUID project) {
        Map<String,Object> value = new LinkedHashMap<>();
        value.put("kind", "EXPLICIT_MEMORIES"); value.put("scope", project == null ? "GLOBAL" : "PROJECT");
        value.put("project_id", project); value.put("format", "JSON_UTF8"); return body(value);
    }
    Map<String,Object> job(UUID project) { return exports().create(session, UUID.randomUUID(), request(project)); }
    JsonNode document(UUID job) { return Json.MAPPER.readTree(exports().download(session, job)); }
    UUID project() { UUID id = UUID.randomUUID(); db.update("INSERT INTO projects(id,owner_id,name) VALUES (?,?,'Synthetic export project')", id, owner); return id; }
    Map<String,Object> createMemory(String name, String value, UUID project) {
        Map<String,Object> args = new LinkedHashMap<>(); args.put("scope", project == null ? "GLOBAL" : "PROJECT"); args.put("project_id", project);
        args.put("key", name); args.put("kind", "PREFERENCE"); args.put("value", value); args.put("change_reason", "Synthetic export reason"); args.put("expected_generation", 0); args.put("revive_deleted", false);
        Map<String,Object> issuance = new LinkedHashMap<>(); issuance.put("tool_id", "memory.write"); issuance.put("operation", "CREATE"); issuance.put("target_id", null); issuance.put("arguments", args);
        var intent = intents().issue(session, UUID.randomUUID(), body(issuance));
        var receipt = memories().save(session, UUID.randomUUID(), null, body(Map.of("intent_id", intent.get("id"), "arguments", args)), true);
        return memories().get(session, UUID.fromString(receipt.get("memory_id").toString()));
    }
    void revise(Map<String,Object> memory, String value) {
        var args = body(Map.of("value", value, "change_reason", "Synthetic revised reason", "expected_version", memory.get("version"), "expected_generation", memory.get("generation")));
        var intent = intents().issue(session, UUID.randomUUID(), body(Map.of("tool_id", "memory.write", "operation", "REVISE", "target_id", memory.get("id"), "arguments", args)));
        memories().save(session, UUID.randomUUID(), id(memory), body(Map.of("intent_id", intent.get("id"), "arguments", args)), false);
    }
    void remove(Map<String,Object> memory) {
        var prepared = deletion().prepare(session, UUID.randomUUID(), id(memory), body(Map.of("expected_version", memory.get("version"), "expected_generation", memory.get("generation"))));
        deletion().confirm(session, UUID.randomUUID(), id(prepared), body(Map.of("payload_hash", prepared.get("payload_hash"), "expected_version", prepared.get("expected_version"), "expected_generation", prepared.get("expected_generation"), "confirm", true)));
    }
    ExportService controlled(MemoryService memory) { return new ExportService(memory, context.getBean(PlatformTransactionManager.class)); }
    void expire(UUID id) { db.update("UPDATE memory_export_jobs SET created_at=created_at-interval '16 minutes',expires_at=expires_at-interval '16 minutes' WHERE id=?", id); }

    @Test void jobsAreOnlyMetadataAndExactReplayDoesNotRefreshExpiry() {
        createMemory("private.key", "synthetic-private-value", null); UUID key = UUID.randomUUID();
        var created = exports().create(session, key, request(null)); var replay = exports().create(session, key, request(null));
        assertEquals(Set.of("id", "kind", "scope", "project_id", "format", "semantics", "status", "created_at", "expires_at", "checked_at"), created.keySet());
        for (String field : List.of("id", "created_at", "expires_at")) assertEquals(created.get(field), replay.get(field));
        assertEquals("CURRENT_AT_DOWNLOAD", created.get("semantics")); assertEquals("AVAILABLE", created.get("status"));
        var receipt = exports().reconcile(session, key); assertEquals("COMMITTED", receipt.get("status")); assertEquals(id(created), id(cast(receipt.get("job"))));
        assertEquals("NOT_OBSERVED", exports().reconcile(session, UUID.randomUUID()).get("status"));
        String rows = Json.canonical(db.queryForList("SELECT * FROM memory_export_jobs"));
        for (String secret : List.of("private.key", "synthetic-private-value", "payload_ref", "arguments_ref", "download_url", "submission_id", "source_hash")) assertFalse(rows.contains(secret), secret);
        assertEquals(1, count("memory_export_jobs")); assertEquals(0, count("jobs")); assertEquals(0, count("runs"));
        UUID project = project(); code("IDEMPOTENCY_CONFLICT", () -> exports().create(session, key, request(project)));
        expire(id(created)); var expired = exports().create(session, key, request(null)); assertEquals("EXPIRED", expired.get("status"));
        code("EXPORT_EXPIRED", () -> exports().download(session, id(created))); assertEquals(1, count("memory_export_jobs"));
    }
    @SuppressWarnings("unchecked") static Map<String,Object> cast(Object value) { return (Map<String,Object>) value; }

    @Test void exactScopeCurrentValuesCanonicalUnicodeAndClosedOutputFields() {
        UUID project = project(), otherProject = project(); String exact = "  한글 😀 e\u0301 <script>inert</script>\n";
        var global = createMemory("global.key", "synthetic-old", null);
        var selected = createMemory("selected.key", exact, project); createMemory("other.key", "synthetic-other-project", otherProject);
        var globalJob = job(null); var projectJob = job(project);
        revise(global, "synthetic-current");
        JsonNode globalFile = document(id(globalJob)); assertEquals(1, globalFile.get("items").size()); assertEquals("synthetic-current", globalFile.get("items").get(0).get("value").asText());
        byte[] bytes = exports().download(session, id(projectJob)); JsonNode file = Json.MAPPER.readTree(bytes);
        assertEquals(Set.of("schema_version", "kind", "scope", "project_id", "export_job_id", "exported_at", "semantics", "items"), Set.copyOf(file.propertyNames()));
        assertEquals(Json.canonical(file), new String(bytes, StandardCharsets.UTF_8)); assertEquals(1, file.get("items").size());
        JsonNode item = file.get("items").get(0);
        assertEquals(Set.of("id", "key", "kind", "value", "change_reason", "version", "confirmed_at", "created_at", "updated_at", "source"), Set.copyOf(item.propertyNames()));
        assertEquals(Set.of("type", "submitted_at"), Set.copyOf(item.get("source").propertyNames())); assertEquals(exact, item.get("value").asText()); assertEquals(id(selected).toString(), item.get("id").asText());
        for (String forbidden : List.of("source_hash", "submission_id", "generation", "session_hash", "payload_ref", "security_epoch", "request_digest", "synthetic-other-project", "synthetic-old")) assertFalse(new String(bytes, StandardCharsets.UTF_8).contains(forbidden), forbidden);
        db.update("UPDATE projects SET active=false WHERE owner_id=? AND id=?", owner, project);
        assertEquals(1, document(id(projectJob)).get("items").size());
    }

    @Test void currentSnapshotIncludesLaterCreateAndExcludesLaterDeleteWithoutArtifactCopies() {
        var job = job(null); assertTrue(document(id(job)).get("items").isEmpty());
        var memory = createMemory("after.creation", "synthetic-delete-sentinel", null);
        assertEquals(1, document(id(job)).get("items").size()); remove(memory);
        String file = new String(exports().download(session, id(job)), StandardCharsets.UTF_8);
        assertFalse(file.contains("synthetic-delete-sentinel")); assertTrue(Json.MAPPER.readTree(file).get("items").isEmpty());
        assertNull(db.queryForObject("SELECT payload_ref FROM memories WHERE id=?", String.class, id(memory))); assertEquals(0, count("memory_revisions"));
        assertEquals(1, count("memory_export_jobs"));
        assertEquals(Set.of("id", "owner_id", "kind", "scope", "project_id", "format", "session_hash", "security_epoch", "idempotency_key", "request_digest", "created_at", "expires_at"), db.queryForMap("SELECT * FROM memory_export_jobs").keySet());
    }

    @Test void ownerSessionEpochReauthAndPauseAreCheckedIndependently() {
        var created = job(null); UUID id = id(created); UUID key = db.queryForObject("SELECT idempotency_key FROM memory_export_jobs WHERE id=?", UUID.class, id);
        UUID otherOwner = UUID.randomUUID();
        // Synthetic adversarial fixture only, matching the existing cross-owner memory tests.
        db.execute("ALTER TABLE owners DROP CONSTRAINT owners_singleton_key");
        try {
            db.update("INSERT INTO owners(id,login_name,password_hash) VALUES (?,'other-export-owner','synthetic-unused')", otherOwner);
            var other = auth.find(auth.issue(otherOwner, true, null).token());
            code("NOT_FOUND", () -> exports().status(other, id)); code("NOT_FOUND", () -> exports().download(other, id));
            assertEquals("NOT_OBSERVED", exports().reconcile(other, key).get("status"));
        } finally {
            try { db.update("DELETE FROM sessions WHERE owner_id=?", otherOwner); }
            finally {
                try { db.update("DELETE FROM owners WHERE id=?", otherOwner); }
                finally { db.execute("ALTER TABLE owners ADD CONSTRAINT owners_singleton_key UNIQUE(singleton)"); }
            }
        }
        assertEquals(1L, db.queryForObject("SELECT count(*) FROM pg_constraint WHERE conrelid='owners'::regclass AND conname='owners_singleton_key' AND contype='u'", Long.class));
        var separate = auth.find(auth.issue(owner, true, null).token());
        code("AUTHORITY_NOT_VALID", () -> exports().status(separate, id)); code("AUTHORITY_NOT_VALID", () -> exports().reconcile(separate, key)); code("AUTHORITY_NOT_VALID", () -> exports().download(separate, id));
        db.update("UPDATE sessions SET reauthenticated_at=clock_timestamp()-interval '301 seconds' WHERE token_hash=?", session.hash());
        assertEquals("AVAILABLE", exports().status(session, id).get("status"));
        code("REAUTHENTICATION_REQUIRED", () -> exports().download(session, id)); code("REAUTHENTICATION_REQUIRED", () -> job(null));
        db.update("UPDATE sessions SET reauthenticated_at=clock_timestamp()+interval '1 minute' WHERE token_hash=?", session.hash());
        code("REAUTHENTICATION_REQUIRED", () -> exports().download(session, id));
        db.update("UPDATE sessions SET reauthenticated_at=clock_timestamp() WHERE token_hash=?", session.hash());
        db.update("UPDATE owners SET execution_paused=true WHERE id=?", owner);
        assertEquals("AVAILABLE", exports().status(session, id).get("status")); assertTrue(document(id).get("items").isEmpty());
        code("EXECUTION_PAUSED", () -> job(null)); assertEquals(id, id(exports().create(session, key, request(null))));
        var rotated = auth.find(auth.issue(owner, true, session.hash()).token());
        code("SESSION_REVOKED", () -> exports().status(session, id)); code("AUTHORITY_NOT_VALID", () -> exports().download(rotated, id));
        db.update("UPDATE owners SET security_epoch=security_epoch+1 WHERE id=?", owner);
        code("SESSION_REVOKED", () -> exports().reconcile(rotated, key));
    }

    @Test void strictRequestRejectsAuthoritySmugglingScopeConfusionAndMalformedIdentifiers() {
        var valid = request(null);
        for (String field : List.of("owner_id", "payload_ref", "session_hash", "download_url", "include_deleted", "include_revisions", "instructions")) {
            var bad = (ObjectNode)valid.deepCopy(); bad.put(field, "synthetic"); code("INVALID_REQUEST", () -> exports().create(session, UUID.randomUUID(), bad));
        }
        for (String field : List.of("kind", "scope", "project_id", "format")) {
            var missing = (ObjectNode)valid.deepCopy(); missing.remove(field); code("INVALID_REQUEST", () -> exports().create(session, UUID.randomUUID(), missing));
        }
        for (String json : List.of("{\"kind\":\"EXPLICIT_MEMORIES\",\"scope\":\"ALL\",\"project_id\":null,\"format\":\"JSON_UTF8\"}", "{\"kind\":\"EXPLICIT_MEMORIES\",\"scope\":\"PROJECT\",\"project_id\":null,\"format\":\"JSON_UTF8\"}", "{\"kind\":\"EXPLICIT_MEMORIES\",\"scope\":\"GLOBAL\",\"project_id\":\"00000000-0000-4000-8000-000000000001\",\"format\":\"JSON_UTF8\"}"))
            code("INVALID_REQUEST", () -> exports().create(session, UUID.randomUUID(), Json.parse(json.getBytes(StandardCharsets.UTF_8))));
        code("NOT_FOUND", () -> job(UUID.randomUUID())); assertEquals(0, count("memory_export_jobs"));
    }

    @Test void ownerCapAndCreationOnlyRetentionAreBoundedAndReplayDoesNotConsumeCapacity() {
        UUID originalKey = UUID.randomUUID(); var original = exports().create(session, originalKey, request(null));
        for (int i = 1; i < ExportService.MAX_JOBS; i++) job(null);
        code("EXPORT_JOB_LIMIT", () -> job(null)); assertEquals(ExportService.MAX_JOBS, count("memory_export_jobs"));
        assertEquals(id(original), id(exports().create(session, originalKey, request(null))));
        db.update("UPDATE memory_export_jobs SET created_at=created_at-interval '25 hours',expires_at=expires_at-interval '25 hours' WHERE id=?", id(original));
        assertEquals("EXPIRED", exports().status(session, id(original)).get("status")); assertEquals(ExportService.MAX_JOBS, count("memory_export_jobs"));
        job(null); assertEquals(ExportService.MAX_JOBS, count("memory_export_jobs")); assertEquals("NOT_OBSERVED", exports().reconcile(session, originalKey).get("status"));
    }

    void syntheticRows(int rows) {
        db.update("INSERT INTO memory_slots(id,owner_id,scope,project_id,key_digest,generation) SELECT gen_random_uuid(),?,'GLOBAL',NULL,lpad(to_hex(n),64,'0'),1 FROM generate_series(1,?) n", owner, rows);
        db.update("INSERT INTO memories(id,owner_id,slot_id,kind,generation,version,payload_ref,confirmed_at,created_at,updated_at) SELECT id,owner_id,id,'PREFERENCE',1,1,'synthetic-not-a-real-ciphertext',now(),now(),now() FROM memory_slots WHERE owner_id=?", owner);
        db.update("UPDATE memory_slots SET current_memory_id=id WHERE owner_id=?", owner);
    }
    @Test void rowCeilingFailsBeforeDecryptAndByteCeilingFailsWithoutTruncation() {
        var job = job(null); syntheticRows(ExportService.MAX_ROWS + 1);
        code("EXPORT_ROW_LIMIT", () -> exports().download(session, id(job)));
        db.update("UPDATE memory_slots SET current_memory_id=NULL WHERE owner_id=?", owner);
        db.update("DELETE FROM memories WHERE id=(SELECT id FROM memories WHERE owner_id=? ORDER BY id LIMIT 1)", owner);
        db.update("UPDATE memory_slots SET current_memory_id=id WHERE owner_id=? AND EXISTS (SELECT 1 FROM memories m WHERE m.id=memory_slots.id)", owner);
        var memory = spy(memories());
        doAnswer(call -> Map.of("key", "synthetic.key", "value", "\u001f".repeat(2000), "change_reason", "Synthetic limit fixture", "source", Map.of("type", "EXPLICIT_OWNER_FORM", "submitted_at", AuthService.instant(((Map<?,?>)call.getArgument(0)).get("confirmed_at")).toString()))).when(memory).payload(anyMap());
        code("EXPORT_BYTE_LIMIT", () -> controlled(memory).download(session, id(job)));
        assertEquals(1, count("memory_export_jobs"));
    }

    @Test void byteBudgetCountsUtf8AndAllowsExactBoundary() {
        var bytes = new ExportService.BoundedBytes(); bytes.append("😀".repeat(ExportService.MAX_BYTES / 4));
        assertEquals(ExportService.MAX_BYTES, bytes.bytes().length); code("EXPORT_BYTE_LIMIT", () -> bytes.append("x"));
    }

    @Test void exactThousandRowsSucceedInCanonicalIdOrderWithOnlyAllowlistedFields() {
        UUID job = id(job(null)); syntheticRows(ExportService.MAX_ROWS);
        var memory = spy(memories());
        doAnswer(call -> {
            Map<?,?> row = call.getArgument(0);
            return Map.of("key", "fixture." + row.get("id"), "value", "Synthetic current value 😀", "change_reason", "Synthetic bounded fixture",
                    "private_configuration", "synthetic-never-export-this", "source", Map.of("type", "EXPLICIT_OWNER_FORM", "submitted_at", AuthService.instant(row.get("confirmed_at")).toString(), "submission_id", "synthetic-authority", "source_hash", "synthetic-private-hash"));
        }).when(memory).payload(anyMap());
        byte[] bytes = controlled(memory).download(session, job); JsonNode file = Json.MAPPER.readTree(bytes);
        assertEquals(ExportService.MAX_ROWS, file.get("items").size()); assertTrue(bytes.length <= ExportService.MAX_BYTES);
        String previous = "";
        for (JsonNode item : file.get("items")) {
            String current = item.get("id").asText(); assertTrue(current.compareTo(previous) > 0); previous = current;
            assertEquals(Set.of("type", "submitted_at"), Set.copyOf(item.get("source").propertyNames()));
        }
        String text = new String(bytes, StandardCharsets.UTF_8); assertEquals(Json.canonical(file), text);
        for (String secret : List.of("private_configuration", "synthetic-never-export-this", "submission_id", "synthetic-authority", "source_hash", "synthetic-private-hash")) assertFalse(text.contains(secret));
    }

    @Test void databaseOwnerLockTimeoutIsExplicitAndCannotCreateAJob() throws Exception {
        try (var pool = Executors.newSingleThreadExecutor()) {
            memories().writeTx.executeWithoutResult(tx -> {
                db.queryForObject("SELECT id FROM owners WHERE id=? FOR UPDATE", UUID.class, owner);
                Future<String> pending = pool.submit(() -> {
                    try { job(null); return "UNEXPECTED_COMMIT"; }
                    catch (ApiException e) { return e.status + ":" + e.code; }
                });
                try { assertEquals("503:EXPORT_TIME_LIMIT", pending.get(9, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                catch (ExecutionException | TimeoutException e) { throw new IllegalStateException(e); }
            });
        }
        assertEquals(0, count("memory_export_jobs"));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM audit_events WHERE event_type='MEMORY_EXPORT_CREATED'", Long.class));
    }

    @Test void metadataSurvivesRestartAndDownloadsAreRebuiltFromCurrentRows() {
        var memory = createMemory("restart.key", "synthetic-restart", null); UUID job = id(job(null));
        context.close(); open(); assertEquals("synthetic-restart", document(job).get("items").get(0).get("value").asText());
        remove(memory); assertTrue(document(job).get("items").isEmpty());
    }

    @Test void priorDeletionLogoutAndRotationWinAgainstWaitingDownload() throws Exception {
        for (String change : List.of("DELETE", "LOGOUT", "ROTATE")) {
            session = auth.find(auth.issue(owner, true, null).token()); var current = session;
            var memory = createMemory("race." + change.toLowerCase(Locale.ROOT), "synthetic-race", null); UUID job = id(job(null));
            CountDownLatch started = new CountDownLatch(1); AtomicReference<Future<String>> pending = new AtomicReference<>();
            try (var pool = Executors.newSingleThreadExecutor()) {
                memories().writeTx.executeWithoutResult(tx -> {
                    db.queryForObject("SELECT id FROM owners WHERE id=? FOR UPDATE", UUID.class, owner);
                    pending.set(pool.submit(() -> { started.countDown(); try { return new String(exports().download(current, job), StandardCharsets.UTF_8); } catch (ApiException e) { return e.code; } }));
                    try { assertTrue(started.await(2, TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new IllegalStateException(e); }
                    if (change.equals("DELETE")) remove(memory);
                    else if (change.equals("LOGOUT")) auth.logout(current);
                    else auth.issue(owner, true, current.hash());
                });
                String result = pending.get().get(10, TimeUnit.SECONDS);
                if (change.equals("DELETE")) assertFalse(result.contains("synthetic-race")); else assertEquals("SESSION_REVOKED", result);
            }
        }
    }

    @Test void generatedSnapshotCannotBeRecalledButLaterDeleteFencesNextDownload() throws Exception {
        var memory = createMemory("snapshot.key", "synthetic-snapshot", null); UUID job = id(job(null));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        var controlled = spy(memories());
        doAnswer(call -> { entered.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS)); return call.callRealMethod(); }).when(controlled).payload(anyMap());
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<byte[]> pending = pool.submit(() -> controlled(controlled).download(session, job)); assertTrue(entered.await(2, TimeUnit.SECONDS));
            Future<?> deletion = pool.submit(() -> remove(memory));
            assertThrows(TimeoutException.class, () -> deletion.get(100, TimeUnit.MILLISECONDS)); release.countDown();
            byte[] earlier = pending.get(10, TimeUnit.SECONDS); deletion.get(10, TimeUnit.SECONDS);
            assertTrue(new String(earlier, StandardCharsets.UTF_8).contains("synthetic-snapshot"));
            assertTrue(document(job).get("items").isEmpty()); assertNull(db.queryForObject("SELECT payload_ref FROM memories WHERE id=?", String.class, id(memory)));
        }
    }

    @Test void finalReleaseRechecksReauthAndExpiryAndReturnsNoPartialDocument() {
        createMemory("release.key", "synthetic-release", null); UUID job = id(job(null));
        for (boolean reauth : List.of(true, false)) {
            var memory = spy(memories());
            doAnswer(call -> {
                var result = call.callRealMethod();
                if (reauth) db.update("UPDATE sessions SET reauthenticated_at=clock_timestamp()-interval '301 seconds' WHERE token_hash=?", session.hash());
                else expire(job);
                return result;
            }).when(memory).payload(anyMap());
            code(reauth ? "REAUTHENTICATION_REQUIRED" : "EXPORT_EXPIRED", () -> controlled(memory).download(session, job));
            // Both test-only changes occurred in the failed download transaction and rolled back.
            assertEquals("AVAILABLE", exports().status(session, job).get("status"));
        }
        // Controlled clock-facade evidence for expiry crossing with immutable metadata.
        // This advances the checked instant after payload work; it is not a wall-clock wait.
        var memory = spy(memories()); var serialized = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(call -> { var result = call.callRealMethod(); serialized.set(true); return result; }).when(memory).payload(anyMap());
        doAnswer(call -> {
            Instant databaseNow = (Instant)call.callRealMethod();
            return serialized.get() ? databaseNow.plusSeconds(901) : databaseNow;
        }).when(memory).databaseNow();
        code("EXPORT_EXPIRED", () -> controlled(memory).download(session, job));
        assertTrue(serialized.get()); assertEquals("AVAILABLE", exports().status(session, job).get("status"));
    }

    @Test void databaseClockIgnoresApplicationClockSkewAndTimeBudgetFailsClosed() {
        for (Instant skew : List.of(now().minusSeconds(86400), now().plusSeconds(86400))) {
            try (var mocked = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
                mocked.when(Instant::now).thenReturn(skew); Instant before = now(); var job = job(null); Instant after = now();
                Instant created = Instant.parse(job.get("created_at").toString()); assertFalse(created.isBefore(before)); assertFalse(created.isAfter(after));
                assertEquals(900, java.time.Duration.between(created, Instant.parse(job.get("expires_at").toString())).toSeconds());
                assertTrue(document(id(job)).get("items").isEmpty());
            }
        }
        var budget = new ExportService.Budget();
        try { Thread.sleep(5100); } catch (InterruptedException e) { throw new IllegalStateException(e); }
        code("EXPORT_TIME_LIMIT", budget::check);
    }

    @Test void realHttpAuthenticationAndDatabaseProduceOnlyExplicitDownloadThenRejectRotation() throws Exception {
        var http = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(context.getBean(ExportController.class))
                .setControllerAdvice(new ApiErrors()).addFilters(new WebSecurity(auth, "https://localhost:8443", false)).build();
        var cookie = new jakarta.servlet.http.Cookie(WebSecurity.COOKIE, token); String csrf = auth.refreshCsrf(session);
        createMemory("http.synthetic", "Synthetic HTTP encrypted current value 한글", null);
        UUID key = UUID.randomUUID();
        var created = http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/exports")
                .cookie(cookie).header("Origin", "https://localhost:8443").header("X-CSRF-Token", csrf).header("Idempotency-Key", key.toString())
                .contentType("application/json").content(Json.canonical(request(null)))).andReturn().getResponse();
        assertEquals(200, created.getStatus());
        JsonNode metadata = Json.parse(created.getContentAsByteArray()).get("data"); String id = metadata.get("id").asText();
        assertFalse(created.getContentAsString().contains("Synthetic HTTP encrypted")); assertEquals(1, count("memory_export_jobs"));
        var download = http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/exports/" + id + "/download").cookie(cookie)).andReturn().getResponse();
        assertEquals(200, download.getStatus()); assertEquals("no-store", download.getHeader("Cache-Control"));
        assertEquals("Synthetic HTTP encrypted current value 한글", Json.parse(download.getContentAsByteArray()).get("items").get(0).get("value").asText());
        var next = auth.issue(owner, true, session.hash());
        assertEquals(401, http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/exports/" + id + "/download").cookie(cookie)).andReturn().getResponse().getStatus());
        var rebound = http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/exports/" + id + "/download")
                .cookie(new jakarta.servlet.http.Cookie(WebSecurity.COOKIE, next.token()))).andReturn().getResponse();
        assertEquals(403, rebound.getStatus()); assertEquals("AUTHORITY_NOT_VALID", Json.parse(rebound.getContentAsByteArray()).get("error").get("code").asText());
        assertNull(rebound.getHeader("Content-Disposition"));
    }
}

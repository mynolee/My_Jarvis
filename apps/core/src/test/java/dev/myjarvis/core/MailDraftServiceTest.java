package dev.myjarvis.core;

import java.nio.file.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.JsonNode;
import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL transactions and encryption; all inputs are synthetic. */
class MailDraftServiceTest extends ReminderTestSupport {
    MailDraftService drafts;
    @BeforeEach void mailService() { drafts = context.getBean(MailDraftService.class); }
    Map<String,Object> editable() {
        Map<String,Object> value = new LinkedHashMap<>(); value.put("sender_candidate", null);
        value.put("to", List.of()); value.put("cc", List.of()); value.put("bcc", List.of());
        value.put("subject", ""); value.put("body", ""); value.put("project_id", null); value.put("attachments", List.of()); return value;
    }
    Map<String,Object> createDraft() { return drafts.create(session, UUID.randomUUID(), body(editable())); }
    @SuppressWarnings("unchecked") Map<String,Object> record(Map<String,Object> result) { return (Map<String,Object>) result.get("draft"); }
    UUID draftId(Map<String,Object> result) { return (UUID) record(result).get("id"); }
    AuthService.Session foreign() {
        UUID id = UUID.randomUUID(); db.update("INSERT INTO owners(id,login_name,password_hash) VALUES (?,?,?)", id, "foreign-" + id, "synthetic-unused");
        return auth.find(auth.issue(id, true, null).token());
    }
    UUID project(boolean active) { UUID id = UUID.randomUUID(); db.update("INSERT INTO projects(id,owner_id,name,active) VALUES (?,?,?,?)", id, owner, "Synthetic", active); return id; }
    @Test void encryptedCreateDetailMetadataAndReceipt() {
        var value = editable(); value.put("sender_candidate", "  unverified synthetic sender  "); value.put("to", List.of("synthetic-recipient@example.invalid"));
        value.put("subject", "synthetic-subject-sentinel"); value.put("body", "synthetic-body-sentinel\n<script>inert</script>");
        UUID key = UUID.randomUUID(); var result = drafts.create(session, key, body(value)); var draft = record(result);
        assertEquals(value.get("sender_candidate"), draft.get("sender_candidate")); assertEquals("NOT_SENT", draft.get("delivery_status")); assertEquals(1L, draft.get("version"));
        assertEquals(draft, drafts.get(session, draftId(result))); assertEquals(result.get("mutation"), drafts.reconcile(session, key));
        String row = Json.MAPPER.writeValueAsString(db.queryForMap("SELECT * FROM mail_drafts WHERE id=?", draftId(result)));
        for (String secret : List.of("synthetic-subject-sentinel", "synthetic-body-sentinel", "synthetic-recipient")) assertFalse(row.contains(secret));
        String metadata = Json.MAPPER.writeValueAsString(drafts.list(session, null, null, 20));
        for (String field : List.of("sender_candidate", "subject", "body", "attachments", "synthetic-recipient")) assertFalse(metadata.contains(field));
        String receipts = Json.MAPPER.writeValueAsString(db.queryForList("SELECT * FROM mail_draft_mutations")); assertFalse(receipts.contains("synthetic-body"));
        assertEquals(1, count("mail_drafts")); assertEquals(1, count("mail_draft_mutations")); assertEquals(1, count("audit_events"));
        for (String table : List.of("runs", "jobs", "task_user_intents", "notifications", "schedules")) assertEquals(0, count(table));
    }
    @Test void patchPreservesOmittedFieldsAndOriginalReceiptReplaysCurrentRecord() {
        UUID key = UUID.randomUUID(); var initial = body(editable()); var created = drafts.create(session, key, initial); UUID id = draftId(created);
        UUID patchKey = UUID.randomUUID(); var update = body(Map.of("expected_version", 1, "subject", "  exact text  "));
        var updated = drafts.patch(session, id, patchKey, update); assertEquals(2L, record(updated).get("version")); assertEquals("  exact text  ", record(updated).get("subject"));
        assertEquals(List.of(), record(updated).get("to")); assertEquals(updated, drafts.patch(session, id, patchKey, update));
        var replay = drafts.create(session, key, initial); assertEquals(record(updated), record(replay)); assertEquals(created.get("mutation"), replay.get("mutation"));
        code("PRECONDITION_CHANGED", () -> drafts.patch(session, id, UUID.randomUUID(), update));
        code("IDEMPOTENCY_CONFLICT", () -> drafts.patch(session, id, patchKey, body(Map.of("expected_version", 1, "subject", "changed"))));
        code("IDEMPOTENCY_CONFLICT", () -> drafts.patch(session, id, key, update));
        UUID second = draftId(createDraft()); code("IDEMPOTENCY_CONFLICT", () -> drafts.patch(session, second, patchKey, update));
        assertEquals(2, count("mail_drafts")); assertEquals(3, count("mail_draft_mutations"));
    }
    @Test void concurrentSameKeyCommitsOneDraft() throws Exception {
        UUID key = UUID.randomUUID(); JsonNode request = body(editable());
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Callable<Map<String,Object>>> calls = new ArrayList<>(); for (int i = 0; i < 8; i++) calls.add(() -> drafts.create(session, key, request));
            Object first = null; for (var future : pool.invokeAll(calls, 30, TimeUnit.SECONDS)) { assertFalse(future.isCancelled()); var value = future.get(); if (first == null) first = value; else assertEquals(first, value); }
        }
        assertEquals(1, count("mail_drafts")); assertEquals(1, count("mail_draft_mutations")); assertEquals(1, count("audit_events"));
    }
    @Test void concurrentDistinctPatchKeysCannotLoseAnUpdate() throws Exception {
        UUID id = draftId(createDraft());
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Callable<String>> calls = new ArrayList<>();
            for (String subject : List.of("first", "second")) calls.add(() -> { try { drafts.patch(session, id, UUID.randomUUID(), body(Map.of("expected_version", 1, "subject", subject))); return "COMMITTED"; } catch (ApiException e) { return e.code; } });
            Set<String> outcomes = new HashSet<>(); for (var future : pool.invokeAll(calls, 30, TimeUnit.SECONDS)) outcomes.add(future.get());
            assertEquals(Set.of("COMMITTED", "PRECONDITION_CHANGED"), outcomes);
        }
        assertEquals(2L, drafts.get(session, id).get("version")); assertEquals(2, count("mail_draft_mutations"));
    }
    @Test void liveOwnerAndOriginalSessionAreRequiredForReplayAndReconciliation() {
        UUID key = UUID.randomUUID(); var request = body(editable()); var created = drafts.create(session, key, request); UUID id = draftId(created); var foreign = foreign();
        code("NOT_FOUND", () -> drafts.get(foreign, id)); code("NOT_FOUND", () -> drafts.patch(foreign, id, UUID.randomUUID(), body(Map.of("expected_version", 1, "body", "x"))));
        assertEquals(List.of(), drafts.list(foreign, null, null, 20).get("items")); assertEquals("NOT_OBSERVED", drafts.reconcile(foreign, key).get("status"));
        var newSession = auth.find(auth.issue(owner, true, null).token()); assertEquals(record(created), drafts.get(newSession, id));
        code("MUTATION_SESSION_CHANGED", () -> drafts.create(newSession, key, request)); code("MUTATION_SESSION_CHANGED", () -> drafts.reconcile(newSession, key));
        db.update("UPDATE sessions SET revoked_at=clock_timestamp() WHERE token_hash=?", session.hash());
        code("SESSION_REVOKED", () -> drafts.get(session, id)); code("SESSION_REVOKED", () -> drafts.reconcile(session, key)); code("SESSION_REVOKED", () -> drafts.create(session, key, request));
    }
    @Test void expiredAndStaleEpochSessionsCannotReadOrWrite() {
        UUID id = draftId(createDraft()); db.update("UPDATE sessions SET last_seen_at=clock_timestamp()-interval '3 hours' WHERE token_hash=?", session.hash());
        code("SESSION_REVOKED", () -> drafts.get(session, id)); code("SESSION_REVOKED", this::createDraft); code("SESSION_REVOKED", () -> drafts.list(session, null, null, 20));
        db.update("UPDATE sessions SET last_seen_at=clock_timestamp(),created_at=clock_timestamp()-interval '13 hours' WHERE token_hash=?", session.hash()); code("SESSION_REVOKED", () -> drafts.get(session, id));
        db.update("UPDATE sessions SET created_at=clock_timestamp() WHERE token_hash=?", session.hash()); db.update("UPDATE owners SET security_epoch=security_epoch+1 WHERE id=?", owner); code("SESSION_REVOKED", () -> drafts.get(session, id));
    }
    @Test void pausedBlocksNewWritesButAllowsReadsReceiptsAndExactReplays() {
        UUID key = UUID.randomUUID(); var request = body(editable()); var created = drafts.create(session, key, request); UUID id = draftId(created);
        db.update("UPDATE owners SET execution_paused=true WHERE id=?", owner);
        code("EXECUTION_PAUSED", this::createDraft); code("EXECUTION_PAUSED", () -> drafts.patch(session, id, UUID.randomUUID(), body(Map.of("expected_version", 1, "body", "x"))));
        assertEquals(created, drafts.create(session, key, request)); assertEquals(record(created), drafts.get(session, id)); assertEquals(created.get("mutation"), drafts.reconcile(session, key)); assertEquals(1, ((List<?>) drafts.list(session, null, null, 20).get("items")).size());
        var changed = editable(); changed.put("subject", "changed"); code("IDEMPOTENCY_CONFLICT", () -> drafts.create(session, key, body(changed)));
        assertEquals(1, count("mail_draft_mutations"));
    }
    @Test void noRecentReauthenticationIsNeeded() {
        db.update("UPDATE sessions SET reauthenticated_at=clock_timestamp()-interval '1 hour' WHERE token_hash=?", session.hash());
        session = auth.find(token); assertDoesNotThrow(this::createDraft);
    }
    @Test void projectReferencesRequireActiveOwnerAndListsRetainInactiveHistory() {
        UUID project = project(true); var value = editable(); value.put("project_id", project.toString()); UUID id = draftId(drafts.create(session, UUID.randomUUID(), body(value)));
        assertEquals(1, ((List<?>) drafts.list(session, project, null, 20).get("items")).size());
        db.update("UPDATE projects SET active=false WHERE id=?", project);
        assertEquals(1, ((List<?>) drafts.list(session, project, null, 20).get("items")).size()); assertNotNull(drafts.get(session, id));
        code("INVALID_REQUEST", () -> drafts.create(session, UUID.randomUUID(), body(value))); code("INVALID_REQUEST", () -> drafts.patch(session, id, UUID.randomUUID(), body(Map.of("expected_version", 1, "subject", "x"))));
        var clear = new LinkedHashMap<String,Object>(); clear.put("expected_version", 1); clear.put("project_id", null); assertNull(record(drafts.patch(session, id, UUID.randomUUID(), body(clear))).get("project_id"));
        var foreign = foreign(); code("NOT_FOUND", () -> drafts.list(foreign, project, null, 20)); value.put("project_id", UUID.randomUUID().toString()); code("NOT_FOUND", () -> drafts.create(session, UUID.randomUUID(), body(value)));
    }
    @SuppressWarnings("unchecked")
    @Test void paginationIsStableAndOwnerFilterAndSignatureBound() {
        UUID project = project(true); for (int i = 0; i < 7; i++) { var value = editable(); value.put("project_id", project.toString()); drafts.create(session, UUID.randomUUID(), body(value)); }
        db.update("UPDATE mail_drafts SET created_at='2026-01-01T00:00:00Z'");
        Set<Object> ids = new HashSet<>(); String cursor = null, first = null;
        do { var page = drafts.list(session, project, cursor, 2); for (var item : (List<Map<String,Object>>) page.get("items")) assertTrue(ids.add(item.get("id"))); cursor = (String) page.get("next_cursor"); if (first == null) first = cursor; } while (cursor != null);
        assertEquals(7, ids.size()); String valid = first; var foreign = foreign();
        code("INVALID_REQUEST", () -> drafts.list(foreign, project, valid, 2)); code("INVALID_REQUEST", () -> drafts.list(session, null, valid, 2)); code("INVALID_REQUEST", () -> drafts.list(session, project, "x" + valid, 2));
        for (int limit : new int[]{0, -1, 101}) code("INVALID_REQUEST", () -> drafts.list(session, null, null, limit));
    }
    @Test void strictValidationAndMergedRecipientLimit() {
        for (String field : MailDraftService.FIELDS) { var value = editable(); value.remove(field); code("INVALID_REQUEST", () -> drafts.create(session, UUID.randomUUID(), body(value))); }
        for (String field : List.of("owner_id", "send", "delivery_status", "version", "expected_version")) { var value = editable(); value.put(field, "unexpected"); code("INVALID_REQUEST", () -> drafts.create(session, UUID.randomUUID(), body(value))); }
        for (String invalid : List.of("", "\u00a0\ufeff", "x\u0085y", "x\ny", "x".repeat(321), "\ud800")) { var value = editable(); value.put("sender_candidate", invalid); code("INVALID_REQUEST", () -> drafts.create(session, UUID.randomUUID(), body(value))); }
        var value = editable(); value.put("to", Collections.nCopies(30, "synthetic@example.invalid")); UUID id = draftId(drafts.create(session, UUID.randomUUID(), body(value)));
        code("INVALID_REQUEST", () -> drafts.patch(session, id, UUID.randomUUID(), body(Map.of("expected_version", 1, "cc", List.of("another@example.invalid")))));
        code("INVALID_REQUEST", () -> drafts.patch(session, id, UUID.randomUUID(), body(Map.of("expected_version", 1))));
        assertEquals(1L, drafts.get(session, id).get("version"));
    }
    @Test void bodyAndCanonicalByteLimitsAndEmptyContent() {
        for (var entry : Map.of("subject", "😀".repeat(201), "body", "x".repeat(16001), "sender_candidate", "x\u0000y").entrySet()) { var value = editable(); value.put(entry.getKey(), entry.getValue()); code("INVALID_REQUEST", () -> drafts.create(session, UUID.randomUUID(), body(value))); }
        var multiline = editable(); multiline.put("body", "\t\r\n"); assertDoesNotThrow(() -> drafts.create(session, UUID.randomUUID(), body(multiline)));
        var huge = editable(); huge.put("body", "😀".repeat(16000)); huge.put("subject", "😀".repeat(200)); huge.put("to", Collections.nCopies(30, "😀".repeat(320))); code("INVALID_REQUEST", () -> drafts.create(session, UUID.randomUUID(), body(huge)));
        var near = editable(); near.put("body", "😀".repeat(16000)); UUID id = draftId(drafts.create(session, UUID.randomUUID(), body(near)));
        code("INVALID_REQUEST", () -> drafts.patch(session, id, UUID.randomUUID(), body(Map.of("expected_version", 1, "to", Collections.nCopies(30, "😀".repeat(320))))));
    }
    @Test void versionExhaustionAndAuditFailureRollBackEverything() {
        UUID id = draftId(createDraft()); db.update("UPDATE mail_drafts SET version=? WHERE id=?", MailDraftService.MAX, id);
        code("VERSION_EXHAUSTED", () -> drafts.patch(session, id, UUID.randomUUID(), body(Map.of("expected_version", MailDraftService.MAX, "subject", "x"))));
        db.execute("ALTER TABLE audit_events ADD CONSTRAINT mail_test_failure CHECK(event_type NOT IN ('MAIL_DRAFT_CREATED','MAIL_DRAFT_UPDATED')) NOT VALID");
        try {
            assertThrows(Exception.class, this::createDraft);
            db.update("UPDATE mail_drafts SET version=1 WHERE id=?", id); var before = drafts.get(session, id);
            assertThrows(Exception.class, () -> drafts.patch(session, id, UUID.randomUUID(), body(Map.of("expected_version", 1, "subject", "rollback sentinel"))));
            assertEquals(before, drafts.get(session, id)); assertEquals(1, count("mail_drafts")); assertEquals(1, count("mail_draft_mutations"));
        }
        finally { db.execute("ALTER TABLE audit_events DROP CONSTRAINT mail_test_failure"); }
    }
    @Test void ciphertextCannotBeSwappedAcrossDrafts() {
        UUID first = draftId(createDraft()), second = draftId(createDraft());
        db.update("UPDATE mail_drafts SET content_ref=(SELECT content_ref FROM mail_drafts WHERE id=?) WHERE id=?", first, second);
        assertThrows(IllegalStateException.class, () -> drafts.get(session, second)); assertDoesNotThrow(() -> drafts.list(session, null, null, 20));
    }
    @Test void missingReceiptHasExactlyThreeNullMetadataFields() {
        var result = drafts.reconcile(session, UUID.randomUUID()); assertEquals(Set.of("status", "draft_id", "version", "operation"), result.keySet()); assertEquals("NOT_OBSERVED", result.get("status"));
        for (String field : List.of("draft_id", "version", "operation")) assertNull(result.get(field));
    }

    @Test void actualPostgresHttpCreatePatchReceiptAndMissingCsrf() throws Exception {
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new MailDraftController(drafts))
                .setControllerAdvice(new ApiErrors()).addFilters(new WebSecurity(auth, "https://localhost:8443", false)).build();
        var cookie = new jakarta.servlet.http.Cookie(WebSecurity.COOKIE, token); String csrf = auth.refreshCsrf(session); UUID key = UUID.randomUUID();
        var denied = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/mail-drafts")
                .cookie(cookie).header("Origin", "https://localhost:8443").header("Idempotency-Key", key.toString())
                .contentType("application/json").content(Json.canonical(editable()))).andReturn().getResponse();
        assertEquals(403, denied.getStatus()); assertEquals(0, count("mail_drafts"));
        var created = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/mail-drafts")
                .cookie(cookie).header("Origin", "https://localhost:8443").header("X-CSRF-Token", csrf).header("Idempotency-Key", key.toString())
                .contentType("application/json").content(Json.canonical(editable()))).andReturn().getResponse();
        assertEquals(200, created.getStatus()); assertEquals("no-store", created.getHeader("Cache-Control"));
        UUID id = UUID.fromString(Json.parse(created.getContentAsByteArray()).path("data").path("draft").path("id").asText());
        var updated = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/mail-drafts/" + id)
                .cookie(cookie).header("Origin", "https://localhost:8443").header("X-CSRF-Token", csrf).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json").content("{\"expected_version\":1,\"body\":\"synthetic private HTTP sentinel\"}")).andReturn().getResponse();
        assertEquals(200, updated.getStatus()); assertEquals(2L, drafts.get(session, id).get("version"));
        var receipt = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/mail-draft-mutations/" + key).cookie(cookie)).andReturn().getResponse();
        assertEquals(200, receipt.getStatus()); assertFalse(receipt.getContentAsString().contains("synthetic private HTTP sentinel"));
        assertEquals(1, count("mail_drafts")); assertEquals(2, count("mail_draft_mutations"));
    }

    @Test void frozenCommitmentAndCursorVectorMatches() throws Exception {
        var vector = Json.parse(Files.readAllBytes(Path.of("../../packages/contracts/fixtures/mail-drafts/commitment-vector.json")));
        Path file = Files.createTempFile("mail-vector-", ".key"); Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); Files.write(file, HexFormat.of().parseHex(vector.path("synthetic_key_hex").asText()));
        MailDraftKeyCodec codec = new MailDraftKeyCodec(file.toString());
        try {
            UUID owner = UUID.fromString(vector.path("owner_id").asText());
            assertEquals(vector.path("request_digest").asText(), codec.request(owner, vector.path("method").asText(), vector.path("target").asText(), vector.get("body")));
            var cursor = vector.path("cursor"); UUID id = UUID.fromString(cursor.path("id").asText()); Instant time = Instant.parse(cursor.path("created_at").asText());
            assertEquals(cursor.path("token").asText(), codec.encode(owner, null, Map.of("id", id, "created_at", Timestamp.from(time)))); assertEquals(new MailDraftKeyCodec.Boundary(time, id), codec.decode(owner, null, cursor.path("token").asText()));
        } finally { codec.close(); Files.deleteIfExists(file); }
    }

    @Test void authenticatedCursorStillRequiresStrictTimestampAndPayloadShape() throws Exception {
        var codec = context.getBean(MailDraftKeyCodec.class);
        var mac = javax.crypto.Mac.getInstance("HmacSHA256"); mac.init(new javax.crypto.spec.SecretKeySpec(Files.readAllBytes(key), "HmacSHA256"));
        byte[] derived = mac.doFinal("jarvis:mail-drafts:cursor:v1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try {
            for (String time : List.of("2030-01-01T00:00:00.1Z", "2030-01-01T00:00:00.000000Z", "2030-01-01T00:00:00+00:00", "0000-01-01T00:00:00Z", "2030-02-30T00:00:00Z", "2030-01-01T00:00:60Z", "2030-01-01T00:00:00.1234567Z")) {
                Map<String,Object> payload = new LinkedHashMap<>(); payload.put("owner_id", owner.toString()); payload.put("project_id", null); payload.put("id", owner.toString()); payload.put("created_at", time);
                byte[] bytes = Json.canonical(payload).getBytes(java.nio.charset.StandardCharsets.UTF_8); mac.init(new javax.crypto.spec.SecretKeySpec(derived, "HmacSHA256"));
                String cursor = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(bytes));
                if (time.equals("2030-01-01T00:00:00.1Z") || time.equals("2030-01-01T00:00:00.000000Z")) assertDoesNotThrow(() -> codec.decode(owner, null, cursor));
                else code("INVALID_REQUEST", () -> codec.decode(owner, null, cursor));
            }
        } finally { Arrays.fill(derived, (byte) 0); }
    }

    @Test void sharedCreateAndPatchFixturesMatchRuntimeShapeValidation() throws Exception {
        var cases = Json.parse(Files.readAllBytes(Path.of("../../packages/contracts/fixtures/mail-drafts/cases.json")));
        for (var item : cases) {
            String schema = item.path("schema").asText(); if (!schema.equals("mail-draft-create-request") && !schema.equals("mail-draft-patch-request")) continue;
            boolean patch = schema.equals("mail-draft-patch-request"); var request = item.get("instance");
            if (item.path("valid").asBoolean()) assertDoesNotThrow(() -> MailDraftService.validate(request, patch), item.path("name").asText());
            else assertThrows(ApiException.class, () -> MailDraftService.validate(request, patch), item.path("name").asText());
        }
    }
}

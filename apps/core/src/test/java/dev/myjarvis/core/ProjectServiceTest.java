package dev.myjarvis.core;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL, synthetic project metadata, no model or external provider. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProjectServiceTest {
    EmbeddedPostgres postgres; ConfigurableApplicationContext context; JdbcTemplate db;
    AuthService auth; ProjectService projects; WorkflowService flow; Path key;
    AuthService.Session session; UUID owner;
    @BeforeAll void start() throws Exception {
        postgres = EmbeddedPostgres.builder().setPort(0).start();
        key = Files.createTempFile("jarvis-project-test-", ".bin"); byte[] bytes = new byte[32]; Vault.RNG.nextBytes(bytes); Files.write(key, bytes);
        Files.setPosixFilePermissions(key, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        context = new SpringApplicationBuilder(CoreApplication.class).web(WebApplicationType.NONE).run("--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"), "--spring.datasource.username=postgres", "--spring.datasource.password=", "--jarvis.key-file=" + key, "--jarvis.mode=mock", "--jarvis.worker.enabled=false", "--spring.main.banner-mode=off");
        db = context.getBean(JdbcTemplate.class); auth = context.getBean(AuthService.class); projects = context.getBean(ProjectService.class); flow = context.getBean(WorkflowService.class);
    }
    @AfterAll void stop() throws Exception { if (context != null) context.close(); if (postgres != null) postgres.close(); if (key != null) Files.deleteIfExists(key); }
    @BeforeEach void reset() {
        db.execute("TRUNCATE owners,bootstrap_tokens,login_limits,crypto_nonces CASCADE"); owner = UUID.randomUUID();
        db.update("INSERT INTO owners(id,login_name,password_hash) VALUES (?,'project-owner','synthetic-unused')", owner);
        session = auth.find(auth.issue(owner, true, null).token());
    }
    JsonNode body(String name, String description) { return Json.MAPPER.valueToTree(Map.of("name", name, "description", description)); }
    UUID id(Map<String,Object> project) { return UUID.fromString(project.get("id").toString()); }
    Map<String,Object> create() { return projects.create(session, UUID.randomUUID(), body("Synthetic project", "private-synthetic-project-description")); }
    long count(String table) { return db.queryForObject("SELECT count(*) FROM " + table, Long.class); }
    @Test void createReadReceiptAndEncryption() {
        UUID key = UUID.randomUUID(); var request = body("Synthetic project", "private-synthetic-project-description");
        var project = projects.create(session, key, request);
        assertEquals(project, projects.get(session, id(project))); assertEquals(1L, project.get("version")); assertEquals(true, project.get("active"));
        assertEquals(project, projects.reconcile(session, key).get("project")); assertEquals("COMMITTED", projects.reconcile(session, key).get("status"));
        var missing = projects.reconcile(session, UUID.randomUUID()); assertEquals("NOT_OBSERVED", missing.get("status")); assertNull(missing.get("project"));
        String encrypted = db.queryForObject("SELECT description_ref FROM projects WHERE id=?", String.class, id(project));
        assertFalse(encrypted.contains("private-synthetic")); assertEquals(1, count("project_creations")); assertEquals(1, count("audit_events"));
        assertEquals(0, count("runs")); assertEquals(0, count("jobs")); assertEquals(0, count("task_user_intents"));
    }
    @Test void replayConflictAndDuplicateDisplayNames() {
        UUID key = UUID.randomUUID(); var request = body("Duplicate label", ""); var first = projects.create(session, key, request);
        assertEquals(first, projects.create(session, key, request));
        assertEquals("IDEMPOTENCY_CONFLICT", assertThrows(ApiException.class, () -> projects.create(session, key, body("Duplicate label", "changed"))).code);
        assertNotEquals(first.get("id"), projects.create(session, UUID.randomUUID(), request).get("id")); assertEquals(2, count("projects"));
    }
    @Test void concurrentSameKeyCreatesExactlyOne() throws Exception {
        UUID key = UUID.randomUUID(); JsonNode request = body("Concurrent", "synthetic");
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Callable<Map<String,Object>>> tasks = new ArrayList<>();
            for (int i = 0; i < 8; i++) tasks.add(() -> projects.create(session, key, request));
            Object first = null;
            for (var future : pool.invokeAll(tasks, 30, TimeUnit.SECONDS)) { assertFalse(future.isCancelled()); var result = future.get(); if (first == null) first = result; else assertEquals(first, result); }
        }
        assertEquals(1, count("projects")); assertEquals(1, count("project_creations")); assertEquals(1, count("audit_events"));
    }
    @Test void pausedAndRevokedSessionsCannotCreateButReadsAndExistingReplayWork() {
        UUID key = UUID.randomUUID(); var request = body("Paused", ""); var project = projects.create(session, key, request);
        db.update("UPDATE owners SET execution_paused=true WHERE id=?", owner);
        assertEquals("EXECUTION_PAUSED", assertThrows(ApiException.class, this::create).code);
        assertEquals(project, projects.get(session, id(project))); assertEquals(project, projects.create(session, key, request));
        db.update("UPDATE sessions SET revoked_at=now() WHERE token_hash=?", session.hash());
        assertEquals("SESSION_REVOKED", assertThrows(ApiException.class, () -> projects.create(session, key, request)).code);
        assertEquals(1, count("projects"));
    }
    @Test void staleEpochAndExpiredSessionFailBeforeMutation() {
        db.update("UPDATE owners SET security_epoch=security_epoch+1 WHERE id=?", owner);
        assertEquals(401, assertThrows(ApiException.class, this::create).status); assertEquals(0, count("projects"));
        db.update("UPDATE owners SET security_epoch=1 WHERE id=?", owner);
        db.update("UPDATE sessions SET last_seen_at=now()-interval '3 hours' WHERE token_hash=?", session.hash());
        assertEquals(401, assertThrows(ApiException.class, this::create).status);
    }
    @Test void ownerFiltersApplyBeforeDecryptionAndReceipts() {
        UUID key = UUID.randomUUID(); var project = projects.create(session, key, body("Owned", "private"));
        var foreign = new AuthService.Session("synthetic-foreign", UUID.randomUUID(), Instant.now(), 1);
        assertEquals(404, assertThrows(ApiException.class, () -> projects.get(foreign, id(project))).status);
        assertEquals(List.of(), projects.list(foreign, null, 20).get("items")); assertEquals("NOT_OBSERVED", projects.reconcile(foreign, key).get("status"));
        assertEquals(404, assertThrows(ApiException.class, () -> flow.createConversation(owner, Json.MAPPER.valueToTree(Map.of("title", "Synthetic", "project_id", UUID.randomUUID().toString())))).status);
        var conversation = flow.createConversation(owner, Json.MAPPER.valueToTree(Map.of("title", "Synthetic", "project_id", project.get("id").toString())));
        assertEquals(project.get("id"), conversation.get("project_id"));
    }
    @SuppressWarnings("unchecked")
    @Test void stableKeysetPagingHandlesEqualTimestampsAndScopedTamper() {
        for (int i = 0; i < 27; i++) create();
        db.update("UPDATE projects SET created_at='2026-01-01T00:00:00Z'");
        Set<Object> ids = new HashSet<>(); String cursor = null, firstCursor = null;
        do {
            var page = projects.list(session, cursor, 5);
            for (var item : (List<Map<String,Object>>) page.get("items")) assertTrue(ids.add(item.get("id")));
            cursor = (String) page.get("next_cursor"); if (firstCursor == null) firstCursor = cursor;
        } while (cursor != null);
        assertEquals(27, ids.size()); final String valid = firstCursor;
        var foreign = new AuthService.Session("foreign", UUID.randomUUID(), Instant.now(), 1);
        assertEquals(400, assertThrows(ApiException.class, () -> projects.list(foreign, valid, 5)).status);
        assertEquals(400, assertThrows(ApiException.class, () -> projects.list(session, "x" + valid, 5)).status);
        for (int limit : new int[]{0, -1, 101}) assertThrows(ApiException.class, () -> projects.list(session, null, limit));
    }
    @Test void legacyProjectsReadWithEmptyDescriptionAndMalformedInputsFail() {
        UUID legacy = UUID.randomUUID(); db.update("INSERT INTO projects(id,owner_id,name) VALUES (?,?,?)", legacy, owner, "Legacy synthetic");
        assertEquals("", projects.get(session, legacy).get("description"));
        for (String name : List.of("", " ", "\t", "x\u0000y", "x\ny", "x".repeat(201))) assertThrows(ApiException.class, () -> projects.create(session, UUID.randomUUID(), body(name, "")));
        assertThrows(ApiException.class, () -> projects.create(session, UUID.randomUUID(), body("valid", "x".repeat(4001))));
        assertThrows(ApiException.class, () -> projects.create(session, UUID.randomUUID(), Json.MAPPER.valueToTree(Map.of("name", "valid"))));
        assertThrows(ApiException.class, () -> projects.create(session, UUID.randomUUID(), Json.MAPPER.valueToTree(Map.of("name", "valid", "description", "", "owner_id", owner))));
        assertThrows(ApiException.class, () -> ProjectService.uuid("1-1-1-1-1"));
        assertThrows(ApiException.class, () -> ProjectService.uuid(owner.toString().toUpperCase(Locale.ROOT)));
        assertEquals(1, count("projects"));
    }
    @Test void auditFailureRollsBackProjectAndReceipt() {
        db.execute("ALTER TABLE audit_events ADD CONSTRAINT project_test_failure CHECK(event_type<>'PROJECT_CREATED')");
        try { assertThrows(Exception.class, this::create); assertEquals(0, count("projects")); assertEquals(0, count("project_creations")); }
        finally { db.execute("ALTER TABLE audit_events DROP CONSTRAINT project_test_failure"); }
    }
    @Test void sharedCreateFixturesMatchRuntimeValidation() throws Exception {
        var cases = Json.parse(Files.readAllBytes(Path.of("../../packages/contracts/fixtures/projects/cases.json")));
        for (var item : cases) {
            if (!item.path("schema").asText().equals("project-create-request")) continue;
            UUID key = UUID.randomUUID(); var input = item.get("instance");
            if (item.path("valid").asBoolean()) assertDoesNotThrow(() -> projects.create(session, key, input), item.path("name").asText());
            else assertThrows(ApiException.class, () -> projects.create(session, key, input), item.path("name").asText());
        }
    }
    @SuppressWarnings("unchecked")
    @Test void descriptionHeavyPagesStayWithinClientBudgetWithoutDroppingRecords() {
        for (int i = 0; i < 40; i++) projects.create(session, UUID.randomUUID(), body("Synthetic " + i, "😀".repeat(4000)));
        var first = projects.list(session, null, 100);
        assertTrue(((List<?>) first.get("items")).size() < 40); assertNotNull(first.get("next_cursor"));
        assertTrue(Json.MAPPER.writeValueAsString(first).getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 1024 * 1024);
        var second = projects.list(session, (String) first.get("next_cursor"), 100);
        Set<Object> ids = new HashSet<>();
        for (var page : List.of(first, second)) for (var row : (List<Map<String,Object>>) page.get("items")) assertTrue(ids.add(row.get("id")));
        assertEquals(40, ids.size()); assertNull(second.get("next_cursor"));
    }

    @Test void expiredSignedCursorRequiresReadOnlyRefresh() throws Exception {
        var project = create();
        String data = String.join("|", "1", owner.toString(), project.get("created_at").toString(), project.get("id").toString(), "1");
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(Files.readAllBytes(key), "HmacSHA256"));
        byte[] derived = mac.doFinal("jarvis:projects:cursor:v1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mac.init(new javax.crypto.spec.SecretKeySpec(derived, "HmacSHA256"));
        String cursor = encoded + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        long nonces = count("crypto_nonces");
        assertEquals("PROJECT_CURSOR_EXPIRED", assertThrows(ApiException.class, () -> projects.list(session, cursor, 20)).code);
        projects.list(session, null, 20); projects.get(session, id(project)); projects.reconcile(session, UUID.randomUUID());
        assertEquals(nonces, count("crypto_nonces")); assertEquals(1, count("projects")); assertEquals(1, count("audit_events"));
    }

}

package dev.myjarvis.core;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConversationHistoryTest {
    private EmbeddedPostgres postgres;
    private ConfigurableApplicationContext context;
    private Path key;
    private JdbcTemplate db;
    private WorkflowService flow;
    private ConversationHistoryService history;
    private ConversationReplyWriter replies;
    private UUID owner;

    @BeforeAll void start() throws Exception {
        postgres = EmbeddedPostgres.builder().setPort(0).start();
        key = Files.createTempFile("jarvis-history-test-", ".key");
        byte[] material = new byte[32]; Vault.RNG.nextBytes(material); Files.write(key, material);
        Files.setPosixFilePermissions(key, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        context = new SpringApplicationBuilder(CoreApplication.class).web(WebApplicationType.NONE).run(
                "--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"), "--spring.datasource.username=postgres", "--spring.datasource.password=",
                "--jarvis.key-file=" + key, "--jarvis.mode=mock", "--jarvis.worker.enabled=false", "--jarvis.local.enabled=false", "--spring.main.banner-mode=off");
        db = context.getBean(JdbcTemplate.class); flow = context.getBean(WorkflowService.class);
        history = context.getBean(ConversationHistoryService.class); replies = context.getBean(ConversationReplyWriter.class);
        int version = Integer.parseInt(db.queryForObject("SHOW server_version_num", String.class));
        assertTrue(version >= 170000 && version < 180000);
    }
    @AfterAll void stop() throws Exception {
        try { if (context != null) context.close(); }
        finally { try { if (postgres != null) postgres.close(); } finally { if (key != null) Files.deleteIfExists(key); } }
    }
    @BeforeEach void reset() {
        db.execute("TRUNCATE owners,bootstrap_tokens,login_limits,crypto_nonces CASCADE");
        owner = UUID.randomUUID();
        db.update("INSERT INTO owners(id,login_name,password_hash) VALUES (?,'history-test','unusable-test-hash')", owner);
    }
    private UUID conversation() { return (UUID) flow.createConversation(owner, Json.MAPPER.valueToTree(Map.of("title", "Synthetic history"))).get("id"); }
    private UUID message(UUID conversation, String text) {
        return (UUID) flow.accept(owner, conversation, Json.MAPPER.valueToTree(Map.of("client_message_id", UUID.randomUUID().toString(), "text", text, "attachment_ids", List.of()))).get("run_id");
    }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> items(Map<String,Object> page) { return (List<Map<String,Object>>) page.get("items"); }
    private long count(String table) { return db.queryForObject("SELECT count(*) FROM " + table, Long.class); }
    private ApiException failure(Runnable action, int status, String code) {
        var error = assertThrows(ApiException.class, action::run); assertEquals(status, error.status); assertEquals(code, error.code); return error;
    }

    @Test void latestPageAndStableOlderPagesCoverMoreThanTwoHundredMessages() {
        UUID conversation = conversation(); UUID newest = null;
        for (int i = 0; i < 251; i++) newest = message(conversation, "Synthetic message " + i);
        var first = history.messages(owner, conversation, null, 20);
        assertEquals(newest, ((Map<?,?>) first.get("current_run")).get("id"));
        assertEquals("Synthetic message 250", items(first).getLast().get("text"));
        Set<Object> seen = new HashSet<>(); String cursor = null;
        do {
            var page = history.messages(owner, conversation, cursor, 20);
            for (var item : items(page)) assertTrue(seen.add(item.get("id")));
            cursor = (String) page.get("next_cursor");
        } while (cursor != null);
        assertEquals(251, seen.size());
    }
    @Test void equalTimestampsRemainStableAndNewMessagesDoNotDisplaceOlderCursor() {
        UUID conversation = conversation();
        for (int i = 0; i < 45; i++) message(conversation, "Equal timestamp " + i);
        db.update("UPDATE messages SET created_at=?", Timestamp.from(Instant.parse("2030-01-01T00:00:00Z")));
        var first = history.messages(owner, conversation, null, 20);
        Set<Object> seen = new HashSet<>(); items(first).forEach(m -> seen.add(m.get("id")));
        UUID newRun = message(conversation, "New arrival");
        db.update("UPDATE messages SET created_at=? WHERE run_id=?", Timestamp.from(Instant.parse("2030-01-02T00:00:00Z")), newRun);
        String cursor = (String) first.get("next_cursor");
        do { var page = history.messages(owner, conversation, cursor, 20); for (var m : items(page)) assertTrue(seen.add(m.get("id"))); cursor = (String) page.get("next_cursor"); } while (cursor != null);
        assertEquals(45, seen.size());
    }
    @Test void readsNeverMutateAndForeignScopesStayHidden() {
        UUID conversation = conversation(), other = conversation();
        message(conversation, "Private synthetic source"); message(conversation, "Second");
        long messages = count("messages"), jobs = count("jobs"), nonces = count("crypto_nonces");
        String cursor = (String) history.messages(owner, conversation, null, 1).get("next_cursor");
        failure(() -> history.messages(owner, other, cursor, 1), 400, "INVALID_CURSOR");
        failure(() -> history.messages(UUID.randomUUID(), conversation, null, 20), 404, "NOT_FOUND");
        failure(() -> history.messages(owner, conversation, "x" + cursor, 1), 400, "INVALID_CURSOR");
        failure(() -> history.conversations(owner, cursor, 1), 400, "INVALID_CURSOR");
        failure(() -> history.messages(owner, conversation, null, 101), 400, "INVALID_REQUEST");
        history.messages(owner, conversation, null, 20); history.conversations(owner, null, 20); history.events(owner, conversation, null);
        assertEquals(messages, count("messages")); assertEquals(jobs, count("jobs")); assertEquals(nonces, count("crypto_nonces"));
    }
    @Test void durableReplyIsEncryptedIdempotentAndLatestRunIgnoresLateReply() {
        UUID conversation = conversation(), oldRun = message(conversation, "First"), newRun = message(conversation, "Second");
        db.update("UPDATE runs SET created_at=? WHERE id=?", Timestamp.from(Instant.parse("2030-01-01T00:00:00Z")), newRun);
        flow.tx.executeWithoutResult(s -> { flow.setRun(owner, oldRun, "SUCCEEDED", "Synthetic accepted answer"); replies.appendAccepted(owner, oldRun); replies.appendAccepted(owner, oldRun); });
        var page = history.messages(owner, conversation, null, 20);
        assertEquals(newRun, ((Map<?,?>) page.get("current_run")).get("id"));
        assertEquals(1, items(page).stream().filter(m -> m.get("role").equals("ASSISTANT")).count());
        String encrypted = db.queryForObject("SELECT content_ref FROM messages WHERE role='ASSISTANT'", String.class);
        assertFalse(encrypted.contains("Synthetic accepted answer"));
        assertNull(db.queryForObject("SELECT client_message_id FROM messages WHERE role='ASSISTANT'", UUID.class));
        assertEquals("MODEL_PROPOSAL", db.queryForObject("SELECT trust FROM messages WHERE role='ASSISTANT'", String.class));
        assertThrows(IllegalStateException.class, () -> replies.appendAccepted(owner, oldRun));
    }
    @Test void unknownCancellationAndRollbackNeverInventAReply() {
        UUID conversation = conversation(), run = message(conversation, "Synthetic cancellation");
        flow.tx.executeWithoutResult(s -> { flow.setRun(owner, run, "UNKNOWN", "Not confirmed"); replies.appendAccepted(owner, run); });
        assertEquals(1, count("messages"));
        flow.tx.executeWithoutResult(s -> { flow.setRun(owner, run, "CANCELLED", null); replies.appendAccepted(owner, run); });
        assertEquals(1, count("messages"));
        assertThrows(IllegalStateException.class, () -> flow.tx.executeWithoutResult(s -> { flow.setRun(owner, run, "SUCCEEDED", "Rolled back"); replies.appendAccepted(owner, run); throw new IllegalStateException("synthetic rollback"); }));
        assertEquals(1, count("messages")); assertEquals("CANCELLED", flow.run(owner, run).get("status"));
    }
    @Test void needInputIsAnAssistantQuestionAndNoJobsAreAdded() {
        UUID run = message(conversation(), "Needs clarification"); long jobs = count("jobs");
        flow.tx.executeWithoutResult(s -> { flow.setRun(owner, run, "WAITING_INPUT", "Synthetic question?"); replies.appendAccepted(owner, run); });
        assertEquals(1, db.queryForObject("SELECT count(*) FROM messages WHERE role='ASSISTANT'", Long.class));
        assertEquals(jobs, count("jobs"));
    }
    @Test void replayIsBoundedAndUnavailableCursorRequiresCanonicalResync() {
        UUID conversation = conversation(), other = conversation(), run = message(conversation, "Stream seed");
        for (int i = 0; i < 110; i++) flow.tx.executeWithoutResult(s -> flow.event(owner, run, "SYNTHETIC_STATUS"));
        String first = history.events(owner, conversation, null);
        assertEquals(100, first.lines().filter(line -> line.startsWith("id: ")).count());
        String last = first.lines().filter(line -> line.startsWith("id: ")).reduce((a,b) -> b).orElseThrow().substring(4);
        String tail = history.events(owner, conversation, last);
        assertEquals(11, tail.lines().filter(line -> line.startsWith("id: ")).count()); assertTrue(tail.contains("created_at"));
        failure(() -> history.events(owner, other, last), 410, "EVENT_CURSOR_UNAVAILABLE");
        failure(() -> history.events(UUID.randomUUID(), conversation, last), 404, "NOT_FOUND");
        db.update("DELETE FROM stream_events WHERE event_id=?", UUID.fromString(last));
        failure(() -> history.events(owner, conversation, last), 410, "EVENT_CURSOR_UNAVAILABLE");
        var page = history.messages(owner, conversation, null, 20);
        String watermark = ((Map<?,?>) page.get("stream_cursor")).get("event_id").toString();
        assertEquals("retry: 2000\n\n", history.events(owner, conversation, watermark));
    }
    @Test void cursorExpiryAuthenticationAndRestartAreDeterministic() throws Exception {
        UUID conversation = conversation(); message(conversation, "First"); message(conversation, "Second");
        String cursor = (String) history.messages(owner, conversation, null, 1).get("next_cursor");
        var restarted = new ConversationHistoryService(db, flow.vault, context.getBean(org.springframework.transaction.PlatformTransactionManager.class), key.toString());
        try { assertEquals(1, items(restarted.messages(owner, conversation, cursor, 1)).size()); }
        finally { restarted.close(); }
        String[] token = cursor.split("\\.");
        String[] fields = new String(Base64.getUrlDecoder().decode(token[0]), java.nio.charset.StandardCharsets.UTF_8).split("\\|", -1);
        fields[6] = "1";
        String body = Base64.getUrlEncoder().withoutPadding().encodeToString(String.join("|", fields).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(Files.readAllBytes(key), "HmacSHA256"));
        byte[] signing = mac.doFinal("jarvis:conversation-history:cursor:v1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mac.init(new javax.crypto.spec.SecretKeySpec(signing, "HmacSHA256"));
        String expired = body + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        failure(() -> history.messages(owner, conversation, expired, 1), 410, "HISTORY_CURSOR_EXPIRED");
        failure(() -> history.messages(owner, conversation, cursor + "x", 1), 400, "INVALID_CURSOR");
    }

    @Test void emptyConversationsAndPausedExecutionRemainReadable() {
        UUID conversation = conversation(); db.update("UPDATE owners SET execution_paused=true WHERE id=?", owner);
        var page = history.messages(owner, conversation, null, 20);
        assertTrue(items(page).isEmpty()); assertNull(page.get("current_run")); assertNull(page.get("stream_cursor")); assertNull(page.get("next_cursor"));
        assertEquals(1, items(history.conversations(owner, null, 20)).size());
    }
    @Test void messageByteBudgetPreservesAllPages() {
        UUID conversation = conversation();
        for (int i = 0; i < 50; i++) message(conversation, "가".repeat(7900));
        var page = history.messages(owner, conversation, null, 100);
        assertTrue(items(page).size() < 50); assertNotNull(page.get("next_cursor"));
        assertTrue(Json.MAPPER.writeValueAsString(page).getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 1024 * 1024);
        Set<Object> seen = new HashSet<>(); String cursor = null;
        do { page = history.messages(owner, conversation, cursor, 100); for (var item : items(page)) assertTrue(seen.add(item.get("id"))); cursor = (String) page.get("next_cursor"); } while (cursor != null);
        assertEquals(50, seen.size());
    }
}

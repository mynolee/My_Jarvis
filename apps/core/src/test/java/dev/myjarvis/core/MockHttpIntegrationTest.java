package dev.myjarvis.core;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyStore;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Real HTTPS -> servlet filters/controllers -> PostgreSQL -> deterministic mock worker.
 * No HTTP interception, model, Python provider, external effect, or browser certification.
 * All credentials and TLS material are ephemeral and outside the source tree.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MockHttpIntegrationTest {
    private static final String ORIGIN = "https://localhost:8443";
    private EmbeddedPostgres postgres;
    private ConfigurableApplicationContext context;
    private JdbcTemplate db;
    private MockWorker worker;
    private Path directory;
    private HttpClient client;
    private String base, cookie, csrf, password;

    @BeforeAll void start() throws Exception {
        directory = Files.createTempDirectory("jarvis-http-test-");
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        Path key = directory.resolve("encryption.bin"), store = directory.resolve("tls.p12");
        byte[] bytes = new byte[32]; Vault.RNG.nextBytes(bytes);
        Files.write(key, bytes); Files.setPosixFilePermissions(key, PosixFilePermissions.fromString("rw-------"));
        String storePassword = Vault.token();
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-validity", "1",
                "-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", storePassword,
                "-keypass", storePassword, "-noprompt")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try { assertTrue(process.waitFor(30, TimeUnit.SECONDS)); assertEquals(0, process.exitValue()); }
        finally { if (process.isAlive()) { process.destroyForcibly(); process.waitFor(); } }
        Files.setPosixFilePermissions(store, PosixFilePermissions.fromString("rw-------"));
        KeyStore trust = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(store)) { trust.load(in, storePassword.toCharArray()); }
        var managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); managers.init(trust);
        SSLContext tls = SSLContext.getInstance("TLSv1.3"); tls.init(null, managers.getTrustManagers(), null);
        client = HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        postgres = EmbeddedPostgres.builder().setPort(0).start();
        context = new SpringApplicationBuilder(CoreApplication.class).web(WebApplicationType.SERVLET).run(
                "--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"),
                "--spring.datasource.username=postgres", "--spring.datasource.password=",
                "--jarvis.key-file=" + key, "--jarvis.mode=mock", "--jarvis.worker.enabled=false",
                "--jarvis.local.enabled=false", "--jarvis.web.enabled=false", "--jarvis.origin=" + ORIGIN,
                "--server.address=127.0.0.1", "--server.port=0", "--server.ssl.enabled=true",
                "--server.ssl.key-store=" + store.toUri(), "--server.ssl.key-store-type=PKCS12",
                "--server.ssl.key-store-password=" + storePassword, "--server.ssl.enabled-protocols=TLSv1.3",
                "--spring.main.banner-mode=off");
        base = "https://localhost:" + context.getEnvironment().getRequiredProperty("local.server.port");
        db = context.getBean(JdbcTemplate.class); worker = context.getBean(MockWorker.class);
        int version = Integer.parseInt(db.queryForObject("SHOW server_version_num", String.class));
        assertTrue(version >= 170000 && version < 180000, "Requires actual PostgreSQL 17");
    }

    @AfterAll void stop() throws Exception {
        try { if (context != null) context.close(); }
        finally {
            try { if (postgres != null) postgres.close(); }
            finally {
                if (client != null) client.close();
                if (directory != null) try (var files = Files.walk(directory)) {
                    for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
                }
            }
        }
    }

    @BeforeEach void reset() {
        db.execute("TRUNCATE owners,bootstrap_tokens,login_limits,crypto_nonces CASCADE");
        cookie = null; csrf = null; password = Vault.token();
    }

    private HttpResponse<String> request(String method, String path, String body,
                                         String session, String token, String origin) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10));
        if (session != null) builder.header("Cookie", session);
        if (token != null) builder.header("X-CSRF-Token", token);
        if (origin != null) builder.header("Origin", origin);
        if (body != null) builder.header("Content-Type", "application/json");
        return client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> get(String path) throws Exception { return request("GET", path, null, cookie, null, null); }
    private HttpResponse<String> post(String path, Object body) throws Exception {
        return request("POST", path, Json.canonical(body), cookie, csrf, ORIGIN);
    }
    private JsonNode data(HttpResponse<String> response, int status) {
        assertEquals(status, response.statusCode());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        JsonNode envelope = Json.parse(response.body().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertDoesNotThrow(() -> UUID.fromString(envelope.get("request_id").asText()));
        return envelope.get("data");
    }
    private void error(HttpResponse<String> response, int status, String code) {
        data(response, status);
        var error = Json.parse(response.body().getBytes(java.nio.charset.StandardCharsets.UTF_8)).get("error");
        assertEquals(code, error.get("code").asText()); assertFalse(error.get("retryable").asBoolean());
    }
    private void session(HttpResponse<String> response) {
        var value = data(response, 200);
        String header = response.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(header.startsWith("__Host-jarvis="));
        for (String attribute : List.of("Path=/", "Secure", "HttpOnly", "SameSite=Lax")) assertTrue(header.contains(attribute));
        assertFalse(header.toLowerCase(Locale.ROOT).contains("domain="));
        cookie = header.split(";", 2)[0]; csrf = value.get("csrf_token").asText();
    }
    private void owner() throws Exception {
        if (cookie == null) session(get("/api/v1/auth/csrf"));
        else csrf = data(get("/api/v1/auth/csrf"), 200).get("csrf_token").asText();
        String token = Vault.token();
        db.update("INSERT INTO bootstrap_tokens(token_hash,expires_at) VALUES (?,now()+interval '15 minutes')", Json.hash(token));
        session(post("/api/v1/auth/bootstrap", Map.of("bootstrap_token", token, "login_name", "synthetic-owner", "password", password)));
    }
    private String conversation() throws Exception { return data(post("/api/v1/conversations", Map.of("title", "Synthetic integration")), 200).get("id").asText(); }
    private Map<String,Object> message(String clientId, String text) { return Map.of("client_message_id", clientId, "text", text, "attachment_ids", List.of()); }
    private String accept(String conversation, String text) throws Exception {
        return data(post("/api/v1/conversations/" + conversation + "/messages", message(UUID.randomUUID().toString(), text)), 202).get("run_id").asText();
    }
    private JsonNode run(String id) throws Exception { return data(get("/api/v1/runs/" + id), 200); }
    private JsonNode approval(String run) throws Exception { return data(get("/api/v1/approvals/" + run(run).get("approval_id").asText()), 200); }
    private String decisionPath(JsonNode approval) { return "/api/v1/approvals/" + approval.get("id").asText() + "/decision"; }
    private Map<String,Object> approve(JsonNode a) { return Map.of("decision", "APPROVE", "expected_action_version", a.get("action_version").asLong(), "payload_hash", a.get("payload_hash").asText()); }
    private long count(String table) { return db.queryForObject("SELECT count(*) FROM " + table, Long.class); }

    @Test void authenticationCsrfOriginAndSessionRevocation() throws Exception {
        error(get("/api/v1/me"), 401, "AUTHENTICATION_REQUIRED");
        session(get("/api/v1/auth/csrf"));
        String anonymous = cookie, anonymousCsrf = csrf;
        error(post("/api/v1/conversations", Map.of("title", "Synthetic")), 401, "AUTHENTICATION_REQUIRED");
        owner();
        error(request("POST", "/api/v1/conversations", "{\"title\":\"Synthetic\"}", cookie, null, ORIGIN), 403, "CSRF_REJECTED");
        error(request("POST", "/api/v1/conversations", "{}", cookie, csrf, "https://untrusted.invalid"), 403, "ORIGIN_REJECTED");
        error(request("POST", "/api/v1/conversations", "{}", cookie, csrf, null), 403, "ORIGIN_REJECTED");
        error(request("POST", "/api/v1/conversations", "{}", cookie, anonymousCsrf, ORIGIN), 403, "CSRF_REJECTED");
        error(request("GET", "/api/v1/me", null, anonymous, null, null), 401, "AUTHENTICATION_REQUIRED");
        String oldCookie = cookie, oldCsrf = csrf;
        session(post("/api/v1/auth/reauthenticate", Map.of("password", password)));
        assertNotEquals(oldCookie, cookie); assertNotEquals(oldCsrf, csrf);
        error(request("GET", "/api/v1/me", null, oldCookie, null, null), 401, "AUTHENTICATION_REQUIRED");
        data(get("/api/v1/me"), 200);
        data(post("/api/v1/auth/logout", Map.of()), 200);
        error(get("/api/v1/me"), 401, "AUTHENTICATION_REQUIRED");
        assertEquals(0, count("conversations"));
    }

    @Test void strictBindingDeduplicationAndMockCompletion() throws Exception {
        owner(); String c = conversation(), other = conversation(), id = UUID.randomUUID().toString();
        String path = "/api/v1/conversations/" + c + "/messages";
        Map<String,Object> body = message(id, "Synthetic question");
        var injected = new LinkedHashMap<>(body); injected.put("owner_id", UUID.randomUUID().toString());
        assertEquals(400, post(path, injected).statusCode());
        assertEquals(400, request("POST", path, "{\"text\":\"one\",\"text\":\"two\"}", cookie, csrf, ORIGIN).statusCode());
        assertEquals(0, count("runs"));
        String r = data(post(path, body), 202).get("run_id").asText();
        assertEquals(r, data(post(path, body), 202).get("run_id").asText());
        error(post(path, message(id, "Changed")), 409, "IDEMPOTENCY_CONFLICT");
        error(post("/api/v1/conversations/" + other + "/messages", body), 409, "IDEMPOTENCY_CONFLICT");
        assertEquals(1, count("messages")); assertEquals(1, count("jobs"));
        assertTrue(worker.runOne()); assertFalse(worker.runOne());
        assertEquals("SUCCEEDED", run(r).get("status").asText());
        assertEquals("MOCK", run(r).get("execution_mode").asText());
        assertTrue(run(r).get("result").asText().contains("No model or external provider was called"));
        assertEquals(0, count("mock_effects"));
    }

    @Test void approvalPayloadVersionAndSingleConsumption() throws Exception {
        owner(); String r = accept(conversation(), "/calendar"); assertTrue(worker.runOne());
        assertEquals("WAITING_APPROVAL", run(r).get("status").asText());
        JsonNode a = approval(r); assertEquals(0, count("mock_effects"));
        var injected = new LinkedHashMap<>(approve(a)); injected.put("approved", true);
        assertEquals(400, post(decisionPath(a), injected).statusCode());
        var wrongHash = new LinkedHashMap<>(approve(a)); wrongHash.put("payload_hash", "0".repeat(64));
        error(post(decisionPath(a), wrongHash), 409, "PAYLOAD_CHANGED");
        var wrongVersion = new LinkedHashMap<>(approve(a)); wrongVersion.put("expected_action_version", a.get("action_version").asLong() + 1);
        error(post(decisionPath(a), wrongVersion), 409, "PAYLOAD_CHANGED");
        assertEquals(0, count("authorizations"));
        data(post(decisionPath(a), approve(a)), 200);
        error(post(decisionPath(a), approve(a)), 409, "APPROVAL_NOT_PENDING");
        assertTrue(worker.runOne()); assertFalse(worker.runOne());
        assertEquals("SUCCEEDED", run(r).get("status").asText());
        assertEquals(1, count("authorizations")); assertEquals(1, count("action_executions")); assertEquals(1, count("mock_effects"));
    }

    @Test void cancellationRevokesPendingApprovalAndQueuedEffect() throws Exception {
        owner(); String r = accept(conversation(), "/calendar"); assertTrue(worker.runOne());
        JsonNode a = approval(r);
        error(post("/api/v1/runs/" + r + "/cancel", Map.of("expected_version", run(r).get("version").asLong() + 1)), 409, "PRECONDITION_CHANGED");
        assertEquals("WAITING_APPROVAL", run(r).get("status").asText());
        data(post("/api/v1/runs/" + r + "/cancel", Map.of("expected_version", run(r).get("version").asLong())), 202);
        assertEquals("CANCELLED", run(r).get("status").asText());
        assertEquals("REVOKED", approval(r).get("status").asText());
        error(post(decisionPath(a), approve(a)), 409, "APPROVAL_NOT_PENDING");
        String queued = accept(conversation(), "/calendar"); assertTrue(worker.runOne());
        JsonNode next = approval(queued); data(post(decisionPath(next), approve(next)), 200);
        data(post("/api/v1/runs/" + queued + "/cancel", Map.of("expected_version", run(queued).get("version").asLong())), 202);
        assertFalse(worker.runOne()); assertEquals("CANCELLED", run(queued).get("status").asText());
        assertEquals(0, count("mock_effects")); assertEquals(0, count("action_executions"));
    }

    @Test void unknownDoesNotRetryAndReconciliationOnlyReadsExistingEffect() throws Exception {
        owner(); String r = accept(conversation(), "/calendar-timeout"); assertTrue(worker.runOne());
        JsonNode a = approval(r); data(post(decisionPath(a), approve(a)), 200); assertTrue(worker.runOne());
        assertEquals("UNKNOWN", run(r).get("status").asText());
        worker.recover(); assertFalse(worker.runOne());
        assertEquals("UNKNOWN", run(r).get("status").asText());
        data(post("/api/v1/runs/" + r + "/cancel", Map.of("expected_version", run(r).get("version").asLong())), 202);
        assertEquals("UNKNOWN", run(r).get("status").asText(), "Cancellation cannot undo an uncertain started effect");
        error(post(decisionPath(a), approve(a)), 409, "APPROVAL_NOT_PENDING");
        assertEquals(1, count("mock_effects")); assertEquals(1, count("action_executions"));
        assertEquals("SUCCEEDED", data(post("/api/v1/runs/" + r + "/reconcile", Map.of()), 200).get("status").asText());
        assertEquals(1, count("mock_effects")); assertEquals(1, count("action_executions")); assertFalse(worker.runOne());
    }

    @Test void eventCursorBoundToConversationAndUnknownRunDenied() throws Exception {
        owner(); String c = conversation(), other = conversation(); String r = accept(c, "Synthetic");
        HttpResponse<String> events = get("/api/v1/conversations/" + c + "/events");
        assertEquals(200, events.statusCode()); assertTrue(events.body().contains("run.status"));
        assertTrue(events.body().contains(r));
        String cursor = events.body().lines().filter(line -> line.startsWith("id: ")).findFirst().orElseThrow().substring(4);
        var response = client.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/conversations/" + other + "/events"))
                .timeout(Duration.ofSeconds(10)).header("Cookie", cookie).header("Last-Event-ID", cursor).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(404, response.statusCode());
        assertEquals(404, get("/api/v1/runs/" + UUID.randomUUID()).statusCode());
        assertEquals("DISABLED", data(get("/api/v1/local-model/status"), 200).get("status").asText());
    }
}

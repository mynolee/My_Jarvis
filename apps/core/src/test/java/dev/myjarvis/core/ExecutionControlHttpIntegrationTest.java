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
class ExecutionControlHttpIntegrationTest {
    private static final String ORIGIN = "https://localhost:8443";
    private EmbeddedPostgres postgres;
    private ConfigurableApplicationContext context;
    private JdbcTemplate db;
    private MockWorker worker;
    private Path directory;
    private HttpClient client;
    private String base, cookie, csrf, password;

    @BeforeAll void start() throws Exception {
        directory = Files.createTempDirectory("jarvis-execution-http-test-");
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
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "SYNTHETIC_TLS_KEYTOOL_WAIT_EXPIRED");
            assertEquals(0, process.exitValue(), "SYNTHETIC_TLS_KEYTOOL_NONZERO_EXIT");
        }
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
                "--jarvis.reminders.worker-enabled=false", "--jarvis.local.enabled=false", "--jarvis.web.enabled=false", "--jarvis.origin=" + ORIGIN,
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

    private JsonNode control(boolean paused,long version) throws Exception {return data(post("/api/v1/execution/"+(paused?"pause":"resume"),Map.of("expected_version",version)),200);}
    private long epoch(){return db.queryForObject("SELECT security_epoch FROM owners",Long.class);}
    private long controlEvents(){return db.queryForObject("SELECT count(*) FROM audit_events WHERE event_type IN ('EXECUTION_PAUSED','EXECUTION_RESUMED')",Long.class);}

    @Test void authoritativeStatusIsPrivateDatabaseCheckedAndRequiresAuthentication() throws Exception {
        error(get("/api/v1/execution"),401,"AUTHENTICATION_REQUIRED");owner();
        java.time.Instant before=AuthService.instant(db.queryForObject("SELECT clock_timestamp()",Object.class));JsonNode state=data(get("/api/v1/execution"),200);
        java.time.Instant after=AuthService.instant(db.queryForObject("SELECT clock_timestamp()",Object.class));java.time.Instant checked=java.time.Instant.parse(state.path("checked_at").asText());
        assertEquals(Set.of("execution_paused","version","checked_at"),Set.copyOf(state.propertyNames()));assertFalse(state.path("execution_paused").asBoolean());assertEquals(1,state.path("version").asLong());
        assertFalse(checked.isBefore(before));assertFalse(checked.isAfter(after));assertEquals(0,checked.getNano()%1000);
        assertFalse(state.toString().contains("synthetic-owner"));assertFalse(state.toString().contains(cookie));assertFalse(state.toString().contains(csrf));assertEquals(0,controlEvents());
    }

    @Test void freshCasTransitionResponseLossRetryAndSameStateNeverDoubleIncrement() throws Exception {
        owner();String originalCookie=cookie,originalCsrf=csrf;JsonNode paused=control(true,1);assertTrue(paused.path("execution_paused").asBoolean());assertEquals(2,paused.path("version").asLong());assertEquals(2,epoch());
        error(post("/api/v1/execution/pause",Map.of("expected_version",1)),409,"PRECONDITION_CHANGED");
        JsonNode same=control(true,2);assertEquals(2,same.path("version").asLong());assertEquals(2,epoch());assertEquals(1,controlEvents());
        assertEquals(originalCookie,cookie);assertEquals(originalCsrf,csrf);JsonNode resumed=control(false,2);assertFalse(resumed.path("execution_paused").asBoolean());assertEquals(3,resumed.path("version").asLong());assertEquals(3,epoch());assertEquals(2,controlEvents());
        error(post("/api/v1/execution/resume",Map.of("expected_version",2)),409,"PRECONDITION_CHANGED");assertFalse(data(get("/api/v1/execution"),200).path("execution_paused").asBoolean());
    }

    @Test void originCsrfRecentReauthAndStrictBodiesCannotBeBypassedByEmergencyControls() throws Exception {
        owner();for(String action:List.of("pause","resume")){
            String path="/api/v1/execution/"+action;
            error(request("POST",path,"{\"expected_version\":1}",cookie,csrf,"https://untrusted.invalid"),403,"ORIGIN_REJECTED");
            error(request("POST",path,"{\"expected_version\":1}",cookie,null,ORIGIN),403,"CSRF_REJECTED");
            for(String body:List.of("{}","{\"expected_version\":1,\"owner_id\":\"spoof\"}","{\"expected_version\":1,\"expected_version\":1}","{\"expected_version\":1.0}","{\"expected_version\":0}","{\"expected_version\":null}","{\"expected_version\":9007199254740992}","{\"expected_version\":true}","{\"expected_version\":\"1\"}"))error(request("POST",path,body,cookie,csrf,ORIGIN),400,"INVALID_REQUEST");
            error(post(path+"?owner_id=spoof",Map.of("expected_version",1)),400,"INVALID_REQUEST");
        }
        db.update("UPDATE sessions SET reauthenticated_at=clock_timestamp()-interval '6 minutes' WHERE owner_id IS NOT NULL");data(get("/api/v1/execution"),200);
        error(post("/api/v1/execution/pause",Map.of("expected_version",1)),403,"REAUTHENTICATION_REQUIRED");assertEquals(1,epoch());assertEquals(0,controlEvents());
        session(post("/api/v1/auth/reauthenticate",Map.of("password",password)));control(true,1);assertEquals(2,epoch());
    }

    @Test void statusRejectsOwnerSpoofQueryAndAnyBody() throws Exception {
        owner();error(get("/api/v1/execution?owner_id=spoof"),400,"INVALID_REQUEST");error(request("GET","/api/v1/execution","{}",cookie,null,null),400,"INVALID_REQUEST");assertEquals(0,controlEvents());
    }

    @Test void initiatingCookieSurvivesButOtherSessionCannotReadOrResume() throws Exception {
        owner();UUID id=db.queryForObject("SELECT id FROM owners",UUID.class);AuthService.Issued other=context.getBean(AuthService.class).issue(id,true,null);
        String otherCookie=WebSecurity.COOKIE+"="+other.token();control(true,1);
        error(request("GET","/api/v1/execution",null,otherCookie,null,null),401,"AUTHENTICATION_REQUIRED");
        error(request("POST","/api/v1/execution/resume","{\"expected_version\":2}",otherCookie,other.csrf(),ORIGIN),403,"CSRF_REJECTED");
        assertTrue(data(get("/api/v1/execution"),200).path("execution_paused").asBoolean());control(false,2);assertEquals(3,epoch());
    }

    @Test void pausePreventsQueuedWorkAndResumeOnlyPermitsNewWork() throws Exception {
        owner();String conversation=conversation();String approved=accept(conversation,"/calendar");assertTrue(worker.runOne());JsonNode approval=approval(approved);data(post(decisionPath(approval),approve(approval)),200);
        String queued=accept(conversation,"Synthetic queued message");control(true,1);assertFalse(worker.runOne());assertEquals(0,count("mock_effects"));assertEquals("CANCELLED",run(approved).path("status").asText());assertEquals("CANCELLED",run(queued).path("status").asText());
        error(post("/api/v1/conversations/"+conversation+"/messages",message(UUID.randomUUID().toString(),"Synthetic while paused")),409,"EXECUTION_PAUSED");
        control(false,2);assertFalse(worker.runOne());assertEquals(0,count("mock_effects"));String fresh=accept(conversation,"Synthetic new work");assertTrue(worker.runOne());assertEquals("SUCCEEDED",run(fresh).path("status").asText());
    }

    @Test void unknownRunDoesNotDisableAccountControlsOrBecomeRetriedAfterResume() throws Exception {
        owner();String run=accept(conversation(),"/calendar-timeout");assertTrue(worker.runOne());JsonNode approval=approval(run);data(post(decisionPath(approval),approve(approval)),200);assertTrue(worker.runOne());assertEquals("UNKNOWN",run(run).path("status").asText());
        control(true,1);control(false,2);assertEquals("UNKNOWN",run(run).path("status").asText());worker.recover();assertFalse(worker.runOne());assertEquals(1,count("mock_effects"));assertEquals(1,count("action_executions"));
    }

    @Test void sharedProfileVersionConflictsWithoutLeakingCurrentProfileOrSecurityEpoch() throws Exception {
        owner();db.update("UPDATE owners SET version=version+1");HttpResponse<String> response=post("/api/v1/execution/pause",Map.of("expected_version",1));error(response,409,"PRECONDITION_CHANGED");
        assertFalse(response.body().contains("security_epoch"));assertFalse(response.body().contains("synthetic-owner"));assertEquals(1,epoch());assertEquals(0,controlEvents());assertEquals(2,data(get("/api/v1/execution"),200).path("version").asLong());
    }
}

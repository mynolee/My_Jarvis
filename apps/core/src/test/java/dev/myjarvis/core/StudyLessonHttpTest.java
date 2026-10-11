package dev.myjarvis.core;

import jakarta.servlet.http.Cookie;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Actual controller + service + synthetic provider + disposable storage. No native model. */
class StudyLessonHttpTest extends ConversationDeletionFixtures {
    MockMvc http; LocalInferenceService local; Cookie cookie; UUID target; int calls;
    @BeforeEach void setupHttp() {
        target = conversation("HTTP study"); calls = 0;
        LocalInferenceClient client = new LocalInferenceClient(new MockEnvironment().withProperty("jarvis.local.manifest-hash", "a".repeat(64))) {
            @Override boolean ready() { return true; }
            @Override boolean configured() { return true; }
            @Override JsonNode infer(UUID owner, JsonNode request) { calls++; return StudyLessonContractTest.response(request); }
        };
        local = new LocalInferenceService(flow, client, context.getBean(InferenceContextService.class), context.getBean(InferenceContextFreshness.class));
        AuthService auth = mock(AuthService.class); cookie = new Cookie(WebSecurity.COOKIE, "synthetic-study-cookie");
        when(auth.find("synthetic-study-cookie")).thenReturn(session); when(auth.csrf(eq(session), eq("synthetic-csrf"))).thenReturn(true);
        http = MockMvcBuilders.standaloneSetup(new StudyLessonController(local)).setControllerAdvice(new ApiErrors())
                .addFilters(new WebSecurity(auth, "https://localhost:8443", false)).build();
    }
    @AfterEach void closeHttp() { local.close(); }
    String path() { return "/api/v1/conversations/" + target + "/study-lessons"; }
    String body(UUID key) { return Json.canonical(Map.of("client_message_id", key, "text", "Synthetic HTTP topic")); }
    MockHttpServletRequestBuilder postWith(UUID key, String body) {
        return post(path()).cookie(cookie).header("Origin", "https://localhost:8443").header("X-CSRF-Token", "synthetic-csrf")
                .header("Idempotency-Key", key).contentType("application/json").content(body);
    }
    @Test void authenticatedExplicitRouteSettlesAndReadsTheExactTypedLesson() throws Exception {
        UUID key = UUID.randomUUID(); var accepted = http.perform(postWith(key, body(key))).andReturn().getResponse();
        assertEquals(202, accepted.getStatus()); assertEquals("no-store", accepted.getHeader("Cache-Control"));
        UUID run = UUID.fromString(Json.parse(accepted.getContentAsByteArray()).path("data").path("run_id").asText());
        assertTrue(local.runOne()); assertEquals(1, calls);
        var response = http.perform(get("/api/v1/runs/" + run + "/study-lesson").cookie(cookie)).andReturn().getResponse();
        assertEquals(200, response.getStatus()); assertEquals("no-store", response.getHeader("Cache-Control"));
        var data = Json.parse(response.getContentAsByteArray()).path("data"); assertEquals("SUCCEEDED", data.path("status").asText());
        assertEquals(3, data.path("lesson").path("concepts").size()); assertEquals(2, data.path("lesson").path("questions").size());
        assertEquals(202, http.perform(postWith(key, body(key))).andReturn().getResponse().getStatus()); assertFalse(local.runOne()); assertEquals(1, calls);
    }
    @Test void missingAuthenticationOriginCsrfAndJsonRejectBeforeReservation() throws Exception {
        UUID key = UUID.randomUUID();
        assertEquals(401, http.perform(get("/api/v1/runs/" + UUID.randomUUID() + "/study-lesson")).andReturn().getResponse().getStatus());
        assertEquals(403, http.perform(post(path()).cookie(cookie).contentType("application/json").content(body(key))).andReturn().getResponse().getStatus());
        assertEquals(403, http.perform(post(path()).cookie(cookie).header("Origin", "https://localhost:8443").contentType("application/json").content(body(key))).andReturn().getResponse().getStatus());
        assertEquals(415, http.perform(postWith(key, body(key)).contentType("text/plain")).andReturn().getResponse().getStatus());
        assertEquals(0, count("inference_reservations")); assertEquals(0, calls);
    }
    @Test void clientAuthorityPurposeSourceAndReceiptFieldsCannotEnterTheRoute() throws Exception {
        for (String field : List.of("purpose", "verified", "owner_id", "context", "project_context", "lesson", "usage", "question_ids")) {
            UUID key = UUID.randomUUID(); var payload = new LinkedHashMap<String,Object>(Map.of("client_message_id", key, "text", "Synthetic")); payload.put(field, "untrusted");
            assertEquals(400, http.perform(postWith(key, Json.canonical(payload))).andReturn().getResponse().getStatus());
        }
        assertEquals(0, count("inference_reservations")); assertEquals(0, calls);
    }
    @Test void idempotencyHeaderMustBeSingleCanonicalAndEqualToBodyIdentity() throws Exception {
        UUID key = UUID.randomUUID();
        assertEquals(400, http.perform(postWith(key, body(UUID.randomUUID()))).andReturn().getResponse().getStatus());
        assertEquals(400, http.perform(postWith(key, body(key)).header("Idempotency-Key", UUID.randomUUID())).andReturn().getResponse().getStatus());
        assertEquals(400, http.perform(post(path()).cookie(cookie).header("Origin", "https://localhost:8443").header("X-CSRF-Token", "synthetic-csrf").contentType("application/json").content(body(key))).andReturn().getResponse().getStatus());
        assertEquals(0, count("inference_reservations"));
    }
    @Test void queriesBodiesDuplicateJsonAndNoncanonicalTargetsAreClosed() throws Exception {
        UUID key = UUID.randomUUID();
        assertEquals(400, http.perform(postWith(key, body(key)).queryParam("verified", "true")).andReturn().getResponse().getStatus());
        assertEquals(400, http.perform(postWith(key, "{\"client_message_id\":\"" + key + "\",\"text\":\"one\",\"text\":\"two\"}")).andReturn().getResponse().getStatus());
        String read = "/api/v1/runs/" + UUID.randomUUID() + "/study-lesson";
        assertEquals(400, http.perform(get(read).cookie(cookie).queryParam("owner_id", owner.toString())).andReturn().getResponse().getStatus());
        assertEquals(400, http.perform(get(read).cookie(cookie).content("{}")).andReturn().getResponse().getStatus());
        assertEquals(400, http.perform(get("/api/v1/runs/" + "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA" + "/study-lesson").cookie(cookie)).andReturn().getResponse().getStatus());
        assertEquals(0, count("inference_reservations"));
    }
    @Test void deletionAndRevokedSessionCannotExposeAFormerTypedResult() throws Exception {
        UUID key = UUID.randomUUID(); var response = http.perform(postWith(key, body(key))).andReturn().getResponse();
        UUID run = UUID.fromString(Json.parse(response.getContentAsByteArray()).path("data").path("run_id").asText()); local.runOne();
        commit(prepare(target), UUID.randomUUID());
        var deleted = http.perform(get("/api/v1/runs/" + run + "/study-lesson").cookie(cookie)).andReturn().getResponse();
        assertEquals(410, deleted.getStatus()); assertFalse(deleted.getContentAsString().contains("Synthetic first concept"));
        db.update("UPDATE sessions SET revoked_at=clock_timestamp() WHERE token_hash=?", session.hash());
        assertEquals(401, http.perform(get("/api/v1/runs/" + run + "/study-lesson").cookie(cookie)).andReturn().getResponse().getStatus());
    }
}

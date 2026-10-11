package dev.myjarvis.core;

import jakarta.servlet.http.Cookie;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Real controller/service/storage. The lesson provider is explicitly synthetic. */
class DurableStudyHttpTest extends DurableStudyFixtures {
    MockMvc http; Cookie cookie; UUID conversation, run;
    @BeforeEach void setupHttp() {
        conversation = conversation("durable HTTP"); run = lesson(conversation);
        AuthService auth = mock(AuthService.class); cookie = new Cookie(WebSecurity.COOKIE, "synthetic-durable-cookie");
        when(auth.find("synthetic-durable-cookie")).thenReturn(session); when(auth.csrf(eq(session), eq("synthetic-csrf"))).thenReturn(true);
        http = MockMvcBuilders.standaloneSetup(new StudySessionController(study)).setControllerAdvice(new ApiErrors())
                .addFilters(new WebSecurity(auth, "https://localhost:8443", false)).build();
    }
    String startPath() { return "/api/v1/conversations/" + conversation + "/study-sessions"; }
    String startBody() { return Json.canonical(Map.of("lesson_run_id", run, "expected_conversation_version", conversationVersion(conversation))); }
    MockHttpServletRequestBuilder postWith(String path, UUID key, String body) {
        return post(path).cookie(cookie).header("Origin", "https://localhost:8443").header("X-CSRF-Token", "synthetic-csrf")
                .header("Idempotency-Key", key).contentType("application/json").content(body);
    }
    @Test void realStartAnswerReadCloseAndReceiptRoutesUseNoInferenceOrMessageFallback() throws Exception {
        long messages = count("messages"), reservations = count("inference_reservations"), jobs = count("jobs");
        var start = http.perform(postWith(startPath(), UUID.randomUUID(), startBody())).andReturn().getResponse();
        assertEquals(200, start.getStatus()); assertEquals("no-store", start.getHeader("Cache-Control"));
        UUID id = UUID.fromString(Json.parse(start.getContentAsByteArray()).path("data").path("receipt").path("study_session_id").asText());
        UUID answerKey = UUID.randomUUID(); var answer = http.perform(postWith("/api/v1/study-sessions/" + id + "/answers", answerKey, Json.canonical(answerBody(id, 0, "Exact <script>text</script>")))).andReturn().getResponse();
        assertEquals(200, answer.getStatus()); assertFalse(answer.getContentAsString().contains("Exact <script>"));
        var read = http.perform(get("/api/v1/study-sessions/" + id).cookie(cookie)).andReturn().getResponse();
        assertEquals(200, read.getStatus()); assertEquals("Exact <script>text</script>", Json.parse(read.getContentAsByteArray()).path("data").path("attempts").get(0).path("text").asText());
        var receipt = http.perform(get("/api/v1/study-mutations/" + answerKey).cookie(cookie)).andReturn().getResponse(); assertEquals(200, receipt.getStatus());
        assertEquals(Json.parse(answer.getContentAsByteArray()).path("data").path("receipt"), Json.parse(receipt.getContentAsByteArray()).path("data").path("receipt"));
        assertEquals(200, http.perform(postWith("/api/v1/study-sessions/" + id + "/close", UUID.randomUUID(), Json.canonical(closeBody(id)))).andReturn().getResponse().getStatus());
        assertEquals(messages, count("messages")); assertEquals(reservations, count("inference_reservations")); assertEquals(jobs, count("jobs")); assertEquals(1, providerCalls);
    }
    @Test void authenticationOriginCsrfContentTypeAndIdempotencyHeadersAreMandatory() throws Exception {
        assertEquals(401, http.perform(get("/api/v1/conversations/" + conversation + "/study-index")).andReturn().getResponse().getStatus());
        assertEquals(403, http.perform(post(startPath()).cookie(cookie).contentType("application/json").content(startBody())).andReturn().getResponse().getStatus());
        assertEquals(403, http.perform(post(startPath()).cookie(cookie).header("Origin", "https://localhost:8443").contentType("application/json").content(startBody())).andReturn().getResponse().getStatus());
        assertEquals(415, http.perform(postWith(startPath(), UUID.randomUUID(), startBody()).contentType("text/plain")).andReturn().getResponse().getStatus());
        assertEquals(400, http.perform(postWith(startPath(), UUID.randomUUID(), startBody()).header("Idempotency-Key", UUID.randomUUID())).andReturn().getResponse().getStatus());
        assertEquals(400, http.perform(post(startPath()).cookie(cookie).header("Origin", "https://localhost:8443").header("X-CSRF-Token", "synthetic-csrf").contentType("application/json").content(startBody())).andReturn().getResponse().getStatus());
        assertEquals(0L, count("study_sessions"));
    }
    @Test void duplicateJsonFloatsExponentsUnsafeNumbersSurrogatesAndClientAuthorityFailClosed() throws Exception {
        for (String value : List.of("1.0", "1e0", "9007199254740992", "true")) {
            String body = "{\"lesson_run_id\":\"" + run + "\",\"expected_conversation_version\":" + value + "}";
            assertEquals(400, http.perform(postWith(startPath(), UUID.randomUUID(), body)).andReturn().getResponse().getStatus());
        }
        String duplicate = "{\"lesson_run_id\":\"" + run + "\",\"expected_conversation_version\":1,\"expected_conversation_version\":2}";
        assertEquals(400, http.perform(postWith(startPath(), UUID.randomUUID(), duplicate)).andReturn().getResponse().getStatus());
        for (String field : List.of("lesson", "owner_id", "session_hash", "security_epoch", "purpose", "source", "verified", "feedback")) {
            var body = new LinkedHashMap<String,Object>(Map.of("lesson_run_id", run, "expected_conversation_version", conversationVersion(conversation))); body.put(field, "untrusted");
            assertEquals(400, http.perform(postWith(startPath(), UUID.randomUUID(), Json.canonical(body))).andReturn().getResponse().getStatus());
        }
        assertEquals(0L, count("study_sessions"));
    }
    @Test void allReadRoutesRejectBodiesQueriesAndNoncanonicalIdentifiers() throws Exception {
        UUID id = start(conversation, run);
        for (String path : List.of("/api/v1/study-sessions/" + id, "/api/v1/study-mutations/" + UUID.randomUUID(), "/api/v1/conversations/" + conversation + "/study-index")) {
            assertEquals(400, http.perform(get(path).cookie(cookie).content("{}")).andReturn().getResponse().getStatus());
            assertEquals(400, http.perform(get(path).cookie(cookie).queryParam("owner_id", owner.toString())).andReturn().getResponse().getStatus());
        }
        assertEquals(400, http.perform(get("/api/v1/study-sessions/AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA").cookie(cookie)).andReturn().getResponse().getStatus());
        assertEquals(400, http.perform(get("/api/v1/conversations/" + conversation + "/study-index").cookie(cookie).queryParam("limit", "1", "2")).andReturn().getResponse().getStatus());
        assertEquals(400, http.perform(get("/api/v1/conversations/" + conversation + "/study-index").cookie(cookie).queryParam("limit", "51")).andReturn().getResponse().getStatus());
    }
    @Test void answerTextIsUnicodeScalarBoundedAndErrorsNeverEchoContent() throws Exception {
        UUID id = start(conversation, run); String path = "/api/v1/study-sessions/" + id + "/answers";
        String body = Json.canonical(answerBody(id, 0, "Synthetic private sentinel"));
        for (String text : List.of("\\ud800", "\\u0000", " ", "x".repeat(8001))) {
            String changed = body.replace("Synthetic private sentinel", text); var response = http.perform(postWith(path, UUID.randomUUID(), changed)).andReturn().getResponse();
            assertEquals(400, response.getStatus()); assertFalse(response.getContentAsString().contains("Synthetic private sentinel"));
        }
        assertEquals(200, http.perform(postWith(path, UUID.randomUUID(), Json.canonical(answerBody(id, 0, "😀".repeat(8000))))).andReturn().getResponse().getStatus());
        assertEquals(1L, count("study_owner_answer_inputs"));
    }
    @Test void deletedSourceRoutesReturnGoneButOriginalLiveReceiptLookupStaysMetadataOnly() throws Exception {
        UUID id = start(conversation, run), key = UUID.randomUUID(); String body = Json.canonical(answerBody(id, 0, "Private deletion sentinel"));
        assertEquals(200, http.perform(postWith("/api/v1/study-sessions/" + id + "/answers", key, body)).andReturn().getResponse().getStatus());
        commit(prepare(conversation), UUID.randomUUID());
        for (String path : List.of("/api/v1/study-sessions/" + id, "/api/v1/conversations/" + conversation + "/study-index")) {
            var response = http.perform(get(path).cookie(cookie)).andReturn().getResponse(); assertEquals(410, response.getStatus()); assertFalse(response.getContentAsString().contains("Private deletion sentinel"));
        }
        assertEquals(410, http.perform(postWith("/api/v1/study-sessions/" + id + "/answers", key, body)).andReturn().getResponse().getStatus());
        var result = http.perform(get("/api/v1/study-mutations/" + key).cookie(cookie)).andReturn().getResponse(); assertEquals(200, result.getStatus()); assertFalse(result.getContentAsString().contains("Private deletion sentinel"));
        db.update("UPDATE sessions SET revoked_at=clock_timestamp() WHERE token_hash=?", session.hash());
        assertEquals(401, http.perform(get("/api/v1/study-mutations/" + key).cookie(cookie)).andReturn().getResponse().getStatus());
    }
}

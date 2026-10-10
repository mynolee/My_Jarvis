package dev.myjarvis.core;

import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** HTTP boundary tests use the actual filter/controller and a service mock. */
class MailDraftHttpTest {
    MockMvc http; MailDraftService drafts; AuthService auth; AuthService.Session session;
    Cookie cookie; UUID owner, id, key;
    @BeforeEach void setup() {
        owner = UUID.randomUUID(); id = UUID.randomUUID(); key = UUID.randomUUID(); session = new AuthService.Session("synthetic-mail-session", owner, Instant.now(), 1);
        cookie = new Cookie(WebSecurity.COOKIE, "synthetic-mail-token"); auth = mock(AuthService.class); drafts = mock(MailDraftService.class);
        when(auth.find("synthetic-mail-token")).thenReturn(session); when(auth.csrf(session, "synthetic-csrf")).thenReturn(true);
        http = MockMvcBuilders.standaloneSetup(new MailDraftController(drafts)).setControllerAdvice(new ApiErrors()).addFilters(new WebSecurity(auth, "https://localhost:8443", false)).build();
    }
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder write(boolean patch) {
        return (patch ? patch("/api/v1/mail-drafts/" + id) : post("/api/v1/mail-drafts")).cookie(cookie).header("Origin", "https://localhost:8443").header("X-CSRF-Token", "synthetic-csrf").header("Idempotency-Key", key.toString()).contentType("application/json");
    }
    @Test void createAndPatchUseOwnerSessionAndExactBodyWithNoStore() throws Exception {
        when(drafts.create(eq(session), eq(key), any())).thenReturn(Map.of("draft", Map.of("id", id), "mutation", Map.of("status", "COMMITTED")));
        var response = http.perform(write(false).content("{\"subject\":\"  exact  \"}")).andReturn().getResponse();
        assertEquals(200, response.getStatus()); assertEquals("no-store", response.getHeader("Cache-Control"));
        verify(drafts).create(eq(session), eq(key), argThat(b -> b.path("subject").asText().equals("  exact  ")));
        when(drafts.patch(eq(session), eq(id), eq(key), any())).thenReturn(Map.of("draft", Map.of("id", id), "mutation", Map.of("status", "COMMITTED")));
        assertEquals(200, http.perform(write(true).content("{\"expected_version\":1,\"subject\":\"\"}")).andReturn().getResponse().getStatus());
        verify(drafts).patch(eq(session), eq(id), eq(key), argThat(b -> b.path("expected_version").asInt() == 1 && b.path("subject").asText().isEmpty()));
    }
    @Test void authenticationOriginAndCsrfFailBeforeService() throws Exception {
        assertEquals(401, http.perform(get("/api/v1/mail-drafts")).andReturn().getResponse().getStatus());
        assertEquals(403, http.perform(post("/api/v1/mail-drafts").cookie(cookie).header("Origin", "https://attacker.invalid").header("X-CSRF-Token", "synthetic-csrf").contentType("application/json").content("{}")).andReturn().getResponse().getStatus());
        assertEquals(403, http.perform(post("/api/v1/mail-drafts").cookie(cookie).header("Origin", "https://localhost:8443").contentType("application/json").content("{}")).andReturn().getResponse().getStatus());
        assertEquals(403, http.perform(patch("/api/v1/mail-drafts/" + id).cookie(cookie).header("Origin", "https://localhost:8443").contentType("application/json").content("{}")).andReturn().getResponse().getStatus());
        verifyNoInteractions(drafts);
    }
    @Test void missingDuplicateAndNonCanonicalKeysFail() throws Exception {
        for (String value : List.of("", "1-1-1-1-1", "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA")) {
            var request = post("/api/v1/mail-drafts").cookie(cookie).header("Origin", "https://localhost:8443").header("X-CSRF-Token", "synthetic-csrf").contentType("application/json").content("{}");
            if (!value.isEmpty()) request.header("Idempotency-Key", value); assertEquals(400, http.perform(request).andReturn().getResponse().getStatus());
        }
        assertEquals(400, http.perform(write(false).header("Idempotency-Key", key.toString()).content("{}")).andReturn().getResponse().getStatus()); verifyNoInteractions(drafts);
    }
    @Test void strictQueryAndPathValidation() throws Exception {
        for (String query : List.of("limit=0", "limit=01", "limit=101", "limit=1.0", "limit=20&limit=30", "cursor=", "cursor=%2B", "owner_id=" + owner, "project_id=null", "project_id=" + id + "&project_id=" + id))
            assertEquals(400, http.perform(get("/api/v1/mail-drafts?" + query).cookie(cookie)).andReturn().getResponse().getStatus(), query);
        for (String path : List.of("/api/v1/mail-drafts/1-1-1-1-1", "/api/v1/mail-drafts/" + id + "?cursor=x", "/api/v1/mail-draft-mutations/1-1-1-1-1"))
            assertEquals(400, http.perform(get(path).cookie(cookie)).andReturn().getResponse().getStatus());
        assertEquals(400, http.perform(write(false).queryParam("send", "true").content("{}")).andReturn().getResponse().getStatus()); verifyNoInteractions(drafts);
    }
    @Test void strictJsonRejectsDuplicateKeysDecimalsAndMalformedUnicode() throws Exception {
        for (String json : List.of("{\"subject\":\"x\",\"subject\":\"y\"}", "{\"expected_version\":1.0}", "{\"subject\":\"\\ud800\"}", "{} {}", "{\"subject\":NaN}"))
            assertEquals(400, http.perform(write(false).content(json)).andReturn().getResponse().getStatus());
        assertEquals(415, http.perform(write(false).contentType("text/plain").content("{}")).andReturn().getResponse().getStatus()); verifyNoInteractions(drafts);
    }
    @Test void metadataReadsAndReceiptNeverMutate() throws Exception {
        Map<String,Object> page = new LinkedHashMap<>(); page.put("items", List.of()); page.put("next_cursor", null); when(drafts.list(session, null, null, 20)).thenReturn(page);
        var receipt = new LinkedHashMap<String,Object>(); receipt.put("status", "NOT_OBSERVED"); receipt.put("draft_id", null); receipt.put("version", null); receipt.put("operation", null); when(drafts.reconcile(session, key)).thenReturn(receipt);
        assertEquals(200, http.perform(get("/api/v1/mail-drafts").cookie(cookie)).andReturn().getResponse().getStatus());
        var response = http.perform(get("/api/v1/mail-draft-mutations/" + key).cookie(cookie)).andReturn().getResponse(); assertEquals(200, response.getStatus()); assertEquals("no-store", response.getHeader("Cache-Control"));
        assertEquals("NOT_OBSERVED", Json.parse(response.getContentAsByteArray()).path("data").path("status").asText()); verify(drafts).list(session, null, null, 20); verify(drafts).reconcile(session, key); verifyNoMoreInteractions(drafts);
    }
    @Test void explicitProjectAndMaximumLimitAreForwarded() throws Exception {
        when(drafts.list(session, id, "abc.def", 100)).thenReturn(Map.of("items", List.of()));
        assertEquals(200, http.perform(get("/api/v1/mail-drafts").cookie(cookie).queryParam("project_id", id.toString()).queryParam("cursor", "abc.def").queryParam("limit", "100")).andReturn().getResponse().getStatus()); verify(drafts).list(session, id, "abc.def", 100);
    }
    @Test void errorsAreRedactedAndNoSendRouteExists() throws Exception {
        when(drafts.get(session, id)).thenThrow(new ApiException(401, "SESSION_REVOKED"));
        var response = http.perform(get("/api/v1/mail-drafts/" + id).cookie(cookie)).andReturn().getResponse(); assertEquals(401, response.getStatus());
        var error = Json.parse(response.getContentAsByteArray()).path("error"); assertEquals("SESSION_REVOKED", error.path("code").asText()); assertTrue(error.path("details").isEmpty());
        assertEquals(404, http.perform(post("/api/v1/mail-drafts/" + id + "/send").cookie(cookie).header("Origin", "https://localhost:8443").header("X-CSRF-Token", "synthetic-csrf").contentType("application/json").content("{}")).andReturn().getResponse().getStatus());
        verify(drafts).get(session, id); verifyNoMoreInteractions(drafts);
    }
}

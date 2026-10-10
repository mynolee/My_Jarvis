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

class ProjectHttpTest {
    MockMvc http; ProjectService projects; AuthService auth; AuthService.Session session;
    Cookie cookie; UUID owner, id, key;
    @BeforeEach void setup() {
        owner = UUID.randomUUID(); id = UUID.randomUUID(); key = UUID.randomUUID();
        session = new AuthService.Session("synthetic-project-session-hash", owner, Instant.now(), 1);
        cookie = new Cookie(WebSecurity.COOKIE, "synthetic-project-token");
        auth = mock(AuthService.class); projects = mock(ProjectService.class);
        when(auth.find("synthetic-project-token")).thenReturn(session); when(auth.csrf(session, "synthetic-csrf")).thenReturn(true);
        http = MockMvcBuilders.standaloneSetup(new ProjectController(projects)).setControllerAdvice(new ApiErrors()).addFilters(new WebSecurity(auth, "https://localhost:8443", false)).build();
    }
    Map<String,Object> project() { return Map.of("id", id, "name", "Synthetic", "description", "", "active", true, "version", 1, "created_at", "2026-01-01T00:00:00Z"); }
    @Test void explicitCreateUsesServerSessionAndExactBody() throws Exception {
        when(projects.create(eq(session), eq(key), any())).thenReturn(project());
        var response = http.perform(post("/api/v1/projects").cookie(cookie).header("Origin", "https://localhost:8443").header("X-CSRF-Token", "synthetic-csrf").header("Idempotency-Key", key.toString()).contentType("application/json").content("{\"name\":\"Synthetic\",\"description\":\"\"}")).andReturn().getResponse();
        assertEquals(200, response.getStatus()); assertEquals("no-store", response.getHeader("Cache-Control"));
        assertEquals(id.toString(), Json.parse(response.getContentAsByteArray()).path("data").path("id").asText());
        verify(projects).create(eq(session), eq(key), argThat(b -> b.size() == 2 && b.path("name").asText().equals("Synthetic")));
    }
    @Test void unauthenticatedAndWrongOriginOrCsrfCannotCreate() throws Exception {
        assertEquals(401, http.perform(get("/api/v1/projects")).andReturn().getResponse().getStatus());
        assertEquals(403, http.perform(post("/api/v1/projects").cookie(cookie).contentType("application/json").content("{}")).andReturn().getResponse().getStatus());
        assertEquals(403, http.perform(post("/api/v1/projects").cookie(cookie).header("Origin", "https://localhost:8443").contentType("application/json").content("{}")).andReturn().getResponse().getStatus());
        verifyNoInteractions(projects);
    }
    @Test void missingDuplicateAndNonCanonicalCreateKeysAreRejected() throws Exception {
        for (String value : List.of("", "1-1-1-1-1", key.toString().toUpperCase(Locale.ROOT))) {
            var request = post("/api/v1/projects").cookie(cookie).header("Origin", "https://localhost:8443").header("X-CSRF-Token", "synthetic-csrf").contentType("application/json").content("{}");
            if (!value.isEmpty()) request.header("Idempotency-Key", value);
            assertEquals(400, http.perform(request).andReturn().getResponse().getStatus());
        }
        assertEquals(400, http.perform(post("/api/v1/projects").cookie(cookie).header("Origin", "https://localhost:8443").header("X-CSRF-Token", "synthetic-csrf").header("Idempotency-Key", key.toString(), key.toString()).contentType("application/json").content("{}")).andReturn().getResponse().getStatus());
        verifyNoInteractions(projects);
    }
    @Test void listAndGetAreReadOnlyAndStrict() throws Exception {
        var page = new LinkedHashMap<String,Object>(); page.put("items", List.of(project())); page.put("next_cursor", null);
        when(projects.list(session, null, 20)).thenReturn(page); when(projects.get(session, id)).thenReturn(project());
        assertEquals(200, http.perform(get("/api/v1/projects").cookie(cookie)).andReturn().getResponse().getStatus());
        assertEquals(200, http.perform(get("/api/v1/projects/" + id).cookie(cookie)).andReturn().getResponse().getStatus());
        for (String query : List.of("limit=0", "limit=01", "limit=x", "limit=20&limit=30", "cursor=", "owner_id=" + owner)) assertEquals(400, http.perform(get("/api/v1/projects?" + query).cookie(cookie)).andReturn().getResponse().getStatus(), query);
        for (String path : List.of("/api/v1/projects/1-1-1-1-1", "/api/v1/projects/" + id + "?owner_id=" + owner, "/api/v1/project-creations/1-1-1-1-1")) assertEquals(400, http.perform(get(path).cookie(cookie)).andReturn().getResponse().getStatus());
        verify(projects).list(session, null, 20); verify(projects).get(session, id); verifyNoMoreInteractions(projects);
    }
    @Test void uncertaintyReceiptNeverTriggersMutation() throws Exception {
        var missing = new LinkedHashMap<String,Object>(); missing.put("status", "NOT_OBSERVED"); missing.put("project", null);
        when(projects.reconcile(session, key)).thenReturn(missing);
        var response = http.perform(get("/api/v1/project-creations/" + key).cookie(cookie)).andReturn().getResponse();
        assertEquals(200, response.getStatus()); assertEquals("NOT_OBSERVED", Json.parse(response.getContentAsByteArray()).path("data").path("status").asText());
        verify(projects).reconcile(session, key); verifyNoMoreInteractions(projects);
    }
    @Test void errorsAreRedactedAndUnknownResourceIs404() throws Exception {
        when(projects.get(session, id)).thenThrow(ApiException.missing());
        var response = http.perform(get("/api/v1/projects/" + id).cookie(cookie)).andReturn().getResponse();
        assertEquals(404, response.getStatus()); assertEquals("NOT_FOUND", Json.parse(response.getContentAsByteArray()).path("error").path("code").asText());
        assertTrue(Json.parse(response.getContentAsByteArray()).path("error").path("details").isEmpty());
    }
}

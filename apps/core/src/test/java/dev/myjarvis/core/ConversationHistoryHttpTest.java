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

/** Servlet contract/filter tests; database behavior is covered separately by ConversationHistoryTest. */
class ConversationHistoryHttpTest {
    private MockMvc http;
    private ConversationHistoryService history;
    private UUID owner, conversation;
    private Cookie cookie;
    private AuthService.Session session;

    @BeforeEach void setup() {
        owner = UUID.randomUUID(); conversation = UUID.randomUUID(); cookie = new Cookie(WebSecurity.COOKIE, "synthetic-history-session");
        AuthService auth = mock(AuthService.class); history = mock(ConversationHistoryService.class);
        session = new AuthService.Session("synthetic-session-hash", owner, Instant.now(), 1);
        when(auth.find("synthetic-history-session")).thenReturn(session);
        http = MockMvcBuilders.standaloneSetup(new ConversationHistoryController(history)).setControllerAdvice(new ApiErrors())
                .addFilters(new WebSecurity(auth, "https://localhost:8443", false)).build();
    }
    private tools.jackson.databind.JsonNode data(org.springframework.mock.web.MockHttpServletResponse response) {
        return Json.parse(response.getContentAsByteArray());
    }
    @Test void authenticatedReadUsesSessionOwnerAndNoStore() throws Exception {
        when(history.conversations(session, null, 20)).thenReturn(new LinkedHashMap<>(Map.of("items", List.of())));
        var response = http.perform(get("/api/v1/conversations").cookie(cookie)).andReturn().getResponse();
        assertEquals(200, response.getStatus()); assertEquals("no-store", response.getHeader("Cache-Control"));
        assertTrue(data(response).path("data").path("items").isArray());
        assertDoesNotThrow(() -> UUID.fromString(data(response).path("request_id").asText()));
        verify(history).conversations(session, null, 20);
    }
    @Test void noSessionCannotReadHistory() throws Exception {
        var response = http.perform(get("/api/v1/conversations/" + conversation + "/messages")).andReturn().getResponse();
        assertEquals(401, response.getStatus()); verifyNoInteractions(history);
    }
    @Test void queryBoundsUnknownFieldsAndRepeatedValuesAreRejected() throws Exception {
        for (String query : List.of("limit=0", "limit=101", "limit=-1", "limit=01", "limit=x", "owner_id=" + owner, "cursor=", "limit=20&limit=30")) {
            var response = http.perform(get("/api/v1/conversations?" + query).cookie(cookie)).andReturn().getResponse();
            assertEquals(400, response.getStatus(), query);
        }
        verifyNoInteractions(history);
    }
    @Test void messagePageRetainsNullableCanonicalFields() throws Exception {
        Map<String,Object> page = new LinkedHashMap<>(); page.put("items", List.of()); page.put("next_cursor", null); page.put("current_run", null); page.put("stream_cursor", null);
        when(history.messages(session, conversation, null, 100)).thenReturn(page);
        var response = http.perform(get("/api/v1/conversations/" + conversation + "/messages?limit=100").cookie(cookie)).andReturn().getResponse();
        assertEquals(200, response.getStatus());
        assertTrue(data(response).path("data").path("current_run").isNull()); assertTrue(data(response).path("data").path("stream_cursor").isNull());
    }
    @Test void streamResumeUsesOneExplicitCursorAndPreservesResyncCode() throws Exception {
        String event = UUID.randomUUID().toString(), path = "/api/v1/conversations/" + conversation + "/events";
        when(history.events(session, conversation, event)).thenReturn("retry: 2000\n\n");
        var response = http.perform(get(path).cookie(cookie).header("Last-Event-ID", event)).andReturn().getResponse();
        assertEquals(200, response.getStatus()); assertTrue(response.getContentType().startsWith("text/event-stream"));
        assertEquals("retry: 2000\n\n", response.getContentAsString());
        assertEquals(400, http.perform(get(path + "?cursor=" + event).cookie(cookie).header("Last-Event-ID", event)).andReturn().getResponse().getStatus());
        when(history.events(session, conversation, event)).thenThrow(new ApiException(410, "EVENT_CURSOR_UNAVAILABLE"));
        var stale = http.perform(get(path + "?cursor=" + event).cookie(cookie)).andReturn().getResponse();
        assertEquals(410, stale.getStatus()); assertEquals("EVENT_CURSOR_UNAVAILABLE", data(stale).path("error").path("code").asText());
    }
    @Test void foreignConversationIsNotFoundAndErrorsDoNotDiscloseContent() throws Exception {
        when(history.messages(session, conversation, null, 20)).thenThrow(ApiException.missing());
        var response = http.perform(get("/api/v1/conversations/" + conversation + "/messages").cookie(cookie)).andReturn().getResponse();
        assertEquals(404, response.getStatus()); assertEquals("NOT_FOUND", data(response).path("error").path("code").asText());
        assertTrue(data(response).path("error").path("details").isEmpty());
    }
}

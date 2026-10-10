package dev.myjarvis.core;

import jakarta.servlet.http.Cookie;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Real audit service, authentication filter and PostgreSQL. No stub response or real provider. */
class AuditHistoryHttpTest extends ReminderTestSupport {
    MockMvc http;
    @BeforeEach void setupHttp() {
        http = MockMvcBuilders.standaloneSetup(new AuditHistoryController(context.getBean(AuditHistoryService.class)))
                .setControllerAdvice(new ApiErrors()).addFilters(new WebSecurity(auth, "https://localhost:8443", false)).build();
    }
    Cookie cookie() { return new Cookie(WebSecurity.COOKIE, token); }
    void event(String type, UUID resource) {
        db.update("INSERT INTO audit_events(id,owner_id,event_type,resource_id) VALUES (?,?,?,?)", UUID.randomUUID(), owner, type, resource);
    }

    @Test void authenticatedGetUsesExistingEnvelopeAndNoStoreWithClosedEmptyPage() throws Exception {
        var response = http.perform(get("/api/v1/audit").cookie(cookie())).andReturn().getResponse();
        assertEquals(200, response.getStatus()); assertEquals("no-store", response.getHeader("Cache-Control"));
        var envelope = Json.parse(response.getContentAsByteArray());
        assertEquals(Set.of("data", "request_id"), Set.copyOf(envelope.propertyNames()));
        assertDoesNotThrow(() -> UUID.fromString(envelope.path("request_id").asText()));
        var data = envelope.path("data"); assertEquals(Set.of("checked_at", "scope", "items", "next_cursor"), Set.copyOf(data.propertyNames()));
        assertEquals("ALL", data.path("scope").asText()); assertTrue(data.path("items").isEmpty()); assertTrue(data.path("next_cursor").isNull());
    }

    @Test void defaultPageSizeAndExplicitRunsScopeAreBounded() throws Exception {
        for (int i = 0; i < 21; i++) event("OWNER_CREATED", owner);
        event("RUN_ACCEPTED", UUID.randomUUID());
        var response = http.perform(get("/api/v1/audit").cookie(cookie())).andReturn().getResponse();
        var data = Json.parse(response.getContentAsByteArray()).path("data"); assertEquals(20, data.path("items").size()); assertTrue(data.path("next_cursor").isTextual());
        response = http.perform(get("/api/v1/audit").cookie(cookie()).queryParam("scope", "RUNS").queryParam("limit", "100")).andReturn().getResponse();
        assertEquals(200, response.getStatus()); data = Json.parse(response.getContentAsByteArray()).path("data");
        assertEquals("RUNS", data.path("scope").asText()); assertEquals(1, data.path("items").size()); assertTrue(data.path("items").get(0).path("run").isNull());
    }

    @Test void unknownDuplicateAndBlankQueryParametersAreRejected() throws Exception {
        for (String query : List.of("owner_id=" + owner, "from=2026-01-01", "anything=", "scope=ALL&scope=ALL", "limit=20&limit=20", "cursor=a&cursor=b", "scope=", "limit=", "cursor=")) {
            var response = http.perform(get("/api/v1/audit?" + query).cookie(cookie())).andReturn().getResponse();
            assertEquals(400, response.getStatus(), query); assertEquals("no-store", response.getHeader("Cache-Control"));
            assertEquals("INVALID_REQUEST", Json.parse(response.getContentAsByteArray()).path("error").path("code").asText());
        }
    }

    @Test void invalidScopeNoncanonicalLimitsAndOversizedQueriesFailClosed() throws Exception {
        for (String scope : List.of("all", "RUN", "OTHER", "ALL "))
            assertEquals(400, http.perform(get("/api/v1/audit").cookie(cookie()).queryParam("scope", scope)).andReturn().getResponse().getStatus());
        for (String limit : List.of("0", "01", "-1", "+1", "1.0", "1e2", "101", "1000", " 20", "20 "))
            assertEquals(400, http.perform(get("/api/v1/audit").cookie(cookie()).queryParam("limit", limit)).andReturn().getResponse().getStatus());
        var response = http.perform(get("/api/v1/audit").cookie(cookie()).queryParam("cursor", "x".repeat(1025))).andReturn().getResponse();
        assertEquals(400, response.getStatus()); assertEquals("INVALID_REQUEST", Json.parse(response.getContentAsByteArray()).path("error").path("code").asText());
    }

    @Test void cursorTamperingReturnsSanitizedNonretryableError() throws Exception {
        var response = http.perform(get("/api/v1/audit").cookie(cookie()).queryParam("cursor", "private-cursor-sentinel")).andReturn().getResponse();
        assertEquals(400, response.getStatus()); assertEquals("no-store", response.getHeader("Cache-Control"));
        var error = Json.parse(response.getContentAsByteArray()).path("error"); assertEquals("INVALID_CURSOR", error.path("code").asText()); assertFalse(error.path("retryable").asBoolean());
        assertFalse(response.getContentAsString().contains("private-cursor-sentinel")); assertFalse(response.getContentAsString().contains(session.hash()));
    }

    @Test void missingRevokedIdleExpiredAbsoluteExpiredAndOldEpochSessionsReturn401() throws Exception {
        assertEquals(401, http.perform(get("/api/v1/audit")).andReturn().getResponse().getStatus());
        db.update("UPDATE sessions SET last_seen_at=now()-interval '3 hours'"); assertEquals(401, http.perform(get("/api/v1/audit").cookie(cookie())).andReturn().getResponse().getStatus());
        db.update("UPDATE sessions SET last_seen_at=now(),created_at=now()-interval '13 hours'"); assertEquals(401, http.perform(get("/api/v1/audit").cookie(cookie())).andReturn().getResponse().getStatus());
        db.update("UPDATE sessions SET created_at=now(),revoked_at=now()"); assertEquals(401, http.perform(get("/api/v1/audit").cookie(cookie())).andReturn().getResponse().getStatus());
        db.update("UPDATE sessions SET revoked_at=NULL"); db.update("UPDATE owners SET security_epoch=security_epoch+1"); assertEquals(401, http.perform(get("/api/v1/audit").cookie(cookie())).andReturn().getResponse().getStatus());
    }

    @Test void everyNonemptyGetBodyIsRejectedRegardlessOfContentType() throws Exception {
        for (String content : List.of("{}", " ", "null", "{\"owner_id\":\"" + owner + "\"}")) {
            var response = http.perform(get("/api/v1/audit").cookie(cookie()).contentType("application/json").content(content)).andReturn().getResponse();
            assertEquals(400, response.getStatus()); assertEquals("INVALID_REQUEST", Json.parse(response.getContentAsByteArray()).path("error").path("code").asText());
        }
        assertEquals(400, http.perform(get("/api/v1/audit").cookie(cookie()).header("Transfer-Encoding", "chunked").content("unexpected")).andReturn().getResponse().getStatus());
        assertEquals(200, http.perform(get("/api/v1/audit").cookie(cookie()).content(new byte[0])).andReturn().getResponse().getStatus());
    }

    Map<String,String> businessContents() {
        Map<String,String> result = new TreeMap<>();
        for (String table : db.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename<>'sessions' ORDER BY tablename", String.class)) {
            if (!table.matches("[a-z_][a-z0-9_]*")) throw new AssertionError("Unexpected table identifier");
            result.put(table, db.queryForObject("SELECT COALESCE(jsonb_agg(value ORDER BY value::text),'[]'::jsonb)::text FROM (SELECT to_jsonb(t) AS value FROM " + table + " t) rows", String.class));
        }
        return result;
    }

    @Test void pausedHistoryOnlyRefreshesMiddlewareLastSeenAndNeverMutatesBusinessTables() throws Exception {
        event("EXECUTION_PAUSED", owner); db.update("UPDATE owners SET execution_paused=true WHERE id=?", owner);
        db.update("UPDATE sessions SET last_seen_at=now()-interval '1 hour'");
        var beforeSeen = AuthService.instant(db.queryForObject("SELECT last_seen_at FROM sessions WHERE token_hash=?", Object.class, session.hash()));
        String beforeSession = db.queryForObject("SELECT (to_jsonb(s)-'last_seen_at')::text FROM sessions s WHERE token_hash=?", String.class, session.hash());
        var before = businessContents(); assertEquals(200, http.perform(get("/api/v1/audit").cookie(cookie())).andReturn().getResponse().getStatus());
        assertEquals(before, businessContents());
        assertEquals(beforeSession, db.queryForObject("SELECT (to_jsonb(s)-'last_seen_at')::text FROM sessions s WHERE token_hash=?", String.class, session.hash()));
        var afterSeen = AuthService.instant(db.queryForObject("SELECT last_seen_at FROM sessions WHERE token_hash=?", Object.class, session.hash())); assertTrue(afterSeen.isAfter(beforeSeen));
    }

    @Test void databaseFailureReturnsSanitizedErrorRatherThanEmptyHistory() throws Exception {
        db.execute("ALTER TABLE audit_events RENAME TO audit_http_hidden_events");
        try {
            var response = http.perform(get("/api/v1/audit").cookie(cookie())).andReturn().getResponse();
            assertEquals(500, response.getStatus()); assertEquals("no-store", response.getHeader("Cache-Control"));
            var envelope = Json.parse(response.getContentAsByteArray()); assertFalse(envelope.has("data")); assertEquals("INTERNAL_ERROR", envelope.path("error").path("code").asText());
            assertFalse(response.getContentAsString().contains("audit_events")); assertFalse(response.getContentAsString().contains("SELECT"));
        } finally { db.execute("ALTER TABLE audit_http_hidden_events RENAME TO audit_events"); }
    }

    @Test void corruptTimestampReturnsSanitized500AndUnknownEventTextNeverLeaks() throws Exception {
        event("RUN_PRIVATE_SECRET_SENTINEL", owner);
        var response = http.perform(get("/api/v1/audit").cookie(cookie())).andReturn().getResponse();
        assertEquals(200, response.getStatus()); assertFalse(response.getContentAsString().contains("PRIVATE_SECRET"));
        assertEquals("OTHER", Json.parse(response.getContentAsByteArray()).path("data").path("items").get(0).path("event_type").asText());
        db.update("UPDATE audit_events SET happened_at='infinity'::timestamptz");
        response = http.perform(get("/api/v1/audit").cookie(cookie())).andReturn().getResponse();
        assertEquals(500, response.getStatus()); assertEquals("no-store", response.getHeader("Cache-Control"));
        assertFalse(Json.parse(response.getContentAsByteArray()).has("data")); assertEquals("INTERNAL_ERROR", Json.parse(response.getContentAsByteArray()).path("error").path("code").asText());
        assertFalse(response.getContentAsString().contains("infinity"));
    }
}

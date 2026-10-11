package dev.myjarvis.core;

import jakarta.servlet.http.Cookie;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Actual service, authentication middleware and PostgreSQL; no mocked usage API. */
class UsageHttpTest extends ReminderTestSupport {
    MockMvc http;
    @BeforeEach void setupHttp() {
        http = MockMvcBuilders.standaloneSetup(new UsageController(context.getBean(UsageService.class)))
                .setControllerAdvice(new ApiErrors()).addFilters(new WebSecurity(auth, "https://localhost:8443", false)).build();
    }
    Cookie cookie() { return new Cookie(WebSecurity.COOKIE, token); }

    @Test void authenticatedSummaryUsesExistingEnvelopeNoStoreAndExplicitEmptyUnknowns() throws Exception {
        var response = http.perform(get("/api/v1/usage").cookie(cookie())).andReturn().getResponse();
        assertEquals(200, response.getStatus()); assertEquals("no-store", response.getHeader("Cache-Control"));
        var envelope = Json.parse(response.getContentAsByteArray()); assertEquals(Set.of("data", "request_id"), Set.copyOf(envelope.propertyNames()));
        assertDoesNotThrow(() -> UUID.fromString(envelope.path("request_id").asText()));
        var data = envelope.path("data"); assertEquals(Set.of("checked_at", "timezone", "attribution", "quota_timezone", "day", "month"), Set.copyOf(data.propertyNames()));
        assertEquals(0, data.path("day").path("request_count").asLong()); assertTrue(data.path("day").path("measured").path("external_cost_usd_micro").isNull());
        assertTrue(data.path("month").path("measured").path("peak_memory_bytes").isNull());
    }

    @Test void everyQueryParameterIncludingOwnerDatesDuplicatesAndEmptyValuesIsRejected() throws Exception {
        for (String query : List.of("owner_id=" + owner, "date=2026-01-01", "from=2026-01-01", "period=day", "limit=10", "anything=", "timezone=UTC", "period=day&period=day")) {
            var response = http.perform(get("/api/v1/usage?" + query).cookie(cookie())).andReturn().getResponse();
            assertEquals(400, response.getStatus(), query); assertEquals("no-store", response.getHeader("Cache-Control"));
            assertEquals("INVALID_REQUEST", Json.parse(response.getContentAsByteArray()).path("error").path("code").asText());
        }
    }

    @Test void everyNonemptyGetBodyIsRejectedWithoutReflectingPrivateContent() throws Exception {
        for (String content : List.of("{}", " ", "null", "private-usage-body-sentinel")) {
            var response = http.perform(get("/api/v1/usage").cookie(cookie()).contentType("application/json").content(content)).andReturn().getResponse();
            assertEquals(400, response.getStatus()); assertFalse(response.getContentAsString().contains("private-usage-body-sentinel"));
        }
        assertEquals(400, http.perform(get("/api/v1/usage").cookie(cookie()).header("Transfer-Encoding", "chunked").content("unexpected")).andReturn().getResponse().getStatus());
        assertEquals(200, http.perform(get("/api/v1/usage").cookie(cookie()).content(new byte[0])).andReturn().getResponse().getStatus());
    }

    @Test void missingExpiredRevokedAndOldEpochSessionCannotReadUsage() throws Exception {
        assertEquals(401, http.perform(get("/api/v1/usage")).andReturn().getResponse().getStatus());
        db.update("UPDATE sessions SET last_seen_at=now()-interval '3 hours'"); assertEquals(401, http.perform(get("/api/v1/usage").cookie(cookie())).andReturn().getResponse().getStatus());
        db.update("UPDATE sessions SET last_seen_at=now(),created_at=now()-interval '13 hours'"); assertEquals(401, http.perform(get("/api/v1/usage").cookie(cookie())).andReturn().getResponse().getStatus());
        db.update("UPDATE sessions SET created_at=now(),revoked_at=now()"); assertEquals(401, http.perform(get("/api/v1/usage").cookie(cookie())).andReturn().getResponse().getStatus());
        db.update("UPDATE sessions SET revoked_at=NULL"); db.update("UPDATE owners SET security_epoch=security_epoch+1"); assertEquals(401, http.perform(get("/api/v1/usage").cookie(cookie())).andReturn().getResponse().getStatus());
    }

    @Test void pausedOwnerCanReadWithoutChangingBusinessTablesOrAuthority() throws Exception {
        db.update("UPDATE owners SET execution_paused=true");
        var before = contents(); assertEquals(200, http.perform(get("/api/v1/usage").cookie(cookie())).andReturn().getResponse().getStatus());
        assertEquals(before, contents()); assertEquals(0, count("authorizations")); assertEquals(0, count("jobs"));
    }
    Map<String,String> contents() {
        Map<String,String> result = new TreeMap<>();
        for (String table : db.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename<>'sessions' ORDER BY tablename", String.class)) {
            if (!table.matches("[a-z_][a-z0-9_]*")) throw new AssertionError("Unexpected table identifier");
            result.put(table, db.queryForObject("SELECT COALESCE(jsonb_agg(value ORDER BY value::text),'[]'::jsonb)::text FROM (SELECT to_jsonb(t) AS value FROM " + table + " t) rows", String.class));
        }
        return result;
    }

    @Test void databaseFailureReturnsSanitizedFailureRatherThanZeroUsage() throws Exception {
        db.execute("ALTER TABLE inference_usage_receipts RENAME TO usage_http_hidden_receipts");
        try {
            var response = http.perform(get("/api/v1/usage").cookie(cookie())).andReturn().getResponse();
            assertEquals(500, response.getStatus()); assertEquals("no-store", response.getHeader("Cache-Control"));
            var envelope = Json.parse(response.getContentAsByteArray()); assertFalse(envelope.has("data")); assertEquals("INTERNAL_ERROR", envelope.path("error").path("code").asText());
            assertFalse(response.getContentAsString().contains("inference_usage_receipts")); assertFalse(response.getContentAsString().contains("SELECT"));
        } finally { db.execute("ALTER TABLE usage_http_hidden_receipts RENAME TO inference_usage_receipts"); }
    }

    @Test void corruptProfileDoesNotBecomeUtcEmptyUsage() throws Exception {
        db.update("UPDATE owners SET profile_ref='private-corrupt-profile-sentinel',profile_updated_at=clock_timestamp()");
        var response = http.perform(get("/api/v1/usage").cookie(cookie())).andReturn().getResponse();
        assertEquals(500, response.getStatus()); assertFalse(Json.parse(response.getContentAsByteArray()).has("data"));
        assertFalse(response.getContentAsString().contains("private-corrupt-profile-sentinel"));
    }
}

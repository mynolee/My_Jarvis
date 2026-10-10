package dev.myjarvis.core;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Real filter/controller with synthetic mocked service output; database behavior is separate. */
class ExportHttpTest {
    MockMvc http; ExportService exports; AuthService auth; AuthService.Session session; Cookie cookie;
    UUID owner, id, key;
    static final String REQUEST = "{\"kind\":\"EXPLICIT_MEMORIES\",\"scope\":\"GLOBAL\",\"project_id\":null,\"format\":\"JSON_UTF8\"}";
    @BeforeEach void setup() {
        owner = UUID.randomUUID(); id = UUID.randomUUID(); key = UUID.randomUUID();
        session = new AuthService.Session("synthetic-export-session", owner, Instant.now(), 1);
        cookie = new Cookie(WebSecurity.COOKIE, "synthetic-export-token"); auth = mock(AuthService.class); exports = mock(ExportService.class);
        when(auth.find("synthetic-export-token")).thenReturn(session); when(auth.csrf(session, "synthetic-csrf")).thenReturn(true);
        http = MockMvcBuilders.standaloneSetup(new ExportController(exports)).setControllerAdvice(new ApiErrors())
                .addFilters(new WebSecurity(auth, "https://localhost:8443", false)).build();
    }
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder create() {
        return post("/api/v1/exports").cookie(cookie).header("Origin", "https://localhost:8443").header("X-CSRF-Token", "synthetic-csrf")
                .header("Idempotency-Key", key.toString()).contentType("application/json").content(REQUEST);
    }
    List<String> reads() { return List.of("/api/v1/exports/" + id, "/api/v1/exports/requests/" + key, "/api/v1/exports/" + id + "/download"); }

    @Test void createBindsExactSessionKeyAndBodyWithMetadataEnvelope() throws Exception {
        when(exports.create(eq(session), eq(key), any())).thenReturn(Map.of("id", id, "status", "AVAILABLE"));
        var result = http.perform(create()).andReturn().getResponse(); assertEquals(200, result.getStatus()); assertEquals("no-store", result.getHeader("Cache-Control"));
        assertEquals(id.toString(), Json.parse(result.getContentAsByteArray()).path("data").path("id").asText());
        verify(exports).create(eq(session), eq(key), argThat(body -> body.equals(Json.parse(REQUEST.getBytes(StandardCharsets.UTF_8)))));
    }

    @Test void metadataReadsForwardExactlyAndNeverTriggerGeneration() throws Exception {
        when(exports.status(session, id)).thenReturn(Map.of("id", id));
        var absent = new LinkedHashMap<String,Object>(); absent.put("status", "NOT_OBSERVED"); absent.put("job", null);
        when(exports.reconcile(session, key)).thenReturn(absent);
        for (String path : reads().subList(0, 2)) {
            var result = http.perform(get(path).cookie(cookie)).andReturn().getResponse(); assertEquals(200, result.getStatus()); assertEquals("no-store", result.getHeader("Cache-Control"));
        }
        verify(exports).status(session, id); verify(exports).reconcile(session, key); verifyNoMoreInteractions(exports);
    }

    @Test void contentIsRawUtf8BoundedAttachmentWithSafeConstantFilename() throws Exception {
        byte[] bytes = "{\"items\":[\"한글 😀\"]}".getBytes(StandardCharsets.UTF_8); when(exports.download(session, id)).thenReturn(bytes);
        var result = http.perform(get(reads().getLast()).cookie(cookie)).andReturn().getResponse();
        assertEquals(200, result.getStatus()); assertArrayEquals(bytes, result.getContentAsByteArray());
        assertEquals(org.springframework.http.MediaType.parseMediaType("application/json;charset=UTF-8"), org.springframework.http.MediaType.parseMediaType(result.getContentType()));
        assertEquals("attachment; filename=\"my-jarvis-memories.json\"", result.getHeader("Content-Disposition"));
        assertEquals(Integer.toString(bytes.length), result.getHeader("Content-Length"));
        assertEquals("no-store", result.getHeader("Cache-Control")); assertEquals("nosniff", result.getHeader("X-Content-Type-Options"));
        assertNull(result.getHeader("Location")); verify(exports).download(session, id); verifyNoMoreInteractions(exports);
    }

    @Test void authenticationOriginCsrfAndMediaTypeFailBeforeService() throws Exception {
        for (String path : reads()) assertEquals(401, http.perform(get(path)).andReturn().getResponse().getStatus());
        assertEquals(403, http.perform(create().with(request -> { request.removeHeader("Origin"); request.addHeader("Origin", "https://attacker.invalid"); return request; })).andReturn().getResponse().getStatus());
        assertEquals(403, http.perform(post("/api/v1/exports").cookie(cookie).header("Origin", "https://localhost:8443").contentType("application/json").content(REQUEST)).andReturn().getResponse().getStatus());
        assertEquals(415, http.perform(create().contentType("text/plain")).andReturn().getResponse().getStatus()); verifyNoInteractions(exports);
    }

    @Test void duplicateAbsentAndNonCanonicalKeysFailBeforeService() throws Exception {
        var noKey = post("/api/v1/exports").cookie(cookie).header("Origin", "https://localhost:8443").header("X-CSRF-Token", "synthetic-csrf").contentType("application/json").content(REQUEST);
        assertEquals(400, http.perform(noKey).andReturn().getResponse().getStatus());
        for (String invalid : List.of("", "1-1-1-1-1", "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA")) {
            var request = post("/api/v1/exports").cookie(cookie).header("Origin", "https://localhost:8443").header("X-CSRF-Token", "synthetic-csrf").header("Idempotency-Key", invalid).contentType("application/json").content(REQUEST);
            assertEquals(400, http.perform(request).andReturn().getResponse().getStatus());
        }
        assertEquals(400, http.perform(create().header("Idempotency-Key", key.toString())).andReturn().getResponse().getStatus()); verifyNoInteractions(exports);
    }

    @Test void everyQueryAndNonemptyReadBodyIsRejectedIncludingChunkedAndEmptySegments() throws Exception {
        for (String path : reads()) {
            for (String query : List.of("scope=GLOBAL", "owner_id=" + owner, "download=true", "x=1&x=2", "&"))
                assertEquals(400, http.perform(get(path + "?" + query).cookie(cookie)).andReturn().getResponse().getStatus());
            assertEquals(400, http.perform(get(path).cookie(cookie).content("x")).andReturn().getResponse().getStatus());
            assertEquals(400, http.perform(get(path).cookie(cookie).header("Transfer-Encoding", "chunked").content("x")).andReturn().getResponse().getStatus());
            assertEquals(400, http.perform(get(path).cookie(cookie).with(request -> { request.setQueryString(""); return request; })).andReturn().getResponse().getStatus());
        }
        assertEquals(400, http.perform(create().queryParam("scope", "GLOBAL")).andReturn().getResponse().getStatus());
        for (String path : List.of("/api/v1/exports/1-1-1-1-1", "/api/v1/exports/requests/1-1-1-1-1", "/api/v1/exports/AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA/download"))
            assertEquals(400, http.perform(get(path).cookie(cookie)).andReturn().getResponse().getStatus()); verifyNoInteractions(exports);
    }

    @Test void strictJsonRejectsDuplicatesMalformedUnicodeAndOversizedInput() throws Exception {
        for (String json : List.of("{\"scope\":\"GLOBAL\",\"scope\":\"PROJECT\"}", "{\"value\":\"\\ud800\"}", "{} {}", "{\"kind\":NaN}", "{\"schema_version\":1.0}"))
            assertEquals(400, http.perform(create().content(json)).andReturn().getResponse().getStatus());
        assertEquals(413, http.perform(create().content(" ".repeat(262145))).andReturn().getResponse().getStatus()); verifyNoInteractions(exports);
    }

    @Test void knownAndUnexpectedErrorsContainNoAttachmentOrPartialContent() throws Exception {
        for (var failure : List.of(new ApiException(410, "EXPORT_EXPIRED"), new ApiException(413, "EXPORT_ROW_LIMIT"), new ApiException(413, "EXPORT_BYTE_LIMIT"), new ApiException(503, "EXPORT_TIME_LIMIT"))) {
            when(exports.download(session, id)).thenThrow(failure);
            var result = http.perform(get(reads().getLast()).cookie(cookie)).andReturn().getResponse(); assertEquals(failure.status, result.getStatus());
            assertNull(result.getHeader("Content-Disposition")); assertEquals("no-store", result.getHeader("Cache-Control"));
            assertEquals(failure.code, Json.parse(result.getContentAsByteArray()).path("error").path("code").asText());
        }
        when(exports.download(session, id)).thenThrow(new IllegalStateException("synthetic-private-ciphertext"));
        var result = http.perform(get(reads().getLast()).cookie(cookie)).andReturn().getResponse(); assertEquals(500, result.getStatus());
        assertFalse(result.getContentAsString().contains("synthetic-private")); assertNull(result.getHeader("Content-Disposition"));
    }
}

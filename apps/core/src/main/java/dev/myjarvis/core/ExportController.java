package dev.myjarvis.core;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Explicit owner-only privacy export. The job identifier is not a bearer token. */
@RestController
final class ExportController {
    private final ExportService exports;
    ExportController(ExportService exports) { this.exports = exports; }
    private static void query(HttpServletRequest request) {
        if (request.getQueryString() != null || !request.getParameterMap().isEmpty()) throw ApiException.bad();
    }
    private static void read(HttpServletRequest request) throws IOException {
        query(request);
        // Test the stream itself, including unknown Content-Length/chunked request bodies.
        if (request.getInputStream().read() != -1) throw ApiException.bad();
    }

    @PostMapping("/api/v1/exports")
    Map<String,Object> create(HttpServletRequest request) {
        query(request);
        return ApiController.wrap(request, exports.create(ApiController.session(request), MemoryController.key(request), ApiController.body(request)));
    }

    @GetMapping("/api/v1/exports/{id}")
    Map<String,Object> status(@PathVariable String id, HttpServletRequest request) throws IOException {
        read(request);
        return ApiController.wrap(request, exports.status(ApiController.session(request), MemoryService.uuid(id)));
    }

    @GetMapping("/api/v1/exports/requests/{key}")
    Map<String,Object> reconcile(@PathVariable String key, HttpServletRequest request) throws IOException {
        read(request);
        return ApiController.wrap(request, exports.reconcile(ApiController.session(request), MemoryService.uuid(key)));
    }

    @GetMapping("/api/v1/exports/{id}/download")
    ResponseEntity<byte[]> download(@PathVariable String id, HttpServletRequest request) throws IOException {
        read(request);
        // The complete bounded document is authorized and built before any response body is sent.
        byte[] document = exports.download(ApiController.session(request), MemoryService.uuid(id));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/json; charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"my-jarvis-memories.json\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .contentLength(document.length).body(document);
    }
}

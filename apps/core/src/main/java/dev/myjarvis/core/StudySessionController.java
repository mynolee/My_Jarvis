package dev.myjarvis.core;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** Dedicated owner-input routes. They never enqueue ordinary messages or inference. */
@RestController
final class StudySessionController {
    private final StudySessionService study;
    StudySessionController(StudySessionService study) { this.study = study; }

    private static void read(HttpServletRequest request, String... query) {
        MemoryController.query(request, query);
        if (request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null) throw ApiException.bad();
        try { if (request.getInputStream().read() != -1) throw ApiException.bad(); }
        catch (java.io.IOException invalid) { throw ApiException.bad(); }
    }
    private static Map<String,Object> wrap(HttpServletRequest request, Map<String,Object> data, int limit) {
        var result = ApiController.wrap(request, data);
        if (Json.canonical(result).getBytes(StandardCharsets.UTF_8).length > limit)
            throw new ApiException(409, "STUDY_SOURCE_UNAVAILABLE");
        return result;
    }
    @PostMapping("/api/v1/conversations/{id}/study-sessions")
    Map<String,Object> start(@PathVariable String id, HttpServletRequest request) {
        MemoryController.query(request);
        return wrap(request, study.start(ApiController.session(request), MemoryController.key(request),
                MemoryService.uuid(id), ApiController.body(request)), 65536);
    }
    @PostMapping("/api/v1/study-sessions/{id}/answers")
    Map<String,Object> answer(@PathVariable String id, HttpServletRequest request) {
        MemoryController.query(request);
        return wrap(request, study.answer(ApiController.session(request), MemoryController.key(request),
                MemoryService.uuid(id), ApiController.body(request)), 65536);
    }
    @PostMapping("/api/v1/study-sessions/{id}/close")
    Map<String,Object> close(@PathVariable String id, HttpServletRequest request) {
        MemoryController.query(request);
        return wrap(request, study.close(ApiController.session(request), MemoryController.key(request),
                MemoryService.uuid(id), ApiController.body(request)), 65536);
    }
    @GetMapping("/api/v1/study-sessions/{id}")
    Map<String,Object> get(@PathVariable String id, HttpServletRequest request) {
        read(request);
        return wrap(request, study.get(ApiController.session(request), MemoryService.uuid(id)), 262144);
    }
    @GetMapping("/api/v1/study-mutations/{id}")
    Map<String,Object> receipt(@PathVariable String id, HttpServletRequest request) {
        read(request);
        return wrap(request, study.reconcile(ApiController.session(request), MemoryService.uuid(id)), 65536);
    }
    @GetMapping("/api/v1/conversations/{id}/study-index")
    Map<String,Object> index(@PathVariable String id, HttpServletRequest request) {
        read(request, "limit", "cursor");
        return wrap(request, study.index(ApiController.session(request), MemoryService.uuid(id),
                request.getParameter("cursor"), MemoryController.limit(request)), 65536);
    }
}

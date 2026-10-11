package dev.myjarvis.core;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** An explicit owner request selects the purpose; model/client fields cannot supply authority. */
@RestController
final class StudyLessonController {
    private final LocalInferenceService local;
    StudyLessonController(LocalInferenceService local) { this.local = local; }

    @PostMapping("/api/v1/conversations/{id}/study-lessons")
    Map<String,Object> create(@PathVariable String id, HttpServletRequest request, HttpServletResponse response) {
        MemoryController.query(request);
        var body = ApiController.body(request);
        Json.fields(body, "client_message_id", "text");
        if (!MemoryController.key(request).equals(MemoryService.uuid(Json.string(body, "client_message_id", 36)))) throw ApiException.bad();
        var result = local.acceptLesson(ApiController.session(request), MemoryService.uuid(id), ApiController.body(request));
        response.setStatus(202);
        return ApiController.wrap(request, result);
    }

    @GetMapping("/api/v1/runs/{id}/study-lesson")
    Map<String,Object> read(@PathVariable String id, HttpServletRequest request) {
        MemoryController.query(request);
        if (request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null) throw ApiException.bad();
        try { if (request.getInputStream().read() != -1) throw ApiException.bad(); }
        catch (java.io.IOException invalid) { throw ApiException.bad(); }
        return ApiController.wrap(request, local.lesson(ApiController.session(request), MemoryService.uuid(id)));
    }
}

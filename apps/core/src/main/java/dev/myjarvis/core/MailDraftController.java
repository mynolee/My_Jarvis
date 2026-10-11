package dev.myjarvis.core;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** Same-origin owner forms. There is deliberately no send route. */
@RestController
final class MailDraftController {
    private final MailDraftService drafts;
    MailDraftController(MailDraftService drafts) { this.drafts = drafts; }
    @PostMapping("/api/v1/mail-drafts") Map<String,Object> create(HttpServletRequest request) {
        query(request); return ApiController.wrap(request, drafts.create(ApiController.session(request), key(request), ApiController.body(request)));
    }
    @PatchMapping("/api/v1/mail-drafts/{id}") Map<String,Object> patch(@PathVariable String id, HttpServletRequest request) {
        query(request); return ApiController.wrap(request, drafts.patch(ApiController.session(request), ProjectService.uuid(id), key(request), ApiController.body(request)));
    }
    @GetMapping("/api/v1/mail-drafts") Map<String,Object> list(HttpServletRequest request) {
        query(request, "cursor", "limit", "project_id");
        String limit = request.getParameter("limit"), cursor = request.getParameter("cursor"), project = request.getParameter("project_id");
        if (limit != null && !limit.matches("(?:[1-9][0-9]?|100)")) throw ApiException.bad();
        if (cursor != null && !cursor.matches("[A-Za-z0-9_.-]{1,1024}")) throw ApiException.bad();
        return ApiController.wrap(request, drafts.list(ApiController.session(request), project == null ? null : ProjectService.uuid(project), cursor, limit == null ? 20 : Integer.parseInt(limit)));
    }
    @GetMapping("/api/v1/mail-drafts/{id}") Map<String,Object> get(@PathVariable String id, HttpServletRequest request) {
        query(request); return ApiController.wrap(request, drafts.get(ApiController.session(request), ProjectService.uuid(id)));
    }
    @GetMapping("/api/v1/mail-draft-mutations/{key}") Map<String,Object> reconcile(@PathVariable String key, HttpServletRequest request) {
        query(request); return ApiController.wrap(request, drafts.reconcile(ApiController.session(request), ProjectService.uuid(key)));
    }
    private static UUID key(HttpServletRequest request) {
        var values = Collections.list(request.getHeaders("Idempotency-Key"));
        if (values.size() != 1) throw ApiException.bad(); return ProjectService.uuid(values.getFirst());
    }
    private static void query(HttpServletRequest request, String... allowed) {
        Set<String> keys = Set.of(allowed);
        request.getParameterMap().forEach((key, values) -> {
            if (!keys.contains(key) || values.length != 1 || values[0].isEmpty() || values[0].length() > 1024) throw ApiException.bad();
        });
    }
}

package dev.myjarvis.core;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** Explicit owner forms only. No project tool, grant or model execution route exists. */
@RestController
final class ProjectController {
    private final ProjectService projects;
    ProjectController(ProjectService projects) { this.projects = projects; }
    @PostMapping("/api/v1/projects") Map<String,Object> create(HttpServletRequest request) {
        query(request);
        var headers = Collections.list(request.getHeaders("Idempotency-Key"));
        if (headers.size() != 1) throw ApiException.bad();
        return ApiController.wrap(request, projects.create(ApiController.session(request), ProjectService.uuid(headers.getFirst()), ApiController.body(request)));
    }
    @GetMapping("/api/v1/projects") Map<String,Object> list(HttpServletRequest request) {
        query(request, "cursor", "limit");
        String value = request.getParameter("limit");
        if (value != null && !value.matches("[1-9][0-9]{0,2}")) throw ApiException.bad();
        return ApiController.wrap(request, projects.list(ApiController.session(request), request.getParameter("cursor"), value == null ? 20 : Integer.parseInt(value)));
    }
    @GetMapping("/api/v1/projects/{id}") Map<String,Object> get(@PathVariable String id, HttpServletRequest request) {
        query(request);
        return ApiController.wrap(request, projects.get(ApiController.session(request), ProjectService.uuid(id)));
    }
    @GetMapping("/api/v1/project-creations/{key}") Map<String,Object> reconcile(@PathVariable String key, HttpServletRequest request) {
        query(request);
        return ApiController.wrap(request, projects.reconcile(ApiController.session(request), ProjectService.uuid(key)));
    }
    private static void query(HttpServletRequest request, String... allowed) {
        Set<String> keys = Set.of(allowed);
        request.getParameterMap().forEach((key, values) -> {
            if (!keys.contains(key) || values.length != 1 || values[0].isEmpty() || values[0].length() > 1024) throw ApiException.bad();
        });
    }
}

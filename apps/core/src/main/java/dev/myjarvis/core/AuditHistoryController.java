package dev.myjarvis.core;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** A closed, read-only audit projection. There is no execution action on this endpoint. */
@RestController
final class AuditHistoryController {
    private final AuditHistoryService history;
    AuditHistoryController(AuditHistoryService history) { this.history = history; }

    @GetMapping("/api/v1/audit")
    ResponseEntity<Map<String,Object>> get(HttpServletRequest request) throws java.io.IOException {
        request.getParameterMap().forEach((name, values) -> {
            if (!Set.of("scope", "cursor", "limit").contains(name) || values.length != 1
                    || values[0].isEmpty() || values[0].length() > 1024) throw ApiException.bad();
        });
        if (request.getInputStream().read() != -1) throw ApiException.bad();
        String suppliedLimit = request.getParameter("limit");
        int limit = 20;
        if (suppliedLimit != null) {
            if (!suppliedLimit.matches("[1-9][0-9]{0,2}")) throw ApiException.bad();
            limit = Integer.parseInt(suppliedLimit);
        }
        String scope = request.getParameter("scope");
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(ApiController.wrap(request,
                history.get(ApiController.session(request), scope == null ? "ALL" : scope,
                        request.getParameter("cursor"), limit)));
    }
}

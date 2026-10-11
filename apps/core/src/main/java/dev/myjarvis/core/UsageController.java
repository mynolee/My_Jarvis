package dev.myjarvis.core;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Bounded, owner-only current day/month projection. No action or reconciliation endpoint. */
@RestController
final class UsageController {
    private final UsageService usage;
    UsageController(UsageService usage) { this.usage = usage; }

    @GetMapping("/api/v1/usage")
    ResponseEntity<Map<String,Object>> get(HttpServletRequest request) throws java.io.IOException {
        if (!request.getParameterMap().isEmpty() || request.getInputStream().read() != -1) throw ApiException.bad();
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(ApiController.wrap(request, usage.get(ApiController.session(request))));
    }
}

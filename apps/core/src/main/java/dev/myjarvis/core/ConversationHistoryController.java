package dev.myjarvis.core;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

/** Enable only after removing the legacy three read mappings from ApiController. */
@RestController
@ConditionalOnProperty(name="jarvis.history.enabled", havingValue="true")
final class ConversationHistoryController {
    private final ConversationHistoryService history;
    ConversationHistoryController(ConversationHistoryService history) { this.history = history; }

    @GetMapping("/api/v1/conversations")
    Map<String,Object> conversations(HttpServletRequest request) {
        query(request, "cursor", "limit");
        return ApiController.wrap(request, history.conversations(ApiController.session(request).owner(), request.getParameter("cursor"), limit(request)));
    }
    @GetMapping("/api/v1/conversations/{id}/messages")
    Map<String,Object> messages(@PathVariable UUID id, HttpServletRequest request) {
        query(request, "cursor", "limit");
        return ApiController.wrap(request, history.messages(ApiController.session(request).owner(), id, request.getParameter("cursor"), limit(request)));
    }
    @GetMapping(value="/api/v1/conversations/{id}/events", produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    String events(@PathVariable UUID id, @RequestHeader(value="Last-Event-ID", required=false) String last, HttpServletRequest request) {
        query(request, "cursor");
        if (last != null && request.getParameter("cursor") != null) throw ApiException.bad();
        if (Collections.list(request.getHeaders("Last-Event-ID")).size() > 1) throw ApiException.bad();
        return history.events(ApiController.session(request).owner(), id, last == null ? request.getParameter("cursor") : last);
    }
    private static int limit(HttpServletRequest request) {
        String value = request.getParameter("limit");
        if (value == null) return 20;
        if (!value.matches("[1-9][0-9]{0,2}")) throw ApiException.bad();
        int limit = Integer.parseInt(value); if (limit > 100) throw ApiException.bad(); return limit;
    }
    private static void query(HttpServletRequest request, String... allowed) {
        Set<String> fields = Set.of(allowed);
        request.getParameterMap().forEach((key, values) -> {
            if (!fields.contains(key) || values.length != 1 || values[0].isEmpty() || values[0].length() > 1024) throw ApiException.bad();
        });
    }
}

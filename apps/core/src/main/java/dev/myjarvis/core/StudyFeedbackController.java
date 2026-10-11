package dev.myjarvis.core;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** Separate bounded surface; reading and receipt reconciliation never enqueue work. */
@RestController
final class StudyFeedbackController {
    private final StudyFeedbackService feedback;
    StudyFeedbackController(StudyFeedbackService feedback){this.feedback=feedback;}
    private static void read(HttpServletRequest request){
        MemoryController.query(request);
        if(request.getContentLengthLong()>0||request.getHeader("Transfer-Encoding")!=null)throw ApiException.bad();
        try{if(request.getInputStream().read()!=-1)throw ApiException.bad();}catch(java.io.IOException invalid){throw ApiException.bad();}
    }
    private static tools.jackson.databind.JsonNode body(HttpServletRequest request){
        MemoryController.query(request);
        return ApiController.body(request);
    }
    private static Map<String,Object> wrap(HttpServletRequest request,Map<String,Object> data,int limit){
        var result=ApiController.wrap(request,data);if(Json.canonical(result).getBytes(StandardCharsets.UTF_8).length>limit)throw new ApiException(409,"FEEDBACK_SOURCE_UNAVAILABLE");return result;
    }
    @GetMapping("/api/v1/study-sessions/{id}/feedback")
    Map<String,Object> read(@PathVariable String id,HttpServletRequest request){read(request);return wrap(request,feedback.read(ApiController.session(request),MemoryService.uuid(id)),262144);}
    @PostMapping("/api/v1/study-sessions/{id}/feedback-requests") @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String,Object> request(@PathVariable String id,HttpServletRequest request){return wrap(request,feedback.request(ApiController.session(request),MemoryController.key(request),MemoryService.uuid(id),body(request)),16384);}
    @PostMapping("/api/v1/study-feedback-requests/{id}/revoke")
    Map<String,Object> revoke(@PathVariable String id,HttpServletRequest request){return wrap(request,feedback.revoke(ApiController.session(request),MemoryController.key(request),MemoryService.uuid(id),body(request)),16384);}
    @GetMapping("/api/v1/study-feedback-mutations/{id}")
    Map<String,Object> mutation(@PathVariable String id,HttpServletRequest request){read(request);return wrap(request,feedback.mutation(ApiController.session(request),MemoryService.uuid(id)),16384);}
}

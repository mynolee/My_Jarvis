package dev.myjarvis.core;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
final class ConversationDeletionController {
    private final ConversationDeletionService deletion;
    ConversationDeletionController(ConversationDeletionService deletion){this.deletion=deletion;}
    private static void read(HttpServletRequest request){MemoryController.query(request);if(request.getContentLengthLong()>0||request.getHeader("Transfer-Encoding")!=null)throw ApiException.bad();try{if(request.getInputStream().read()!=-1)throw ApiException.bad();}catch(java.io.IOException e){throw ApiException.bad();}}
    @GetMapping("/api/v1/conversations/{id}/deletion-state") Map<String,Object> state(@PathVariable String id,HttpServletRequest request){read(request);return ApiController.wrap(request,deletion.state(ApiController.session(request),MemoryService.uuid(id)));}
    @PostMapping("/api/v1/conversations/{id}/delete-preparations") Map<String,Object> prepare(@PathVariable String id,HttpServletRequest request){MemoryController.query(request);return ApiController.wrap(request,deletion.prepare(ApiController.session(request),MemoryController.key(request),MemoryService.uuid(id),ApiController.body(request)));}
    @PostMapping("/api/v1/conversation-delete-preparations/{id}/confirm") Map<String,Object> confirm(@PathVariable String id,HttpServletRequest request){MemoryController.query(request);return ApiController.wrap(request,deletion.confirm(ApiController.session(request),MemoryController.key(request),MemoryService.uuid(id),ApiController.body(request)));}
    @GetMapping("/api/v1/conversation-deletions/requests/{key}") Map<String,Object> receipt(@PathVariable String key,HttpServletRequest request){read(request);return ApiController.wrap(request,deletion.reconcile(ApiController.session(request),MemoryService.uuid(key)));}
}

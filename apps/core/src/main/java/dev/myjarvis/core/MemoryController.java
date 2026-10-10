package dev.myjarvis.core;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** Explicit authenticated forms; no model or execution-registry dispatch. */
@RestController
final class MemoryController {
    private final MemoryService memories; private final MemoryIntentService intents; private final MemoryDeletionService deletion;
    MemoryController(MemoryService memories,MemoryIntentService intents,MemoryDeletionService deletion){this.memories=memories;this.intents=intents;this.deletion=deletion;}
    static UUID key(HttpServletRequest request){var headers=Collections.list(request.getHeaders("Idempotency-Key"));if(headers.size()!=1)throw ApiException.bad();return MemoryService.uuid(headers.getFirst());}
    static void query(HttpServletRequest request,String... allowed){Set<String> keys=Set.of(allowed);request.getParameterMap().forEach((key,values)->{if(!keys.contains(key)||values.length!=1||values[0].isEmpty()||values[0].length()>1024)throw ApiException.bad();});}
    static int limit(HttpServletRequest request){String value=request.getParameter("limit");if(value==null)return 20;if(!value.matches("[1-9][0-9]?"))throw ApiException.bad();int result=Integer.parseInt(value);if(result>50)throw ApiException.bad();return result;}
    @PostMapping("/api/v1/memory-intents") Map<String,Object> issue(HttpServletRequest req){query(req);return ApiController.wrap(req,intents.issue(ApiController.session(req),key(req),ApiController.body(req)));}
    @PostMapping("/api/v1/memories/key-state") Map<String,Object> state(HttpServletRequest req){query(req);return ApiController.wrap(req,memories.keyState(ApiController.session(req),ApiController.body(req)));}
    @PostMapping("/api/v1/memories") Map<String,Object> create(HttpServletRequest req){query(req);return ApiController.wrap(req,memories.save(ApiController.session(req),key(req),null,ApiController.body(req),true));}
    @PatchMapping("/api/v1/memories/{id}") Map<String,Object> revise(@PathVariable String id,HttpServletRequest req){query(req);return ApiController.wrap(req,memories.save(ApiController.session(req),key(req),MemoryService.uuid(id),ApiController.body(req),false));}
    @GetMapping("/api/v1/memories/{id}") Map<String,Object> get(@PathVariable String id,HttpServletRequest req){query(req);return ApiController.wrap(req,memories.get(ApiController.session(req),MemoryService.uuid(id)));}
    @GetMapping("/api/v1/memories") Map<String,Object> list(HttpServletRequest req){query(req,"scope","project_id","cursor","limit");String scope=req.getParameter("scope"),project=req.getParameter("project_id");if(scope==null)throw ApiException.bad();return ApiController.wrap(req,memories.list(ApiController.session(req),scope,project==null?null:MemoryService.uuid(project),req.getParameter("cursor"),limit(req)));}
    @GetMapping("/api/v1/memories/{id}/revisions") Map<String,Object> revisions(@PathVariable String id,HttpServletRequest req){query(req,"cursor","limit");return ApiController.wrap(req,memories.revisions(ApiController.session(req),MemoryService.uuid(id),req.getParameter("cursor"),limit(req)));}
    @GetMapping("/api/v1/memory-mutations/{id}") Map<String,Object> receipt(@PathVariable String id,HttpServletRequest req){query(req);return ApiController.wrap(req,memories.reconcile(ApiController.session(req),MemoryService.uuid(id)));}
    @PostMapping("/api/v1/memories/{id}/delete-preparations") Map<String,Object> prepare(@PathVariable String id,HttpServletRequest req){query(req);return ApiController.wrap(req,deletion.prepare(ApiController.session(req),key(req),MemoryService.uuid(id),ApiController.body(req)));}
    @PostMapping("/api/v1/memory-delete-preparations/{id}/confirm") Map<String,Object> confirm(@PathVariable String id,HttpServletRequest req){query(req);return ApiController.wrap(req,deletion.confirm(ApiController.session(req),key(req),MemoryService.uuid(id),ApiController.body(req)));}
}

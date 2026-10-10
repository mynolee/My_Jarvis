package dev.myjarvis.core;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
final class TaskController {
    private final TaskService tasks;
    private final TaskIntentService intents;
    TaskController(TaskService tasks,TaskIntentService intents){this.tasks=tasks;this.intents=intents;}
    static UUID idempotencyKey(HttpServletRequest request){
        var headers=Collections.list(request.getHeaders("Idempotency-Key"));
        if(headers.size()!=1)throw ApiException.bad();return TaskService.parseUuid(headers.getFirst());
    }
    @PostMapping("/api/v1/user-intents") Map<String,Object> issue(HttpServletRequest request){return ApiController.wrap(request,intents.issue(ApiController.session(request),idempotencyKey(request),ApiController.body(request)));}
    @PostMapping("/api/v1/tasks") Map<String,Object> create(HttpServletRequest request){return ApiController.wrap(request,tasks.create(ApiController.session(request),idempotencyKey(request),ApiController.body(request)));}
    @PatchMapping("/api/v1/tasks/{id}") Map<String,Object> update(@PathVariable String id,HttpServletRequest request){return ApiController.wrap(request,tasks.update(ApiController.session(request),idempotencyKey(request),TaskService.parseUuid(id),ApiController.body(request)));}
    @GetMapping("/api/v1/tasks/{id}") Map<String,Object> get(@PathVariable String id,HttpServletRequest request){return ApiController.wrap(request,tasks.get(ApiController.session(request),TaskService.parseUuid(id)));}
    @GetMapping("/api/v1/tasks") Map<String,Object> list(HttpServletRequest request){return ApiController.wrap(request,tasks.list(ApiController.session(request),request.getParameterMap()));}
    @GetMapping("/api/v1/task-mutations/{key}") Map<String,Object> reconcile(@PathVariable String key,HttpServletRequest request){return ApiController.wrap(request,tasks.reconcile(ApiController.session(request),TaskService.parseUuid(key)));}
    @ExceptionHandler(TaskService.TaskConflict.class) ResponseEntity<?> conflict(TaskService.TaskConflict exception,HttpServletRequest request){
        return ResponseEntity.status(409).body(Map.of("error",Map.of("code","PRECONDITION_CHANGED","message","PRECONDITION_CHANGED","retryable",false,"details",Map.of("current_task",exception.currentTask)),"request_id",request.getAttribute("request_id")));
    }
}

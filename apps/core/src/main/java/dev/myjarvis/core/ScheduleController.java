package dev.myjarvis.core;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
final class ScheduleController {
    private final ScheduleService schedules;
    ScheduleController(ScheduleService schedules){this.schedules=schedules;}
    static void query(HttpServletRequest request,String... allowed){MemoryController.query(request,allowed);}
    static UUID key(HttpServletRequest request){var values=Collections.list(request.getHeaders("Idempotency-Key"));if(values.size()!=1)throw ApiException.bad();return ScheduleService.uuid(values.getFirst());}
    static UUID project(HttpServletRequest request){String value=request.getParameter("project_id");return value==null?null:ScheduleService.uuid(value);}
    @PostMapping("/api/v1/schedule-preparations") Map<String,Object> prepare(HttpServletRequest request){query(request);return ApiController.wrap(request,schedules.prepare(ApiController.session(request),ApiController.body(request)));}
    @PostMapping("/api/v1/schedule-preparations/{id}/confirm") Map<String,Object> confirm(@PathVariable String id,HttpServletRequest request){query(request);return ApiController.wrap(request,schedules.confirm(ApiController.session(request),key(request),ScheduleService.uuid(id),ApiController.body(request)));}
    @PostMapping("/api/v1/schedules/{id}/disable") Map<String,Object> disable(@PathVariable String id,HttpServletRequest request){query(request);return ApiController.wrap(request,schedules.disable(ApiController.session(request),key(request),ScheduleService.uuid(id),ApiController.body(request)));}
    @GetMapping("/api/v1/schedules") Map<String,Object> list(HttpServletRequest request){query(request,"project_id","cursor","limit");return ApiController.wrap(request,schedules.list(ApiController.session(request),project(request),request.getParameter("cursor"),MemoryController.limit(request)));}
    @GetMapping("/api/v1/schedules/{id}") Map<String,Object> get(@PathVariable String id,HttpServletRequest request){query(request);return ApiController.wrap(request,schedules.get(ApiController.session(request),ScheduleService.uuid(id)));}
    @GetMapping("/api/v1/schedules/{id}/occurrences") Map<String,Object> occurrences(@PathVariable String id,HttpServletRequest request){query(request,"cursor","limit");return ApiController.wrap(request,schedules.occurrences(ApiController.session(request),ScheduleService.uuid(id),request.getParameter("cursor"),MemoryController.limit(request)));}
    @GetMapping("/api/v1/schedule-mutations/{key}") Map<String,Object> receipt(@PathVariable String key,HttpServletRequest request){query(request);return ApiController.wrap(request,schedules.reconcile(ApiController.session(request),ScheduleService.uuid(key)));}
    @ExceptionHandler(ScheduleService.ScheduleConflict.class) ResponseEntity<?> conflict(ScheduleService.ScheduleConflict error,HttpServletRequest request){return ResponseEntity.status(409).body(Map.of("error",Map.of("code","PRECONDITION_CHANGED","message","PRECONDITION_CHANGED","retryable",false,"details",Map.of("current_version",error.currentVersion)),"request_id",request.getAttribute("request_id")));}
}

package dev.myjarvis.core;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
final class NotificationController {
    private final NotificationService notifications;
    NotificationController(NotificationService notifications){this.notifications=notifications;}
    @GetMapping("/api/v1/notifications") Map<String,Object> list(HttpServletRequest request){ScheduleController.query(request,"project_id","cursor","limit");return ApiController.wrap(request,notifications.list(ApiController.session(request),ScheduleController.project(request),request.getParameter("cursor"),MemoryController.limit(request)));}
    @GetMapping("/api/v1/notifications/{id}") Map<String,Object> get(@PathVariable String id,HttpServletRequest request){ScheduleController.query(request);return ApiController.wrap(request,notifications.get(ApiController.session(request),ScheduleService.uuid(id)));}
    @PostMapping("/api/v1/notifications/{id}/open") Map<String,Object> open(@PathVariable String id,HttpServletRequest request){ScheduleController.query(request);return ApiController.wrap(request,notifications.open(ApiController.session(request),ScheduleService.uuid(id),ApiController.body(request)));}
}

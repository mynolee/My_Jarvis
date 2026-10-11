package dev.myjarvis.core;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** GET /me remains integrated by the existing API controller, avoiding duplicate mappings. */
@RestController
final class OwnerProfileController {
    private final OwnerProfileService profiles;
    OwnerProfileController(OwnerProfileService profiles){this.profiles=profiles;}
    static void query(HttpServletRequest request){if(!request.getParameterMap().isEmpty())throw ApiException.bad();}
    static UUID key(HttpServletRequest request){var values=Collections.list(request.getHeaders("Idempotency-Key"));if(values.size()!=1)throw ApiException.bad();return OwnerProfileService.uuid(values.getFirst());}
    @PatchMapping("/api/v1/me") Map<String,Object> patch(HttpServletRequest request){query(request);return ApiController.wrap(request,profiles.patch(ApiController.session(request),key(request),ApiController.body(request)));}
    @GetMapping("/api/v1/profile-options") Map<String,Object> options(HttpServletRequest request){query(request);return ApiController.wrap(request,profiles.options(ApiController.session(request)));}
    @GetMapping("/api/v1/profile-mutations/{key}") Map<String,Object> receipt(@PathVariable String key,HttpServletRequest request){query(request);return ApiController.wrap(request,profiles.reconcile(ApiController.session(request),OwnerProfileService.uuid(key)));}
    @ExceptionHandler(OwnerProfileService.ProfileConflict.class) ResponseEntity<?> conflict(OwnerProfileService.ProfileConflict error,HttpServletRequest request){return ResponseEntity.status(409).body(Map.of("error",Map.of("code","PRECONDITION_CHANGED","message","PRECONDITION_CHANGED","retryable",false,"details",Map.of("current_version",error.currentVersion)),"request_id",request.getAttribute("request_id")));}
}

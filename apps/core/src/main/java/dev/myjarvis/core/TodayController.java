package dev.myjarvis.core;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
final class TodayController {
    private final TodayService today;
    TodayController(TodayService today){this.today=today;}
    @GetMapping("/api/v1/today") ResponseEntity<Map<String,Object>> get(HttpServletRequest request) throws java.io.IOException{
        if(!request.getParameterMap().isEmpty()||request.getInputStream().read()!=-1)throw ApiException.bad();
        return ResponseEntity.ok().header("Cache-Control","no-store").body(ApiController.wrap(request,today.get(ApiController.session(request))));
    }
}

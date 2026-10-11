package dev.myjarvis.core;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** Account control remains accessible while unrelated operations have uncertain outcomes. */
@RestController
final class ExecutionControlController {
    private final WorkflowService flow;
    private final LocalInferenceService local;
    ExecutionControlController(WorkflowService flow,LocalInferenceService local){this.flow=flow;this.local=local;}
    private static void query(HttpServletRequest request){if(!request.getParameterMap().isEmpty())throw ApiException.bad();}
    @GetMapping("/api/v1/execution") Map<String,Object> status(HttpServletRequest request) throws IOException {
        query(request);if(request.getInputStream().read()!=-1)throw ApiException.bad();
        return ApiController.wrap(request,flow.executionStatus(ApiController.session(request)));
    }
    @PostMapping("/api/v1/execution/pause") Map<String,Object> pause(HttpServletRequest request){return transition(request,true);}
    @PostMapping("/api/v1/execution/resume") Map<String,Object> resume(HttpServletRequest request){return transition(request,false);}
    private Map<String,Object> transition(HttpServletRequest request,boolean paused){
        query(request);var session=ApiController.session(request);
        var result=flow.executionTransition(session,paused,ApiController.body(request));
        if(paused&&((Number)result.get("version")).longValue()!=Json.integer(ApiController.body(request),"expected_version"))local.interruptOwner(session.owner());
        return ApiController.wrap(request,result);
    }
}

package dev.myjarvis.core;

import jakarta.servlet.http.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import java.util.*;

@RestController
final class ApiController {
    private final AuthService auth;private final WorkflowService flow;private final MockWorker worker;private final LocalInferenceService local;private final OwnerProfileService profiles;
    ApiController(AuthService auth,WorkflowService flow,MockWorker worker,LocalInferenceService local,OwnerProfileService profiles){this.auth=auth;this.flow=flow;this.worker=worker;this.local=local;this.profiles=profiles;}
    static AuthService.Session session(HttpServletRequest r){return (AuthService.Session)r.getAttribute("session");}
    static JsonNode body(HttpServletRequest r){return (JsonNode)r.getAttribute("json");}
    static Map<String,Object> wrap(HttpServletRequest req,Object data){return Map.of("data",data,"request_id",req.getAttribute("request_id"));}
    @GetMapping("/health") Map<String,Object> health(HttpServletRequest req){flow.db.queryForObject("SELECT 1",Integer.class);return wrap(req,Map.of("status","UP","mode","MOCK"));}
    @GetMapping("/api/v1/auth/csrf") Map<String,Object> csrf(HttpServletRequest req,HttpServletResponse res){var s=session(req);String csrf;if(s==null){var issued=auth.issue(null,false,null);WebSecurity.cookie(res,issued.token());csrf=issued.csrf();}else csrf=auth.refreshCsrf(s);return wrap(req,Map.of("csrf_token",csrf));}
    @PostMapping("/api/v1/auth/bootstrap") Map<String,Object> bootstrap(HttpServletRequest req,HttpServletResponse res){var b=body(req);Json.fields(b,"bootstrap_token","login_name","password");UUID owner=auth.bootstrap(Json.string(b,"bootstrap_token",43),Json.string(b,"login_name",64),Json.string(b,"password",128));return authenticated(req,res,auth.issue(owner,true,session(req).hash()));}
    @PostMapping("/api/v1/auth/login") Map<String,Object> login(HttpServletRequest req,HttpServletResponse res){var b=body(req);Json.fields(b,"login_name","password");UUID owner=auth.login(Json.string(b,"login_name",64),Json.string(b,"password",128),req.getRemoteAddr());return authenticated(req,res,auth.issue(owner,true,session(req).hash()));}
    @PostMapping("/api/v1/auth/reauthenticate") Map<String,Object> reauth(HttpServletRequest req,HttpServletResponse res){var b=body(req);Json.fields(b,"password");auth.reauthenticate(session(req),Json.string(b,"password",128),req.getRemoteAddr());return authenticated(req,res,auth.issue(session(req).owner(),true,session(req).hash()));}
    private Map<String,Object> authenticated(HttpServletRequest req,HttpServletResponse res,AuthService.Issued issued){WebSecurity.cookie(res,issued.token());return wrap(req,Map.of("csrf_token",issued.csrf(),"authenticated",true));}
    @PostMapping("/api/v1/auth/logout") Map<String,Object> logout(HttpServletRequest req,HttpServletResponse res){Json.fields(body(req));auth.logout(session(req));res.addHeader("Set-Cookie",WebSecurity.COOKIE+"=; Path=/; Secure; HttpOnly; SameSite=Lax; Max-Age=0");return wrap(req,Map.of("logged_out",true));}
    @GetMapping("/api/v1/me") Map<String,Object> me(HttpServletRequest req){if(!req.getParameterMap().isEmpty())throw ApiException.bad();return wrap(req,profiles.get(session(req)));}
    @PostMapping("/api/v1/conversations") Map<String,Object> createConversation(HttpServletRequest req){return wrap(req,flow.createConversation(session(req).owner(),body(req)));}
    @PostMapping("/api/v1/conversations/{id}/messages") Map<String,Object> message(@PathVariable UUID id,HttpServletRequest req,HttpServletResponse res){var result=flow.accept(session(req).owner(),id,body(req));res.setStatus(202);return wrap(req,result);}
    @GetMapping("/api/v1/local-model/status") Map<String,Object> localStatus(HttpServletRequest req){return wrap(req,local.status());}
    @PostMapping("/api/v1/conversations/{id}/local-inference") Map<String,Object> localInference(@PathVariable UUID id,HttpServletRequest req,HttpServletResponse res){var result=local.accept(session(req),id,body(req));res.setStatus(202);return wrap(req,result);}
    @GetMapping("/api/v1/runs/{id}") Map<String,Object> run(@PathVariable UUID id,HttpServletRequest req){return wrap(req,flow.run(session(req).owner(),id));}
    @PostMapping("/api/v1/runs/{id}/cancel") Map<String,Object> cancel(@PathVariable UUID id,HttpServletRequest req,HttpServletResponse res){Json.fields(body(req),"expected_version");var result=flow.cancel(session(req).owner(),id,Json.integer(body(req),"expected_version"));local.interrupt(id);res.setStatus(202);return wrap(req,result);}
    @PostMapping("/api/v1/runs/{id}/reconcile") Map<String,Object> reconcile(@PathVariable UUID id,HttpServletRequest req){Json.fields(body(req));return wrap(req,worker.reconcile(session(req).owner(),id));}
    @GetMapping("/api/v1/approvals/{id}") Map<String,Object> approval(@PathVariable UUID id,HttpServletRequest req){return wrap(req,flow.approval(session(req).owner(),id));}
    @GetMapping("/api/v1/approvals") Map<String,Object> approvals(HttpServletRequest req){return wrap(req,Map.of("items",flow.db.queryForList("SELECT id FROM approvals WHERE owner_id=? AND status='PENDING' ORDER BY expires_at LIMIT 100",session(req).owner()).stream().map(r->flow.approval(session(req).owner(),(UUID)r.get("id"))).toList()));}
    @PostMapping("/api/v1/approvals/{id}/decision") Map<String,Object> decision(@PathVariable UUID id,HttpServletRequest req){return wrap(req,flow.decision(session(req),id,body(req)));}
    @PostMapping("/api/v1/execution/pause") Map<String,Object> pause(HttpServletRequest req){Json.fields(body(req));flow.pause(session(req),true);local.interruptOwner(session(req).owner());return wrap(req,Map.of("execution_paused",true));}
    @PostMapping("/api/v1/execution/resume") Map<String,Object> resume(HttpServletRequest req){Json.fields(body(req));flow.pause(session(req),false);local.interruptOwner(session(req).owner());return wrap(req,Map.of("execution_paused",false));}
}

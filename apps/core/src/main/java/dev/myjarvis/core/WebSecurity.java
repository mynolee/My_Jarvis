package dev.myjarvis.core;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.*;
import java.util.*;

@Component
final class WebSecurity extends OncePerRequestFilter {
    static final String COOKIE="__Host-jarvis";
    private final AuthService auth;private final String origin;private final boolean webEnabled;
    WebSecurity(AuthService auth,@Value("${jarvis.origin}")String origin,@Value("${jarvis.web.enabled:false}")boolean webEnabled){this.auth=auth;this.origin=origin;this.webEnabled=webEnabled;if(!origin.startsWith("https://"))throw new IllegalStateException("HTTPS origin required");}
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException {
        String requestId=UUID.randomUUID().toString();req.setAttribute("request_id",requestId);
        res.setHeader("Cache-Control","no-store");res.setHeader("X-Content-Type-Options","nosniff");res.setHeader("Referrer-Policy","no-referrer");res.setHeader("Content-Security-Policy","default-src 'none'; frame-ancestors 'none'; base-uri 'none'");
        res.setHeader("Permissions-Policy","camera=(), microphone=(), geolocation=()");
        try {
            String path=req.getRequestURI();boolean safe=Set.of("GET","HEAD").contains(req.getMethod());
            if(webEnabled&&safe&&(path.equals("/")||path.equals("/index.html")||path.matches("/assets/[A-Za-z0-9_-]+\\.(js|css|woff2|png|svg)"))){res.setHeader("Content-Security-Policy","default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self'; font-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'; object-src 'none'");chain.doFilter(req,res);return;}
            if(!path.startsWith("/api/v1/")&&!path.equals("/health"))throw ApiException.missing();
            String token=null;if(req.getCookies()!=null)for(Cookie c:req.getCookies())if(c.getName().equals(COOKIE))token=c.getValue();
            AuthService.Session session=auth.find(token);req.setAttribute("session",session);
            if(!safe){
                if(!origin.equals(req.getHeader("Origin")))throw new ApiException(403,"ORIGIN_REJECTED");
                if(!auth.csrf(session,req.getHeader("X-CSRF-Token")))throw new ApiException(403,"CSRF_REJECTED");
                String type=req.getContentType();if(type==null||!type.split(";",2)[0].trim().equalsIgnoreCase("application/json"))throw new ApiException(415,"JSON_REQUIRED");
                boolean feedbackPost="POST".equals(req.getMethod())&&(path.matches("^/api/v1/study-sessions/[^/]+/feedback-requests$")||path.matches("^/api/v1/study-feedback-requests/[^/]+/revoke$"));
                byte[] body=req.getInputStream().readNBytes(feedbackPost?8193:262145);
                if(feedbackPost&&body.length>8192)throw new ApiException(413,"BODY_TOO_LARGE");
                JsonNodeRequest wrapped=new JsonNodeRequest(req,body);req=wrapped;
            }
            boolean publicPath=Set.of("/health","/api/v1/auth/csrf","/api/v1/auth/login","/api/v1/auth/bootstrap").contains(path);
            if(!publicPath)AuthService.requireOwner(session);
            chain.doFilter(req,res);
        }catch(ApiException e){writeError(res,e,requestId);}
    }
    static void writeError(HttpServletResponse res,ApiException e,String requestId)throws IOException {
        res.setStatus(e.status);res.setContentType("application/json");res.getWriter().write(Json.MAPPER.writeValueAsString(Map.of("error",Map.of("code",e.code,"message",e.code,"retryable",false,"details",Map.of()),"request_id",requestId)));
    }
    static void cookie(HttpServletResponse res,String token){res.addHeader("Set-Cookie",COOKIE+"="+token+"; Path=/; Secure; HttpOnly; SameSite=Lax");}
    static final class JsonNodeRequest extends HttpServletRequestWrapper {
        JsonNodeRequest(HttpServletRequest request,byte[] body){super(request);setAttribute("json",Json.parse(body));}
    }
}

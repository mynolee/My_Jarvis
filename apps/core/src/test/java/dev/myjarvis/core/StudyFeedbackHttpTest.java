package dev.myjarvis.core;

import jakarta.servlet.http.Cookie;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class StudyFeedbackHttpTest extends StudyFeedbackFixtures {
    MockMvc http;Cookie cookie;
    @BeforeEach void feedbackHttp(){AuthService auth=mock(AuthService.class);cookie=new Cookie(WebSecurity.COOKIE,"synthetic-feedback-cookie");when(auth.find("synthetic-feedback-cookie")).thenReturn(session);when(auth.csrf(eq(session),eq("synthetic-csrf"))).thenReturn(true);http=MockMvcBuilders.standaloneSetup(new StudyFeedbackController(feedback),new StudySessionController(study),new ApiController(auth,flow,mock(MockWorker.class),feedbackLocal,context.getBean(OwnerProfileService.class))).setControllerAdvice(new ApiErrors()).addFilters(new WebSecurity(auth,"https://localhost:8443",false)).build();}
    String path(){return "/api/v1/study-sessions/"+studyId+"/feedback-requests";}
    MockHttpServletRequestBuilder postWith(String path,UUID key,String body){return post(path).cookie(cookie).header("Origin","https://localhost:8443").header("X-CSRF-Token","synthetic-csrf").header("Idempotency-Key",key).contentType("application/json").content(body);}
    @Test void explicitRequestAndSeparateReadUseClosedNoStoreEnvelopes()throws Exception {saveTwo();var response=http.perform(postWith(path(),UUID.randomUUID(),Json.canonical(requestBody(0)))).andReturn().getResponse();assertEquals(202,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));assertEquals("COMMITTED",Json.parse(response.getContentAsByteArray()).path("data").path("status").asText());assertEquals(0,feedbackClient.calls);var read=http.perform(get("/api/v1/study-sessions/"+studyId+"/feedback").cookie(cookie)).andReturn().getResponse();assertEquals(200,read.getStatus());assertEquals(2,Json.parse(read.getContentAsByteArray()).path("data").path("targets").size());assertFalse(read.getContentAsString().contains("source_binding_ref"));}
    @Test void authOriginCsrfAndSingleCanonicalKeyAreRequiredBeforeAdmission()throws Exception {
        saveTwo();String body=Json.canonical(requestBody(0)),oversize=" ".repeat(8193);
        assertEquals(403,http.perform(post(path()).contentType("application/json").content(body)).andReturn().getResponse().getStatus());
        assertEquals(401,http.perform(get("/api/v1/study-sessions/"+studyId+"/feedback")).andReturn().getResponse().getStatus());
        assertEquals(403,http.perform(post(path()).cookie(cookie).contentType("application/json").content(body)).andReturn().getResponse().getStatus());
        assertEquals(403,http.perform(post(path()).cookie(cookie).header("Origin","https://localhost:8443").contentType("application/json").content(body)).andReturn().getResponse().getStatus());
        assertEquals(403,http.perform(post(path()).contentType("application/json").content(oversize)).andReturn().getResponse().getStatus());
        assertEquals(403,http.perform(post(path()).cookie(cookie).header("Origin","https://localhost:8443").contentType("application/json").content(oversize)).andReturn().getResponse().getStatus());
        assertEquals(415,http.perform(postWith(path(),UUID.randomUUID(),oversize).contentType("text/plain")).andReturn().getResponse().getStatus());
        assertEquals(400,http.perform(postWith(path(),UUID.randomUUID(),body).header("Idempotency-Key",UUID.randomUUID())).andReturn().getResponse().getStatus());
        assertEquals(0L,count("study_feedback_requests"));assertEquals(0,feedbackClient.calls);
    }
    @Test void browserProseAuthorityExtraFieldsAndDuplicateJsonNeverReachSource()throws Exception {saveTwo();for(String field:List.of("answer","feedback_source","owner_id","verified","user_input","purpose")){var body=(tools.jackson.databind.node.ObjectNode)requestBody(0);body.put(field,"injected");assertEquals(400,http.perform(postWith(path(),UUID.randomUUID(),Json.canonical(body))).andReturn().getResponse().getStatus());}assertEquals(400,http.perform(postWith(path(),UUID.randomUUID(),"{\"expected_study_version\":3,\"expected_study_version\":3}")).andReturn().getResponse().getStatus());assertEquals(0L,count("study_feedback_requests"));}
    @Test void readQueriesBodiesAndNoncanonicalIdsAreRefusedWithoutMutation()throws Exception {saveTwo();String path="/api/v1/study-sessions/"+studyId+"/feedback";assertEquals(400,http.perform(get(path).cookie(cookie).queryParam("verified","true")).andReturn().getResponse().getStatus());assertEquals(400,http.perform(get(path).cookie(cookie).content("{}")).andReturn().getResponse().getStatus());assertEquals(400,http.perform(get("/api/v1/study-sessions/"+studyId.toString().toUpperCase(Locale.ROOT)+"/feedback").cookie(cookie)).andReturn().getResponse().getStatus());assertEquals(0L,count("study_feedback_requests"));assertEquals(0,feedbackClient.calls);}
    @Test void revokeReceiptIsExplicitMetadataAndReadingItCannotRetryGeneration()throws Exception {saveTwo();UUID id=requestFeedback(0),key=UUID.randomUUID();var response=http.perform(postWith("/api/v1/study-feedback-requests/"+id+"/revoke",key,Json.canonical(revokeBody(id)))).andReturn().getResponse();assertEquals(200,response.getStatus());assertEquals("REVOKE_FEEDBACK",Json.parse(response.getContentAsByteArray()).path("data").path("receipt").path("operation").asText());for(int i=0;i<2;i++)assertEquals(200,http.perform(get("/api/v1/study-feedback-mutations/"+key).cookie(cookie)).andReturn().getResponse().getStatus());assertEquals(0,feedbackClient.calls);assertEquals(1L,count("study_feedback_requests"));}
    private String paddedBody(String body,int bytes){int length=body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;assertTrue(length<=bytes);return body+" ".repeat(bytes-length);}
    @Test void oversizeBodiesAreBoundedBeforeParsingOrAllocation()throws Exception {
        saveTwo();String body=Json.canonical(requestBody(0));long runs=count("runs"),reservations=count("inference_reservations"),version=conversationVersion(targetConversation);
        for(String oversized:List.of(" ".repeat(8193),paddedBody(body,8193),Json.canonical(Map.of("unexpected","😀".repeat(2048))))){
            assertTrue(oversized.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>8192);
            var response=http.perform(postWith(path(),UUID.randomUUID(),oversized)).andReturn().getResponse();
            assertEquals(413,response.getStatus());assertEquals("BODY_TOO_LARGE",Json.parse(response.getContentAsByteArray()).path("error").path("code").asText());
        }
        assertEquals(0L,count("study_feedback_requests"));assertEquals(runs,count("runs"));assertEquals(reservations,count("inference_reservations"));assertEquals(version,conversationVersion(targetConversation));assertEquals(0,feedbackClient.calls);
        var accepted=http.perform(postWith(path(),UUID.randomUUID(),paddedBody(body,8192))).andReturn().getResponse();assertEquals(202,accepted.getStatus());
        UUID id=UUID.fromString(Json.parse(accepted.getContentAsByteArray()).path("data").path("receipt").path("feedback_request_id").asText());
        String revoke="/api/v1/study-feedback-requests/"+id+"/revoke",revokeJson=Json.canonical(revokeBody(id));var retained=feedbackRow(id);long activeVersion=conversationVersion(targetConversation);
        for(String oversized:List.of(" ".repeat(8193),paddedBody(revokeJson,8193))){assertEquals(413,http.perform(postWith(revoke,UUID.randomUUID(),oversized)).andReturn().getResponse().getStatus());}
        assertEquals(retained,feedbackRow(id));assertEquals(activeVersion,conversationVersion(targetConversation));assertEquals(1L,count("study_feedback_requests"));assertEquals(0,feedbackClient.calls);
        assertEquals(200,http.perform(postWith(revoke,UUID.randomUUID(),paddedBody(revokeJson,8192))).andReturn().getResponse().getStatus());
        assertEquals("REVOKED",feedbackRow(id).get("state"));assertEquals(0,feedbackClient.calls);
        String close="/api/v1/study-sessions/"+studyId+"/close";
        assertEquals(200,http.perform(postWith(close,UUID.randomUUID(),paddedBody(Json.canonical(closeBody(studyId)),8193))).andReturn().getResponse().getStatus());
    }
    @Test void deletedSourcePostReturnsGoneEvenWhenFeedbackModelIsUnavailable()throws Exception {saveTwo();String body=Json.canonical(requestBody(0));commit(prepare(targetConversation),UUID.randomUUID());feedbackClient.available=false;assertEquals(410,http.perform(postWith(path(),UUID.randomUUID(),body)).andReturn().getResponse().getStatus());assertEquals(0,feedbackClient.calls);}
    @Test void activeFeedbackBlocksSourceHttpWritesAndRevokeReleasesThemBeforeProof()throws Exception {
        saveTwo();UUID id=requestFeedback(0);long version=conversationVersion(targetConversation),messages=count("messages"),runs=count("runs");
        String close="/api/v1/study-sessions/"+studyId+"/close",ordinary="/api/v1/conversations/"+targetConversation+"/messages";
        String body=Json.canonical(Map.of("client_message_id",UUID.randomUUID(),"text","Synthetic ordinary message after revoke","attachment_ids",List.of()));
        var blockedClose=http.perform(postWith(close,UUID.randomUUID(),Json.canonical(closeBody(studyId)))).andReturn().getResponse();
        assertEquals(409,blockedClose.getStatus());assertTrue(blockedClose.getContentAsString().contains("FEEDBACK_IN_PROGRESS"));
        var blockedRun=http.perform(postWith(ordinary,UUID.randomUUID(),body)).andReturn().getResponse();assertEquals(409,blockedRun.getStatus());assertTrue(blockedRun.getContentAsString().contains("FEEDBACK_IN_PROGRESS"));
        assertEquals(version,conversationVersion(targetConversation));assertEquals(messages,count("messages"));assertEquals(runs,count("runs"));
        assertEquals(200,http.perform(postWith("/api/v1/study-feedback-requests/"+id+"/revoke",UUID.randomUUID(),Json.canonical(revokeBody(id)))).andReturn().getResponse().getStatus());
        assertFalse(quiescent(id));assertEquals(200,http.perform(postWith(close,UUID.randomUUID(),Json.canonical(closeBody(studyId)))).andReturn().getResponse().getStatus());
        assertEquals(202,http.perform(postWith(ordinary,UUID.randomUUID(),body)).andReturn().getResponse().getStatus());assertEquals(0,feedbackClient.calls);
    }
    private MockMvc realAuthHttp(AuthService auth){return MockMvcBuilders.standaloneSetup(new ApiController(auth,flow,context.getBean(MockWorker.class),feedbackLocal,context.getBean(OwnerProfileService.class))).setControllerAdvice(new ApiErrors()).addFilters(new WebSecurity(auth,"https://localhost:8443",false)).build();}
    private Cookie responseCookie(org.springframework.mock.web.MockHttpServletResponse response){String header=response.getHeader("Set-Cookie");assertNotNull(header);assertTrue(header.startsWith(WebSecurity.COOKIE+"="));return new Cookie(WebSecurity.COOKIE,header.substring(WebSecurity.COOKIE.length()+1).split(";",2)[0]);}
    private String responseCsrf(org.springframework.mock.web.MockHttpServletResponse response){String token=Json.parse(response.getContentAsByteArray()).path("data").path("csrf_token").asText();assertEquals(43,token.length());return token;}
    private MockHttpServletRequestBuilder authPost(String path,Cookie token,String csrf,Object body){return post(path).cookie(token).header("Origin","https://localhost:8443").header("X-CSRF-Token",csrf).contentType("application/json").content(Json.canonical(body));}
    @Test void actualAnonymousCsrfRefreshBootstrapLogoutAndLoginRemainCompatible()throws Exception {
        db.execute("TRUNCATE owners,bootstrap_tokens,login_limits,crypto_nonces CASCADE");AuthService auth=context.getBean(AuthService.class);var publicHttp=realAuthHttp(auth);
        var issued=publicHttp.perform(get("/api/v1/auth/csrf")).andReturn().getResponse();assertEquals(200,issued.getStatus());assertEquals("no-store",issued.getHeader("Cache-Control"));Cookie anonymous=responseCookie(issued);String first=responseCsrf(issued);
        assertNull(db.queryForObject("SELECT owner_id FROM sessions WHERE token_hash=?",UUID.class,Json.hash(anonymous.getValue())));
        var refreshed=publicHttp.perform(get("/api/v1/auth/csrf").cookie(anonymous)).andReturn().getResponse();assertEquals(200,refreshed.getStatus());String csrf=responseCsrf(refreshed);assertNotEquals(first,csrf);
        String bootstrap=Vault.token(),password="synthetic-feedback-password-123";db.update("INSERT INTO bootstrap_tokens(token_hash,expires_at) VALUES (?,clock_timestamp()+interval '15 minutes')",Json.hash(bootstrap));
        var registered=publicHttp.perform(authPost("/api/v1/auth/bootstrap",anonymous,csrf,Map.of("bootstrap_token",bootstrap,"login_name","synthetic-feedback-owner","password",password))).andReturn().getResponse();assertEquals(200,registered.getStatus());Cookie authenticated=responseCookie(registered);String authenticatedCsrf=responseCsrf(registered);
        assertEquals(1L,count("owners"));assertNotNull(db.queryForObject("SELECT revoked_at FROM sessions WHERE token_hash=?",Object.class,Json.hash(anonymous.getValue())));assertEquals(200,publicHttp.perform(get("/api/v1/me").cookie(authenticated)).andReturn().getResponse().getStatus());
        assertEquals(200,publicHttp.perform(authPost("/api/v1/auth/logout",authenticated,authenticatedCsrf,Map.of())).andReturn().getResponse().getStatus());
        var next=publicHttp.perform(get("/api/v1/auth/csrf")).andReturn().getResponse();assertEquals(200,next.getStatus());Cookie loginAnonymous=responseCookie(next);
        var loggedIn=publicHttp.perform(authPost("/api/v1/auth/login",loginAnonymous,responseCsrf(next),Map.of("login_name","synthetic-feedback-owner","password",password))).andReturn().getResponse();assertEquals(200,loggedIn.getStatus());assertEquals(200,publicHttp.perform(get("/api/v1/me").cookie(responseCookie(loggedIn))).andReturn().getResponse().getStatus());
        assertNotNull(db.queryForObject("SELECT revoked_at FROM sessions WHERE token_hash=?",Object.class,Json.hash(loginAnonymous.getValue())));assertEquals(0L,count("study_feedback_requests"));assertEquals(0L,count("inference_reservations"));assertEquals(0,feedbackClient.calls);
    }
    @Test void actualUnownedRefreshAndRotationDoNotBypassOrDisturbActiveFeedback()throws Exception {
        saveTwo();UUID id=requestFeedback(0);var before=feedbackRow(id);long version=conversationVersion(targetConversation);AuthService auth=context.getBean(AuthService.class);var publicHttp=realAuthHttp(auth);
        var issued=publicHttp.perform(get("/api/v1/auth/csrf")).andReturn().getResponse();assertEquals(200,issued.getStatus());Cookie anonymous=responseCookie(issued);
        assertEquals(200,publicHttp.perform(get("/api/v1/auth/csrf").cookie(anonymous)).andReturn().getResponse().getStatus());var replacement=auth.issue(null,false,Json.hash(anonymous.getValue()));assertNotNull(replacement.token());assertNull(auth.find(replacement.token()).owner());
        assertNotNull(db.queryForObject("SELECT revoked_at FROM sessions WHERE token_hash=?",Object.class,Json.hash(anonymous.getValue())));assertEquals(before,feedbackRow(id));assertEquals(version,conversationVersion(targetConversation));assertEquals("ACTIVE",feedbackRow(id).get("output_authority"));assertEquals(0,feedbackClient.calls);
    }
}

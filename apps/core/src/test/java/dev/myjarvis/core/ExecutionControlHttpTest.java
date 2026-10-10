package dev.myjarvis.core;

import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Controller-side interruption is attempted only after an actual committed pause. */
class ExecutionControlHttpTest {
    MockMvc http;WorkflowService flow;LocalInferenceService local;AuthService.Session session;Cookie cookie;
    @BeforeEach void setup(){
        session=new AuthService.Session("synthetic-control-session",UUID.randomUUID(),Instant.now(),1);cookie=new Cookie(WebSecurity.COOKIE,"synthetic-control-token");
        AuthService auth=mock(AuthService.class);flow=mock(WorkflowService.class);local=mock(LocalInferenceService.class);
        when(auth.find(cookie.getValue())).thenReturn(session);when(auth.csrf(session,"synthetic-csrf")).thenReturn(true);
        http=MockMvcBuilders.standaloneSetup(new ExecutionControlController(flow,local)).setControllerAdvice(new ApiErrors()).addFilters(new WebSecurity(auth,"https://localhost:8443",false)).build();
    }
    int postControl(boolean pause,long expected) throws Exception{return http.perform(post("/api/v1/execution/"+(pause?"pause":"resume")).cookie(cookie).header("Origin","https://localhost:8443").header("X-CSRF-Token","synthetic-csrf").contentType("application/json").content("{\"expected_version\":"+expected+"}")).andReturn().getResponse().getStatus();}
    Map<String,Object> state(boolean pause,long version){return Map.of("execution_paused",pause,"version",version,"checked_at","2026-10-10T00:00:00Z");}
    @Test void committedPauseInterruptsOwnerExactlyOnceAfterTransition() throws Exception{
        when(flow.executionTransition(eq(session),eq(true),any())).thenReturn(state(true,2));assertEquals(200,postControl(true,1));
        var order=inOrder(flow,local);order.verify(flow).executionTransition(eq(session),eq(true),argThat(body->body.size()==1&&body.path("expected_version").asLong()==1));order.verify(local).interruptOwner(session.owner());order.verifyNoMoreInteractions();
    }
    @Test void alreadyPausedNoOpDoesNotAttemptProcessInterruption() throws Exception{
        when(flow.executionTransition(eq(session),eq(true),any())).thenReturn(state(true,2));assertEquals(200,postControl(true,2));verifyNoInteractions(local);
    }
    @Test void resumeDoesNotAttemptProcessInterruption() throws Exception{
        when(flow.executionTransition(eq(session),eq(false),any())).thenReturn(state(false,3));assertEquals(200,postControl(false,2));verifyNoInteractions(local);
    }
    @Test void stalePauseDoesNotAttemptProcessInterruption() throws Exception{
        when(flow.executionTransition(eq(session),eq(true),any())).thenThrow(new ApiException(409,"PRECONDITION_CHANGED"));assertEquals(409,postControl(true,1));verifyNoInteractions(local);
    }
    @Test void statusDelegatesLiveSessionWithoutWorkerSideEffects() throws Exception{
        when(flow.executionStatus(session)).thenReturn(state(false,1));var response=http.perform(get("/api/v1/execution").cookie(cookie)).andReturn().getResponse();assertEquals(200,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));verify(flow).executionStatus(session);verifyNoInteractions(local);
    }
}

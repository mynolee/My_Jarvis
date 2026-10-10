package dev.myjarvis.core;

import jakarta.servlet.http.Cookie;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Actual service, auth filter and PostgreSQL; no mock Today response. */
class TodayHttpTest extends ReminderTestSupport {
    MockMvc http;
    @BeforeEach void setupHttp(){http=MockMvcBuilders.standaloneSetup(new TodayController(context.getBean(TodayService.class))).setControllerAdvice(new ApiErrors()).addFilters(new WebSecurity(auth,"https://localhost:8443",false)).build();}
    Cookie cookie(){return new Cookie(WebSecurity.COOKIE,token);}
    @Test void authenticatedGetHasExistingEnvelopeNoStoreAndHonestEmptySections()throws Exception{
        var response=http.perform(get("/api/v1/today").cookie(cookie())).andReturn().getResponse();
        assertEquals(200,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));var envelope=Json.parse(response.getContentAsByteArray());
        assertEquals(2,envelope.size());assertDoesNotThrow(()->UUID.fromString(envelope.path("request_id").asText()));
        var data=envelope.path("data");assertEquals(10,data.size());assertEquals("UTC",data.path("timezone").asText());
        var tasks=data.path("tasks");assertEquals("AVAILABLE",tasks.path("status").asText());assertEquals(0,tasks.path("top_tasks").size());assertEquals(0,tasks.path("deadlines").size());assertEquals(0,tasks.path("deadline_total").asLong());assertFalse(tasks.path("deadlines_truncated").asBoolean());
        for(String section:List.of("calendar","briefing","device")){assertEquals("UNAVAILABLE",data.path(section).path("status").asText());assertEquals("NOT_IMPLEMENTED",data.path(section).path("reason").asText());}
    }
    @Test void noQueryParametersAreAccepted()throws Exception{
        for(String query:List.of("owner_id="+owner,"timezone=Asia%2FSeoul","limit=3","from=2026-01-01","anything=","status=TODO&status=TODO")){
            var response=http.perform(get("/api/v1/today?"+query).cookie(cookie())).andReturn().getResponse();assertEquals(400,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));assertEquals("INVALID_REQUEST",Json.parse(response.getContentAsByteArray()).path("error").path("code").asText());
        }
    }
    @Test void missingRevokedIdleExpiredAndAbsoluteExpiredSessionsReturn401()throws Exception{
        assertEquals(401,http.perform(get("/api/v1/today")).andReturn().getResponse().getStatus());
        db.update("UPDATE sessions SET last_seen_at=now()-interval '3 hours'");assertEquals(401,http.perform(get("/api/v1/today").cookie(cookie())).andReturn().getResponse().getStatus());
        db.update("UPDATE sessions SET last_seen_at=now(),created_at=now()-interval '13 hours'");assertEquals(401,http.perform(get("/api/v1/today").cookie(cookie())).andReturn().getResponse().getStatus());
        db.update("UPDATE sessions SET created_at=now(),revoked_at=now()");assertEquals(401,http.perform(get("/api/v1/today").cookie(cookie())).andReturn().getResponse().getStatus());
        db.update("UPDATE sessions SET revoked_at=NULL");db.update("UPDATE owners SET security_epoch=security_epoch+1");assertEquals(401,http.perform(get("/api/v1/today").cookie(cookie())).andReturn().getResponse().getStatus());
    }
    @Test void databaseFailureReturnsSanitizedErrorNotAnEmptyDashboard()throws Exception{
        db.execute("ALTER TABLE tasks RENAME TO today_http_hidden_tasks");
        try{
            var response=http.perform(get("/api/v1/today").cookie(cookie())).andReturn().getResponse();assertEquals(500,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));var envelope=Json.parse(response.getContentAsByteArray());assertFalse(envelope.has("data"));assertEquals("INTERNAL_ERROR",envelope.path("error").path("code").asText());assertFalse(response.getContentAsString().contains("tasks"));
        }finally{db.execute("ALTER TABLE today_http_hidden_tasks RENAME TO tasks");}
    }
    @Test void getDoesNotCreateBusinessSideEffectsAndOnlyMiddlewareTouchesSession()throws Exception{
        db.update("UPDATE sessions SET last_seen_at=now()-interval '1 hour'");var before=AuthService.instant(db.queryForObject("SELECT last_seen_at FROM sessions WHERE token_hash=?",Object.class,session.hash()));
        Map<String,Long> counts=new LinkedHashMap<>();for(String table:List.of("tasks","task_user_intents","task_mutations","audit_events","runs","jobs","schedules","notifications","crypto_nonces"))counts.put(table,count(table));
        assertEquals(200,http.perform(get("/api/v1/today").cookie(cookie())).andReturn().getResponse().getStatus());
        for(var item:counts.entrySet())assertEquals(item.getValue().longValue(),count(item.getKey()),item.getKey());
        var after=AuthService.instant(db.queryForObject("SELECT last_seen_at FROM sessions WHERE token_hash=?",Object.class,session.hash()));assertTrue(after.isAfter(before));
    }
    @Test void nonemptyGetBodiesAreRejectedRegardlessOfContentType()throws Exception{
        for(String content:List.of("{}"," ","null","{\"owner_id\":\""+owner+"\"}")){
            var response=http.perform(get("/api/v1/today").cookie(cookie()).contentType("application/json").content(content)).andReturn().getResponse();
            assertEquals(400,response.getStatus());assertEquals("INVALID_REQUEST",Json.parse(response.getContentAsByteArray()).path("error").path("code").asText());assertEquals("no-store",response.getHeader("Cache-Control"));
        }
        assertEquals(400,http.perform(get("/api/v1/today").cookie(cookie()).header("Transfer-Encoding","chunked").content("unexpected")).andReturn().getResponse().getStatus());
        assertEquals(200,http.perform(get("/api/v1/today").cookie(cookie()).content(new byte[0])).andReturn().getResponse().getStatus());
    }
    @Test void malformedStoredSummaryReturnsSanitized500()throws Exception{
        UUID id=UUID.randomUUID();db.update("INSERT INTO tasks(id,owner_id,title,description_ref,priority,status) VALUES (?,?,' ','unused','HIGH','TODO')",id,owner);
        var response=http.perform(get("/api/v1/today").cookie(cookie())).andReturn().getResponse();assertEquals(500,response.getStatus());assertFalse(Json.parse(response.getContentAsByteArray()).has("data"));assertEquals("INTERNAL_ERROR",Json.parse(response.getContentAsByteArray()).path("error").path("code").asText());
        db.update("UPDATE tasks SET title='Synthetic title',due_at='infinity'::timestamptz WHERE id=?",id);
        response=http.perform(get("/api/v1/today").cookie(cookie())).andReturn().getResponse();assertEquals(500,response.getStatus());assertFalse(Json.parse(response.getContentAsByteArray()).has("data"));assertEquals("no-store",response.getHeader("Cache-Control"));
    }

}

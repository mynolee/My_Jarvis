package dev.myjarvis.core;

import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class NotificationHttpTest {
    MockMvc http;NotificationService notifications;AuthService auth;AuthService.Session session;Cookie cookie;UUID id;
    @BeforeEach void setup(){id=UUID.randomUUID();session=new AuthService.Session("synthetic-inbox-session",UUID.randomUUID(),Instant.now(),1);cookie=new Cookie(WebSecurity.COOKIE,"synthetic-inbox-token");auth=mock(AuthService.class);notifications=mock(NotificationService.class);when(auth.find("synthetic-inbox-token")).thenReturn(session);when(auth.csrf(session,"synthetic-csrf")).thenReturn(true);http=MockMvcBuilders.standaloneSetup(new NotificationController(notifications)).setControllerAdvice(new ApiErrors()).addFilters(new WebSecurity(auth,"https://localhost:8443",false)).build();}
    @Test void getAndListOnlyInvokeReadMethods() throws Exception{when(notifications.get(session,id)).thenReturn(Map.of("status","CREATED"));when(notifications.list(session,null,null,20)).thenReturn(Map.of("items",List.of()));assertEquals(200,http.perform(get("/api/v1/notifications/"+id).cookie(cookie)).andReturn().getResponse().getStatus());assertEquals(200,http.perform(get("/api/v1/notifications").cookie(cookie)).andReturn().getResponse().getStatus());verify(notifications).get(session,id);verify(notifications).list(session,null,null,20);verifyNoMoreInteractions(notifications);}
    @Test void explicitOpenIsCsrfProtectedWithoutIdempotencyHeader() throws Exception{when(notifications.open(eq(session),eq(id),any())).thenReturn(Map.of("status","OPENED"));var response=http.perform(post("/api/v1/notifications/"+id+"/open").cookie(cookie).header("Origin","https://localhost:8443").header("X-CSRF-Token","synthetic-csrf").contentType("application/json").content("{}")).andReturn().getResponse();assertEquals(200,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));verify(notifications).open(eq(session),eq(id),argThat(b->b.isObject()&&b.size()==0));}
    @Test void openWithoutOriginOrCsrfCannotReachService() throws Exception{assertEquals(401,http.perform(get("/api/v1/notifications")).andReturn().getResponse().getStatus());assertEquals(403,http.perform(post("/api/v1/notifications/"+id+"/open").cookie(cookie).contentType("application/json").content("{}")).andReturn().getResponse().getStatus());
        for(String csrf:List.of("","wrong-csrf")){var request=post("/api/v1/notifications/"+id+"/open").cookie(cookie).header("Origin","https://localhost:8443").contentType("application/json").content("{}");if(!csrf.isEmpty())request.header("X-CSRF-Token",csrf);var response=http.perform(request).andReturn().getResponse();assertEquals(403,response.getStatus());assertEquals("CSRF_REJECTED",Json.parse(response.getContentAsByteArray()).get("error").get("code").asText());}
        verifyNoInteractions(notifications);}
    @Test void inboxQueryLimitsAndUnknownFieldsAreRejected() throws Exception{for(String path:List.of("/api/v1/notifications?limit=01","/api/v1/notifications?limit=51","/api/v1/notifications?limit=1&limit=2","/api/v1/notifications?read=true","/api/v1/notifications/"+id+"?open=true")){assertEquals(400,http.perform(get(path).cookie(cookie)).andReturn().getResponse().getStatus());}verifyNoInteractions(notifications);}
    @Test void foreignItemErrorsDoNotEchoContentOrIdentifier() throws Exception{when(notifications.get(session,id)).thenThrow(ApiException.missing());var response=http.perform(get("/api/v1/notifications/"+id).cookie(cookie)).andReturn().getResponse();assertEquals(404,response.getStatus());assertEquals("NOT_FOUND",Json.parse(response.getContentAsByteArray()).get("error").get("code").asText());assertFalse(response.getContentAsString().contains(id.toString()));}
}

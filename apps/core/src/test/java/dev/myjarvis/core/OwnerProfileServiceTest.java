package dev.myjarvis.core;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic singleton-owner settings, independent of model or scheduling execution. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OwnerProfileServiceTest {
    EmbeddedPostgres postgres;ConfigurableApplicationContext context;JdbcTemplate db;Path key;
    AuthService auth;OwnerProfileService profiles;WorkflowService flow;AuthService.Session session;UUID owner;String token;
    @BeforeAll void start() throws Exception{postgres=EmbeddedPostgres.builder().setPort(0).start();key=Files.createTempFile("jarvis-profile-test-",".bin");byte[] bytes=new byte[32];Vault.RNG.nextBytes(bytes);Files.write(key,bytes);Files.setPosixFilePermissions(key,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));open();}
    void open(){context=new SpringApplicationBuilder(CoreApplication.class).web(WebApplicationType.NONE).run("--spring.datasource.url="+postgres.getJdbcUrl("postgres","postgres"),"--spring.datasource.username=postgres","--spring.datasource.password=","--jarvis.key-file="+key,"--jarvis.mode=mock","--jarvis.worker.enabled=false","--spring.main.banner-mode=off");db=context.getBean(JdbcTemplate.class);auth=context.getBean(AuthService.class);profiles=context.getBean(OwnerProfileService.class);flow=context.getBean(WorkflowService.class);}
    @AfterAll void stop() throws Exception{if(context!=null)context.close();if(postgres!=null)postgres.close();if(key!=null)Files.deleteIfExists(key);}
    @BeforeEach void reset(){db.execute("TRUNCATE owners,bootstrap_tokens,login_limits,crypto_nonces CASCADE");owner=UUID.randomUUID();db.update("INSERT INTO owners(id,login_name,password_hash) VALUES (?,'profile-owner','synthetic-unused')",owner);token=auth.issue(owner,true,null).token();session=auth.find(token);}
    JsonNode body(Object value){return Json.MAPPER.valueToTree(value);}
    Map<String,Object> patch(long version,String field,Object value){Map<String,Object> fields=new LinkedHashMap<>();fields.put("expected_version",version);fields.put(field,value);return profiles.patch(session,UUID.randomUUID(),body(fields));}
    void code(String expected,org.junit.jupiter.api.function.Executable call){assertEquals(expected,assertThrows(ApiException.class,call).code);}
    long count(String table){return db.queryForObject("SELECT count(*) FROM "+table,Long.class);}
    @SuppressWarnings("unchecked") Map<String,Object> profile(Map<String,Object> response){return (Map<String,Object>)response.get("profile");}
    @SuppressWarnings("unchecked") Map<String,Object> mutation(Map<String,Object> response){return (Map<String,Object>)response.get("mutation");}

    @Test void legacyDefaultsAreProjectionWithoutWritesOrInferredName(){var me=profiles.get(session);assertNull(me.get("display_name"));assertEquals("ko-KR",me.get("locale"));assertEquals("UTC",me.get("timezone"));assertEquals("profile-owner",me.get("login_name"));assertEquals(1L,me.get("version"));assertNull(me.get("profile_updated_at"));assertEquals(0,count("owner_profile_mutations"));assertNull(db.queryForObject("SELECT profile_ref FROM owners WHERE id=?",String.class,owner));var effects=(Map<?,?>)me.get("profile_effects");assertEquals(false,effects.get("chat_personalization"));assertEquals(false,effects.get("ui_translation"));assertEquals(false,effects.get("scheduling"));assertEquals("UTC",effects.get("inference_timezone"));}
    @Test void encryptedPatchRoundTripAndOnlySubmittedFieldsChange(){
        var request=body(Map.of("expected_version",1,"display_name","Synthetic Private Name","locale","en-US","timezone","Asia/Seoul"));var response=profiles.patch(session,UUID.randomUUID(),request);assertEquals(2L,profile(response).get("version"));assertEquals("Synthetic Private Name",profile(response).get("display_name"));assertEquals(List.of("display_name","locale","timezone"),mutation(response).get("updated_fields"));
        var next=patch(2,"timezone","America/New_York");assertEquals("en-US",profile(next).get("locale"));assertEquals("Synthetic Private Name",profile(next).get("display_name"));assertEquals(3L,profile(next).get("version"));assertEquals(List.of("timezone"),mutation(next).get("updated_fields"));
        for(String table:List.of("owners","owner_profile_mutations","audit_events")){String dump=Json.canonical(db.queryForList("SELECT * FROM "+table));assertFalse(dump.contains("Synthetic Private Name"));assertFalse(dump.contains("America/New_York"));assertFalse(dump.contains("Asia/Seoul"));}
        assertEquals(1L,db.queryForObject("SELECT security_epoch FROM owners WHERE id=?",Long.class,owner));assertEquals(false,profiles.get(session).get("execution_paused"));assertEquals(0,count("jobs"));assertEquals(0,count("runs"));assertEquals(0,count("memory_write_intents"));
    }
    @Test void nullNameClearsWhileOmittedFieldsRemain(){patch(1,"display_name","Synthetic");patch(2,"display_name",null);var me=profiles.get(session);assertNull(me.get("display_name"));assertEquals("ko-KR",me.get("locale"));assertEquals("UTC",me.get("timezone"));assertEquals(3L,me.get("version"));}
    @Test void sameKeyReplayUsesCurrentProfileAndOriginalMetadataOnly(){
        UUID key=UUID.randomUUID();var first=body(Map.of("expected_version",1,"display_name","Synthetic Original"));var result=profiles.patch(session,key,first);patch(2,"display_name","Synthetic Newer");var replay=profiles.patch(session,key,first);assertEquals(mutation(result),mutation(replay));assertEquals(2L,mutation(replay).get("version"));assertEquals(3L,profile(replay).get("version"));assertEquals("Synthetic Newer",profile(replay).get("display_name"));assertEquals(mutation(result),profiles.reconcile(session,key));assertFalse(Json.canonical(profiles.reconcile(session,key)).contains("Synthetic"));assertEquals(2,count("owner_profile_mutations"));
        code("IDEMPOTENCY_CONFLICT",()->profiles.patch(session,key,body(Map.of("expected_version",1,"display_name","Different"))));
    }
    @Test void receiptReadIsNonMutatingAndUnknownDoesNotMeanFailed(){var missing=profiles.reconcile(session,UUID.randomUUID());assertEquals("NOT_OBSERVED",missing.get("status"));assertNull(missing.get("owner_id"));assertNull(missing.get("version"));assertNull(missing.get("updated_fields"));assertEquals(0,count("owner_profile_mutations"));assertEquals(1L,profiles.get(session).get("version"));}
    @Test void ordinaryProfileEditRequiresNoRecentReauthAndWorksPaused(){
        flow.pause(session,true);var old=session;session=auth.find(token);assertEquals(2L,profiles.get(session).get("version"));code("SESSION_REVOKED",()->profiles.get(old));
        var conflict=assertThrows(OwnerProfileService.ProfileConflict.class,()->patch(1,"locale","en-US"));assertEquals(2,conflict.currentVersion);
        db.update("UPDATE sessions SET reauthenticated_at=NULL WHERE token_hash=?",session.hash());var saved=patch(2,"locale","en-US");assertEquals(true,profile(saved).get("execution_paused"));assertEquals(3L,profile(saved).get("version"));assertEquals(2L,db.queryForObject("SELECT security_epoch FROM owners WHERE id=?",Long.class,owner));assertNull(db.queryForObject("SELECT reauthenticated_at FROM sessions WHERE token_hash=?",Object.class,session.hash()));
    }
    @Test void pauseResumeShareVersionButProfileDoesNotChangeSecurityEpoch(){patch(1,"locale","en-US");flow.pause(session,true);session=auth.find(token);assertEquals(3L,profiles.get(session).get("version"));flow.pause(session,false);session=auth.find(token);var me=profiles.get(session);assertEquals(4L,me.get("version"));assertEquals(false,me.get("execution_paused"));assertEquals("en-US",me.get("locale"));assertEquals(4,assertThrows(OwnerProfileService.ProfileConflict.class,()->patch(2,"timezone","Asia/Seoul")).currentVersion);}
    @Test void rotatedSessionCanReconcileButCannotReplayOldWrite(){UUID key=UUID.randomUUID();var request=body(Map.of("expected_version",1,"locale","en-US"));var first=profiles.patch(session,key,request);var old=session;token=auth.issue(owner,true,session.hash()).token();session=auth.find(token);assertEquals(mutation(first),profiles.reconcile(session,key));code("MUTATION_SESSION_CHANGED",()->profiles.patch(session,key,request));code("SESSION_REVOKED",()->profiles.get(old));assertEquals(2L,profiles.get(session).get("version"));}
    @Test void revokedStaleExpiredAndUnknownSessionsFailBeforeDecrypt(){patch(1,"display_name","Synthetic Private");var unknown=new AuthService.Session(session.hash(),UUID.randomUUID(),Instant.now(),session.epoch());code("SESSION_REVOKED",()->profiles.get(unknown));code("SESSION_REVOKED",()->profiles.reconcile(unknown,UUID.randomUUID()));db.update("UPDATE sessions SET last_seen_at=clock_timestamp()-interval '3 hours' WHERE token_hash=?",session.hash());code("SESSION_REVOKED",()->profiles.get(session));db.update("UPDATE sessions SET last_seen_at=clock_timestamp() WHERE token_hash=?",session.hash());db.update("UPDATE owners SET security_epoch=security_epoch+1 WHERE id=?",owner);code("SESSION_REVOKED",()->patch(2,"timezone","Asia/Seoul"));assertEquals(1,count("owner_profile_mutations"));}
    @Test void strictFieldsTypesBoundsAndSharedUnicodeConvention(){
        for(var request:List.of(Map.of("expected_version",1),Map.of("expected_version",1,"execution_paused",false),Map.of("expected_version",1,"security_epoch",2),Map.of("expected_version",1,"login_name","another"),Map.of("expected_version",0,"locale","en-US"),Map.of("expected_version",9007199254740992L,"locale","en-US"),Map.of("expected_version",1.5,"locale","en-US")))code("INVALID_REQUEST",()->profiles.patch(session,UUID.randomUUID(),body(request)));
        for(String name:List.of(""," ","\u00a0","\u0085","\u2007","\u202f","\ufeff","x\n","x\u0000y","\ud800","x".repeat(81)))code("INVALID_REQUEST",()->patch(1,"display_name",name));
        for(String locale:List.of("en-us","ko-kr","EN-US","ko","fr-FR","en-US "))code("INVALID_REQUEST",()->patch(1,"locale",locale));
        var invalid=body(new LinkedHashMap<String,Object>(){{put("expected_version",1);put("locale",null);}});code("INVALID_REQUEST",()->profiles.patch(session,UUID.randomUUID(),invalid));
        assertDoesNotThrow(()->patch(1,"display_name","😀".repeat(80)));assertEquals("😀".repeat(80),profiles.get(session).get("display_name"));
    }
    @SuppressWarnings("unchecked") @Test void exactTimezoneOptionsRejectOffsetsAndAbbreviations(){
        var options=profiles.options(session);assertEquals(List.of("ko-KR","en-US"),options.get("locales"));var zones=(List<String>)options.get("timezones");assertEquals(zones.stream().sorted().toList(),zones);assertTrue(zones.containsAll(List.of("UTC","Etc/UTC","Asia/Seoul","America/New_York")));assertFalse(zones.stream().anyMatch(z->z.startsWith("SystemV/")));assertFalse(options.get("tzdb_version").toString().isBlank());
        for(String zone:List.of("+09:00","UTC+09:00","GMT+09:00","CST","EST","Asia/seoul"," Asia/Seoul","Asia/Seoul ","Unknown/Zone","SystemV/EST5EDT"))code("INVALID_TIMEZONE",()->patch(1,"timezone",zone));
        assertEquals("Etc/UTC",profile(patch(1,"timezone","Etc/UTC")).get("timezone"));
    }
    @Test void safeVersionExhaustionIsFailClosed(){db.update("UPDATE owners SET version=? WHERE id=?",OwnerProfileService.MAX,owner);code("VERSION_EXHAUSTED",()->patch(OwnerProfileService.MAX,"locale","en-US"));assertEquals(0,count("owner_profile_mutations"));assertEquals(OwnerProfileService.MAX,profiles.get(session).get("version"));}
    @Test void auditFailureRollsBackProfileVersionAndReceipt(){db.execute("ALTER TABLE audit_events ADD CONSTRAINT profile_test_failure CHECK(event_type<>'OWNER_PROFILE_UPDATED')");try{assertThrows(Exception.class,()->patch(1,"display_name","Synthetic Rollback"));assertNull(profiles.get(session).get("display_name"));assertEquals(1L,profiles.get(session).get("version"));assertEquals(0,count("owner_profile_mutations"));}finally{db.execute("ALTER TABLE audit_events DROP CONSTRAINT profile_test_failure");}}
    @Test void restartRetainsEncryptedProfileAndReceipt(){UUID key=UUID.randomUUID();var saved=profiles.patch(session,key,body(Map.of("expected_version",1,"timezone","Asia/Seoul")));context.close();open();assertEquals("Asia/Seoul",profiles.get(session).get("timezone"));assertEquals(mutation(saved),profiles.reconcile(session,key));assertEquals(2L,profiles.get(session).get("version"));}
    @Test void concurrentSameKeyCommitsExactlyOnce() throws Exception{UUID key=UUID.randomUUID();var request=body(Map.of("expected_version",1,"locale","en-US"));try(var pool=Executors.newFixedThreadPool(6)){List<Callable<Map<String,Object>>> jobs=new ArrayList<>();for(int i=0;i<6;i++)jobs.add(()->profiles.patch(session,key,request));Object first=null;for(var future:pool.invokeAll(jobs,30,TimeUnit.SECONDS)){assertFalse(future.isCancelled());var result=mutation(future.get());if(first==null)first=result;else assertEquals(first,result);}}assertEquals(1,count("owner_profile_mutations"));assertEquals(2L,profiles.get(session).get("version"));}
    @Test void concurrentDifferentKeysSameVersionHaveOneWinner() throws Exception{var request=body(Map.of("expected_version",1,"timezone","Asia/Seoul"));try(var pool=Executors.newFixedThreadPool(2)){var work=List.<Callable<Boolean>>of(()->{try{profiles.patch(session,UUID.randomUUID(),request);return true;}catch(OwnerProfileService.ProfileConflict e){return false;}},()->{try{profiles.patch(session,UUID.randomUUID(),request);return true;}catch(OwnerProfileService.ProfileConflict e){return false;}});int wins=0;for(var future:pool.invokeAll(work,30,TimeUnit.SECONDS)){assertFalse(future.isCancelled());if(future.get())wins++;}assertEquals(1,wins);}assertEquals(1,count("owner_profile_mutations"));}
    @Test void ownerLockSerializesLogoutBeforePendingPatch() throws Exception{CountDownLatch started=new CountDownLatch(1);try(var pool=Executors.newSingleThreadExecutor()){var pending=new java.util.concurrent.atomic.AtomicReference<Future<String>>();profiles.writeTx.executeWithoutResult(tx->{db.queryForObject("SELECT id FROM owners WHERE id=? FOR UPDATE",UUID.class,owner);pending.set(pool.submit(()->{started.countDown();try{patch(1,"locale","en-US");return "COMMITTED";}catch(ApiException e){return e.code;}}));try{assertTrue(started.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}auth.logout(session);});assertEquals("SESSION_REVOKED",pending.get().get(15,TimeUnit.SECONDS));assertEquals(0,count("owner_profile_mutations"));}}
    @Test void profileTimestampsUseDatabaseClockUnderApplicationSkew(){Instant real=AuthService.instant(db.queryForObject("SELECT clock_timestamp()",Object.class));long version=1;for(Instant skew:List.of(real.minusSeconds(86400),real.plusSeconds(86400))){try(var mocked=org.mockito.Mockito.mockStatic(Instant.class,org.mockito.Mockito.CALLS_REAL_METHODS)){mocked.when(Instant::now).thenReturn(skew);var result=patch(version++,"locale","en-US");Instant updated=Instant.parse(profile(result).get("profile_updated_at").toString()),server=Instant.parse(profile(result).get("server_time").toString());assertFalse(updated.isBefore(real));assertFalse(server.isBefore(updated));assertTrue(Duration.between(real,server).abs().toSeconds()<30);}}}
    @Test void knownInstantsDisplayDstOffsetsWithoutCreatingLocalSchedules(){
        patch(1,"timezone","America/New_York");ZoneId zone=ZoneId.of(profiles.get(session).get("timezone").toString());var before=Instant.parse("2024-03-10T06:59:00Z").atZone(zone);var after=Instant.parse("2024-03-10T07:00:00Z").atZone(zone);assertEquals(ZoneOffset.ofHours(-5),before.getOffset());assertEquals(ZoneOffset.ofHours(-4),after.getOffset());assertEquals(1,before.getHour());assertEquals(3,after.getHour());
        var first=Instant.parse("2024-11-03T05:30:00Z").atZone(zone);var second=Instant.parse("2024-11-03T06:30:00Z").atZone(zone);assertEquals(first.toLocalDateTime(),second.toLocalDateTime());assertNotEquals(first.getOffset(),second.getOffset());assertEquals(0,count("jobs"));assertEquals(0,count("inference_reservations"));
    }
    @Test void sharedPatchFixturesAgreeWithServerValidation() throws Exception{
        var cases=Json.parse(Files.readAllBytes(Path.of("../../packages/contracts/fixtures/owner-profile/cases.json")));
        for(var item:cases){if(!item.path("schema").asText().equals("owner-profile-patch-request"))continue;if(item.path("valid").asBoolean())assertDoesNotThrow(()->profiles.validate(item.get("instance")),item.path("name").asText());else assertThrows(ApiException.class,()->profiles.validate(item.get("instance")),item.path("name").asText());}
    }

    @Test void ownerLockSerializesRotationAndPauseBeforePendingPatch() throws Exception{
        for(boolean pause:List.of(false,true)){
            token=auth.issue(owner,true,null).token();session=auth.find(token);final AuthService.Session current=session;long version=((Number)profiles.get(current).get("version")).longValue();var request=body(Map.of("expected_version",version,"locale","en-US"));CountDownLatch started=new CountDownLatch(1);
            try(var pool=Executors.newSingleThreadExecutor()){
                var pending=new java.util.concurrent.atomic.AtomicReference<Future<String>>();profiles.writeTx.executeWithoutResult(tx->{
                    db.queryForObject("SELECT id FROM owners WHERE id=? FOR UPDATE",UUID.class,owner);
                    pending.set(pool.submit(()->{started.countDown();try{profiles.patch(current,UUID.randomUUID(),request);return "COMMITTED";}catch(ApiException e){return e.code;}}));
                    try{assertTrue(started.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                    if(pause)flow.pause(current,true);else auth.issue(owner,true,current.hash());
                });
                assertEquals("SESSION_REVOKED",pending.get().get(15,TimeUnit.SECONDS));assertEquals(0,count("owner_profile_mutations"));assertNull(db.queryForObject("SELECT profile_ref FROM owners WHERE id=?",String.class,owner));
            }
        }
    }

    @Test void integratedGetMeUsesPersistedProfileAndStrictQuery() throws Exception{
        patch(1,"timezone","Asia/Seoul");
        var http=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(context.getBean(ApiController.class),context.getBean(OwnerProfileController.class)).setControllerAdvice(new ApiErrors()).addFilters(context.getBean(WebSecurity.class)).build();
        var cookie=new jakarta.servlet.http.Cookie(WebSecurity.COOKIE,token);var response=http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/me").cookie(cookie)).andReturn().getResponse();assertEquals(200,response.getStatus());var data=Json.parse(response.getContentAsByteArray()).get("data");assertEquals("Asia/Seoul",data.get("timezone").asText());assertEquals(2,data.get("version").asLong());assertEquals("Asia/Seoul",data.get("profile_effects").get("inference_timezone").asText());
        assertEquals(400,http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/me?scope=GLOBAL").cookie(cookie)).andReturn().getResponse().getStatus());assertEquals(1,count("owner_profile_mutations"));
    }

}

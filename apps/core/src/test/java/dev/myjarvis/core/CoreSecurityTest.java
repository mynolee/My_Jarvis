package dev.myjarvis.core;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CoreSecurityTest {
    EmbeddedPostgres postgres;ConfigurableApplicationContext context;JdbcTemplate db;AuthService auth;WorkflowService flow;MockWorker worker;Path key;
    static final String PASSWORD="Correct horse battery mock 123!";
    @BeforeAll void start()throws Exception {
        postgres=EmbeddedPostgres.builder().setPort(0).start();key=Files.createTempFile("jarvis-test-key-", ".bin");byte[] material=new byte[32];Vault.RNG.nextBytes(material);Files.write(key,material);Files.setPosixFilePermissions(key,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        context=new SpringApplicationBuilder(CoreApplication.class).web(WebApplicationType.NONE).run("--spring.datasource.url="+postgres.getJdbcUrl("postgres","postgres"),"--spring.datasource.username=postgres","--spring.datasource.password=","--jarvis.key-file="+key,"--jarvis.mode=mock","--jarvis.worker.enabled=false","--spring.main.banner-mode=off");
        db=context.getBean(JdbcTemplate.class);int serverVersion=Integer.parseInt(db.queryForObject("SHOW server_version_num",String.class));assertTrue(serverVersion>=170000&&serverVersion<180000,"Integration tests require actual PostgreSQL 17");auth=context.getBean(AuthService.class);flow=context.getBean(WorkflowService.class);worker=context.getBean(MockWorker.class);
    }
    @AfterAll void stop()throws Exception {if(context!=null)context.close();if(postgres!=null)postgres.close();if(key!=null)Files.deleteIfExists(key);}
    @BeforeEach void clear(){db.execute("TRUNCATE owners,bootstrap_tokens,login_limits,crypto_nonces CASCADE");}
    record Owner(UUID id,AuthService.Session session){}
    Owner owner(){String token=Vault.token();db.update("INSERT INTO bootstrap_tokens(token_hash,expires_at) VALUES (?,now()+interval '15 minutes')",Json.hash(token));UUID owner=auth.bootstrap(token,"owner",PASSWORD);var issued=auth.issue(owner,true,null);return new Owner(owner,auth.find(issued.token()));}
    JsonNode json(Object value){return Json.MAPPER.valueToTree(value);}
    UUID conversation(Owner o){return (UUID)flow.createConversation(o.id,json(Map.of("title","Synthetic conversation"))).get("id");}
    UUID run(Owner o,String text){UUID c=conversation(o);UUID r=(UUID)flow.accept(o.id,c,json(Map.of("client_message_id",UUID.randomUUID().toString(),"text",text,"attachment_ids",List.of()))).get("run_id");assertTrue(worker.runOne());return r;}
    Map<String,Object> approval(Owner o,UUID run){return flow.approval(o.id,(UUID)flow.run(o.id,run).get("approval_id"));}
    JsonNode approve(Map<String,Object> a){return json(Map.of("decision","APPROVE","expected_action_version",a.get("action_version"),"payload_hash",a.get("payload_hash")));}
    long count(String table){return db.queryForObject("SELECT count(*) FROM "+table,Long.class);}

    @Test void bootstrapOnlyOnceAndWrongAttemptsPersist(){String token=Vault.token();db.update("INSERT INTO bootstrap_tokens(token_hash,expires_at) VALUES (?,now()+interval '15 minutes')",Json.hash(token));for(int i=0;i<5;i++)assertThrows(ApiException.class,()->auth.bootstrap(Vault.token(),"owner",PASSWORD));assertEquals(5,db.queryForObject("SELECT attempts FROM bootstrap_tokens",Integer.class));assertThrows(ApiException.class,()->auth.bootstrap(token,"owner",PASSWORD));assertEquals(0,count("owners"));}
    @Test void concurrentBootstrapCreatesOneOwner()throws Exception {String token=Vault.token();db.update("INSERT INTO bootstrap_tokens(token_hash,expires_at) VALUES (?,now()+interval '15 minutes')",Json.hash(token));try(var pool=Executors.newFixedThreadPool(2)){var tasks=List.<Callable<Boolean>>of(()->{try{auth.bootstrap(token,"owner",PASSWORD);return true;}catch(ApiException e){return false;}},()->{try{auth.bootstrap(token,"owner",PASSWORD);return true;}catch(ApiException e){return false;}});long successes=0;for(var f:pool.invokeAll(tasks))if(f.get())successes++;assertEquals(1,successes);}assertEquals(1,count("owners"));assertEquals(1,db.queryForObject("SELECT count(*) FROM bootstrap_tokens WHERE consumed_at IS NOT NULL",Long.class));}
    @Test void passwordHashAndSessionRotation(){Owner o=owner();String hash=db.queryForObject("SELECT password_hash FROM owners",String.class);assertTrue(hash.startsWith("$argon2id$"));assertFalse(hash.contains(PASSWORD));var first=auth.issue(o.id,true,null);var second=auth.issue(o.id,true,Json.hash(first.token()));assertNull(auth.find(first.token()));assertNotNull(auth.find(second.token()));assertNotEquals(first.csrf(),second.csrf());db.update("UPDATE sessions SET created_at=now()-interval '13 hours' WHERE token_hash=?",Json.hash(second.token()));assertNull(auth.find(second.token()));}
    @Test void repeatedLoginFailureRateLimited(){owner();for(int i=0;i<5;i++)assertThrows(ApiException.class,()->auth.login("owner","not the valid password","127.0.0.1"));ApiException e=assertThrows(ApiException.class,()->auth.login("owner",PASSWORD,"127.0.0.1"));assertEquals(429,e.status);}
    @Test void strictJsonRejectsDuplicateUnknownFloatTrailingAndMalformedUtf8(){assertThrows(ApiException.class,()->Json.parse("{\"x\":1,\"x\":2}".getBytes()));assertThrows(ApiException.class,()->Json.parse("{\"x\":1.5}".getBytes()));assertThrows(ApiException.class,()->Json.parse("{} {}".getBytes()));assertThrows(ApiException.class,()->Json.parse(new byte[]{(byte)0xc3,0x28}));assertThrows(ApiException.class,()->Json.fields(json(Map.of("owner_id","spoof")),"text"));assertThrows(ApiException.class,()->Json.parse(new byte[262145]));}
    @Test void canonicalIntegersUnicodeAndTamper(){String canonical=Json.canonical(json(Map.of("z",1,"a","한글\n","zero",0)));assertEquals("{\"a\":\"한글\\n\",\"z\":1,\"zero\":0}",canonical);assertNotEquals(Json.hash(canonical),Json.hash(canonical+" "));}
    @Test void encryptedMessageDedupAndOwnerBoundary(){Owner o=owner();UUID c=conversation(o),client=UUID.randomUUID();var request=json(Map.of("client_message_id",client.toString(),"text","synthetic private marker","attachment_ids",List.of()));var first=flow.accept(o.id,c,request);var repeat=flow.accept(o.id,c,request);assertEquals(first,repeat);assertEquals(1,count("runs"));assertEquals(1,count("messages"));assertEquals(1,count("jobs"));assertFalse(db.queryForObject("SELECT content_ref FROM messages",String.class).contains("synthetic private marker"));assertThrows(ApiException.class,()->flow.accept(o.id,c,json(Map.of("client_message_id",client.toString(),"text","changed","attachment_ids",List.of()))));assertEquals(404,assertThrows(ApiException.class,()->flow.run(UUID.randomUUID(),(UUID)first.get("run_id"))).status);assertEquals("synthetic private marker",flow.messages(o.id,c).getFirst().get("text"));}
    @Test void maliciousProposalBlockedWithZeroEffects(){Owner o=owner();UUID r=run(o,"/deny");assertEquals("BLOCKED",flow.run(o.id,r).get("status"));assertEquals(0,count("authorizations"));assertEquals(0,count("mock_effects"));}
    @Test void approvalTamperFailsClosed(){Owner o=owner();UUID r=run(o,"/calendar");var a=approval(o,r);var forged=json(Map.of("decision","APPROVE","expected_action_version",a.get("action_version"),"payload_hash","0".repeat(64)));assertEquals("PAYLOAD_CHANGED",assertThrows(ApiException.class,()->flow.decision(o.session,(UUID)a.get("id"),forged)).code);assertEquals(0,count("authorizations"));assertEquals(0,count("mock_effects"));assertEquals("PENDING",flow.approval(o.id,(UUID)a.get("id")).get("status"));}
    @Test void concurrentApprovalConsumesOnce()throws Exception {Owner o=owner();UUID r=run(o,"/calendar");var a=approval(o,r);try(var pool=Executors.newFixedThreadPool(20)){List<Callable<Boolean>> tasks=new ArrayList<>();for(int i=0;i<20;i++)tasks.add(()->{try{flow.decision(o.session,(UUID)a.get("id"),approve(a));return true;}catch(ApiException e){return false;}});long successful=0;for(var f:pool.invokeAll(tasks))if(f.get())successful++;assertEquals(1,successful);}assertEquals(1,count("authorizations"));assertEquals(1,db.queryForObject("SELECT count(*) FROM jobs WHERE kind='EXECUTE'",Long.class));assertTrue(worker.runOne());assertFalse(worker.runOne());assertEquals(1,count("mock_effects"));assertEquals("SUCCEEDED",flow.run(o.id,r).get("status"));}
    @Test void auditFailureRollsBackApprovalAndJob(){Owner o=owner();UUID r=run(o,"/calendar");var a=approval(o,r);db.execute("ALTER TABLE audit_events ADD CONSTRAINT test_fail CHECK(event_type <> 'APPROVAL_CONSUMED')");try{assertThrows(RuntimeException.class,()->flow.decision(o.session,(UUID)a.get("id"),approve(a)));assertEquals(0,count("authorizations"));assertEquals(0,db.queryForObject("SELECT count(*) FROM jobs WHERE kind='EXECUTE'",Long.class));assertEquals("PENDING",flow.approval(o.id,(UUID)a.get("id")).get("status"));}finally{db.execute("ALTER TABLE audit_events DROP CONSTRAINT test_fail");}}
    @Test void timeoutUnknownNeverRetriesAndReconcileOnlyReads(){Owner o=owner();UUID r=run(o,"/calendar-timeout");var a=approval(o,r);flow.decision(o.session,(UUID)a.get("id"),approve(a));worker.runOne();assertEquals("UNKNOWN",flow.run(o.id,r).get("status"));worker.recover();assertFalse(worker.runOne());assertEquals(1,count("mock_effects"));assertEquals("SUCCEEDED",worker.reconcile(o.id,r).get("status"));assertEquals(1,count("mock_effects"));assertEquals(1,count("action_executions"));}
    @Test void pauseAndCancelPreventQueuedEffects(){Owner o=owner();UUID r=run(o,"/calendar");var a=approval(o,r);flow.decision(o.session,(UUID)a.get("id"),approve(a));flow.pause(o.session,true);assertFalse(worker.runOne());assertEquals(0,count("mock_effects"));assertEquals("CANCELLED",flow.run(o.id,r).get("status"));}
    @Test void cancelPendingApprovalRevokesIt(){Owner o=owner();UUID r=run(o,"/calendar");var a=approval(o,r);flow.cancel(o.id,r,((Number)flow.run(o.id,r).get("version")).longValue());assertEquals("REVOKED",flow.approval(o.id,(UUID)a.get("id")).get("status"));assertThrows(ApiException.class,()->flow.decision(o.session,(UUID)a.get("id"),approve(a)));assertEquals(0,count("mock_effects"));}
    @Test void expiredLeaseFencesOldGenerationAndNoRetryAfterStart(){Owner o=owner();UUID r=run(o,"/calendar");var a=approval(o,r);flow.decision(o.session,(UUID)a.get("id"),approve(a));var claim=worker.claim();db.update("UPDATE jobs SET started_effect=true,lease_until=now()-interval '1 second' WHERE id=?",claim.get("id"));worker.recover();assertEquals("UNKNOWN",flow.run(o.id,r).get("status"));assertFalse(worker.runOne());assertEquals(0,db.update("UPDATE jobs SET status='SUCCEEDED' WHERE id=? AND lease_generation=?",claim.get("id"),claim.get("lease_generation")));}
    @Test void aadPreventsCrossRecordDecryption(){Owner o=owner();UUID id=UUID.randomUUID();String cipher=flow.tx.execute(s->flow.vault.seal(o.id,id,"fixture","synthetic"));assertThrows(IllegalStateException.class,()->flow.vault.open(UUID.randomUUID(),id,"fixture",cipher));assertThrows(IllegalStateException.class,()->flow.vault.open(o.id,UUID.randomUUID(),"fixture",cipher));assertEquals("synthetic",flow.vault.open(o.id,id,"fixture",cipher));}
    @Test void sharedJcsVectorsMatchBytesAndHash()throws Exception {
        JsonNode vectors=Json.parse(Files.readAllBytes(Path.of("../../packages/contracts/fixtures/jcs-vectors.json")));
        for(JsonNode vector:vectors){String canonical=Json.canonical(vector.get("input"));assertEquals(vector.get("canonical").asText(),canonical);assertEquals(vector.get("utf8_hex").asText(),HexFormat.of().formatHex(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));assertEquals(vector.get("sha256").asText(),Json.hash(canonical));}
    }
    @Test void concurrentLoginReservationsNeverExceedFive()throws Exception {
        owner();try(var pool=Executors.newFixedThreadPool(20)){
            List<Callable<Integer>> calls=new ArrayList<>();for(int i=0;i<20;i++)calls.add(()->{try{auth.login("owner","wrong password long enough","192.0.2.1");return 200;}catch(ApiException e){return e.status;}});
            long admitted=0;for(var result:pool.invokeAll(calls))if(result.get()==401)admitted++;assertTrue(admitted<=5);
        }
        assertTrue(db.queryForObject("SELECT max(failures) FROM login_limits",Integer.class)<=5);
    }
    @Test void compositeOwnerFkRejectsCrossOwnerRelation(){
        Owner o=owner();UUID c=conversation(o),second=UUID.randomUUID();
        // Temporarily remove only the product singleton guard to create a synthetic second principal.
        db.execute("ALTER TABLE owners DROP CONSTRAINT owners_singleton_key");
        try {
            db.update("INSERT INTO owners(id,login_name,password_hash) VALUES (?,?,?)",second,"second","synthetic-unusable-hash");
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->db.update("INSERT INTO runs(id,owner_id,conversation_id,status) VALUES (?,?,?,'ACCEPTED')",UUID.randomUUID(),second,c));
            assertEquals(404,assertThrows(ApiException.class,()->flow.messages(second,c)).status);assertEquals(0,count("runs"));assertEquals(0,count("jobs"));
        }finally{db.update("DELETE FROM owners WHERE id=?",second);db.execute("ALTER TABLE owners ADD CONSTRAINT owners_singleton_key UNIQUE(singleton)");}
    }
    @Test void originAndCsrfRejectBeforeController()throws Exception {
        Owner o=owner();var issued=auth.issue(o.id,true,null);WebSecurity filter=context.getBean(WebSecurity.class);
        var request=new org.springframework.mock.web.MockHttpServletRequest("POST","/api/v1/conversations");request.setCookies(new jakarta.servlet.http.Cookie(WebSecurity.COOKIE,issued.token()));request.setContentType("application/json");request.setContent("{}".getBytes());request.addHeader("Origin","https://attacker.invalid");request.addHeader("X-CSRF-Token",issued.csrf());
        var response=new org.springframework.mock.web.MockHttpServletResponse();filter.doFilter(request,response,(a,b)->fail("Controller must not run"));assertEquals(403,response.getStatus());
        var good=new org.springframework.mock.web.MockHttpServletRequest("POST","/api/v1/conversations");good.setCookies(new jakarta.servlet.http.Cookie(WebSecurity.COOKIE,issued.token()));good.setContentType("application/json");good.setContent("{}".getBytes());good.addHeader("Origin","https://localhost:8443");good.addHeader("X-CSRF-Token",issued.csrf());
        var success=new org.springframework.mock.web.MockHttpServletResponse();java.util.concurrent.atomic.AtomicBoolean reached=new java.util.concurrent.atomic.AtomicBoolean();filter.doFilter(good,success,(a,b)->reached.set(true));assertTrue(reached.get());assertEquals("no-store",success.getHeader("Cache-Control"));
        var noCsrf=new org.springframework.mock.web.MockHttpServletRequest("POST","/api/v1/conversations");noCsrf.setCookies(new jakarta.servlet.http.Cookie(WebSecurity.COOKIE,issued.token()));noCsrf.setContentType("application/json");noCsrf.setContent("{}".getBytes());noCsrf.addHeader("Origin","https://localhost:8443");var denied=new org.springframework.mock.web.MockHttpServletResponse();filter.doFilter(noCsrf,denied,(a,b)->fail("Controller must not run"));assertEquals(403,denied.getStatus());
    }

    @Test void capturedSessionCannotApproveAfterLogout(){Owner o=owner();UUID r=run(o,"/calendar");var a=approval(o,r);auth.logout(o.session);assertEquals("SESSION_REVOKED",assertThrows(ApiException.class,()->flow.decision(o.session,(UUID)a.get("id"),approve(a))).code);assertEquals(0,count("authorizations"));assertThrows(ApiException.class,()->flow.pause(o.session,true));}

    @Test void untrustedProposalCannotChooseAuthorityOrUnregisteredTool(){
        Owner o=owner();
        var malicious=json(Map.of("tool_id","mail.send","tool_version","1","arguments",Map.of()));
        assertEquals("TOOL_NOT_ALLOWED",assertThrows(ApiException.class,()->ToolPolicy.validateProposal(malicious)).code);
        var spoof=json(Map.of("tool_id","calendar.event.create","tool_version","1","arguments",Map.of(),"approved",true,"owner_id",o.id.toString()));
        assertThrows(ApiException.class,()->ToolPolicy.validateProposal(spoof));assertEquals(0,count("authorizations"));assertEquals(0,count("jobs"));assertEquals(0,count("mock_effects"));
    }

    @Test void nonceReservationDoesNotBorrowExhaustedBusinessPool()throws Exception {
        Owner o=owner();UUID record=UUID.randomUUID();var primary=context.getBean(com.zaxxer.hikari.HikariDataSource.class);
        List<java.sql.Connection> held=new ArrayList<>();
        try(var executor=Executors.newSingleThreadExecutor()){
            for(int i=0;i<primary.getMaximumPoolSize();i++)held.add(primary.getConnection());
            var result=executor.submit(()->flow.vault.seal(o.id,record,"fixture","synthetic saturation"));
            String ciphertext=result.get(4,TimeUnit.SECONDS);assertEquals("synthetic saturation",flow.vault.open(o.id,record,"fixture",ciphertext));
        }finally{for(var connection:held)connection.close();}
        assertEquals(1,count("crypto_nonces"));
    }
    @Test void twentyConcurrentMessageAcceptsDoNotStarveNoncePool()throws Exception {
        Owner o=owner();UUID conversation=conversation(o);
        try(var pool=Executors.newFixedThreadPool(20)){
            List<Callable<Map<String,Object>>> work=new ArrayList<>();for(int i=0;i<20;i++){UUID client=UUID.randomUUID();work.add(()->flow.accept(o.id,conversation,json(Map.of("client_message_id",client.toString(),"text","synthetic concurrency","attachment_ids",List.of()))));}
            for(var task:pool.invokeAll(work,20,TimeUnit.SECONDS)){assertFalse(task.isCancelled());assertEquals("ACCEPTED",task.get().get("status"));}
        }
        assertEquals(20,count("messages"));assertEquals(20,count("runs"));assertEquals(20,count("crypto_nonces"));
    }
    @Test void nonceReservationSurvivesBusinessRollback(){
        Owner o=owner();UUID record=UUID.randomUUID();
        assertThrows(IllegalStateException.class,()->flow.tx.executeWithoutResult(s->{flow.vault.seal(o.id,record,"fixture","synthetic rollback");throw new IllegalStateException("injected rollback");}));
        assertEquals(1,count("crypto_nonces"));
    }


    LocalInferenceService local(java.util.concurrent.atomic.AtomicInteger calls,boolean uncertain){
        var env=new org.springframework.mock.env.MockEnvironment().withProperty("jarvis.local.manifest-hash","a".repeat(64));
        return new LocalInferenceService(flow,new LocalInferenceClient(env){
            @Override boolean ready(){return true;}
            @Override boolean configured(){return true;}
            @Override JsonNode infer(UUID owner,JsonNode body)throws Exception{calls.incrementAndGet();if(uncertain)throw new java.io.IOException("synthetic transport ambiguity");return LocalInferenceContractTest.response(body);}
        },context.getBean(InferenceContextService.class),context.getBean(InferenceContextFreshness.class));
    }
    @Test void localReservationDedupAndSuccessfulEncryptedResult(){
        Owner o=owner();UUID c=conversation(o);var calls=new java.util.concurrent.atomic.AtomicInteger();var local=local(calls,false);
        try{var body=json(Map.of("client_message_id",UUID.randomUUID().toString(),"text","Synthetic local question"));var result=local.accept(o.session,c,body);assertEquals(result,local.accept(o.session,c,body));assertEquals(1,count("inference_reservations"));assertEquals(0,count("jobs"));assertTrue(local.runOne());assertFalse(local.runOne());UUID run=(UUID)result.get("run_id");assertEquals("SUCCEEDED",flow.run(o.id,run).get("status"));assertEquals("LOCAL_INFERENCE",flow.run(o.id,run).get("execution_mode"));assertEquals("Synthetic answer",flow.run(o.id,run).get("result"));assertEquals(1,calls.get());}finally{local.close();}
    }
    @Test void localTransportAmbiguityKeepsReservationAndNeverRetries(){
        Owner o=owner();var calls=new java.util.concurrent.atomic.AtomicInteger();var local=local(calls,true);
        try{UUID run=(UUID)local.accept(o.session,conversation(o),json(Map.of("client_message_id",UUID.randomUUID().toString(),"text","Synthetic"))).get("run_id");local.runOne();local.recover();assertFalse(local.runOne());assertEquals("UNKNOWN",flow.run(o.id,run).get("status"));assertEquals(1,calls.get());assertEquals(60000,db.queryForObject("SELECT max_compute_ms FROM inference_reservations",Long.class));assertEquals("LOCAL_RECONCILIATION_DISABLED",assertThrows(ApiException.class,()->worker.reconcile(o.id,run)).code);}finally{local.close();}
    }
    @Test void localQueuedCancelMakesZeroModelCalls(){
        Owner o=owner();var calls=new java.util.concurrent.atomic.AtomicInteger();var local=local(calls,false);
        try{UUID run=(UUID)local.accept(o.session,conversation(o),json(Map.of("client_message_id",UUID.randomUUID().toString(),"text","Synthetic"))).get("run_id");flow.cancel(o.id,run,1);assertFalse(local.runOne());assertEquals("CANCELLED",flow.run(o.id,run).get("status"));assertEquals(0,calls.get());}finally{local.close();}
    }
    @Test void localConcurrentReservationsNeverExceedTwo()throws Exception {
        Owner o=owner();UUID c=conversation(o);var calls=new java.util.concurrent.atomic.AtomicInteger();var local=local(calls,false);
        try(var pool=Executors.newFixedThreadPool(12)){List<Callable<Boolean>> tasks=new ArrayList<>();for(int i=0;i<12;i++)tasks.add(()->{try{local.accept(o.session,c,json(Map.of("client_message_id",UUID.randomUUID().toString(),"text","Synthetic")));return true;}catch(ApiException e){assertEquals(429,e.status);return false;}});long admitted=0;for(var f:pool.invokeAll(tasks))if(f.get())admitted++;assertEquals(2,admitted);assertEquals(2,count("inference_reservations"));assertEquals(0,calls.get());}finally{local.close();}
    }
    @Test void localDailyBudgetStopsBeforeModelCall(){
        Owner o=owner();var calls=new java.util.concurrent.atomic.AtomicInteger();var local=local(calls,false);
        try{
            UUID c=conversation(o);local.accept(o.session,c,json(Map.of("client_message_id",UUID.randomUUID().toString(),"text","Synthetic")));local.runOne();
            // Real per-request ceilings stay immutable. Fill the UTC day with independent valid
            // synthetic reservations rather than changing a prior measured request's budget.
            for(int i=0;i<60;i++){
                UUID run=UUID.randomUUID();
                db.update("INSERT INTO runs(id,owner_id,conversation_id,status) VALUES (?,?,?,'SUCCEEDED')",run,o.id,c);
                db.update("INSERT INTO inference_reservations(id,owner_id,run_id,request_id,body_hash,model_manifest_hash,status,max_compute_ms,reserved_output_tokens,security_epoch) VALUES (?,?,?,?,?,?,'SUCCEEDED',60000,512,?)",UUID.randomUUID(),o.id,run,UUID.randomUUID(),"0".repeat(64),"a".repeat(64),o.session.epoch());
            }
            assertEquals("INFERENCE_BUDGET_EXCEEDED",assertThrows(ApiException.class,()->local.accept(o.session,c,json(Map.of("client_message_id",UUID.randomUUID().toString(),"text","Synthetic")))).code);
            assertEquals(1,calls.get());
        }finally{local.close();}
    }
}

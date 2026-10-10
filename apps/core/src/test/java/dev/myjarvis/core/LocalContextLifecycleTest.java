package dev.myjarvis.core;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import static org.junit.jupiter.api.Assertions.*;

/** Real service transactions and PostgreSQL, with a synthetic, non-network model boundary. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LocalContextLifecycleTest {
    EmbeddedPostgres postgres;
    ConfigurableApplicationContext app;
    JdbcTemplate db;
    Path key;
    AuthService auth;
    WorkflowService flow;
    MemoryService memories;
    MemoryIntentService intents;
    MemoryDeletionService deletion;
    OwnerProfileService profiles;
    InferenceContextService contexts;
    InferenceContextFreshness freshness;
    LocalInferenceService local;
    CapturingClient client;
    AuthService.Session session;
    UUID owner, conversation;

    @BeforeAll void start() throws Exception {
        postgres=EmbeddedPostgres.builder().setPort(0).start();
        key=Files.createTempFile("local-context-test-", ".bin");
        byte[] bytes=new byte[32];Vault.RNG.nextBytes(bytes);Files.write(key,bytes);
        Files.setPosixFilePermissions(key,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        app=new SpringApplicationBuilder(CoreApplication.class).web(WebApplicationType.NONE).run(
            "--spring.datasource.url="+postgres.getJdbcUrl("postgres","postgres"),
            "--spring.datasource.username=postgres","--spring.datasource.password=",
            "--jarvis.key-file="+key,"--jarvis.mode=mock","--jarvis.worker.enabled=false",
            "--jarvis.local.enabled=false","--spring.main.banner-mode=off");
        db=app.getBean(JdbcTemplate.class);auth=app.getBean(AuthService.class);flow=app.getBean(WorkflowService.class);
        memories=app.getBean(MemoryService.class);intents=app.getBean(MemoryIntentService.class);
        deletion=app.getBean(MemoryDeletionService.class);profiles=app.getBean(OwnerProfileService.class);
        contexts=app.getBean(InferenceContextService.class);freshness=app.getBean(InferenceContextFreshness.class);
    }
    @AfterAll void stop() throws Exception {
        if(app!=null)app.close();if(postgres!=null)postgres.close();if(key!=null)Files.deleteIfExists(key);
    }
    @BeforeEach void reset() {
        db.execute("TRUNCATE owners,bootstrap_tokens,login_limits,crypto_nonces CASCADE");
        owner=UUID.randomUUID();db.update("INSERT INTO owners(id,login_name,password_hash) VALUES (?,'local-context-owner','synthetic-unused')",owner);
        session=auth.find(auth.issue(owner,true,null).token());conversation=UUID.randomUUID();
        db.update("INSERT INTO conversations(id,owner_id,title) VALUES (?,?,'Synthetic context')",conversation,owner);
        client=new CapturingClient();local=new LocalInferenceService(flow,client,contexts,freshness);
    }
    @AfterEach void closeLocal(){if(local!=null)local.close();Thread.interrupted();}

    static final class CapturingClient extends LocalInferenceClient {
        int calls;JsonNode captured;Runnable duringInfer=()->{};boolean available=true;String answer="Synthetic answer";
        CapturingClient(){super(new MockEnvironment().withProperty("jarvis.local.manifest-hash","a".repeat(64)));}
        @Override boolean configured(){return true;}
        @Override boolean ready(){return available;}
        @Override JsonNode infer(UUID owner,JsonNode body) {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),"Model transport must run outside the database transaction");
            calls++;captured=body;duringInfer.run();return LocalInferenceContractTest.response(body).put("reply_text",answer);
        }
    }
    JsonNode node(Object value){return Json.MAPPER.valueToTree(value);}
    JsonNode input(UUID id,String text){return node(Map.of("client_message_id",id,"text",text));}
    UUID accept(String text){return (UUID)local.accept(session,conversation,input(UUID.randomUUID(),text)).get("run_id");}
    UUID id(Map<String,Object> value){return UUID.fromString(value.get("id").toString());}
    String status(UUID run){return db.queryForObject("SELECT status FROM runs WHERE id=?",String.class,run);}
    String reservationStatus(UUID run){return db.queryForObject("SELECT status FROM inference_reservations WHERE run_id=?",String.class,run);}
    long assistantCount(UUID run){return db.queryForObject("SELECT count(*) FROM messages WHERE run_id=? AND role='ASSISTANT'",Long.class,run);}
    Map<String,Object> intent(UUID target,JsonNode arguments){
        Map<String,Object> body=new LinkedHashMap<>();body.put("tool_id","memory.write");body.put("operation",target==null?"CREATE":"REVISE");body.put("target_id",target);body.put("arguments",arguments);
        return intents.issue(session,UUID.randomUUID(),node(body));
    }
    Map<String,Object> createMemory(String value) {
        Map<String,Object> fields=new LinkedHashMap<>();fields.put("scope","GLOBAL");fields.put("project_id",null);fields.put("key","synthetic.context.key");
        fields.put("kind","PREFERENCE");fields.put("value",value);fields.put("change_reason","Synthetic explicit form");fields.put("expected_generation",0);fields.put("revive_deleted",false);
        JsonNode arguments=node(fields);var authority=intent(null,arguments);
        var saved=memories.save(session,UUID.randomUUID(),null,node(Map.of("intent_id",authority.get("id"),"arguments",arguments)),true);
        return memories.get(session,UUID.fromString(saved.get("memory_id").toString()));
    }
    void reviseMemory(Map<String,Object> memory,String value) {
        var arguments=node(Map.of("value",value,"change_reason","Synthetic revision","expected_version",memory.get("version"),"expected_generation",memory.get("generation")));
        var authority=intent(id(memory),arguments);
        memories.save(session,UUID.randomUUID(),id(memory),node(Map.of("intent_id",authority.get("id"),"arguments",arguments)),false);
    }
    void deleteMemory(Map<String,Object> memory) {
        var prepared=deletion.prepare(session,UUID.randomUUID(),id(memory),node(Map.of("expected_version",memory.get("version"),"expected_generation",memory.get("generation"))));
        deletion.confirm(session,UUID.randomUUID(),id(prepared),node(Map.of("payload_hash",prepared.get("payload_hash"),"expected_version",prepared.get("expected_version"),"expected_generation",prepared.get("expected_generation"),"confirm",true)));
    }
    void profile(long version,String timezone){profiles.patch(session,UUID.randomUUID(),node(Map.of("expected_version",version,"timezone",timezone)));}
    void assertDiscarded(UUID run) {
        assertEquals("UNKNOWN",status(run));assertEquals("UNKNOWN",reservationStatus(run));assertEquals(0L,assistantCount(run));
        assertNull(db.queryForObject("SELECT result_ref FROM runs WHERE id=?",String.class,run));
        assertEquals(60000L,db.queryForObject("SELECT max_compute_ms FROM inference_reservations WHERE run_id=?",Long.class,run));
        assertEquals(512L,db.queryForObject("SELECT reserved_output_tokens FROM inference_reservations WHERE run_id=?",Long.class,run));
        assertEquals("INVALIDATED",db.queryForObject("SELECT state FROM inference_contexts WHERE run_id=?",String.class,run));
        assertFalse(local.runOne());assertEquals(1,client.calls);
    }

    @Test void realDispatchTransportsCurrentMemoryProfileAndPersistedAcceptedPair() {
        createMemory("Synthetic remembered value");profile(1,"Asia/Seoul");
        UUID first=accept("Synthetic previous question");assertTrue(local.runOne());assertEquals("SUCCEEDED",status(first));assertEquals(1L,assistantCount(first));
        UUID second=accept("Synthetic current question");assertTrue(local.runOne());
        var body=client.captured;assertEquals(2,client.calls);assertEquals("Synthetic current question",body.get("user_input").asText());
        assertEquals("Asia/Seoul",body.get("timezone").asText());assertEquals(2L,body.get("context").get("profile_version").asLong());
        assertEquals(1,body.get("memories").size());assertEquals("Synthetic remembered value",body.get("memories").get(0).get("value").asText());
        var history=body.get("history");assertEquals(2,history.size());assertEquals("USER",history.get(0).get("role").asText());assertEquals("Synthetic previous question",history.get(0).get("text").asText());
        assertEquals("ASSISTANT",history.get(1).get("role").asText());assertEquals("Synthetic answer",history.get(1).get("text").asText());
        for(var item:history){assertEquals(first.toString(),item.get("run_id").asText());assertEquals(1L,db.queryForObject("SELECT count(*) FROM messages WHERE id=? AND run_id=?",Long.class,UUID.fromString(item.get("id").asText()),first));}
        assertEquals("SUCCEEDED",status(second));assertEquals(1L,assistantCount(second));
    }
    @Test void publicAcceptRejectsEveryCallerSuppliedContextFieldBeforeReservation() {
        for(String field:List.of("context","memories","history","timezone","project_context","evidence","tool_results","allowed_tool_ids","current_time")){
            Map<String,Object> body=new LinkedHashMap<>();body.put("client_message_id",UUID.randomUUID());body.put("text","Synthetic question");body.put(field,List.of());
            assertEquals("INVALID_REQUEST",assertThrows(ApiException.class,()->local.accept(session,conversation,node(body))).code);
        }
        assertEquals(0L,db.queryForObject("SELECT count(*) FROM inference_reservations",Long.class));assertEquals(0,client.calls);
    }
    @Test void queuedLogoutCancelsWithoutModelCall() {
        UUID run=accept("Synthetic queued question");auth.logout(session);assertFalse(local.runOne());
        assertEquals("CANCELLED",status(run));assertEquals("CANCELLED",reservationStatus(run));assertEquals(0,client.calls);assertEquals(0L,assistantCount(run));
    }
    @Test void queuedMemoryRevisionCancelsWithoutModelCall() {
        var memory=createMemory("Synthetic original value");UUID run=accept("Synthetic queued question");reviseMemory(memory,"Synthetic revised value");
        assertFalse(local.runOne());assertEquals("CANCELLED",status(run));assertEquals("CANCELLED",reservationStatus(run));assertEquals(0,client.calls);
    }
    @Test void inFlightDeletionInterruptsAndDiscardsLateSuccessfulReply() {
        var memory=createMemory("Synthetic deletion value");UUID run=accept("Synthetic question");
        client.duringInfer=()->{assertEquals("Synthetic deletion value",client.captured.get("memories").get(0).get("value").asText());deleteMemory(memory);local.interruptInvalidContexts();assertTrue(Thread.currentThread().isInterrupted());};
        assertTrue(local.runOne());assertDiscarded(run);assertFalse(Thread.currentThread().isInterrupted());
        assertNull(db.queryForObject("SELECT payload_ref FROM memories WHERE id=?",String.class,id(memory)));
    }
    @Test void inFlightSessionRotationDiscardsLateSuccessfulReply() {
        UUID run=accept("Synthetic question");client.duringInfer=()->auth.issue(owner,true,session.hash());
        assertTrue(local.runOne());assertDiscarded(run);
    }
    @Test void inFlightProfileMutationDiscardsLateSuccessfulReply() {
        profile(1,"Asia/Seoul");UUID run=accept("Synthetic question");client.duringInfer=()->profile(2,"America/New_York");
        assertTrue(local.runOne());assertDiscarded(run);
    }
    @Test void firstProfileMutationAlsoRevokesDefaultTimezoneDispatch() {
        UUID run=accept("Synthetic question");client.duringInfer=()->profile(1,"Asia/Seoul");
        assertTrue(local.runOne());assertEquals("UTC",client.captured.get("timezone").asText());assertDiscarded(run);
    }
    @Test void failedDeletionAuditRollsBackEpochContextAndQueuedReservation() {
        var memory=createMemory("Synthetic rollback value");UUID run=accept("Synthetic question");
        long epoch=db.queryForObject("SELECT context_epoch FROM owners",Long.class),history=db.queryForObject("SELECT history_epoch FROM owners",Long.class);
        db.execute("ALTER TABLE audit_events ADD CONSTRAINT lifecycle_delete_failure CHECK(event_type<>'MEMORY_DELETED')");
        try {assertThrows(Exception.class,()->deleteMemory(memory));}
        finally {db.execute("ALTER TABLE audit_events DROP CONSTRAINT lifecycle_delete_failure");}
        assertEquals(epoch,db.queryForObject("SELECT context_epoch FROM owners",Long.class));assertEquals(history,db.queryForObject("SELECT history_epoch FROM owners",Long.class));
        assertEquals("QUEUED",status(run));assertEquals("QUEUED",reservationStatus(run));assertEquals("CAPTURED",db.queryForObject("SELECT state FROM inference_contexts WHERE run_id=?",String.class,run));
        assertEquals("Synthetic rollback value",memories.get(session,id(memory)).get("value"));assertTrue(local.runOne());assertEquals("SUCCEEDED",status(run));assertEquals(1,client.calls);
    }
    @Test void failedProfileAuditRollsBackContextAndRunState() {
        UUID run=accept("Synthetic question");long epoch=db.queryForObject("SELECT context_epoch FROM owners",Long.class);
        db.execute("ALTER TABLE audit_events ADD CONSTRAINT lifecycle_profile_failure CHECK(event_type<>'OWNER_PROFILE_UPDATED')");
        try {assertThrows(Exception.class,()->profile(1,"Asia/Seoul"));}
        finally {db.execute("ALTER TABLE audit_events DROP CONSTRAINT lifecycle_profile_failure");}
        assertEquals(epoch,db.queryForObject("SELECT context_epoch FROM owners",Long.class));assertEquals("QUEUED",status(run));assertEquals("QUEUED",reservationStatus(run));
        assertTrue(local.runOne());assertEquals("UTC",client.captured.get("timezone").asText());assertEquals("SUCCEEDED",status(run));
    }
    @Test void requestHashesAreOwnerHmacAndNoContextPlaintextSnapshotIsStored() {
        createMemory("Synthetic private contextual sentinel");UUID run=accept("Synthetic private input sentinel");assertTrue(local.runOne());
        String canonical=Json.canonical(client.captured),stored=db.queryForObject("SELECT body_hash FROM inference_reservations WHERE run_id=?",String.class,run);
        assertEquals(contexts.requestDigest(owner,canonical),stored);assertNotEquals(Json.hash(canonical),stored);
        for(String table:List.of("inference_contexts","inference_context_history","inference_context_memories","inference_reservations","messages","audit_events")){
            String dump=Json.canonical(db.queryForList("SELECT * FROM "+table));assertFalse(dump.contains("Synthetic private contextual sentinel"));assertFalse(dump.contains("Synthetic private input sentinel"));assertFalse(dump.contains(Json.hash(canonical)));
        }
    }
    @Test void idempotentReplayRequiresCurrentLiveSessionButNotModelAvailability() {
        JsonNode body=input(UUID.randomUUID(),"Synthetic repeat question");var accepted=local.accept(session,conversation,body);client.available=false;
        assertEquals(accepted,local.accept(session,conversation,body));auth.logout(session);
        assertEquals("SESSION_REVOKED",assertThrows(ApiException.class,()->local.accept(session,conversation,body)).code);
        session=auth.find(auth.issue(owner,true,null).token());var replay=local.accept(session,conversation,body);
        assertEquals(accepted.get("run_id"),replay.get("run_id"));assertEquals("CANCELLED",replay.get("status"));assertEquals(0,client.calls);
        assertEquals(1L,db.queryForObject("SELECT count(*) FROM inference_reservations",Long.class));
    }
    @Test void inFlightIdleSessionExpiryDiscardsLateSuccessfulReply() {
        UUID run=accept("Synthetic question");
        client.duringInfer=()->db.update("UPDATE sessions SET last_seen_at=clock_timestamp()-interval '3 hours' WHERE token_hash=?",session.hash());
        assertTrue(local.runOne());assertDiscarded(run);
    }
    @Test void acceptedHistoryRemainsAvailableAfterLogoutAndNextLogin() {
        UUID first=accept("Synthetic accepted question");assertTrue(local.runOne());
        auth.logout(session);session=auth.find(auth.issue(owner,true,null).token());
        UUID second=accept("Synthetic next-login question");assertTrue(local.runOne());
        assertEquals("SUCCEEDED",status(second));assertEquals(2,client.captured.get("history").size());
        assertEquals(first.toString(),client.captured.get("history").get(0).get("run_id").asText());
        assertEquals("Synthetic answer",client.captured.get("history").get(1).get("text").asText());
        assertEquals("SETTLED",db.queryForObject("SELECT state FROM inference_contexts WHERE run_id=?",String.class,first));
    }

    @Test void malformedSourceCiphertextBlocksBeforeModelAndCannotRetry() {
        var memory=createMemory("Synthetic source value");UUID run=accept("Synthetic question");
        db.update("UPDATE memories SET payload_ref='unreadable' WHERE id=?",id(memory));
        assertTrue(local.runOne());assertEquals("BLOCKED",status(run));assertEquals("CANCELLED",reservationStatus(run));
        assertEquals(0,client.calls);assertEquals(0L,assistantCount(run));assertFalse(local.runOne());assertEquals(0,client.calls);
        assertEquals(0L,db.queryForObject("SELECT count(*) FROM inference_context_memories",Long.class));
    }
    @Test void oversizedPersistedHistoryBlocksBeforeModelAndCannotRetry() {
        client.answer="Synthetic "+"a".repeat(15990);
        for(int i=0;i<3;i++){UUID prior=accept("Synthetic "+i+"q".repeat(7980));assertTrue(local.runOne());assertEquals("SUCCEEDED",status(prior));}
        UUID run=accept("Synthetic bounded question");assertTrue(local.runOne());
        assertEquals("BLOCKED",status(run));assertEquals("CANCELLED",reservationStatus(run));assertEquals(3,client.calls);
        assertEquals(0L,assistantCount(run));assertFalse(local.runOne());assertEquals(3,client.calls);
    }
    @Test void queuedIdleSessionExpiryCancelsWithoutModelCall() {
        UUID run=accept("Synthetic question");db.update("UPDATE sessions SET last_seen_at=clock_timestamp()-interval '3 hours' WHERE token_hash=?",session.hash());
        assertTrue(local.runOne());assertEquals("CANCELLED",status(run));assertEquals("CANCELLED",reservationStatus(run));
        assertEquals(0,client.calls);assertFalse(local.runOne());
    }

    UUID legacyRun(JsonNode body,boolean historyEligible,boolean withReservation) {
        UUID run=UUID.randomUUID(),message=UUID.randomUUID();
        String legacyHash=Json.hash(Json.canonical(Map.of("conversation_id",conversation.toString(),"mode","LOCAL_INFERENCE","body",body)));
        db.update("INSERT INTO runs(id,owner_id,conversation_id,status,context_history_eligible) VALUES (?,?,?,'SUCCEEDED',?)",run,owner,conversation,historyEligible);
        db.update("INSERT INTO messages(id,owner_id,conversation_id,run_id,client_message_id,body_hash,content_ref,role,trust) VALUES (?,?,?,?,?,?,?,'USER','USER_DIRECT')",
            message,owner,conversation,run,UUID.fromString(body.get("client_message_id").asText()),legacyHash,flow.vault.seal(owner,message,"message",body.get("text").asText()));
        if(withReservation)db.update("INSERT INTO inference_reservations(id,owner_id,run_id,request_id,body_hash,model_manifest_hash,status,max_compute_ms,reserved_output_tokens,security_epoch) VALUES (?,?,?,?,?,?,'SUCCEEDED',60000,512,?)",
            UUID.randomUUID(),owner,run,UUID.randomUUID(),legacyHash,client.manifestHash,session.epoch());
        return run;
    }
    @Test void legacyShaReplayReturnsOnlyExistingStatusWithoutEnqueueOrContextPreparation() {
        var body=input(UUID.randomUUID(),"Synthetic legacy request");UUID run=legacyRun(body,false,true);client.available=false;
        assertEquals(Map.of("run_id",run,"status","SUCCEEDED"),local.accept(session,conversation,body));
        assertEquals(1L,db.queryForObject("SELECT count(*) FROM runs",Long.class));assertEquals(1L,db.queryForObject("SELECT count(*) FROM messages",Long.class));
        assertEquals(1L,db.queryForObject("SELECT count(*) FROM inference_reservations",Long.class));assertEquals(0L,db.queryForObject("SELECT count(*) FROM inference_contexts",Long.class));
        assertFalse(local.runOne());assertEquals(0,client.calls);
    }
    @Test void legacyShaReplayRejectsChangedBody() {
        UUID message=UUID.randomUUID();var body=input(message,"Synthetic legacy request");legacyRun(body,false,true);
        var error=assertThrows(ApiException.class,()->local.accept(session,conversation,input(message,"Synthetic changed request")));
        assertEquals(409,error.status);assertEquals("IDEMPOTENCY_CONFLICT",error.code);assertEquals(0,client.calls);assertEquals(1L,db.queryForObject("SELECT count(*) FROM runs",Long.class));
    }
    @Test void newEligibleRunCannotUseLegacyPlainShaFallback() {
        var body=input(UUID.randomUUID(),"Synthetic new request");legacyRun(body,true,true);
        var error=assertThrows(ApiException.class,()->local.accept(session,conversation,body));assertEquals(409,error.status);assertEquals("IDEMPOTENCY_CONFLICT",error.code);
        assertEquals(0,client.calls);assertEquals(0L,db.queryForObject("SELECT count(*) FROM inference_contexts",Long.class));
    }
    @Test void legacyShaFallbackRequiresExistingInferenceReservation() {
        var body=input(UUID.randomUUID(),"Synthetic legacy request");legacyRun(body,false,false);
        assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(ApiException.class,()->local.accept(session,conversation,body)).code);
        assertEquals(0,client.calls);assertEquals(0L,db.queryForObject("SELECT count(*) FROM inference_reservations",Long.class));
    }
    @Test void legacyShaReplayStillRequiresLiveSessionAndMatchingConversation() {
        var body=input(UUID.randomUUID(),"Synthetic legacy request");legacyRun(body,false,true);
        UUID other=UUID.randomUUID();db.update("INSERT INTO conversations(id,owner_id,title) VALUES (?,?,'Synthetic other')",other,owner);
        assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(ApiException.class,()->local.accept(session,other,body)).code);
        auth.logout(session);
        assertEquals("SESSION_REVOKED",assertThrows(ApiException.class,()->local.accept(session,conversation,body)).code);
        session=auth.find(auth.issue(owner,true,null).token());
        assertEquals("SUCCEEDED",local.accept(session,conversation,body).get("status"));assertEquals(0,client.calls);
    }

}

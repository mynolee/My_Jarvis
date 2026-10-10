package dev.myjarvis.core;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Synthetic PostgreSQL and service barriers only. No provider, native model or real-user purge. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConversationDeletionBarriersTest {
    private EmbeddedPostgres postgres;
    private ConfigurableApplicationContext app;
    private Path key;
    private JdbcTemplate db;
    private WorkflowService flow;
    private AuthService auth;
    private ConversationHistoryService history;
    private ConversationDeletionService deletion;
    private InferenceContextService contexts;
    private InferenceContextFreshness freshness;
    private UsageService usage;
    private MockWorker worker;
    private LocalInferenceService local;
    private SyntheticClient client;
    private AuthService.Session session;
    private UUID owner;

    @BeforeAll void start() throws Exception {
        postgres=EmbeddedPostgres.builder().setPort(0).start();
        key=Files.createTempFile("conversation-barriers-", ".key");byte[] bytes=new byte[32];Vault.RNG.nextBytes(bytes);Files.write(key,bytes);
        Files.setPosixFilePermissions(key,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));startApp();
    }
    private void startApp() {
        app=new SpringApplicationBuilder(CoreApplication.class).web(WebApplicationType.NONE).run(
            "--spring.datasource.url="+postgres.getJdbcUrl("postgres","postgres"),"--spring.datasource.username=postgres","--spring.datasource.password=",
            "--jarvis.key-file="+key,"--jarvis.mode=mock","--jarvis.worker.enabled=false","--jarvis.local.enabled=false","--spring.main.banner-mode=off");
        db=app.getBean(JdbcTemplate.class);flow=app.getBean(WorkflowService.class);auth=app.getBean(AuthService.class);
        history=app.getBean(ConversationHistoryService.class);deletion=app.getBean(ConversationDeletionService.class);
        contexts=app.getBean(InferenceContextService.class);freshness=app.getBean(InferenceContextFreshness.class);
        usage=app.getBean(UsageService.class);worker=app.getBean(MockWorker.class);
    }
    @AfterAll void stop() throws Exception { if(app!=null)app.close();if(postgres!=null)postgres.close();if(key!=null)Files.deleteIfExists(key); }
    @BeforeEach void reset() {
        db.execute("TRUNCATE owners,bootstrap_tokens,login_limits,crypto_nonces CASCADE");
        owner=UUID.randomUUID();db.update("INSERT INTO owners(id,login_name,password_hash) VALUES (?,'synthetic-barriers-owner','synthetic-unused')",owner);
        session=auth.find(auth.issue(owner,true,null).token());client=new SyntheticClient();local=new LocalInferenceService(flow,client,contexts,freshness,usage);
    }
    @AfterEach void closeLocal() { if(local!=null)local.close();Thread.interrupted(); }
    private static final class SyntheticClient extends LocalInferenceClient {
        int calls;JsonNode request;Runnable duringInfer=()->{};boolean error;
        SyntheticClient(){super(new MockEnvironment().withProperty("jarvis.local.manifest-hash","a".repeat(64)));}
        @Override boolean configured(){return true;}
        @Override boolean ready(){return true;}
        @Override JsonNode infer(UUID owner,JsonNode body){
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());calls++;request=body;duringInfer.run();
            var answer=LocalInferenceContractTest.response(body);
            if(!error)return answer;
            return node(Map.of("kind","ERROR","error_code","MODEL_OUTPUT_INVALID","retryable",false,"usage",answer.get("usage")));
        }
    }
    private static JsonNode node(Object value){return Json.MAPPER.valueToTree(value);}
    private UUID conversation(){return (UUID)flow.createConversation(session,node(Map.of("title","Synthetic title sentinel"))).get("id");}
    private JsonNode mockInput(UUID client,String text){return node(Map.of("client_message_id",client,"text",text,"attachment_ids",List.of()));}
    private UUID mock(UUID conversation,UUID client,String text){return (UUID)flow.accept(session,conversation,mockInput(client,text)).get("run_id");}
    private UUID local(UUID conversation,String text){return (UUID)local.accept(session,conversation,node(Map.of("client_message_id",UUID.randomUUID(),"text",text))).get("run_id");}
    private Map<String,Object> prepare(UUID conversation){
        var state=deletion.state(session,conversation);assertEquals("ELIGIBLE",((Map<?,?>)state.get("eligibility")).get("status"));
        return deletion.prepare(session,UUID.randomUUID(),conversation,node(Map.of("expected_version",state.get("content_version"),"expected_digest",state.get("content_digest"))));
    }
    private JsonNode confirmation(Map<String,Object> prepared){return node(Map.of("payload_hash",prepared.get("payload_hash"),"expected_version",prepared.get("expected_version"),"expected_digest",prepared.get("expected_digest"),"confirm",true));}
    private Map<String,Object> erase(UUID conversation){var prepared=prepare(conversation);return deletion.confirm(session,UUID.randomUUID(),(UUID)prepared.get("id"),confirmation(prepared));}
    private ApiException failure(Runnable work,int status,String code){var error=assertThrows(ApiException.class,work::run);assertEquals(status,error.status);assertEquals(code,error.code);return error;}
    private void deleted(Runnable work){failure(work,410,"CONVERSATION_DELETED");}
    private long count(String table){return db.queryForObject("SELECT count(*) FROM "+table,Long.class);}
    private Map<String,Object> epochs(){return db.queryForMap("SELECT security_epoch,context_epoch,history_epoch,execution_paused FROM owners WHERE id=?",owner);}
    @SuppressWarnings("unchecked") private List<Map<String,Object>> items(Map<String,Object> page){return (List<Map<String,Object>>)page.get("items");}

    @Test void deletedTranscriptIsGatedBeforeDecryptReplayAndEveryCompatibilityPathAfterRestart(){
        UUID conversation=conversation(),clientId=UUID.randomUUID();JsonNode input=mockInput(clientId,"Synthetic question sentinel");
        UUID run=(UUID)flow.accept(session,conversation,input).get("run_id");assertTrue(worker.runOne());
        var page=history.messages(session,conversation,null,1);String cursor=(String)page.get("next_cursor");
        String event=((Map<?,?>)page.get("stream_cursor")).get("event_id").toString();assertNotNull(cursor);
        var epochs=epochs();erase(conversation);assertEquals(epochs,epochs());long nonces=count("crypto_nonces");
        assertTrue(items(history.conversations(session,null,20)).isEmpty());assertTrue(flow.conversations(owner).isEmpty());
        deleted(()->history.messages(session,conversation,cursor,20));deleted(()->history.messages(owner,conversation,null,20));
        deleted(()->history.events(session,conversation,event));deleted(()->history.events(owner,conversation,event));
        deleted(()->flow.messages(owner,conversation));deleted(()->flow.run(session,run));deleted(()->flow.run(owner,run));
        deleted(()->flow.accept(session,conversation,input));deleted(()->flow.accept(owner,conversation,input));
        deleted(()->local.accept(session,conversation,node(Map.of("client_message_id",clientId,"text","Synthetic question sentinel"))));
        deleted(()->flow.cancel(session,run,1));deleted(()->worker.reconcile(session,run));
        deleted(()->flow.tx.executeWithoutResult(s->contexts.authorize(session,conversation)));
        deleted(()->flow.tx.executeWithoutResult(s->flow.setRun(owner,run,"SUCCEEDED","Never persisted")));
        deleted(()->flow.tx.executeWithoutResult(s->flow.replies.appendAccepted(owner,run)));
        deleted(()->flow.tx.executeWithoutResult(s->flow.job(owner,run,null,"PLAN")));
        deleted(()->flow.tx.executeWithoutResult(s->flow.event(owner,run,"RUN_SUCCEEDED")));
        assertEquals(nonces,count("crypto_nonces"),"Tombstone must precede result encryption and nonce reservation");
        assertFalse(flow.tx.execute(s->freshness.valid(owner,run)));
        flow.tx.executeWithoutResult(s->freshness.invalidateRun(owner,run));
        UUID other=conversation();failure(()->flow.accept(session,other,input),409,"IDEMPOTENCY_CONFLICT");
        failure(()->local.accept(session,other,node(Map.of("client_message_id",clientId,"text","Synthetic question sentinel"))),409,"IDEMPOTENCY_CONFLICT");
        assertNull(db.queryForObject("SELECT result_ref FROM runs WHERE id=?",String.class,run));
        assertEquals(0L,db.queryForObject("SELECT count(*) FROM messages WHERE conversation_id=? AND (content_ref IS NOT NULL OR body_hash IS NOT NULL)",Long.class,conversation));
        // Recreate the entire Spring application against the same synthetic durable database.
        local.close();local=null;app.close();startApp();
        deleted(()->history.messages(session,conversation,cursor,20));deleted(()->flow.run(session,run));
        assertEquals("DELETED",deletion.state(session,conversation).get("status"));
        assertEquals(0,client.calls);assertEquals(epochs,epochs());
    }

    @Test void completedMockAuthorityCannotBeReplayedAndOtherWorkStillClaims(){
        UUID target=conversation(),run=mock(target,UUID.randomUUID(),"/calendar");assertTrue(worker.runOne());
        UUID approval=(UUID)flow.run(session,run).get("approval_id");var offered=flow.approval(session,approval);
        JsonNode decision=node(Map.of("decision","APPROVE","expected_action_version",offered.get("action_version"),"payload_hash",offered.get("payload_hash")));
        flow.decision(session,approval,decision);assertTrue(worker.runOne());assertEquals("SUCCEEDED",flow.run(session,run).get("status"));
        var staleJob=db.queryForMap("SELECT * FROM jobs WHERE run_id=? AND kind='EXECUTE'",run);
        erase(target);deleted(()->flow.approval(session,approval));deleted(()->flow.approval(owner,approval));deleted(()->flow.decision(session,approval,decision));
        assertTrue(flow.approvals(session).isEmpty());assertNotNull(db.queryForObject("SELECT revoked_at FROM authorizations",Object.class));
        assertNotNull(db.queryForObject("SELECT source_deleted_at FROM authorizations",Object.class));assertNull(db.queryForObject("SELECT payload_hash FROM authorizations",String.class));
        long effects=count("mock_effects");
        ReflectionTestUtils.invokeMethod(worker,"plan",staleJob);ReflectionTestUtils.invokeMethod(worker,"execute",staleJob);
        deleted(()->flow.tx.executeWithoutResult(s->ReflectionTestUtils.invokeMethod(worker,"finish",staleJob,"SUCCEEDED","Stale mock output")));
        worker.recover();assertFalse(worker.runOne());assertEquals(effects,count("mock_effects"));
        UUID other=conversation(),otherRun=mock(other,UUID.randomUUID(),"Keep unrelated work");assertTrue(worker.runOne());assertEquals("SUCCEEDED",flow.run(session,otherRun).get("status"));
    }

    @Test void supportedDispatchAloneMintsExactImmutableNoCacheProvenance(){
        UUID conversation=conversation(),run=local(conversation,"Synthetic local question");
        assertNull(db.queryForObject("SELECT dispatch_context_id FROM inference_reservations WHERE run_id=?",UUID.class,run));
        assertTrue(local.runOne());assertEquals(1,client.calls);
        var reservation=db.queryForMap("SELECT * FROM inference_reservations WHERE run_id=?",run);
        assertEquals(client.request.path("context").path("id").asText(),reservation.get("dispatch_context_id").toString());
        assertEquals(conversation.toString(),client.request.path("context").path("conversation_id").asText());
        assertEquals("CONTEXT_V1_NO_RESPONSE_CACHE",reservation.get("dispatch_cache_policy"));
        assertEquals(contexts.requestDigest(owner,Json.canonical(client.request)),reservation.get("body_hash"));
        var receipts=db.queryForList("SELECT * FROM inference_usage_receipts");var epochs=epochs();
        erase(conversation);assertEquals(receipts,db.queryForList("SELECT * FROM inference_usage_receipts"));assertEquals(epochs,epochs());
        assertEquals(reservation,db.queryForMap("SELECT * FROM inference_reservations WHERE run_id=?",run));
        assertEquals("INVALIDATED",db.queryForObject("SELECT state FROM inference_contexts WHERE run_id=?",String.class,run));
        deleted(()->flow.tx.executeWithoutResult(s->contexts.capture(session,conversation,run)));
        failure(()->flow.tx.executeWithoutResult(s->contexts.prepare(owner,run)),409,"STALE_INFERENCE_CONTEXT");
        failure(()->flow.tx.executeWithoutResult(s->contexts.markDispatched(owner,run)),409,"STALE_INFERENCE_CONTEXT");
        failure(()->flow.tx.executeWithoutResult(s->contexts.markSettled(owner,run)),409,"STALE_INFERENCE_CONTEXT");
        assertFalse(local.runOne());assertEquals(1,client.calls);
    }

    @Test void requestContextTamperingCannotMintMarkerOrCallTransport(){
        UUID conversation=conversation(),run=local(conversation,"Synthetic tampered request");
        LocalInferenceService tampered=spy(local);
        doAnswer(call->{var request=(JsonNode)call.callRealMethod();((tools.jackson.databind.node.ObjectNode)request.get("context")).put("conversation_id",UUID.randomUUID().toString());return request;})
            .when(tampered).request(anyMap(),anyString(),anyMap());
        assertTrue(tampered.runOne());assertEquals(0,client.calls);
        assertNull(db.queryForObject("SELECT dispatch_context_id FROM inference_reservations WHERE run_id=?",UUID.class,run));
        assertNull(db.queryForObject("SELECT started_at FROM inference_reservations WHERE run_id=?",Object.class,run));
        assertEquals("BLOCKED",db.queryForObject("SELECT status FROM runs WHERE id=?",String.class,run));
        var state=deletion.state(session,conversation);assertEquals("BLOCKED",((Map<?,?>)state.get("eligibility")).get("status"));
        assertTrue(((List<?>)((Map<?,?>)state.get("eligibility")).get("blockers")).contains("UNSUPPORTED_LEGACY_INFERENCE"));
    }

    @Test void cancelledBeforeDispatchRemainsUnmarkedAndUnsupported(){
        UUID conversation=conversation(),run=local(conversation,"Synthetic cancellation");long version=((Number)flow.run(session,run).get("version")).longValue();
        flow.cancel(session,run,version);flow.tx.executeWithoutResult(s->freshness.invalidateRun(owner,run));
        assertNull(db.queryForObject("SELECT dispatch_context_id FROM inference_reservations WHERE run_id=?",UUID.class,run));
        assertNull(db.queryForObject("SELECT started_at FROM inference_reservations WHERE run_id=?",Object.class,run));
        var state=deletion.state(session,conversation);assertNull(state.get("content_digest"));
        assertTrue(((List<?>)((Map<?,?>)state.get("eligibility")).get("blockers")).contains("UNSUPPORTED_LEGACY_INFERENCE"));
        assertFalse(local.runOne());assertEquals(0,client.calls);
    }

    @Test void deletingAnotherConversationDoesNotInvalidateActiveDispatchOrEpochs(){
        UUID target=conversation();mock(target,UUID.randomUUID(),"Closed synthetic target");assertTrue(worker.runOne());
        UUID other=conversation(),run=local(other,"Unrelated active question");var before=epochs();
        client.duringInfer=()->{
            assertEquals("DISPATCHED",db.queryForObject("SELECT state FROM inference_contexts WHERE run_id=?",String.class,run));
            erase(target);assertEquals(before,epochs());
            assertEquals("STARTED",db.queryForObject("SELECT status FROM inference_reservations WHERE run_id=?",String.class,run));
            assertEquals("DISPATCHED",db.queryForObject("SELECT state FROM inference_contexts WHERE run_id=?",String.class,run));
        };
        assertTrue(local.runOne());assertEquals("SUCCEEDED",flow.run(session,run).get("status"));
        assertEquals("SETTLED",db.queryForObject("SELECT state FROM inference_contexts WHERE run_id=?",String.class,run));
        assertEquals(2,items(history.messages(session,other,null,20)).size());assertEquals(before,epochs());
    }

    @Test void lateNumericMeasurementCanCommitWithoutSourceOrAuthorityResurrection(){
        client.error=true;UUID conversation=conversation(),run=local(conversation,"Synthetic failed local question");assertTrue(local.runOne());
        UUID reservation=db.queryForObject("SELECT id FROM inference_reservations WHERE run_id=?",UUID.class,run);
        var dispatch=new LocalInferenceService.Dispatch(owner,run,reservation,client.request);erase(conversation);
        var tombstone=db.queryForMap("SELECT * FROM conversations WHERE id=?",conversation);var before=db.queryForMap("SELECT * FROM runs WHERE id=?",run);
        var response=LocalInferenceContractTest.response(client.request).put("reply_text","Late output must never persist");
        usage.record(dispatch,response);usage.record(dispatch,response);
        assertEquals(2L,count("inference_usage_receipts"));assertEquals(before,db.queryForMap("SELECT * FROM runs WHERE id=?",run));
        assertEquals(tombstone,db.queryForMap("SELECT * FROM conversations WHERE id=?",conversation));
        ReflectionTestUtils.invokeMethod(local,"settle",dispatch,"SUCCEEDED","Late output must never persist");
        assertEquals(before,db.queryForMap("SELECT * FROM runs WHERE id=?",run));
        assertEquals(0L,db.queryForObject("SELECT count(*) FROM messages WHERE run_id=? AND role='ASSISTANT'",Long.class,run));
        assertThrows(org.springframework.dao.DataAccessException.class,()->db.update("UPDATE inference_usage_receipts SET compute_ms=2 WHERE reservation_id=?",reservation));
        assertThrows(org.springframework.dao.DataAccessException.class,()->db.update("DELETE FROM inference_usage_receipts WHERE reservation_id=?",reservation));
        assertThrows(org.springframework.dao.DataAccessException.class,()->db.update("UPDATE inference_reservations SET dispatch_cache_policy=NULL,dispatch_context_id=NULL WHERE id=?",reservation));
    }

    @Test void historyWaitingBehindDeletionUsesCurrentCommittedStateBeforeReturningContent() throws Exception {
        UUID target=conversation();mock(target,UUID.randomUUID(),"Private read race sentinel");assertTrue(worker.runOne());var prepared=prepare(target);
        CountDownLatch attempted=new CountDownLatch(1);var controlled=spy(db);
        doAnswer(call->{assertEquals("read committed",controlled.queryForObject("SHOW transaction_isolation",String.class));
            controlled.queryForObject("SELECT count(*) FROM conversations",Long.class);attempted.countDown();return call.callRealMethod();})
            .when(controlled).queryForList(eq("SELECT * FROM owners WHERE id=? FOR UPDATE"),any(Object[].class));
        try(var reader=new ConversationHistoryServiceHandle(new ConversationHistoryService(controlled,flow.vault,app.getBean(PlatformTransactionManager.class),key.toString()));var pool=Executors.newSingleThreadExecutor()){
            List<Future<ApiException>> future=new ArrayList<>();
            flow.tx.executeWithoutResult(s->{
                deletion.confirm(session,UUID.randomUUID(),(UUID)prepared.get("id"),confirmation(prepared));
                future.add(pool.submit(()->assertThrows(ApiException.class,()->reader.history.messages(session,target,null,20))));
                try{assertTrue(attempted.await(3,TimeUnit.SECONDS));assertFalse(future.getFirst().isDone());}
                catch(InterruptedException e){throw new AssertionError(e);}
            });
            var error=future.getFirst().get(8,TimeUnit.SECONDS);assertEquals(410,error.status);assertEquals("CONVERSATION_DELETED",error.code);
        }
    }
    @Test void historyWaitingBehindLogoutRevalidatesCurrentSessionBeforeAnyContent() throws Exception {
        UUID target=conversation();mock(target,UUID.randomUUID(),"Revoked-session read sentinel");assertTrue(worker.runOne());
        CountDownLatch attempted=new CountDownLatch(1);var controlled=spy(db);
        doAnswer(call->{attempted.countDown();return call.callRealMethod();})
            .when(controlled).queryForList(eq("SELECT * FROM owners WHERE id=? FOR UPDATE"),any(Object[].class));
        try(var reader=new ConversationHistoryServiceHandle(new ConversationHistoryService(controlled,flow.vault,app.getBean(PlatformTransactionManager.class),key.toString()));var pool=Executors.newSingleThreadExecutor()){
            List<Future<ApiException>> future=new ArrayList<>();
            flow.tx.executeWithoutResult(s->{
                auth.logout(session);
                future.add(pool.submit(()->assertThrows(ApiException.class,()->reader.history.messages(session,target,null,20))));
                try{assertTrue(attempted.await(3,TimeUnit.SECONDS));assertFalse(future.getFirst().isDone());}
                catch(InterruptedException e){throw new AssertionError(e);}
            });
            var error=future.getFirst().get(8,TimeUnit.SECONDS);assertEquals(401,error.status);assertEquals("SESSION_REVOKED",error.code);
        }
    }
    private static final class ConversationHistoryServiceHandle implements AutoCloseable {
        final ConversationHistoryService history;ConversationHistoryServiceHandle(ConversationHistoryService history){this.history=history;}
        public void close(){history.close();}
    }

    @Test void contentBarrierRejectsOldRepeatableReadSnapshotAndRevokedSession(){
        UUID target=conversation();var rr=new TransactionTemplate(app.getBean(PlatformTransactionManager.class));rr.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThrows(IllegalStateException.class,()->rr.executeWithoutResult(s->flow.access.requireLive(owner,target)));
        auth.logout(session);failure(()->history.messages(session,target,null,20),401,"SESSION_REVOKED");
        failure(()->history.conversations(session,null,20),401,"SESSION_REVOKED");
        failure(()->history.events(session,target,null),401,"SESSION_REVOKED");
    }
}

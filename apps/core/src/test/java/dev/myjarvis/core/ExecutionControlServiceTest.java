package dev.myjarvis.core;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real PostgreSQL owner-lock and lease-fence regressions. All effects are synthetic. */
class ExecutionControlServiceTest extends ReminderTestSupport {
    Map<String,Object> change(boolean paused,long expected){return flow.executionTransition(session,paused,body(Map.of("expected_version",expected)));}
    void refresh(){session=auth.find(token);assertNotNull(session);}
    long epoch(){return db.queryForObject("SELECT security_epoch FROM owners WHERE id=?",Long.class,owner);}
    long version(){return db.queryForObject("SELECT version FROM owners WHERE id=?",Long.class,owner);}
    long controls(){return db.queryForObject("SELECT count(*) FROM audit_events WHERE event_type IN ('EXECUTION_PAUSED','EXECUTION_RESUMED')",Long.class);}
    UUID pendingRun(){UUID conversation=(UUID)flow.createConversation(owner,body(Map.of("title","synthetic private title"))).get("id");return (UUID)flow.accept(owner,conversation,body(Map.of("client_message_id",UUID.randomUUID().toString(),"text","/calendar","attachment_ids",List.of()))).get("run_id");}
    Map<String,Object> proposedRun(){UUID run=pendingRun();assertTrue(context.getBean(MockWorker.class).runOne());return flow.approval(owner,(UUID)flow.run(owner,run).get("approval_id"));}
    void approve(Map<String,Object> approval){flow.decision(session,(UUID)approval.get("id"),body(Map.of("decision","APPROVE","expected_action_version",approval.get("action_version"),"payload_hash",approval.get("payload_hash"))));}

    @Test void pauseWinningSettlementRaceFencesAlreadyStartedEffectAndPreservesUnknown() throws Exception {
        Map<String,Object> approval=proposedRun();approve(approval);
        WorkflowService controlled=spy(flow);AtomicInteger locks=new AtomicInteger();
        CountDownLatch settlement=new CountDownLatch(1),release=new CountDownLatch(1);
        doAnswer(call->{if(locks.incrementAndGet()==3){settlement.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));}call.callRealMethod();return null;}).when(controlled).lockOwner(eq(owner));
        MockWorker worker=new MockWorker(controlled,false);
        try(var pool=Executors.newSingleThreadExecutor()){
            Future<Boolean> running=pool.submit(worker::runOne);
            try{assertTrue(settlement.await(10,TimeUnit.SECONDS),"Final settlement must enter the owner-lock boundary");assertEquals(1,count("mock_effects"));change(true,1);}
            finally{release.countDown();}
            assertTrue(running.get(15,TimeUnit.SECONDS));
        }
        assertEquals("UNKNOWN",db.queryForObject("SELECT status FROM jobs WHERE kind='EXECUTE'",String.class));
        assertEquals("UNKNOWN",db.queryForObject("SELECT status FROM action_executions",String.class));
        assertEquals("UNKNOWN",db.queryForObject("SELECT status FROM action_proposals",String.class));
        assertEquals("UNKNOWN",db.queryForObject("SELECT status FROM runs",String.class));
        refresh();change(false,2);worker.recover();assertFalse(worker.runOne());assertEquals(1,count("mock_effects"));
        assertEquals("UNKNOWN",db.queryForObject("SELECT status FROM runs",String.class));
    }

    @Test void readHasOneDatabaseCheckedTimeAndClosedPrivateProjection(){
        Instant before=AuthService.instant(db.queryForObject("SELECT clock_timestamp()",Object.class));
        Map<String,Object> state=flow.executionStatus(session);
        Instant after=AuthService.instant(db.queryForObject("SELECT clock_timestamp()",Object.class));
        assertEquals(Set.of("execution_paused","version","checked_at"),state.keySet());assertEquals(false,state.get("execution_paused"));assertEquals(1L,state.get("version"));
        Instant checked=Instant.parse((String)state.get("checked_at"));assertFalse(checked.isBefore(before));assertFalse(checked.isAfter(after));assertEquals(0,checked.getNano()%1000);
        assertFalse(state.toString().contains(owner.toString()));assertFalse(state.toString().contains(session.hash()));assertFalse(state.toString().contains(token));assertEquals(0,controls());
    }

    @Test void actualTransitionsAdvanceOnceAndRetainOnlyInitiatingSession(){
        String other=auth.issue(owner,true,null).token();AuthService.Session captured=session;
        assertEquals(true,change(true,1).get("execution_paused"));assertEquals(2,version());assertEquals(2,epoch());assertNull(auth.find(other));
        code("SESSION_REVOKED",()->flow.executionStatus(captured));refresh();assertEquals(true,flow.executionStatus(session).get("execution_paused"));
        assertEquals(false,change(false,2).get("execution_paused"));assertEquals(3,version());assertEquals(3,epoch());assertEquals(2,controls());
    }

    @Test void exactResponseLossResubmissionCannotAdvanceEpochAgain(){
        change(true,1);refresh();code("PRECONDITION_CHANGED",()->change(true,1));assertEquals(2,epoch());assertEquals(2,version());assertEquals(1,controls());
        assertEquals(true,flow.executionStatus(session).get("execution_paused"));
    }

    @Test void sameStateAtCurrentVersionIsNoOpButStaleVersionStillConflicts(){
        UUID pending=pendingRun();change(false,1);assertEquals("ACCEPTED",flow.run(owner,pending).get("status"));assertEquals(1,epoch());assertEquals(1,version());assertEquals(0,controls());
        change(true,1);refresh();long generation=db.queryForObject("SELECT lease_generation FROM jobs",Long.class);change(true,2);
        assertEquals(2,epoch());assertEquals(2,version());assertEquals(1,controls());assertEquals(generation,db.queryForObject("SELECT lease_generation FROM jobs",Long.class));code("PRECONDITION_CHANGED",()->change(true,1));
    }

    @Test void duplicateConcurrentSnapshotHasExactlyOneTransition() throws Exception {
        AuthService.Session captured=session;CountDownLatch start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(8)){
            List<Future<String>> results=new ArrayList<>();for(int i=0;i<8;i++)results.add(pool.submit(()->{start.await();try{flow.executionTransition(captured,true,body(Map.of("expected_version",1)));return "COMMITTED";}catch(ApiException e){return e.code;}}));
            start.countDown();long committed=0;for(Future<String> result:results){String outcome=result.get(15,TimeUnit.SECONDS);if(outcome.equals("COMMITTED"))committed++;else assertTrue(Set.of("SESSION_REVOKED","PRECONDITION_CHANGED").contains(outcome));}assertEquals(1,committed);
        }
        assertEquals(2,epoch());assertEquals(2,version());assertEquals(1,controls());
    }

    @Test void profileUpdateMakesDisplayedControlVersionStale(){
        context.getBean(OwnerProfileService.class).patch(session,UUID.randomUUID(),body(Map.of("expected_version",1,"locale","en-US")));
        code("PRECONDITION_CHANGED",()->change(true,1));assertEquals(2,version());assertEquals(1,epoch());assertEquals(false,flow.executionStatus(session).get("execution_paused"));assertEquals(0,controls());
    }

    @Test void freshReadDoesNotProveAnOlderAmbiguousPauseSucceeded(){
        change(true,1);refresh();change(false,2);refresh();Map<String,Object> state=flow.executionStatus(session);assertEquals(false,state.get("execution_paused"));assertEquals(3L,state.get("version"));
        code("PRECONDITION_CHANGED",()->change(true,1));assertEquals(3,epoch());assertEquals(2,controls());
    }

    @Test void recentAuthenticationComesFromDatabaseAndReadNeedsOnlyLiveSession(){
        db.update("UPDATE sessions SET reauthenticated_at=clock_timestamp()-interval '6 minutes' WHERE token_hash=?",session.hash());
        assertEquals(false,flow.executionStatus(session).get("execution_paused"));code("REAUTHENTICATION_REQUIRED",()->change(true,1));
        db.update("UPDATE sessions SET reauthenticated_at=clock_timestamp()+interval '1 minute' WHERE token_hash=?",session.hash());code("REAUTHENTICATION_REQUIRED",()->change(true,1));
        db.update("UPDATE sessions SET reauthenticated_at=NULL WHERE token_hash=?",session.hash());code("REAUTHENTICATION_REQUIRED",()->change(true,1));assertEquals(1,epoch());
    }

    @Test void sessionExpiryRevocationAndForgedOwnerFailClosed(){
        AuthService.Session forged=new AuthService.Session(session.hash(),UUID.randomUUID(),session.reauth(),1);code("SESSION_REVOKED",()->flow.executionStatus(forged));code("SESSION_REVOKED",()->flow.executionTransition(forged,true,body(Map.of("expected_version",1))));
        db.update("UPDATE sessions SET last_seen_at=clock_timestamp()-interval '3 hours' WHERE token_hash=?",session.hash());code("SESSION_REVOKED",()->change(true,1));code("SESSION_REVOKED",()->flow.executionStatus(session));
        db.update("UPDATE sessions SET last_seen_at=clock_timestamp(),revoked_at=clock_timestamp() WHERE token_hash=?",session.hash());code("SESSION_REVOKED",()->change(true,1));assertEquals(1,epoch());assertEquals(0,controls());
    }

    @Test void queuedWorkAndPendingApprovalNeverReviveAfterResume(){
        Map<String,Object> approval=proposedRun();UUID queued=pendingRun();change(true,1);refresh();assertEquals("REVOKED",flow.approval(owner,(UUID)approval.get("id")).get("status"));assertEquals("CANCELLED",flow.run(owner,queued).get("status"));
        change(false,2);refresh();code("APPROVAL_NOT_PENDING",()->approve(approval));assertFalse(context.getBean(MockWorker.class).runOne());assertEquals(0,count("mock_effects"));assertEquals(0,count("authorizations"));
    }

    @Test void pauseFencesClaimedButUnstartedLeaseWithZeroEffects(){
        Map<String,Object> approval=proposedRun();approve(approval);MockWorker worker=context.getBean(MockWorker.class);var lease=worker.claim();assertNotNull(lease);change(true,1);
        assertEquals("CANCELLED",db.queryForObject("SELECT status FROM jobs WHERE id=?",String.class,lease.get("id")));assertEquals(0,db.update("UPDATE jobs SET status='SUCCEEDED' WHERE id=? AND lease_generation=?",lease.get("id"),lease.get("lease_generation")));
        refresh();change(false,2);worker.recover();assertFalse(worker.runOne());assertEquals(0,count("mock_effects"));
    }

    @Test void auditFailureRollsBackOwnerFenceAndQueuedInvalidation(){
        UUID run=pendingRun();db.execute("ALTER TABLE audit_events ADD CONSTRAINT execution_test_fail CHECK(event_type<>'EXECUTION_PAUSED')");
        try{assertThrows(RuntimeException.class,()->change(true,1));assertEquals(1,epoch());assertEquals(1,version());assertEquals("ACCEPTED",flow.run(owner,run).get("status"));assertEquals("QUEUED",db.queryForObject("SELECT status FROM jobs",String.class));assertNotNull(auth.find(token));}
        finally{db.execute("ALTER TABLE audit_events DROP CONSTRAINT execution_test_fail");}
    }

    @Test void ownerVersionCeilingAllowsNoOpAndRejectsTransition(){
        db.update("UPDATE owners SET version=9007199254740991 WHERE id=?",owner);change(false,9007199254740991L);code("VERSION_EXHAUSTED",()->change(true,9007199254740991L));assertEquals(1,epoch());assertEquals(0,controls());
    }

    @Test void strictPayloadRejectsMissingSpoofedAndUnsafeValues(){
        for(Object invalid:List.of(Map.of(),Map.of("expected_version",0),Map.of("expected_version",-1),Map.of("expected_version","1"),Map.of("expected_version",true),Map.of("expected_version",9007199254740992L),Map.of("expected_version",1,"owner_id",owner.toString()),Map.of("expected_version",1,"execution_paused",true)))code("INVALID_REQUEST",()->flow.executionTransition(session,true,body(invalid)));
        assertEquals(1,epoch());assertEquals(0,controls());
    }

    @Test void capturedHttpMessageAdmissionCannotWaitAcrossPauseAndResume() throws Exception {
        UUID conversation=(UUID)flow.createConversation(owner,body(Map.of("title","Synthetic"))).get("id");AuthService.Session captured=session;
        CountDownLatch entered=new CountDownLatch(1);var pending=new java.util.concurrent.atomic.AtomicReference<Future<String>>();
        try(var pool=Executors.newSingleThreadExecutor()){
            flow.tx.executeWithoutResult(tx->{flow.lockOwner(owner);pending.set(pool.submit(()->{entered.countDown();try{flow.accept(captured,conversation,body(Map.of("client_message_id",UUID.randomUUID().toString(),"text","Synthetic old request","attachment_ids",List.of())));return "ACCEPTED";}catch(ApiException error){return error.code;}}));
                try{assertTrue(entered.await(5,TimeUnit.SECONDS));}catch(InterruptedException error){throw new IllegalStateException(error);}
                change(true,1);refresh();change(false,2);
            });
            assertEquals("SESSION_REVOKED",pending.get().get(15,TimeUnit.SECONDS));
        }
        assertEquals(0,count("runs"));assertEquals(0,count("jobs"));assertEquals(0,count("messages"));assertEquals(0,count("mock_effects"));
    }

    @Test void blockedTransitionRechecksDatabaseReauthenticationAfterOwnerLockWait() throws Exception {
        AuthService.Session captured=session;CountDownLatch entered=new CountDownLatch(1);var pending=new java.util.concurrent.atomic.AtomicReference<Future<String>>();
        try(var pool=Executors.newSingleThreadExecutor()){
            flow.tx.executeWithoutResult(tx->{flow.lockOwner(owner);pending.set(pool.submit(()->{entered.countDown();try{flow.executionTransition(captured,true,body(Map.of("expected_version",1)));return "COMMITTED";}catch(ApiException error){return error.code;}}));
                try{assertTrue(entered.await(5,TimeUnit.SECONDS));}catch(InterruptedException error){throw new IllegalStateException(error);}
                db.update("UPDATE sessions SET reauthenticated_at=clock_timestamp()-interval '6 minutes' WHERE token_hash=?",captured.hash());
            });
            assertEquals("REAUTHENTICATION_REQUIRED",pending.get().get(15,TimeUnit.SECONDS));
        }
        assertEquals(1,epoch());assertEquals(0,controls());
    }

    @Test void pauseWinningErrorSettlementRaceAlsoPreservesUnknown() throws Exception {
        Map<String,Object> approval=proposedRun();approve(approval);WorkflowService controlled=spy(flow);
        CountDownLatch settlement=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger locks=new AtomicInteger();
        doThrow(new IllegalStateException("Synthetic adapter failure")).when(controlled).owned(eq("action_proposals"),eq(owner),eq((UUID)approval.get("action_id")));
        doAnswer(call->{if(locks.incrementAndGet()==3){settlement.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));}call.callRealMethod();return null;}).when(controlled).lockOwner(eq(owner));
        MockWorker worker=new MockWorker(controlled,false);
        try(var pool=Executors.newSingleThreadExecutor()){
            Future<Boolean> running=pool.submit(worker::runOne);try{assertTrue(settlement.await(10,TimeUnit.SECONDS));change(true,1);}finally{release.countDown();}assertTrue(running.get(15,TimeUnit.SECONDS));
        }
        assertEquals("UNKNOWN",db.queryForObject("SELECT status FROM jobs WHERE kind='EXECUTE'",String.class));assertEquals("UNKNOWN",db.queryForObject("SELECT status FROM runs",String.class));assertEquals("UNKNOWN",db.queryForObject("SELECT status FROM action_executions",String.class));assertEquals(0,count("mock_effects"));
        refresh();change(false,2);worker.recover();assertFalse(worker.runOne());assertEquals(0,count("mock_effects"));
    }

    @Test void securityEpochCeilingRejectsTransitionWithoutChangingState(){
        db.update("UPDATE owners SET security_epoch=9007199254740991 WHERE id=?",owner);db.update("UPDATE sessions SET security_epoch=9007199254740991 WHERE token_hash=?",session.hash());refresh();
        change(false,1);code("VERSION_EXHAUSTED",()->change(true,1));assertEquals(1,version());assertEquals(0,controls());
    }

    @Test void localInFlightRemainsUnknownQueuedReservationIsCancelledAndLateAnswerIsDiscarded() throws Exception {
        var env=new org.springframework.mock.env.MockEnvironment().withProperty("jarvis.local.manifest-hash","a".repeat(64));AtomicInteger calls=new AtomicInteger();CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        LocalInferenceClient client=new LocalInferenceClient(env){
            @Override boolean ready(){return true;}
            @Override tools.jackson.databind.JsonNode infer(UUID owner,tools.jackson.databind.JsonNode request) throws Exception{calls.incrementAndGet();started.countDown();assertTrue(release.await(15,TimeUnit.SECONDS));return LocalInferenceContractTest.response(request);}
        };
        var local=new LocalInferenceService(flow,client,context.getBean(InferenceContextService.class),context.getBean(InferenceContextFreshness.class));
        try(var pool=Executors.newSingleThreadExecutor()){
            UUID conversation=(UUID)flow.createConversation(owner,body(Map.of("title","Synthetic local control"))).get("id");
            UUID first=(UUID)local.accept(session,conversation,body(Map.of("client_message_id",UUID.randomUUID().toString(),"text","Synthetic first"))).get("run_id");
            Future<Boolean> running=pool.submit(local::runOne);
            try{
                assertTrue(started.await(10,TimeUnit.SECONDS));UUID second=(UUID)local.accept(session,conversation,body(Map.of("client_message_id",UUID.randomUUID().toString(),"text","Synthetic second"))).get("run_id");
                change(true,1);assertEquals("UNKNOWN",flow.run(owner,first).get("status"));assertEquals("CANCELLED",flow.run(owner,second).get("status"));refresh();change(false,2);
            }finally{release.countDown();}
            assertTrue(running.get(15,TimeUnit.SECONDS));assertFalse(local.runOne());assertEquals(1,calls.get());assertEquals("UNKNOWN",flow.run(owner,first).get("status"));assertNull(flow.run(owner,first).get("result"));
            assertEquals(2,db.queryForObject("SELECT count(*) FROM inference_contexts WHERE state='INVALIDATED'",Long.class));assertEquals(60000,db.queryForObject("SELECT sum(max_compute_ms) FROM inference_reservations WHERE status='UNKNOWN'",Long.class));assertEquals(0,count("mock_effects"));
        }finally{release.countDown();local.close();}
    }

    @Test void taskAndMemoryIntentsFromBeforePauseCannotBeConsumedAfterResume(){
        Map<String,Object> args=new LinkedHashMap<>();args.put("title","Synthetic task");args.put("description","Synthetic description");args.put("project_id",null);args.put("due_at",null);args.put("priority","NORMAL");args.put("estimated_minutes",null);
        Map<String,Object> taskRequest=new LinkedHashMap<>();taskRequest.put("tool_id","task.create");taskRequest.put("target_id",null);taskRequest.put("arguments",args);
        var task=context.getBean(TaskIntentService.class).issue(session,UUID.randomUUID(),body(taskRequest));Map<String,Object> taskWrite=new LinkedHashMap<>(args);taskWrite.put("user_intent_id",task.get("id").toString());
        Map<String,Object> memoryArgs=new LinkedHashMap<>();memoryArgs.put("scope","GLOBAL");memoryArgs.put("project_id",null);memoryArgs.put("key","synthetic.execution.control");memoryArgs.put("kind","PREFERENCE");memoryArgs.put("value","Synthetic value");memoryArgs.put("change_reason","Synthetic explicit form");memoryArgs.put("expected_generation",0);memoryArgs.put("revive_deleted",false);
        Map<String,Object> memoryRequest=new LinkedHashMap<>();memoryRequest.put("tool_id","memory.write");memoryRequest.put("operation","CREATE");memoryRequest.put("target_id",null);memoryRequest.put("arguments",memoryArgs);
        var memory=context.getBean(MemoryIntentService.class).issue(session,UUID.randomUUID(),body(memoryRequest));
        change(true,1);refresh();change(false,2);refresh();
        code("INTENT_NOT_VALID",()->context.getBean(TaskService.class).create(session,UUID.randomUUID(),body(taskWrite)));
        code("AUTHORITY_NOT_VALID",()->context.getBean(MemoryService.class).save(session,UUID.randomUUID(),null,body(Map.of("intent_id",memory.get("id"),"arguments",memoryArgs)),true));
        assertEquals(0,count("tasks"));assertEquals(0,count("memories"));assertEquals(0,count("task_mutations"));assertEquals(0,count("memory_mutations"));
    }

    @Test void scheduleAuthorityNeverRevivesAcrossPauseAndResume(){
        UUID schedule=create("DAILY");Instant fire=due(schedule);change(true,1);refresh();change(false,2);refresh();
        assertEquals("RECONFIRM_REQUIRED",schedules.get(session,schedule).get("status"));ScheduleWorker worker=workerAt(fire);worker.plan(owner);assertTrue(worker.claim(owner,10).isEmpty());assertEquals(0,count("notifications"));assertNotNull(db.queryForObject("SELECT revoked_at FROM schedule_authorizations",Object.class));
    }

    @Test void settlementCannotUseTransactionStartTimeToAcceptAnExpiredLeaseAfterLockWait() throws Exception {
        Map<String,Object> approval=proposedRun();approve(approval);WorkflowService controlled=spy(flow);AtomicInteger locks=new AtomicInteger();
        CountDownLatch settlement=new CountDownLatch(1),release=new CountDownLatch(1);doAnswer(call->{if(locks.incrementAndGet()==3){settlement.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));}call.callRealMethod();return null;}).when(controlled).lockOwner(eq(owner));
        MockWorker worker=new MockWorker(controlled,false);
        try(var pool=Executors.newSingleThreadExecutor()){
            Future<Boolean> running=pool.submit(worker::runOne);
            try{assertTrue(settlement.await(10,TimeUnit.SECONDS));flow.tx.executeWithoutResult(tx->{flow.lockOwner(owner);db.update("UPDATE jobs SET lease_until=clock_timestamp() WHERE kind='EXECUTE'");release.countDown();});}
            finally{release.countDown();}
            assertTrue(running.get(15,TimeUnit.SECONDS));
        }
        assertEquals("RUNNING",db.queryForObject("SELECT status FROM jobs WHERE kind='EXECUTE'",String.class),"Expired lease must not settle successfully before recovery");
        worker.recover();assertEquals("UNKNOWN",db.queryForObject("SELECT status FROM runs",String.class));assertEquals(1,count("mock_effects"));assertFalse(worker.runOne());
    }

    @Test void leaseExpiringAfterCurrentCheckRollsBackEarlierActionSettlement() throws Exception {
        Map<String,Object> approval=proposedRun();approve(approval);var controlledDb=spy(db);AtomicInteger checks=new AtomicInteger();
        CountDownLatch checked=new CountDownLatch(1),release=new CountDownLatch(1);
        doAnswer(call->{Object result=call.callRealMethod();if(checks.incrementAndGet()==3){assertEquals(true,result);checked.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));}return result;})
            .when(controlledDb).queryForObject(startsWith("SELECT EXISTS(SELECT 1 FROM jobs WHERE id="),eq(Boolean.class),any(Object[].class));
        // Shorten the synthetic lease within its own claim transaction. Parent-aware source
        // guards intentionally forbid mutating a child from another transaction while its
        // owner is locked. Let the real database clock expire the committed lease instead.
        doAnswer(call->{
            @SuppressWarnings("unchecked") var claimed=(Map<String,Object>)call.callRealMethod();
            controlledDb.update("UPDATE jobs SET lease_until=clock_timestamp()+interval '5 seconds' WHERE id=?",claimed.get("id"));
            return claimed;
        }).when(controlledDb).queryForMap(startsWith("UPDATE jobs SET status='RUNNING',lease_owner="),any(Object[].class));
        MockWorker worker=new MockWorker(new WorkflowService(controlledDb,flow.tx,flow.vault,flow.replies),false);
        try(var pool=Executors.newSingleThreadExecutor()){
            Future<Boolean> running=pool.submit(worker::runOne);
            try{
                assertTrue(checked.await(10,TimeUnit.SECONDS));
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                while(!Boolean.TRUE.equals(db.queryForObject("SELECT lease_until<=clock_timestamp() FROM jobs WHERE kind='EXECUTE'",Boolean.class))){
                    assertTrue(System.nanoTime()<deadline,"Synthetic lease must expire by database time");Thread.sleep(10);
                }
            }
            finally{release.countDown();}
            assertTrue(running.get(15,TimeUnit.SECONDS));
        }
        assertEquals("RUNNING",db.queryForObject("SELECT status FROM jobs WHERE kind='EXECUTE'",String.class));
        assertEquals("STARTED",db.queryForObject("SELECT status FROM action_executions",String.class));assertEquals("EXECUTING",db.queryForObject("SELECT status FROM action_proposals",String.class));assertEquals("RUNNING",db.queryForObject("SELECT status FROM runs",String.class));
        worker.recover();assertEquals("UNKNOWN",db.queryForObject("SELECT status FROM runs",String.class));assertEquals(1,count("mock_effects"));assertFalse(worker.runOne());
    }
}

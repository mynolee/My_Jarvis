package dev.myjarvis.core;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StudyFeedbackTransportTest extends StudyFeedbackFixtures {
    @Test void feedbackControlRetainsRevisionOneAndExactPurposeBinding(){var request=InferenceControlResultTest.request();request.put("purpose","STUDY_FEEDBACK");assertDoesNotThrow(()->InferenceControlResult.request(request));var result=InferenceControlResultTest.response(request,"TERMINAL","COMPLETED");assertTrue(InferenceControlResult.verify(result,request).terminal());result.put("purpose","STUDY_LESSON");assertThrows(ApiException.class,()->InferenceControlResult.verify(result,request));}
    @Test void unavailableDedicatedReadinessDoesNotFallBackToOrdinaryModel(){var client=new LocalInferenceClient(new MockEnvironment());assertFalse(client.feedbackReady());saveTwo();feedbackClient.available=false;error(()->requestFeedback(0),503,"FEEDBACK_MODEL_UNAVAILABLE");assertEquals(0L,count("study_feedback_requests"));assertEquals(0,feedbackClient.calls);}
    @Test void preparedTransportHasOnlySavedSourceAndExactLocalBudget(){saveTwo();UUID id=requestFeedback(0);var dispatch=dispatch(id);var body=dispatch.body();assertFalse(body.has("user_input"));assertEquals("STUDY_FEEDBACK",body.path("purpose").asText());assertEquals(3584,body.path("budget").path("max_input_tokens").asInt());assertEquals(512,body.path("budget").path("max_output_tokens").asInt());assertEquals(60000,body.path("budget").path("max_compute_ms").asInt());assertEquals(1,body.path("budget").path("max_model_calls").asInt());assertEquals(1,body.path("control").path("revision").asInt());assertEquals(0L,db.queryForObject("SELECT count(*) FROM messages WHERE run_id=?",Long.class,dispatch.run()));}
    StudyFeedbackService clocked(java.util.function.UnaryOperator<Map<String,Object>> change){
        var control=spy(new InferenceControlService(flow,feedbackClient,context.getBean(InferenceContextService.class),context.getBean(InferenceContextFreshness.class)));control.close();
        doAnswer(call->change.apply(new LinkedHashMap<>((Map<String,Object>)call.callRealMethod()))).when(control).finalObservation(any(UUID.class),anyString());
        return new StudyFeedbackService(db,memories,context.getBean(ConversationAccess.class),study,context.getBean(StudyFeedbackSource.class),context.getBean(StudyFeedbackLifecycle.class),context.getBean(InferenceContextService.class),context.getBean(InferenceContextFreshness.class),control,feedbackClient,context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
    }
    @Test void finalDispatchClockRejectsBackwardObservationBeforeAnyTransport(){saveTwo();UUID id=requestFeedback(0);var service=clocked(row->{row.put("observed_at",java.sql.Timestamp.from(Instant.parse("2000-01-01T00:00:00Z")));return row;});assertThrows(ApiException.class,()->service.prepareDispatch(owner,(UUID)feedbackRow(id).get("reservation_id")));assertEquals("QUEUED",feedbackRow(id).get("state"));assertEquals(0,feedbackClient.calls);}
    @Test void settlementRechecksOriginalExpiryAfterMeasuredUsageCommitted(){saveTwo();UUID id=requestFeedback(0);var dispatch=dispatch(id);var response=StudyFeedbackResultTest.response(dispatch.body());context.getBean(UsageService.class).record(dispatch,response);var service=clocked(row->{row.put("observed_at",java.sql.Timestamp.from(AuthService.instant(row.get("last_seen_at")).plusSeconds(7200)));return row;});assertThrows(ApiException.class,()->service.settle(dispatch,response));assertEquals("RUNNING",feedbackRow(id).get("state"));assertNull(feedbackRow(id).get("result_ref"));assertEquals(1L,db.queryForObject("SELECT count(*) FROM inference_usage_receipts WHERE reservation_id=? AND receipt_kind='MEASURED'",Long.class,dispatch.reservation()));}
    @Test void registrationGateRechecksExpiryAndBackwardClockWithoutTransport(){saveTwo();UUID id=requestFeedback(0);var dispatch=dispatch(id);for(boolean backwards:List.of(false,true)){var service=clocked(row->{row.put("observed_at",java.sql.Timestamp.from(backwards?Instant.parse("2000-01-01T00:00:00Z"):AuthService.instant(row.get("last_seen_at")).plusSeconds(7200)));return row;});assertThrows(ApiException.class,()->service.beforeTransport(dispatch));assertEquals(0,feedbackClient.calls);assertEquals("RUNNING",feedbackRow(id).get("state"));}}
    @Test void deferredDispatchSqlRejectionRollsBackAndClosesWithoutTransport(){saveTwo();UUID id=requestFeedback(0);
        db.execute("CREATE FUNCTION synthetic_feedback_dispatch_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.state='RUNNING' THEN RAISE EXCEPTION 'Synthetic deferred phase rejection'; END IF; RETURN NULL; END $$");
        db.execute("CREATE CONSTRAINT TRIGGER synthetic_feedback_dispatch_failure AFTER UPDATE ON study_feedback_requests DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION synthetic_feedback_dispatch_failure()");
        try {assertTrue(feedbackLocal.runOne());assertEquals(0,feedbackClient.calls);assertEquals("REVOKED",feedbackRow(id).get("state"));assertEquals(2,((Number)feedbackRow(id).get("version")).intValue());assertNull(db.queryForObject("SELECT started_at FROM inference_reservations WHERE id=?",Object.class,feedbackRow(id).get("reservation_id")));}
        finally {db.execute("DROP TRIGGER synthetic_feedback_dispatch_failure ON study_feedback_requests");db.execute("DROP FUNCTION synthetic_feedback_dispatch_failure()");}
    }
    @Test void dedicatedFeedbackRequestsNeverUseGenericTextRequestFactory(){saveTwo();UUID id=requestFeedback(0);var row=db.queryForMap("SELECT * FROM inference_reservations WHERE id=?",feedbackRow(id).get("reservation_id"));assertThrows(ApiException.class,()->feedbackLocal.request(row,"fabricated",Map.of()));assertEquals(0,feedbackClient.calls);}
    @org.junit.jupiter.api.Timeout(150) @Test void actualNinetySecondRecoveryRetainsImmutableDispatchAndUncertainExpense()throws Exception {
        saveTwo();UUID id=requestFeedback(0);var dispatch=dispatch(id);var before=db.queryForMap("SELECT * FROM inference_reservations WHERE id=?",dispatch.reservation());
        feedbackLocal.recover();assertEquals("RUNNING",feedbackRow(id).get("state"));
        // Real elapsed time only: immutable source/dispatch timestamps and DB guards stay untouched.
        Thread.sleep(91000);
        assertTrue(db.queryForObject("SELECT started_at<clock_timestamp()-interval '90 seconds' FROM inference_reservations WHERE id=?",Boolean.class,dispatch.reservation()));
        long version=conversationVersion(targetConversation);feedbackLocal.recover();var after=db.queryForMap("SELECT * FROM inference_reservations WHERE id=?",dispatch.reservation());
        assertEquals("REVOKED",feedbackRow(id).get("state"));assertEquals("UNKNOWN",after.get("status"));assertEquals(version+4,conversationVersion(targetConversation));
        for(String field:List.of("started_at","body_hash","dispatch_context_id","dispatch_cache_policy","max_compute_ms","reserved_output_tokens"))assertEquals(before.get(field),after.get(field),field);
        assertEquals("RECOVERY_UNKNOWN",db.queryForObject("SELECT reason_code FROM study_feedback_events WHERE feedback_id=? AND operation='REVOKE'",String.class,id));assertFalse(quiescent(id));assertEquals(0,feedbackClient.calls);assertEquals(0L,db.queryForObject("SELECT count(*) FROM inference_usage_receipts WHERE reservation_id=?",Long.class,dispatch.reservation()));
        var events=db.queryForList("SELECT * FROM study_feedback_events WHERE feedback_id=? ORDER BY version",id);feedbackLocal.recover();assertFalse(feedbackLocal.runOne());assertEquals(events,db.queryForList("SELECT * FROM study_feedback_events WHERE feedback_id=? ORDER BY version",id));
    }
    @Test void runOnePostRegistrationAuthorityLossPreventsTransportAndClosesExactlyOnce(){
        saveTwo();UUID id=requestFeedback(0);var gate=spy(feedback);feedbackLocal.feedbackService(gate);
        doAnswer(call->{assertEquals("RUNNING",feedbackRow(id).get("state"));context.getBean(AuthService.class).logout(session);return call.callRealMethod();}).when(gate).beforeTransport(any(LocalInferenceService.Dispatch.class));
        assertTrue(feedbackLocal.runOne());verify(gate,times(1)).beforeTransport(any(LocalInferenceService.Dispatch.class));assertEquals(0,feedbackClient.calls);assertEquals("REVOKED",feedbackRow(id).get("state"));assertNull(feedbackRow(id).get("result_ref"));
        assertEquals(1L,db.queryForObject("SELECT count(*) FROM study_feedback_events WHERE feedback_id=? AND operation='REVOKE'",Long.class,id));assertEquals(0L,db.queryForObject("SELECT count(*) FROM inference_usage_receipts WHERE reservation_id=?",Long.class,feedbackRow(id).get("reservation_id")));assertFalse(quiescent(id));
    }
    private StudyFeedbackService expiring(boolean absolute){return clocked(row->{Instant expiry=AuthService.instant(row.get(absolute?"created_at":"last_seen_at")).plusSeconds(absolute?43200:7200);row.put("observed_at",java.sql.Timestamp.from(expiry));if(absolute)row.put("last_seen_at",java.sql.Timestamp.from(expiry.minusSeconds(1)));return row;});}
    @Test void everyPublicAndExecutionFinalGateRejectsIdleAndAbsoluteExpiryEquality(){
        saveTwo();var body=requestBody(0);long baseline=conversationVersion(targetConversation);
        for(boolean absolute:List.of(false,true)){error(()->expiring(absolute).request(session,UUID.randomUUID(),studyId,body),401,"SESSION_REVOKED");assertEquals(0L,count("study_feedback_requests"));assertEquals(baseline,conversationVersion(targetConversation));}
        UUID id=requestFeedback(0);var queued=feedbackRow(id);for(boolean absolute:List.of(false,true)){error(()->expiring(absolute).prepareDispatch(owner,(UUID)queued.get("reservation_id")),401,"SESSION_REVOKED");assertEquals(queued,feedbackRow(id));}
        var dispatch=dispatch(id);var response=StudyFeedbackResultTest.response(dispatch.body());context.getBean(UsageService.class).record(dispatch,response);var running=feedbackRow(id);
        for(boolean absolute:List.of(false,true)){error(()->expiring(absolute).beforeTransport(dispatch),401,"SESSION_REVOKED");error(()->expiring(absolute).settle(dispatch,response),401,"SESSION_REVOKED");assertEquals(running,feedbackRow(id));}
        feedback.settle(dispatch,response);var accepted=feedbackRow(id);for(boolean absolute:List.of(false,true)){error(()->expiring(absolute).read(session,studyId),401,"SESSION_REVOKED");assertEquals(accepted,feedbackRow(id));}
        assertEquals(1L,db.queryForObject("SELECT count(*) FROM inference_usage_receipts WHERE reservation_id=?",Long.class,dispatch.reservation()));assertEquals(0,feedbackClient.calls);
    }
    private void waitedExpiry(String boundary,boolean absolute)throws Exception {
        saveTwo();var body=requestBody(0);UUID id=boundary.equals("ADMISSION")?null:requestFeedback(0);LocalInferenceService.Dispatch dispatched=boundary.equals("SETTLEMENT")||boundary.equals("READ")?dispatch(id):null;
        var response=dispatched==null?null:StudyFeedbackResultTest.response(dispatched.body());
        if(dispatched!=null)context.getBean(UsageService.class).record(dispatched,response);if(boundary.equals("READ"))feedback.settle(dispatched,response);
        var before=id==null?null:feedbackRow(id);long version=conversationVersion(targetConversation),requests=count("study_feedback_requests");
        boolean slot=boundary.equals("ADMISSION"),nonce=boundary.equals("SETTLEMENT");
        if(slot){db.execute("CREATE FUNCTION synthetic_feedback_slot_wait() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.purpose='STUDY_FEEDBACK' THEN PERFORM pg_advisory_xact_lock(870018); END IF; RETURN NULL; END $$");db.execute("CREATE CONSTRAINT TRIGGER synthetic_feedback_slot_wait AFTER INSERT ON inference_control_slots DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION synthetic_feedback_slot_wait()");}
        try(var pool=java.util.concurrent.Executors.newSingleThreadExecutor();var blocker=Objects.requireNonNull(db.getDataSource()).getConnection()){
            blocker.setAutoCommit(false);int blockerPid;try(var statement=blocker.createStatement();var identity=statement.executeQuery("SELECT pg_backend_pid()")){assertTrue(identity.next());blockerPid=identity.getInt(1);}
            if(slot)blocker.createStatement().execute("SELECT pg_advisory_xact_lock(870018)");
            else if(nonce){String keyId=((String)db.queryForMap("SELECT text_ref FROM study_owner_answer_inputs WHERE id=?",Json.uuid(body,"input_id")).get("text_ref")).split("\\.")[0];try(var lock=blocker.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")){lock.setString(1,keyId);lock.execute();}}
            else {try(var lock=blocker.prepareStatement("SELECT id FROM study_owner_answer_inputs WHERE id=? FOR UPDATE")){lock.setObject(1,Json.uuid(body,"input_id"));lock.execute();}}
            db.update(absolute?"UPDATE sessions SET created_at=clock_timestamp()-interval '12 hours'+interval '3 seconds',last_seen_at=clock_timestamp() WHERE token_hash=?":"UPDATE sessions SET last_seen_at=clock_timestamp()-interval '2 hours'+interval '3 seconds' WHERE token_hash=?",session.hash());
            Instant expiry=AuthService.instant(db.queryForObject(absolute?"SELECT created_at+interval '12 hours' FROM sessions WHERE token_hash=?":"SELECT last_seen_at+interval '2 hours' FROM sessions WHERE token_hash=?",Object.class,session.hash()));
            Runnable work=switch(boundary){case "ADMISSION"->()->feedback.request(session,UUID.randomUUID(),studyId,body);case "DISPATCH"->()->dispatch(id);case "SETTLEMENT"->()->feedback.settle(dispatched,response);default->()->feedback.read(session,studyId);};
            var future=pool.submit(()->assertThrows(RuntimeException.class,work::run));long limit=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(2);boolean waiting=false;
            while(System.nanoTime()<limit){waiting=Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE pid<>pg_backend_pid() AND wait_event_type='Lock' AND ?=ANY(pg_blocking_pids(pid)) AND query LIKE ?)",Boolean.class,blockerPid,slot?"%SET CONSTRAINTS%":nonce?"%pg_advisory_xact_lock%":"%study_owner_answer_inputs%"));if(waiting)break;Thread.sleep(10);}
            assertTrue(waiting,"The real source/nonce/deferred lock must be reached while authority is live");
            Instant checked=AuthService.instant(db.queryForObject("SELECT clock_timestamp()",Object.class));assertTrue(checked.isBefore(expiry),"Original authority must still be live when the exact blocker is observed");Thread.sleep(Math.max(0,java.time.Duration.between(checked,expiry).toMillis())+100);blocker.commit();
            var rejected=future.get(5,java.util.concurrent.TimeUnit.SECONDS);assertInstanceOf(ApiException.class,rejected);assertTrue(Set.of(401,409).contains(((ApiException)rejected).status));assertEquals(requests,count("study_feedback_requests"));assertEquals(version,conversationVersion(targetConversation));if(id!=null)assertEquals(before,feedbackRow(id));assertEquals(0,feedbackClient.calls);
            if(dispatched!=null)assertEquals(1L,db.queryForObject("SELECT count(*) FROM inference_usage_receipts WHERE reservation_id=?",Long.class,dispatched.reservation()));
        }finally{if(slot){db.execute("DROP TRIGGER synthetic_feedback_slot_wait ON inference_control_slots");db.execute("DROP FUNCTION synthetic_feedback_slot_wait()");}}
    }
    @Test void absoluteExpiryDuringDeferredSlotWaitRollsBackFreshFeedback()throws Exception{waitedExpiry("ADMISSION",true);}
    @Test void idleExpiryDuringActualStoredInputWaitPreventsDispatch()throws Exception{waitedExpiry("DISPATCH",false);}
    @Test void absoluteExpiryDuringActualNonceWaitCannotRetainMeasuredResponse()throws Exception{waitedExpiry("SETTLEMENT",true);}
    @Test void idleExpiryDuringRetainedSourceReadReturnsNoPrivateProjection()throws Exception{waitedExpiry("READ",false);}
}

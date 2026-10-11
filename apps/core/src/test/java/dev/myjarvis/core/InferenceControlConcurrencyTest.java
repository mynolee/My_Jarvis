package dev.myjarvis.core;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InferenceControlConcurrencyTest extends InferenceControlFixtures {
    InferenceControlService clocked(java.util.function.UnaryOperator<Map<String,Object>> change){
        local.close();var control=spy(new InferenceControlService(flow,client,contexts,freshness));control.close();
        doAnswer(call->change.apply(new LinkedHashMap<>((Map<String,Object>)call.callRealMethod()))).when(control).finalObservation(any(UUID.class),anyString());
        local=new LocalInferenceService(flow,client,contexts,freshness,app.getBean(UsageService.class),control);return control;
    }
    @Test void finalAdmissionClockAtOrAfterIdleExpiryRollsBackAllAllocations(){
        for(long excess:List.of(0L,1L)){
            clocked(row->{row.put("observed_at",Timestamp.from(AuthService.instant(row.get("last_seen_at")).plusSeconds(7200).plusNanos(excess*1000)));return row;});
            long before=version();assertEquals("SESSION_REVOKED",assertThrows(ApiException.class,this::accept).code);
            for(String table:List.of("runs","messages","inference_reservations","inference_contexts","inference_control_slots","outbox_events"))assertEquals(0L,count(table),table);
            assertEquals(before,version());assertEquals(0,client.infers.get());
        }
    }
    @Test void finalAdmissionClockAtAbsoluteExpiryCannotUseEarlierLiveCheck(){
        // Keep idle time current at the injected future boundary so this proves absolute expiry.
        clocked(row->{Instant expiry=AuthService.instant(row.get("created_at")).plusSeconds(43200);row.put("observed_at",Timestamp.from(expiry));row.put("last_seen_at",Timestamp.from(expiry.minusSeconds(1)));return row;});
        assertEquals("SESSION_REVOKED",assertThrows(ApiException.class,this::accept).code);assertEquals(0L,count("inference_control_slots"));assertEquals(0L,count("runs"));
    }
    @Test void backwardFinalAdmissionClockRollsBackAfterDeferredClosure(){
        Instant earlier=Instant.parse("2000-01-01T00:00:00Z");clocked(row->{row.put("observed_at",Timestamp.from(earlier));return row;});
        assertEquals("CONTROL_CLOCK_INVALID",assertThrows(ApiException.class,this::accept).code);assertEquals(0L,count("inference_control_slots"));assertEquals(0L,count("messages"));assertEquals(0,client.infers.get());
    }
    @Test void finalDispatchExpiryRollsBackDescriptorAndNeverCallsTransport()throws Exception {
        UUID run=accept();clocked(row->{row.put("observed_at",Timestamp.from(AuthService.instant(row.get("last_seen_at")).plusSeconds(7200)));return row;});
        assertTrue(local.runOne());assertEquals(0,client.infers.get());assertNull(slot(run).get("dispatch_commitment"));assertNull(slot(run).get("bound_at"));assertNull(db.queryForObject("SELECT started_at FROM inference_reservations WHERE run_id=?",Object.class,run));
        assertEquals("BLOCKED",status(run));assertEquals("CANCELLED",db.queryForObject("SELECT status FROM inference_reservations WHERE run_id=?",String.class,run));
        reconcile(run);assertEquals("UNSTARTED_CORE",slot(run).get("terminal_kind"));assertEquals("BLOCKED",status(run));assertEquals(0,client.controls.get());
    }
    @Test void backwardFinalDispatchClockNeverLeaksPreparedSource(){
        UUID run=accept();clocked(row->{row.put("observed_at",Timestamp.from(Instant.parse("2000-01-01T00:00:00Z")));return row;});
        assertTrue(local.runOne());assertEquals(0,client.infers.get());assertNull(slot(run).get("dispatch_commitment"));assertEquals(0L,count("inference_usage_receipts"));
    }
    @Test void finalOwnerEpochAndRevocationAreComparedWithOriginalStoredSession(){
        for(String field:List.of("owner_epoch","session_epoch","revoked_at")){
            clocked(row->{if(field.equals("revoked_at"))row.put(field,row.get("observed_at"));else row.put(field,session.epoch()+1);return row;});
            assertEquals("SESSION_REVOKED",assertThrows(ApiException.class,this::accept).code);assertEquals(0L,count("inference_control_slots"));
        }
    }
    @Test void finalObservationCannotPrecedeOriginalSessionCreationOrLastSeen(){
        for(String field:List.of("created_at","last_seen_at")){
            clocked(row->{row.put(field,Timestamp.from(AuthService.instant(row.get("observed_at")).plusSeconds(1)));return row;});
            assertEquals("CONTROL_CLOCK_INVALID",assertThrows(ApiException.class,this::accept).code);assertEquals(0L,count("inference_control_slots"));assertEquals(0L,count("runs"));
        }
    }
    @Test void expiryWhileSlotConstraintWaitsRollsBackNewAdmission()throws Exception {
        db.execute("CREATE FUNCTION control_test_wait() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_advisory_xact_lock(870017); RETURN NEW; END $$");
        db.execute("CREATE CONSTRAINT TRIGGER control_test_deferred AFTER INSERT ON inference_control_slots DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION control_test_wait()");
        try(var pool=Executors.newSingleThreadExecutor();var blocker=Objects.requireNonNull(db.getDataSource()).getConnection()){
            blocker.setAutoCommit(false);blocker.createStatement().execute("SELECT pg_advisory_xact_lock(870017)");
            // Only the passage of time invalidates the locked session; the other connection
            // holds a synthetic deferred constraint, not any source or authority row.
            db.update("UPDATE sessions SET last_seen_at=clock_timestamp()-interval '2 hours'+interval '2 seconds' WHERE token_hash=?",session.hash());
            var future=pool.submit(()->assertThrows(ApiException.class,this::accept));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);boolean waiting=false;
            while(System.nanoTime()<deadline){waiting=Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_locks WHERE locktype='advisory' AND NOT granted AND objid=870017)",Boolean.class));if(waiting)break;Thread.sleep(10);}
            assertTrue(waiting,"Admission must reach forced deferred closure before expiry");
            Thread.sleep(2100);blocker.commit();assertEquals("SESSION_REVOKED",future.get(5,TimeUnit.SECONDS).code);
            assertEquals(0L,count("inference_control_slots"));assertEquals(0L,count("runs"));assertEquals(0,client.infers.get());
        }finally{db.execute("DROP TRIGGER control_test_deferred ON inference_control_slots");db.execute("DROP FUNCTION control_test_wait()");}
    }
    @Test void originalSessionRevocationQueuedOnOwnerLockCannotAdmit()throws Exception {
        try(var pool=Executors.newSingleThreadExecutor()){
            var future=new ArrayList<Future<ApiException>>();flow.tx.executeWithoutResult(tx->{flow.lockOwner(owner);future.add(pool.submit(()->assertThrows(ApiException.class,this::accept)));db.update("UPDATE sessions SET revoked_at=clock_timestamp() WHERE token_hash=?",session.hash());});
            assertEquals("SESSION_REVOKED",future.getFirst().get(5,TimeUnit.SECONDS).code);assertEquals(0L,count("runs"));assertEquals(0L,count("inference_control_slots"));
        }
    }
    @Test void fairBoundedScanAdvancesPastSixteenPendingLowKeys(){
        for(int i=0;i<19;i++){accept();local.runOne();}
        var control=spy(new InferenceControlService(flow,client,contexts,freshness));control.close();doReturn(1000L).when(control).backoffNow();
        assertEquals(16,control.reconcileBatch());assertEquals(16,client.controls.get());
        assertEquals(3,control.reconcileBatch());assertEquals(19,client.controls.get());
        assertEquals(0,control.reconcileBatch());assertEquals(19,client.controls.get());assertEquals(19,client.infers.get());
        assertEquals(19L,db.queryForObject("SELECT count(*) FROM inference_control_slots WHERE terminal_kind IS NULL",Long.class));
    }
    @Test void perIdentityBackoffRequiresAtLeastFiveMonotonicSeconds(){
        accept();local.runOne();var control=spy(new InferenceControlService(flow,client,contexts,freshness));control.close();doReturn(1000L).when(control).backoffNow();
        assertEquals(1,control.reconcileBatch());doReturn(1000L+InferenceControlService.BACKOFF_NANOS-1).when(control).backoffNow();assertEquals(0,control.reconcileBatch());assertEquals(1,client.controls.get());
        doReturn(1000L+InferenceControlService.BACKOFF_NANOS).when(control).backoffNow();assertEquals(1,control.reconcileBatch());assertEquals(2,client.controls.get());assertEquals(1,client.infers.get());
    }
    @Test void concurrentIdenticalTerminalFactsHaveOneStableObservation()throws Exception {
        UUID run=accept();local.runOne();var request=InferenceControlService.controlRequest(slot(run),"OBSERVE");var fact=InferenceControlResult.verify(InferenceControlResultTest.response(request,"TERMINAL","COMPLETED"),request);
        long before=version();try(var pool=Executors.newFixedThreadPool(2)){var start=new CountDownLatch(1);Callable<Void> work=()->{start.await();local.controls.record(request,fact);return null;};var one=pool.submit(work);var two=pool.submit(work);start.countDown();one.get(5,TimeUnit.SECONDS);two.get(5,TimeUnit.SECONDS);}
        var stored=slot(run);local.controls.record(request,fact);assertEquals(stored,slot(run));assertEquals(before,version());assertEquals(1L,count("inference_control_slots"));assertEquals(1,client.infers.get());
    }
    @Test void noControlHttpCanExecuteWhileAnyDatabaseTransactionIsActive(){
        UUID run=accept();local.runOne();assertThrows(IllegalStateException.class,()->flow.tx.executeWithoutResult(tx->{try{reconcile(run);}catch(RuntimeException error){throw error;}catch(Exception error){throw new RuntimeException(error);}}));assertEquals(0,client.controls.get());
    }
    @Test void backwardCoreClockLeavesProofPendingAndLaterObservationCanPersistIt(){
        UUID run=accept();local.runOne();var request=InferenceControlService.controlRequest(slot(run),"OBSERVE");var fact=InferenceControlResult.verify(InferenceControlResultTest.response(request,"TERMINAL","COMPLETED"),request);
        var control=spy(new InferenceControlService(flow,client,contexts,freshness));control.close();Instant present=control.now();doReturn(present.plusSeconds(1),present).when(control).now();
        assertEquals("CONTROL_CLOCK_INVALID",assertThrows(ApiException.class,()->control.record(request,fact)).code);assertNull(slot(run).get("terminal_kind"));long before=version();
        doCallRealMethod().when(control).now();control.record(request,fact);assertEquals("COMPLETED",slot(run).get("terminal_kind"));assertEquals(before,version());assertEquals(1,client.infers.get());
    }
}

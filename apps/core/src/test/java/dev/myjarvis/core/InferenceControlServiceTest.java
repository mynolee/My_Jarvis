package dev.myjarvis.core;

import java.util.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic model/control responses exercise real Core transactions, never native inference. */
class InferenceControlServiceTest extends InferenceControlFixtures {
    @Test void newAdmissionPreallocatesOneSlotAndReplayDoesNotAllocate(){
        UUID key=UUID.randomUUID();var body=input(key);UUID run=(UUID)local.accept(session,conversation,body).get("run_id");
        assertEquals(1L,count("inference_control_slots"));assertEquals(1,db.queryForObject("SELECT control_revision FROM inference_reservations WHERE run_id=?",Integer.class,run));
        var before=slot(run);long version=version();assertEquals(run,local.accept(session,conversation,body).get("run_id"));
        assertEquals(before,slot(run));assertEquals(version,version());assertEquals(0,client.infers.get());assertEquals(0L,count("jobs"));assertEquals(0L,count("action_proposals"));
        assertNull(before.get("dispatch_commitment"));assertNull(before.get("terminal_kind"));assertEquals(64,((String)before.get("origin_binding")).length());
    }
    @Test void unavailablePairedReadinessAllocatesNothingButExistingReplayRemainsReadable(){
        client.available=false;assertEquals("LOCAL_MODEL_UNAVAILABLE",assertThrows(ApiException.class,this::accept).code);assertEquals(0L,count("runs"));assertEquals(0L,count("inference_control_slots"));
        client.available=true;UUID key=UUID.randomUUID();var body=input(key);UUID run=(UUID)local.accept(session,conversation,body).get("run_id");client.available=false;
        assertEquals(run,local.accept(session,conversation,body).get("run_id"));assertEquals(1L,count("inference_control_slots"));assertEquals(0,client.infers.get());
    }
    @Test void lessonAdmissionUsesExistingPurposeAndSameForwardOnlySlot(){
        UUID run=(UUID)local.acceptLesson(session,conversation,input(UUID.randomUUID())).get("run_id");
        assertEquals("STUDY_LESSON",slot(run).get("purpose"));assertEquals(1L,count("inference_control_slots"));assertEquals(0L,count("study_sessions"));assertEquals(0,client.infers.get());
    }
    @Test void dispatchBindsThreeDistinctDigestsAndFullDescriptor(){
        UUID run=accept();assertTrue(local.runOne());var slot=slot(run);var body=client.body;
        assertEquals(1,body.path("control").path("revision").asInt());assertEquals(slot.get("origin_binding"),body.path("control").path("origin_binding").asText());
        ObjectNode normal=(ObjectNode)body.deepCopy();normal.remove("control");String dispatch=contexts.controlDigest(owner,"inference-control-dispatch-v1",Json.canonical(normal));
        assertEquals(dispatch,slot.get("dispatch_commitment"));assertEquals(dispatch,body.path("control").path("dispatch_commitment").asText());assertNotEquals(dispatch,Json.hash(Json.canonical(body)));
        assertEquals(contexts.requestDigest(owner,Json.canonical(body)),db.queryForObject("SELECT body_hash FROM inference_reservations WHERE run_id=?",String.class,run));
        assertEquals(db.queryForObject("SELECT started_at FROM inference_reservations WHERE run_id=?",Object.class,run),slot.get("bound_at"));assertEquals(1,client.infers.get());
    }
    @Test void queuedCancellationRecordsOnlyLocalUnstartedEvidence()throws Exception {
        UUID run=accept();cancel(run);long before=version();var inventory=inventory();long rows=count("inference_control_slots");
        reconcile(run);assertEquals("UNSTARTED_CORE",slot(run).get("terminal_kind"));assertEquals("OWNER_CANCEL",slot(run).get("decision"));
        assertNull(slot(run).get("generation_id"));assertNull(slot(run).get("ledger_instance_id"));assertNull(slot(run).get("terminal_ordinal"));assertEquals(0,client.controls.get());assertEquals(0,client.infers.get());
        assertEquals(before,version());assertEquals(inventory,inventory());assertEquals(rows,count("inference_control_slots"));assertEquals(0L,count("inference_usage_receipts"));assertFalse(local.runOne());
    }
    @Test void liveQueuedRequestIsNotFencedByObservationOrNavigation()throws Exception {
        UUID run=accept();long before=version();reconcile(run);
        assertNull(slot(run).get("decision"));assertNull(slot(run).get("terminal_kind"));assertEquals("QUEUED",status(run));assertEquals(0,client.controls.get());assertEquals(0,client.infers.get());assertEquals(before,version());
    }
    @Test void successfulActiveAuthorityIsObservedWithoutStop()throws Exception {
        UUID run=accept();local.runOne();long before=version();client.kind="COMPLETED";reconcile(run);
        assertEquals(List.of("OBSERVE"),client.operations);assertEquals("COMPLETED",slot(run).get("terminal_kind"));assertEquals("SUCCEEDED",status(run));assertNull(slot(run).get("decision"));assertEquals(before,version());assertEquals(1,client.infers.get());assertEquals(1L,count("inference_usage_receipts"));
    }
    @Test void uncertainTransportStopsOnlyAfterCommittedUnknownWithoutRetry()throws Exception {
        client.failInfer=true;UUID run=accept();local.runOne();assertEquals("UNKNOWN",status(run));client.kind="UNSTARTED_FENCED";long before=version();
        reconcile(run);assertEquals(List.of("FENCE_OR_STOP"),client.operations);assertEquals("RECOVERY_UNKNOWN",slot(run).get("decision"));assertEquals("UNSTARTED_FENCED",slot(run).get("terminal_kind"));assertEquals("UNKNOWN",status(run));assertEquals(before,version());assertEquals(60000L,db.queryForObject("SELECT max_compute_ms FROM inference_reservations WHERE run_id=?",Long.class,run));assertEquals(0L,count("inference_usage_receipts"));assertFalse(local.runOne());assertEquals(1,client.infers.get());
    }
    @Test void revokedOriginalSessionCanReconcileWithoutSourceOrCurrentEpoch()throws Exception {
        client.failInfer=true;UUID run=accept();local.runOne();auth.logout(session);client.kind="WORKER_STOPPED";long before=version();var bytes=inventory();
        reconcile(run);assertEquals("SECURITY_REVOKED",slot(run).get("decision"));assertEquals("WORKER_STOPPED",slot(run).get("terminal_kind"));assertEquals("FENCE_OR_STOP",client.operations.getFirst());assertEquals(before,version());assertEquals(bytes,inventory());assertEquals("UNKNOWN",status(run));
    }
    @Test void timeoutPendingAndMissingNeverManufactureTerminalOrUsage()throws Exception {
        client.failInfer=true;UUID run=accept();local.runOne();
        for(String state:List.of("PENDING","NOT_OBSERVED")){client.state=state;reconcile(run);assertNull(slot(run).get("terminal_kind"));}
        client.failControl=true;assertThrows(IllegalStateException.class,()->reconcile(run));assertNull(slot(run).get("terminal_kind"));assertEquals(0L,count("inference_usage_receipts"));assertEquals("UNKNOWN",status(run));assertEquals(1,client.infers.get());
    }
    @Test void identicalFactReplayPreservesTimestampAndConflictingProofFails()throws Exception {
        UUID run=accept();local.runOne();var request=InferenceControlService.controlRequest(slot(run),"OBSERVE");var fact=InferenceControlResult.verify(InferenceControlResultTest.response(request,"TERMINAL","COMPLETED"),request);
        local.controls.record(request,fact);var before=slot(run);long version=version();local.controls.record(request,fact);assertEquals(before,slot(run));assertEquals(version,version());
        var conflict=new InferenceControlResult("TERMINAL",fact.ledger(),fact.kind(),fact.generation(),fact.ordinal()+1,fact.requestBinding());assertEquals("CONTROL_FACT_CONFLICT",assertThrows(ApiException.class,()->local.controls.record(request,conflict)).code);assertEquals(before,slot(run));
    }
    @Test void aVerifiedFactCannotBeRetargetedToAnotherExistingSlot(){
        UUID first=accept();local.runOne();UUID second=accept();local.runOne();var one=InferenceControlService.controlRequest(slot(first),"OBSERVE");var two=InferenceControlService.controlRequest(slot(second),"OBSERVE");
        var fact=InferenceControlResult.verify(InferenceControlResultTest.response(one,"TERMINAL","COMPLETED"),one);
        assertEquals("CONTROL_IDENTITY_CONFLICT",assertThrows(ApiException.class,()->local.controls.record(two,fact)).code);assertNull(slot(first).get("terminal_kind"));assertNull(slot(second).get("terminal_kind"));
    }
    @Test void factAfterSourceDeletionUsesOnlyRetainedIdentityAndDoesNotBumpVersion()throws Exception {
        UUID run=accept();local.runOne();var request=InferenceControlService.controlRequest(slot(run),"OBSERVE");
        var deletion=app.getBean(ConversationDeletionService.class);var state=deletion.state(session,conversation);
        var preparation=deletion.prepare(session,UUID.randomUUID(),conversation,node(Map.of("expected_version",state.get("content_version"),"expected_digest",state.get("content_digest"))));
        deletion.confirm(session,UUID.randomUUID(),(UUID)preparation.get("id"),node(Map.of("payload_hash",preparation.get("payload_hash"),"expected_version",preparation.get("expected_version"),"expected_digest",preparation.get("expected_digest"),"confirm",true)));
        long before=version();var inventory=inventory();auth.logout(session);
        local.controls.record(request,InferenceControlResult.verify(InferenceControlResultTest.response(request,"TERMINAL","COMPLETED"),request));
        assertEquals(before,version());assertEquals(inventory,inventory());assertEquals("COMPLETED",slot(run).get("terminal_kind"));assertEquals(0L,db.queryForObject("SELECT count(*) FROM messages WHERE content_ref IS NOT NULL",Long.class));assertNull(db.queryForObject("SELECT result_ref FROM runs WHERE id=?",String.class,run));assertEquals(1,client.infers.get());
    }
    @Test void lateFactReplayPreservesAnAlreadyPreparedDeletionManifest(){
        UUID run=accept();local.runOne();var deletion=app.getBean(ConversationDeletionService.class);var state=deletion.state(session,conversation);
        var preparation=deletion.prepare(session,UUID.randomUUID(),conversation,node(Map.of("expected_version",state.get("content_version"),"expected_digest",state.get("content_digest"))));
        var request=InferenceControlService.controlRequest(slot(run),"OBSERVE");var fact=InferenceControlResult.verify(InferenceControlResultTest.response(request,"TERMINAL","COMPLETED"),request);
        local.controls.record(request,fact);local.controls.record(request,fact);var after=deletion.state(session,conversation);
        assertEquals(state.get("content_version"),after.get("content_version"));assertEquals(state.get("content_digest"),after.get("content_digest"));assertEquals(state.get("eligibility"),after.get("eligibility"));
        var committed=deletion.confirm(session,UUID.randomUUID(),(UUID)preparation.get("id"),node(Map.of("payload_hash",preparation.get("payload_hash"),"expected_version",preparation.get("expected_version"),"expected_digest",preparation.get("expected_digest"),"confirm",true)));
        assertEquals("COMMITTED",committed.get("status"));assertEquals("COMPLETED",slot(run).get("terminal_kind"));assertEquals(1,client.infers.get());

        // A separate source graph proves the opposite chronology: its terminal fact is
        // already immutable, while a later safety decision is observed after preparation.
        conversation=UUID.randomUUID();db.update("INSERT INTO conversations(id,owner_id,title) VALUES (?,?,'Synthetic late decision')",conversation,owner);
        UUID laterRun=accept();local.runOne();var laterRequest=InferenceControlService.controlRequest(slot(laterRun),"OBSERVE");
        var laterFact=InferenceControlResult.verify(InferenceControlResultTest.response(laterRequest,"TERMINAL","COMPLETED"),laterRequest);
        local.controls.record(laterRequest,laterFact);var terminalBefore=slot(laterRun);assertNull(terminalBefore.get("decision"));assertNull(terminalBefore.get("decision_at"));
        // Fixture authority exists before the deletion snapshot; its legitimate source
        // version and manifest effects are therefore included in that preparation.
        flow.tx.executeWithoutResult(tx->{flow.access.requireRun(session,laterRun);assertEquals(1,db.update("UPDATE runs SET cancel_requested_at=clock_timestamp() WHERE owner_id=? AND id=? AND status='SUCCEEDED'",owner,laterRun));});
        var preparedState=deletion.state(session,conversation);
        var laterPreparation=deletion.prepare(session,UUID.randomUUID(),conversation,node(Map.of("expected_version",preparedState.get("content_version"),"expected_digest",preparedState.get("content_digest"))));
        assertNull(slot(laterRun).get("decision"));
        assertEquals("OWNER_CANCEL",flow.tx.execute(tx->db.queryForObject("SELECT inference_control_observe_decision(?,?)",String.class,owner,terminalBefore.get("reservation_id"))));
        var observed=slot(laterRun);assertEquals("OWNER_CANCEL",observed.get("decision"));
        assertFalse(AuthService.instant(observed.get("decision_at")).isBefore(AuthService.instant(observed.get("recorded_at"))));
        for(String field:List.of("terminal_kind","generation_id","ledger_instance_id","terminal_ordinal","recorded_at"))assertEquals(terminalBefore.get(field),observed.get(field),field);
        var afterDecision=deletion.state(session,conversation);
        for(String field:List.of("content_version","content_digest","eligibility"))assertEquals(preparedState.get(field),afterDecision.get(field),field);
        assertEquals("COMMITTED",deletion.confirm(session,UUID.randomUUID(),(UUID)laterPreparation.get("id"),node(Map.of("payload_hash",laterPreparation.get("payload_hash"),"expected_version",laterPreparation.get("expected_version"),"expected_digest",laterPreparation.get("expected_digest"),"confirm",true))).get("status"));
        assertEquals("COMPLETED",slot(laterRun).get("terminal_kind"));assertEquals(2,client.infers.get());
    }
    @Test void terminalFactsNeverWaiveLegacyUnknownDeletionOrReservedQuota()throws Exception {
        client.failInfer=true;var deletion=app.getBean(ConversationDeletionService.class);
        for(String kind:List.of("UNSTARTED_FENCED","WORKER_STOPPED")){
            UUID run=accept();local.runOne();var before=deletion.state(session,conversation);client.kind=kind;reconcile(run);var after=deletion.state(session,conversation);
            assertEquals(before.get("content_version"),after.get("content_version"));assertEquals(before.get("eligibility"),after.get("eligibility"));assertNull(after.get("content_digest"));
            assertTrue(((List<?>)((Map<?,?>)after.get("eligibility")).get("blockers")).contains("UNKNOWN_WORK"));assertEquals("UNKNOWN",status(run));assertEquals(kind,slot(run).get("terminal_kind"));
            assertEquals(60000L,db.queryForObject("SELECT max_compute_ms FROM inference_reservations WHERE run_id=?",Long.class,run));assertEquals(512L,db.queryForObject("SELECT reserved_output_tokens FROM inference_reservations WHERE run_id=?",Long.class,run));
            assertEquals("CONVERSATION_NOT_QUIESCENT",assertThrows(ApiException.class,()->deletion.prepare(session,UUID.randomUUID(),conversation,node(Map.of("expected_version",after.get("content_version"),"expected_digest","a".repeat(64))))).code);
        }
        assertEquals(0L,count("conversation_delete_preparations"));assertEquals(0L,count("inference_usage_receipts"));assertEquals(2,client.infers.get());assertEquals("INFERENCE_CAPACITY_RESERVED",assertThrows(ApiException.class,this::accept).code);
    }
    @Test void capacityFailureRollsBackSlotRunContextMessageAndEvent(){
        db.update("UPDATE conversations SET title=repeat('x',16777216) WHERE id=?",conversation);long before=version();
        assertEquals(413,assertThrows(ApiException.class,this::accept).status);assertEquals(0L,count("runs"));assertEquals(0L,count("inference_contexts"));assertEquals(0L,count("inference_control_slots"));assertEquals(0L,count("messages"));assertEquals(0L,count("outbox_events"));assertEquals(before,version());assertEquals(0,client.infers.get());
    }
    @Test void legacyCanonicalRequestHasNoDescriptorAndDoesNotCreateEvidence(){
        var row=Map.<String,Object>of("id",UUID.randomUUID(),"request_id",UUID.randomUUID(),"run_id",UUID.randomUUID(),"model_manifest_hash","a".repeat(64),"control_revision",0);
        var selected=Map.<String,Object>of("memories",List.of(),"history",List.of(),"timezone","UTC","current_time","2026-10-11T00:00:00Z");
        assertFalse(local.request(row,"Synthetic historical request",selected).has("control"));assertEquals(0L,count("inference_control_slots"));
        var unsupported=new LinkedHashMap<>(row);unsupported.put("control_revision",2);assertThrows(ApiException.class,()->local.request(unsupported,"Synthetic",selected));
    }
}

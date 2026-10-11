package dev.myjarvis.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StudyFeedbackDeletionTest extends StudyFeedbackFixtures {
    @Test void queuedRevocationRequiresExactLocalProofThenRedactsNineCountSnapshot() throws Exception {
        saveTwo();UUID key=UUID.randomUUID();var requested=feedback.request(session,key,studyId,requestBody(0));UUID id=(UUID)receipt(requested).get("feedback_request_id");
        feedback.revoke(session,UUID.randomUUID(),id,revokeBody(id));assertFalse(quiescent(id));
        assertEquals("BLOCKED",((Map<?,?>)deletion.state(session,targetConversation).get("eligibility")).get("status"));
        proof(id,"UNSTARTED_CORE");assertTrue(quiescent(id));var beforeEvents=db.queryForList("SELECT * FROM study_feedback_events WHERE feedback_id=? ORDER BY version",id);
        var preparation=prepare(targetConversation);var counts=(Map<?,?>)preparation.get("source_counts");assertEquals(9,counts.size());assertEquals(1,((Number)counts.get("feedback_requests")).intValue());assertEquals(0,((Number)counts.get("feedback_results")).intValue());
        long version=conversationVersion(targetConversation);commit(preparation,UUID.randomUUID());assertEquals(version+1,conversationVersion(targetConversation));
        var row=feedbackRow(id);assertNull(row.get("source_binding_ref"));assertNull(row.get("result_ref"));
        assertEquals(db.queryForObject("SELECT deleted_at FROM conversation_deletion_ledger WHERE conversation_id=?",Object.class,targetConversation),row.get("source_deleted_at"));
        assertEquals(beforeEvents,db.queryForList("SELECT * FROM study_feedback_events WHERE feedback_id=? ORDER BY version",id));assertTrue(quiescent(id));
        assertEquals(receipt(requested),receipt(feedback.mutation(session,key)));
        error(()->feedback.read(session,studyId),410,"CONVERSATION_DELETED");
    }
    @Test void revokedDispatchedUnknownKeepsExpenseAfterProofReleasesOnlyActiveCapacity() throws Exception {
        saveTwo();UUID id=requestFeedback(0);dispatch(id);feedback.revoke(session,UUID.randomUUID(),id,revokeBody(id));var before=feedbackRow(id);UUID reservation=(UUID)before.get("reservation_id");
        assertFalse(quiescent(id));assertEquals("UNKNOWN",db.queryForObject("SELECT status FROM inference_reservations WHERE id=?",String.class,reservation));
        long reserved=db.queryForObject("SELECT sum(max_compute_ms) FROM inference_reservations WHERE owner_id=?",Long.class,owner);
        proof(id,"WORKER_STOPPED");assertTrue(quiescent(id));assertEquals(before,feedbackRow(id));
        assertEquals("UNKNOWN",db.queryForObject("SELECT status FROM inference_reservations WHERE id=?",String.class,reservation));
        assertEquals(reserved,db.queryForObject("SELECT sum(max_compute_ms) FROM inference_reservations WHERE owner_id=?",Long.class,owner));
        assertEquals(0L,db.queryForObject("SELECT count(*) FROM inference_usage_receipts WHERE reservation_id=?",Long.class,reservation));
        commit(prepare(targetConversation),UUID.randomUUID());assertTrue(quiescent(id));
    }
    @Test void acceptedHistoricalInvalidationPreservesFeedbackAndCanDelete() throws Exception {
        saveTwo();UUID id=requestFeedback(0);complete(id);proof(id,"COMPLETED");var before=feedbackRow(id);assertTrue(quiescent(id));
        study.close(session,UUID.randomUUID(),studyId,closeBody(studyId));
        db.update("UPDATE inference_contexts SET state='INVALIDATED',invalidated_at=clock_timestamp() WHERE id=?",before.get("context_id"));
        assertTrue(quiescent(id));assertEquals(before,feedbackRow(id));assertNotNull(feedback.read(session,studyId));
        var p=prepare(targetConversation);assertEquals(1,((Number)((Map<?,?>)p.get("source_counts")).get("feedback_results")).intValue());commit(p,UUID.randomUUID());
        assertNull(feedbackRow(id).get("result_ref"));assertTrue(quiescent(id));
    }
    @Test void trustedFailedFeedbackCanDeriveFenceWithoutRequiringItsFutureProof() throws Exception {
        saveTwo();UUID id=requestFeedback(0);feedbackClient.kind="ERROR";assertTrue(feedbackLocal.runOne());
        var f=feedbackRow(id);assertEquals("FAILED",f.get("state"));assertFalse(quiescent(id));
        assertEquals("FEEDBACK_OUTPUT_CLOSED",db.queryForObject("SELECT inference_control_decision_reason(?,?)",String.class,owner,f.get("reservation_id")));
        assertEquals("ERROR",db.queryForObject("SELECT receipt_kind FROM inference_usage_receipts WHERE reservation_id=?",String.class,f.get("reservation_id")));
        proof(id,"UNSTARTED_FENCED");assertTrue(quiescent(id));assertEquals(f,feedbackRow(id));commit(prepare(targetConversation),UUID.randomUUID());
    }
    @Test void ordinaryUnknownAndMissingFeedbackProofRemainDeletionBlockers() throws Exception {
        saveTwo();UUID id=requestFeedback(0);feedback.revoke(session,UUID.randomUUID(),id,revokeBody(id));proof(id,"UNSTARTED_CORE");
        UUID ordinary=UUID.randomUUID();db.update("INSERT INTO runs(id,owner_id,conversation_id,status) VALUES (?,?,?,'UNKNOWN')",ordinary,owner,targetConversation);
        var state=deletion.state(session,targetConversation);assertEquals("BLOCKED",((Map<?,?>)state.get("eligibility")).get("status"));
        assertTrue(((List<?>)((Map<?,?>)state.get("eligibility")).get("blockers")).contains("UNKNOWN_WORK"));assertTrue(quiescent(id));
    }
    @Test void proofForAbsentForeignOrWrongPurposeReservationAlwaysReturnsFalse() {
        saveTwo();assertFalse(db.queryForObject("SELECT study_feedback_execution_quiescent_v18(?,?)",Boolean.class,owner,UUID.randomUUID()));
        UUID lessonReservation=db.queryForObject("SELECT id FROM inference_reservations WHERE purpose='STUDY_LESSON'",UUID.class);
        assertFalse(db.queryForObject("SELECT study_feedback_execution_quiescent_v18(?,?)",Boolean.class,owner,lessonReservation));
        UUID id=requestFeedback(0);assertFalse(db.queryForObject("SELECT study_feedback_execution_quiescent_v18(?,?)",Boolean.class,UUID.randomUUID(),feedbackRow(id).get("reservation_id")));
    }
    private Map<String,Object> chargedBudget(){
        return db.queryForMap("SELECT COALESCE(sum(max_compute_ms) FILTER (WHERE created_at>=date_trunc('day',now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'),0) AS day,COALESCE(sum(max_compute_ms) FILTER (WHERE created_at>=date_trunc('month',now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'),0) AS month FROM inference_reservations WHERE owner_id=? AND status<>'CANCELLED'",owner);
    }
    private Map<String,Object> admitOrdinary(UUID conversation){return feedbackLocal.accept(session,conversation,json(Map.of("client_message_id",UUID.randomUUID(),"text","Synthetic independent capacity request")));}
    @Test void exactProofReleasesOnlyOneOfTwoActiveSlotsBeforeAndAfterSourceDeletion() throws Exception {
        saveTwo();UUID id=requestFeedback(0);dispatch(id);feedback.revoke(session,UUID.randomUUID(),id,revokeBody(id));
        var lifecycle=context.getBean(StudyFeedbackLifecycle.class);assertEquals(1,lifecycle.activeCapacityCount(owner));
        UUID other=conversation("Other active reservation"),third=conversation("Third admission");admitOrdinary(other);assertEquals(2,lifecycle.activeCapacityCount(owner));
        var budget=chargedBudget();assertEquals(180000L,((Number)budget.get("day")).longValue());assertEquals(budget.get("day"),budget.get("month"));
        long reservations=count("inference_reservations");error(()->admitOrdinary(third),429,"INFERENCE_CAPACITY_RESERVED");assertEquals(reservations,count("inference_reservations"));
        var q=db.queryForMap("SELECT * FROM inference_reservations WHERE id=?",feedbackRow(id).get("reservation_id"));
        proof(id,"WORKER_STOPPED");assertEquals(1,lifecycle.activeCapacityCount(owner));assertEquals(budget,chargedBudget());assertEquals(q,db.queryForMap("SELECT * FROM inference_reservations WHERE id=?",q.get("id")));
        commit(prepare(targetConversation),UUID.randomUUID());assertEquals(1,lifecycle.activeCapacityCount(owner));assertEquals(budget,chargedBudget());assertEquals(q,db.queryForMap("SELECT * FROM inference_reservations WHERE id=?",q.get("id")));
        admitOrdinary(third);assertEquals(2,lifecycle.activeCapacityCount(owner));assertEquals(240000L,((Number)chargedBudget().get("day")).longValue());assertEquals(240000L,((Number)chargedBudget().get("month")).longValue());
        assertEquals("UNKNOWN",q.get("status"));assertEquals(0L,db.queryForObject("SELECT count(*) FROM inference_usage_receipts WHERE reservation_id=?",Long.class,q.get("id")));
    }
    @Test void preparedAndConsumedFeedbackManifestSurvivesAllowedLateControlObservations() throws Exception {
        saveTwo();UUID id=requestFeedback(0);complete(id);proof(id,"COMPLETED");var p=prepare(targetConversation);
        var prepared=db.queryForMap("SELECT * FROM conversation_delete_preparations WHERE id=?",p.get("id"));var state=deletion.state(session,targetConversation);long version=conversationVersion(targetConversation);
        UUID lessonReservation=db.queryForObject("SELECT id FROM inference_reservations WHERE run_id=(SELECT lesson_run_id FROM study_sessions WHERE id=?)",UUID.class,studyId);
        var slot=db.queryForMap("SELECT * FROM inference_control_slots WHERE reservation_id=?",lessonReservation);var request=InferenceControlService.controlRequest(slot,"OBSERVE");
        var response=InferenceControlResult.verify(InferenceControlResultTest.response(request,"TERMINAL","COMPLETED"),request);
        feedbackLocal.controls.record(request,response);assertNotNull(db.queryForObject("SELECT terminal_kind FROM inference_control_slots WHERE reservation_id=?",String.class,lessonReservation));
        assertEquals(prepared,db.queryForMap("SELECT * FROM conversation_delete_preparations WHERE id=?",p.get("id")));assertEquals(state.get("content_digest"),deletion.state(session,targetConversation).get("content_digest"));assertEquals(version,conversationVersion(targetConversation));
        UUID key=UUID.randomUUID();var committed=commit(p,key);var consumed=db.queryForMap("SELECT * FROM conversation_delete_preparations WHERE id=?",p.get("id"));var deletedFeedback=feedbackRow(id);var events=db.queryForList("SELECT * FROM study_feedback_events WHERE feedback_id=? ORDER BY version",id);
        feedbackLocal.controls.record(request,response); //First source-free CONTEXT_INVALIDATED decision may be observed after tombstone.
        assertEquals(version+1,conversationVersion(targetConversation));assertEquals(consumed,db.queryForMap("SELECT * FROM conversation_delete_preparations WHERE id=?",p.get("id")));assertEquals(deletedFeedback,feedbackRow(id));assertEquals(events,db.queryForList("SELECT * FROM study_feedback_events WHERE feedback_id=? ORDER BY version",id));
        assertEquals(receipt(committed),receipt(commit(p,key)));assertEquals(receipt(committed),receipt(deletion.reconcile(session,key)));
        for(String source:List.of("source_binding_ref='synthetic-late-source'","result_ref='synthetic-late-result'","source_deleted_at=NULL"))assertThrows(RuntimeException.class,()->db.update("UPDATE study_feedback_requests SET "+source+" WHERE id=?",id));
        assertThrows(RuntimeException.class,()->db.update("UPDATE runs SET result_ref='synthetic-late-result' WHERE id=?",deletedFeedback.get("run_id")));
        error(()->feedback.read(session,studyId),410,"CONVERSATION_DELETED");assertEquals(deletedFeedback,feedbackRow(id));
    }
    @Test void everyProductionProofOmissionAndIdentitySwapIsRejectedWithoutSourceWrites(){
        saveTwo();UUID id=requestFeedback(0);dispatch(id);feedback.revoke(session,UUID.randomUUID(),id,revokeBody(id));
        var f=feedbackRow(id);var before=db.queryForMap("SELECT * FROM inference_control_slots WHERE reservation_id=?",f.get("reservation_id"));var request=InferenceControlService.controlRequest(before,"OBSERVE");
        long version=conversationVersion(targetConversation);var inventory=db.queryForMap("SELECT * FROM conversation_source_inventory_v18(?,?)",owner,targetConversation);
        for(String field:InferenceControlResult.RESPONSE_FIELDS){var changed=InferenceControlResultTest.response(request,"TERMINAL","WORKER_STOPPED");changed.remove(field);assertThrows(ApiException.class,()->feedbackLocal.controls.record(request,InferenceControlResult.verify(changed,request)),field);}
        for(String field:InferenceControlResult.ID_FIELDS){var changed=InferenceControlResultTest.response(request,"TERMINAL","WORKER_STOPPED");changed.put(field,UUID.randomUUID().toString());assertThrows(ApiException.class,()->feedbackLocal.controls.record(request,InferenceControlResult.verify(changed,request)),field);}
        for(String field:List.of("origin_binding","dispatch_commitment","purpose")){var changed=InferenceControlResultTest.response(request,"TERMINAL","WORKER_STOPPED");changed.put(field,field.equals("purpose")?"CONVERSATION":"0".repeat(64));assertThrows(ApiException.class,()->feedbackLocal.controls.record(request,InferenceControlResult.verify(changed,request)),field);}
        for(String field:List.of("kind","generation_id","ordinal")){var changed=InferenceControlResultTest.response(request,"TERMINAL","WORKER_STOPPED");((tools.jackson.databind.node.ObjectNode)changed.get("terminal")).remove(field);assertThrows(ApiException.class,()->feedbackLocal.controls.record(request,InferenceControlResult.verify(changed,request)),field);}
        for(long ordinal:List.of(0L,-1L,MemoryService.MAX+1)){var changed=InferenceControlResultTest.response(request,"TERMINAL","WORKER_STOPPED");((tools.jackson.databind.node.ObjectNode)changed.get("terminal")).put("ordinal",ordinal);assertThrows(ApiException.class,()->feedbackLocal.controls.record(request,InferenceControlResult.verify(changed,request)));}
        assertFalse(quiescent(id));assertEquals(before,db.queryForMap("SELECT * FROM inference_control_slots WHERE reservation_id=?",f.get("reservation_id")));assertEquals(f,feedbackRow(id));assertEquals(version,conversationVersion(targetConversation));assertEquals(inventory,db.queryForMap("SELECT * FROM conversation_source_inventory_v18(?,?)",owner,targetConversation));
    }
    @Test void exactProducerReplayIsStableAndEveryConflictingTerminalFactRollsBack(){
        saveTwo();UUID id=requestFeedback(0);dispatch(id);feedback.revoke(session,UUID.randomUUID(),id,revokeBody(id));var f=feedbackRow(id);
        var request=InferenceControlService.controlRequest(db.queryForMap("SELECT * FROM inference_control_slots WHERE reservation_id=?",f.get("reservation_id")),"OBSERVE");var response=InferenceControlResultTest.response(request,"TERMINAL","WORKER_STOPPED");var verified=InferenceControlResult.verify(response,request);
        feedbackLocal.controls.record(request,verified);var exact=db.queryForMap("SELECT * FROM inference_control_slots WHERE reservation_id=?",f.get("reservation_id"));long version=conversationVersion(targetConversation);feedbackLocal.controls.record(request,verified);assertEquals(exact,db.queryForMap("SELECT * FROM inference_control_slots WHERE reservation_id=?",f.get("reservation_id")));
        for(String field:List.of("ledger_instance_id","kind","generation_id","ordinal")){
            var changed=response.deepCopy();if(field.equals("ledger_instance_id"))changed.put(field,UUID.randomUUID().toString());else {var terminal=(tools.jackson.databind.node.ObjectNode)changed.get("terminal");if(field.equals("ordinal"))terminal.put(field,2L);else terminal.put(field,field.equals("kind")?"COMPLETED":UUID.randomUUID().toString());}
            error(()->feedbackLocal.controls.record(request,InferenceControlResult.verify(changed,request)),409,"CONTROL_FACT_CONFLICT");assertEquals(exact,db.queryForMap("SELECT * FROM inference_control_slots WHERE reservation_id=?",f.get("reservation_id")));
        }
        for(String field:List.of("terminal_kind","generation_id","ledger_instance_id","terminal_ordinal","recorded_at"))assertThrows(RuntimeException.class,()->db.update("UPDATE inference_control_slots SET "+field+"=NULL WHERE reservation_id=?",f.get("reservation_id")),field);
        assertTrue(quiescent(id));assertEquals(f,feedbackRow(id));assertEquals(version,conversationVersion(targetConversation));
    }
    @Test void closedOutputReasonCannotBeForgedWithoutExactFailedFeedbackErrorReceipt(){
        saveTwo();UUID id=requestFeedback(0);var dispatch=dispatch(id);var f=feedbackRow(id);Object feedbackReservation=f.get("reservation_id");
        UUID lessonReservation=db.queryForObject("SELECT id FROM inference_reservations WHERE purpose='STUDY_LESSON'",UUID.class);
        for(Object reservation:List.of(feedbackReservation,lessonReservation)){
            assertNull(db.queryForObject("SELECT inference_control_decision_reason(?,?)",String.class,owner,reservation));
            assertThrows(RuntimeException.class,()->db.update("UPDATE inference_control_slots SET decision='FEEDBACK_OUTPUT_CLOSED',decision_at=clock_timestamp() WHERE reservation_id=?",reservation));
        }
        db.update("INSERT INTO inference_usage_receipts(owner_id,run_id,reservation_id,request_id,model_id,model_manifest_hash,request_digest,budget_revision,max_compute_ms,reserved_output_tokens,reserved_at,receipt_kind,response_kind,error_code,input_tokens,output_tokens,compute_ms,peak_memory_bytes) SELECT owner_id,run_id,id,request_id,model_id,model_manifest_hash,body_hash,budget_revision,max_compute_ms,reserved_output_tokens,created_at,'ERROR','ERROR','MODEL_UNAVAILABLE',0,0,0,0 FROM inference_reservations WHERE id=?",lessonReservation);
        var measured=StudyFeedbackResultTest.response(dispatch.body());var usage=context.getBean(UsageService.class);
        usage.record(dispatch,json(Map.of("kind","ERROR","error_code","MODEL_UNAVAILABLE","retryable",false,"usage",measured.get("usage"))));
        // Neither an old-purpose ERROR nor a feedback ERROR without closed FAILED metadata supplies authority.
        for(Object reservation:List.of(feedbackReservation,lessonReservation)){
            assertNull(db.queryForObject("SELECT inference_control_decision_reason(?,?)",String.class,owner,reservation));
            assertThrows(RuntimeException.class,()->db.update("UPDATE inference_control_slots SET decision='FEEDBACK_OUTPUT_CLOSED',decision_at=clock_timestamp() WHERE reservation_id=?",reservation));
        }
        // A genuine MEASURED FEEDBACK receipt and ACCEPTED metadata still cannot impersonate FAIL+ERROR.
        usage.record(dispatch,measured);feedback.settle(dispatch,measured);
        assertEquals("MEASURED",db.queryForObject("SELECT receipt_kind FROM inference_usage_receipts WHERE reservation_id=? AND receipt_kind='MEASURED'",String.class,feedbackReservation));
        assertNull(db.queryForObject("SELECT inference_control_decision_reason(?,?)",String.class,owner,feedbackReservation));
        assertThrows(RuntimeException.class,()->db.update("UPDATE inference_control_slots SET decision='FEEDBACK_OUTPUT_CLOSED',decision_at=clock_timestamp() WHERE reservation_id=?",feedbackReservation));assertFalse(quiescent(id));
    }

    private Map<String,Object> independentReviewFields() {
        var retained=json(feedback.read(session,studyId));
        String selected=retained.get("targets").get(0).get("request").get("result").get("review_checklist").get(0).asText();
        var fields=new LinkedHashMap<String,Object>();fields.put("title","Review study question 1");fields.put("description","- "+selected);
        fields.put("project_id",null);fields.put("due_at",null);fields.put("priority","NORMAL");fields.put("estimated_minutes",null);return fields;
    }
    private Map<String,Object> independentReviewIntent(UUID target,Map<String,Object> fields) {
        var body=new LinkedHashMap<String,Object>();body.put("tool_id",target==null?"task.create":"task.update");body.put("target_id",target);body.put("arguments",fields);
        return context.getBean(TaskIntentService.class).issue(session,UUID.randomUUID(),json(body));
    }
    private tools.jackson.databind.JsonNode independentReviewMutation(Map<String,Object> intent,Map<String,Object> fields) {
        var body=new LinkedHashMap<>(fields);body.put("user_intent_id",intent.get("id"));return json(body);
    }
    private Map<String,Object> independentReviewSourceSnapshot() {
        var snapshot=new LinkedHashMap<String,Object>();
        for(String table:List.of("study_sessions","study_feedback_requests","study_feedback_events","study_owner_answer_inputs","inference_reservations","inference_usage_receipts","inference_control_slots","runs","jobs")) snapshot.put(table,db.queryForList("SELECT * FROM "+table));
        snapshot.put("conversation_version",conversationVersion(targetConversation));snapshot.put("owner_epochs",db.queryForMap("SELECT history_epoch,context_epoch FROM owners WHERE id=?",owner));snapshot.put("feedback_calls",feedbackClient.calls);return snapshot;
    }
    @Test void independentChecklistTaskAndEncryptedCopiesRemainAfterQuiescentSourceDeletion() throws Exception {
        saveTwo();UUID feedbackId=requestFeedback(0);complete(feedbackId);assertFalse(quiescent(feedbackId));proof(feedbackId,"COMPLETED");assertTrue(quiescent(feedbackId));
        var fields=independentReviewFields();var intent=independentReviewIntent(null,fields);var tasks=context.getBean(TaskService.class);UUID key=UUID.randomUUID();
        assertEquals(0L,count("tasks"));var task=tasks.create(session,key,independentReviewMutation(intent,fields));UUID taskId=UUID.fromString(task.get("id").toString());
        assertEquals("TODO",task.get("status"));assertEquals(1L,task.get("version"));
        var taskRow=db.queryForMap("SELECT * FROM tasks WHERE id=?",taskId);var intentRow=db.queryForMap("SELECT * FROM task_user_intents WHERE id=?",intent.get("id"));var mutationRow=db.queryForMap("SELECT * FROM task_mutations WHERE idempotency_key=?",key);
        for(var row:List.of(intentRow,mutationRow)) assertFalse(row.get("response_ref").toString().contains(fields.get("description").toString()));
        assertFalse(taskRow.get("description_ref").toString().contains(fields.get("description").toString()));
        commit(prepare(targetConversation),UUID.randomUUID());error(()->feedback.read(session,studyId),410,"CONVERSATION_DELETED");
        assertEquals(taskRow,db.queryForMap("SELECT * FROM tasks WHERE id=?",taskId));assertEquals(intentRow,db.queryForMap("SELECT * FROM task_user_intents WHERE id=?",intent.get("id")));assertEquals(mutationRow,db.queryForMap("SELECT * FROM task_mutations WHERE idempotency_key=?",key));
        assertEquals(Json.canonical(task),Json.canonical(tasks.get(session,taskId)));assertEquals(Json.canonical(task),Json.canonical(tasks.reconcile(session,key).get("task")));
        assertNull(feedbackRow(feedbackId).get("result_ref"));assertNull(feedbackRow(feedbackId).get("source_binding_ref"));assertEquals(1L,count("tasks"));assertEquals(1L,count("task_user_intents"));assertEquals(1L,count("task_mutations"));
        assertEquals(Set.of(TaskService.CREATE_FIELDS),fields.keySet());assertFalse(fields.containsKey("study_session_id"));
    }
    @Test void independentIntentCanApplyAfterSourceDeletionWithoutLiveSourceAttestation() throws Exception {
        saveTwo();UUID feedbackId=requestFeedback(0);complete(feedbackId);proof(feedbackId,"COMPLETED");assertTrue(quiescent(feedbackId));
        var fields=independentReviewFields();var intent=independentReviewIntent(null,fields);var stored=db.queryForMap("SELECT * FROM task_user_intents WHERE id=?",intent.get("id"));
        assertNotNull(stored.get("response_ref"));assertNull(stored.get("consumed_at"));assertEquals(0L,count("tasks"));
        commit(prepare(targetConversation),UUID.randomUUID());error(()->feedback.read(session,studyId),410,"CONVERSATION_DELETED");
        assertEquals(stored,db.queryForMap("SELECT * FROM task_user_intents WHERE id=?",intent.get("id")));
        var source=independentReviewSourceSnapshot();var task=context.getBean(TaskService.class).create(session,UUID.randomUUID(),independentReviewMutation(intent,fields));
        assertEquals("TODO",task.get("status"));assertEquals(fields.get("description"),task.get("description"));assertEquals(source,independentReviewSourceSnapshot());assertNull(feedbackRow(feedbackId).get("result_ref"));
    }
    @Test void independentTaskCreateAndExplicitCompletionNeverMutateStudyOrInferenceEvidence() throws Exception {
        saveTwo();UUID feedbackId=requestFeedback(0);complete(feedbackId);proof(feedbackId,"COMPLETED");
        var source=independentReviewSourceSnapshot();var fields=independentReviewFields();var intent=independentReviewIntent(null,fields);assertEquals(source,independentReviewSourceSnapshot());
        var tasks=context.getBean(TaskService.class);var task=tasks.create(session,UUID.randomUUID(),independentReviewMutation(intent,fields));UUID taskId=UUID.fromString(task.get("id").toString());
        assertEquals("TODO",task.get("status"));assertEquals(source,independentReviewSourceSnapshot());
        long version=1;
        for(String status:List.of("DONE","TODO")) {
            var patch=Map.<String,Object>of("status",status,"expected_version",version);var update=independentReviewIntent(taskId,patch);
            task=tasks.update(session,UUID.randomUUID(),taskId,independentReviewMutation(update,patch));assertEquals(status,task.get("status"));assertEquals(++version,task.get("version"));assertEquals(source,independentReviewSourceSnapshot());
        }
        assertEquals("ACCEPTED",feedbackRow(feedbackId).get("state"));assertTrue(quiescent(feedbackId));
    }

}

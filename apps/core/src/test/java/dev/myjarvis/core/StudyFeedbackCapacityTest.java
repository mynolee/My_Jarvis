package dev.myjarvis.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StudyFeedbackCapacityTest extends StudyFeedbackFixtures {
    @Test void actualAdmissionAddsSixRowsAndBindingDigestAndFixedSlotExactlyOnce() {
        saveTwo();var before=db.queryForMap("SELECT * FROM conversation_source_inventory_v18(?,?)",owner,targetConversation);UUID id=requestFeedback(0);
        var after=db.queryForMap("SELECT * FROM conversation_source_inventory_v18(?,?)",owner,targetConversation);
        assertEquals(((Number)before.get("scoped_rows")).longValue()+6,((Number)after.get("scoped_rows")).longValue());
        assertEquals(((Number)before.get("source_bytes")).longValue()+142+128+64,((Number)after.get("source_bytes")).longValue());
        dispatch(id);var dispatched=db.queryForMap("SELECT * FROM conversation_source_inventory_v18(?,?)",owner,targetConversation);
        assertEquals(((Number)after.get("source_bytes")).longValue(),((Number)dispatched.get("source_bytes")).longValue());
        assertEquals(((Number)after.get("scoped_rows")).longValue()+1,((Number)dispatched.get("scoped_rows")).longValue());
    }
    @Test void vaultCiphertextFormulaUsesUtf8AndFitsSingleReservedResult() {
        for(String plain:List.of("","x".repeat(64),"x".repeat(65536),"😀".repeat(16384))){
            String sealed=flow.vault.seal(owner,UUID.randomUUID(),"synthetic-feedback-size",plain);
            long bytes=plain.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            assertEquals(34+4*((bytes+16+2)/3),sealed.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        }
        assertEquals(87438,34+4*((65536+16+2)/3));assertTrue(87438+128<=131072);
    }
    @Test void thousandRunCapCannotBeExpandedByFeedbackLogicalQuota() {
        saveTwo();flow.tx.executeWithoutResult(t->{for(int i=1;i<1000;i++)db.update("INSERT INTO runs(id,owner_id,conversation_id,status,context_history_eligible) VALUES (?,?,?,'SUCCEEDED',true)",UUID.randomUUID(),owner,targetConversation);});
        long runs=count("runs"),contexts=count("inference_contexts"),events=count("study_feedback_events");
        assertThrows(RuntimeException.class,()->requestFeedback(0));assertEquals(runs,count("runs"));assertEquals(contexts,count("inference_contexts"));assertEquals(events,count("study_feedback_events"));
    }
    private void nearVersion(long admittedPrior,long setupCost){
        targetConversation=UUID.randomUUID();db.update("INSERT INTO conversations(id,owner_id,title,content_version) VALUES (?,?,'Synthetic version boundary',?)",targetConversation,owner,admittedPrior-setupCost);
        studyId=start(targetConversation,lesson(targetConversation));study.answer(session,UUID.randomUUID(),studyId,answerBody(studyId,0,"First"));study.answer(session,UUID.randomUUID(),studyId,answerBody(studyId,1,"Second"));
        assertEquals(admittedPrior,conversationVersion(targetConversation));
    }
    @Test void equalityReservesTwoPhasesAllContextsAndFinalDeletionIncrement() {
        saveTwo();long setup=conversationVersion(targetConversation)-1;nearVersion(MemoryService.MAX-39,setup);
        UUID id=requestFeedback(0);assertEquals(MemoryService.MAX-35,conversationVersion(targetConversation));
        feedback.revoke(session,UUID.randomUUID(),id,revokeBody(id));assertEquals(MemoryService.MAX-31,conversationVersion(targetConversation));
        assertFalse(quiescent(id));
    }
    @Test void oneBeyondReservedVersionBudgetRollsBackEntireAllocation() {
        saveTwo();long setup=conversationVersion(targetConversation)-1;nearVersion(MemoryService.MAX-38,setup);
        long runs=count("runs"),contexts=count("inference_contexts"),slots=count("inference_control_slots");
        assertThrows(RuntimeException.class,()->requestFeedback(0));assertEquals(runs,count("runs"));assertEquals(contexts,count("inference_contexts"));assertEquals(slots,count("inference_control_slots"));assertEquals(0L,count("study_feedback_requests"));
    }
    private void fillSourceBytes(long desired){
        UUID run=UUID.randomUUID();db.update("INSERT INTO runs(id,owner_id,conversation_id,status) VALUES (?,?,?,'SUCCEEDED')",run,owner,targetConversation);
        long bytes=db.queryForObject("SELECT source_bytes FROM conversation_source_inventory_v18(?,?)",Long.class,owner,targetConversation);
        db.update("UPDATE runs SET result_ref=repeat('x',?) WHERE id=?",Math.toIntExact(desired-bytes),run);
        assertEquals(desired,db.queryForObject("SELECT source_bytes FROM conversation_source_inventory_v18(?,?)",Long.class,owner,targetConversation));
    }
    @Test void exactSourceByteEscrowBoundaryAdmitsWithoutDoubleChargingSlot() {
        saveTwo();fillSourceBytes(16777216L-131072-142-128-64);UUID id=requestFeedback(0);
        assertEquals(16777216L-131072,db.queryForObject("SELECT source_bytes FROM conversation_source_inventory_v18(?,?)",Long.class,owner,targetConversation));
        assertEquals("QUEUED",feedbackRow(id).get("state"));
    }
    @Test void oneByteBeyondFutureEscrowRefusesAndLeavesNoAllocation() {
        saveTwo();fillSourceBytes(16777216L-131072-142-128-64+1);long runs=count("runs"),slots=count("inference_control_slots");
        assertThrows(RuntimeException.class,()->requestFeedback(0));assertEquals(0L,count("study_feedback_requests"));assertEquals(runs,count("runs"));assertEquals(slots,count("inference_control_slots"));
    }
    private void fillSourceRows(long desired){
        UUID contextId=db.queryForObject("SELECT id FROM inference_contexts WHERE conversation_id=?",UUID.class,targetConversation);
        long before=db.queryForObject("SELECT scoped_rows FROM conversation_source_inventory_v18(?,?)",Long.class,owner,targetConversation);int additional=Math.toIntExact(desired-before);
        db.update("INSERT INTO memory_slots(id,owner_id,scope,key_digest,generation) SELECT ('91000000-0000-4000-8000-'||lpad(i::text,12,'0'))::uuid,?,'GLOBAL',lpad(i::text,64,'0'),1 FROM generate_series(1,?) i",owner,additional);
        db.update("INSERT INTO memories(id,owner_id,slot_id,kind,generation,version,payload_ref,confirmed_at) SELECT ('92000000-0000-4000-8000-'||lpad(i::text,12,'0'))::uuid,?,('91000000-0000-4000-8000-'||lpad(i::text,12,'0'))::uuid,'PREFERENCE',1,1,'synthetic-fixture-only',clock_timestamp() FROM generate_series(1,?) i",owner,additional);
        db.update("INSERT INTO inference_context_memories(context_id,source_id,memory_id,slot_id,version,generation) SELECT ?,('93000000-0000-4000-8000-'||lpad(i::text,12,'0'))::uuid,('92000000-0000-4000-8000-'||lpad(i::text,12,'0'))::uuid,('91000000-0000-4000-8000-'||lpad(i::text,12,'0'))::uuid,1,1 FROM generate_series(1,?) i",contextId,additional);
        assertEquals(desired,db.queryForObject("SELECT scoped_rows FROM conversation_source_inventory_v18(?,?)",Long.class,owner,targetConversation));
    }
    @Test void physicalRowEqualityReservesBothRemainingEventRows() {
        saveTwo();fillSourceRows(40000-6-2);UUID id=requestFeedback(0);
        assertEquals(39998L,db.queryForObject("SELECT scoped_rows FROM conversation_source_inventory_v18(?,?)",Long.class,owner,targetConversation));assertEquals("QUEUED",feedbackRow(id).get("state"));
        long admitted=conversationVersion(targetConversation);var source=db.queryForMap("SELECT * FROM conversations WHERE id=?",targetConversation);
        assertThrows(RuntimeException.class,()->db.update("UPDATE conversations SET title='escrow thief' WHERE id=?",targetConversation));
        assertThrows(RuntimeException.class,()->study.close(session,UUID.randomUUID(),studyId,closeBody(studyId)));
        assertThrows(RuntimeException.class,()->db.update("INSERT INTO runs(id,owner_id,conversation_id,status) VALUES (?,?,?,'SUCCEEDED')",UUID.randomUUID(),owner,targetConversation));
        assertEquals(source,db.queryForMap("SELECT * FROM conversations WHERE id=?",targetConversation));assertEquals(admitted,conversationVersion(targetConversation));
        var dispatched=dispatch(id);assertEquals(39999L,db.queryForObject("SELECT scoped_rows FROM conversation_source_inventory_v18(?,?)",Long.class,owner,targetConversation));assertEquals(admitted+5,conversationVersion(targetConversation));
        var response=StudyFeedbackResultTest.response(dispatched.body());context.getBean(UsageService.class).record(dispatched,response);feedback.settle(dispatched,response);
        assertEquals(40000L,db.queryForObject("SELECT scoped_rows FROM conversation_source_inventory_v18(?,?)",Long.class,owner,targetConversation));assertEquals(admitted+9,conversationVersion(targetConversation));assertEquals("ACCEPTED",feedbackRow(id).get("state"));
    }
    @Test void physicalRowOneBeyondEscrowRollsBackEveryFeedbackRow() {
        saveTwo();fillSourceRows(40000-6-2+1);long rows=db.queryForObject("SELECT scoped_rows FROM conversation_source_inventory_v18(?,?)",Long.class,owner,targetConversation);
        assertThrows(RuntimeException.class,()->requestFeedback(0));assertEquals(0L,count("study_feedback_requests"));assertEquals(rows,db.queryForObject("SELECT scoped_rows FROM conversation_source_inventory_v18(?,?)",Long.class,owner,targetConversation));
        var removable=db.queryForMap("SELECT context_id,memory_id FROM inference_context_memories ORDER BY memory_id LIMIT 1");
        assertEquals(1,db.update("DELETE FROM inference_context_memories WHERE context_id=? AND memory_id=?",removable.get("context_id"),removable.get("memory_id")));
        UUID id=requestFeedback(0);assertEquals(39998L,db.queryForObject("SELECT scoped_rows FROM conversation_source_inventory_v18(?,?)",Long.class,owner,targetConversation));
        long active=conversationVersion(targetConversation);assertThrows(RuntimeException.class,()->db.update("INSERT INTO runs(id,owner_id,conversation_id,status) VALUES (?,?,?,'SUCCEEDED')",UUID.randomUUID(),owner,targetConversation));assertEquals(active,conversationVersion(targetConversation));
        feedback.revoke(session,UUID.randomUUID(),id,revokeBody(id));assertEquals(39999L,db.queryForObject("SELECT scoped_rows FROM conversation_source_inventory_v18(?,?)",Long.class,owner,targetConversation));assertEquals(active+4,conversationVersion(targetConversation));assertFalse(quiescent(id));
        // Output closure releases the unused DISPATCH event credit; termination proof is a separate gate.
        assertEquals(1,db.update("INSERT INTO runs(id,owner_id,conversation_id,status) VALUES (?,?,?,'SUCCEEDED')",UUID.randomUUID(),owner,targetConversation));
        assertEquals(40000L,db.queryForObject("SELECT scoped_rows FROM conversation_source_inventory_v18(?,?)",Long.class,owner,targetConversation));long closed=conversationVersion(targetConversation);
        assertThrows(RuntimeException.class,()->db.update("INSERT INTO runs(id,owner_id,conversation_id,status) VALUES (?,?,?,'SUCCEEDED')",UUID.randomUUID(),owner,targetConversation));assertEquals(closed,conversationVersion(targetConversation));
    }

    /** SQL-valid historical source, with every admission/dispatch/study transition and receipt retained. */
    private void addHistoricalStudy(){
        UUID run=UUID.randomUUID(),reservation=UUID.randomUUID(),request=UUID.randomUUID(),contextId=UUID.randomUUID(),study=UUID.randomUUID(),q1=UUID.randomUUID(),q2=UUID.randomUUID();
        flow.tx.executeWithoutResult(t->{
            db.update("INSERT INTO runs(id,owner_id,conversation_id,status) VALUES (?,?,?,'QUEUED')",run,owner,targetConversation);
            db.update("INSERT INTO inference_reservations(id,owner_id,run_id,request_id,body_hash,model_manifest_hash,status,max_compute_ms,reserved_output_tokens,security_epoch,purpose,control_revision) VALUES (?,?,?,?,?,?,'QUEUED',1,1,1,'STUDY_LESSON',1)",reservation,owner,run,request,"a".repeat(64),"b".repeat(64));
            db.update("INSERT INTO inference_contexts(id,owner_id,run_id,conversation_id,session_hash,security_epoch,context_epoch,history_epoch,state) VALUES (?,?,?,?,?,1,1,1,'CAPTURED')",contextId,owner,run,targetConversation,session.hash());
            db.update("INSERT INTO inference_control_slots(reservation_id,owner_id,run_id,request_id,conversation_id,context_id,revision,purpose,origin_binding,security_epoch,created_at) VALUES (?,?,?,?,?,?,1,'STUDY_LESSON',?,1,clock_timestamp())",reservation,owner,run,request,targetConversation,contextId,"c".repeat(64));
            db.update("UPDATE inference_contexts SET state='PREPARED',profile_version=1 WHERE id=?",contextId);db.update("UPDATE inference_contexts SET state='DISPATCHED' WHERE id=?",contextId);
            db.update("UPDATE runs SET status='RUNNING',version=2 WHERE id=?",run);
            db.update("UPDATE inference_reservations SET status='STARTED',started_at=clock_timestamp(),dispatch_context_id=?,dispatch_cache_policy='CONTEXT_V1_NO_RESPONSE_CACHE' WHERE id=?",contextId,reservation);
            db.update("UPDATE inference_control_slots SET dispatch_commitment=?,bound_at=(SELECT started_at FROM inference_reservations WHERE id=?) WHERE reservation_id=?","d".repeat(64),reservation,reservation);
            db.update("UPDATE inference_reservations SET status='SUCCEEDED',completed_at=clock_timestamp() WHERE id=?",reservation);db.update("UPDATE inference_contexts SET state='SETTLED' WHERE id=?",contextId);
            db.update("UPDATE runs SET status='SUCCEEDED',version=3,result_ref='synthetic-sealed-historical-lesson' WHERE id=?",run);
            db.update("INSERT INTO inference_usage_receipts(owner_id,run_id,reservation_id,request_id,model_id,model_manifest_hash,request_digest,budget_revision,max_compute_ms,reserved_output_tokens,reserved_at,receipt_kind,response_kind,input_tokens,output_tokens,compute_ms,peak_memory_bytes) SELECT owner_id,run_id,id,request_id,model_id,model_manifest_hash,body_hash,budget_revision,max_compute_ms,reserved_output_tokens,created_at,'MEASURED','LESSON',1,1,1,1 FROM inference_reservations WHERE id=?",reservation);
            db.update("WITH t AS (SELECT clock_timestamp() at) INSERT INTO study_sessions(id,owner_id,conversation_id,lesson_run_id,lesson_run_version,question_1_id,question_2_id,lesson_binding_ref,version,lifecycle,created_at,updated_at) SELECT ?,?,?,?,3,?,?,'synthetic-historical-binding',1,'OPEN',at,at FROM t",study,owner,targetConversation,run,q1,q2);
            db.update("INSERT INTO study_mutations(owner_id,idempotency_key,operation,session_hash,security_epoch,request_digest,conversation_id,study_session_id,lesson_run_id,resulting_version,prior_conversation_version,resulting_conversation_version,committed_at) SELECT ?,?,'START',?,1,?,?,?, ?,1,c.content_version,c.content_version+1,s.created_at FROM conversations c JOIN study_sessions s ON s.conversation_id=c.id WHERE c.id=? AND s.id=?",owner,UUID.randomUUID(),session.hash(),"e".repeat(64),targetConversation,study,run,targetConversation,study);
            for(int version=1;version<=2;version++){
                UUID input=UUID.randomUUID(),attempt=UUID.randomUUID(),question=version==1?q1:q2;
                db.update("INSERT INTO study_owner_answer_inputs(id,attempt_id,owner_id,conversation_id,study_session_id,question_id,expected_study_version,resulting_study_version,session_hash,security_epoch,observed_conversation_version,created_at,text_ref,source_binding_ref) SELECT ?,?,?,?,?,?,?,?, ?,1,content_version,clock_timestamp(),'synthetic-historical-answer','synthetic-historical-input-binding' FROM conversations WHERE id=?",input,attempt,owner,targetConversation,study,question,version,version+1,session.hash(),targetConversation);
                db.update("UPDATE study_sessions SET version=version+1,updated_at=clock_timestamp() WHERE id=?",study);
                db.update("INSERT INTO study_mutations(owner_id,idempotency_key,operation,session_hash,security_epoch,request_digest,conversation_id,study_session_id,lesson_run_id,question_id,attempt_id,input_id,prior_version,resulting_version,prior_conversation_version,resulting_conversation_version,committed_at) SELECT ?,?,'ANSWER',?,1,?,?,?,?,?,?,?,?,?,c.content_version,c.content_version+1,s.updated_at FROM conversations c JOIN study_sessions s ON s.conversation_id=c.id WHERE c.id=? AND s.id=?",owner,UUID.randomUUID(),session.hash(),"f".repeat(64),targetConversation,study,run,question,attempt,input,version,version+1,targetConversation,study);
            }
        });
    }
    @Test void allReachableInventoryCapsAtEqualityRetainTheTighterRunAndPhysicalConjunction(){
        saveTwo();for(int i=1;i<1000;i++)addHistoricalStudy();
        // Two messages and one supported action per run produce the reachable joint equality.
        for(UUID run:db.queryForList("SELECT id FROM runs WHERE conversation_id=? ORDER BY id",UUID.class,targetConversation)){
            int messages=db.queryForObject("SELECT count(*) FROM messages WHERE run_id=?",Integer.class,run);
            for(int i=messages;i<2;i++)db.update("INSERT INTO messages(id,owner_id,conversation_id,run_id,client_message_id,body_hash,content_ref,role,trust) VALUES (?,?,?,?,?,?,'synthetic-content','USER','USER_DIRECT')",UUID.randomUUID(),owner,targetConversation,run,UUID.randomUUID(),"a".repeat(64));
            db.update("INSERT INTO action_proposals(id,owner_id,run_id,tool_id,tool_version,payload_ref,payload_hash,status,security_epoch,valid_until) VALUES (?,?,?,'calendar.event.create','1','synthetic-action',?,'SUCCEEDED',1,clock_timestamp())",UUID.randomUUID(),owner,run,"a".repeat(64));
        }
        for(String table:List.of("runs","action_proposals","inference_contexts","study_sessions","inference_control_slots"))assertEquals(1000L,db.queryForObject("SELECT count(*) FROM "+table,Long.class),table);
        assertEquals(2000L,count("messages"));assertEquals(2000L,count("study_owner_answer_inputs"));
        var inventory=db.queryForMap("SELECT * FROM conversation_source_inventory_v18(?,?)",owner,targetConversation);assertTrue(((Number)inventory.get("scoped_rows")).longValue()<40000);
        long before=conversationVersion(targetConversation);long rows=((Number)inventory.get("scoped_rows")).longValue();
        assertThrows(RuntimeException.class,()->requestFeedback(0));assertEquals(before,conversationVersion(targetConversation));assertEquals(0L,count("study_feedback_requests"));
        UUID run=db.queryForObject("SELECT lesson_run_id FROM study_sessions WHERE id=?",UUID.class,studyId);
        assertThrows(RuntimeException.class,()->flow.tx.executeWithoutResult(t->{db.update("INSERT INTO messages(id,owner_id,conversation_id,run_id,client_message_id,body_hash,content_ref,role,trust) VALUES (?,?,?,?,?,?,'one beyond','USER','USER_DIRECT')",UUID.randomUUID(),owner,targetConversation,run,UUID.randomUUID(),"a".repeat(64));db.queryForMap("SELECT * FROM conversation_source_inventory_v18(?,?)",owner,targetConversation);}));
        assertThrows(RuntimeException.class,()->addHistoricalStudy()); //1001st run: no study/context/slot may evade its cap.
        // Supported actions are UNIQUE(owner,tool,run); a1001st supported action needs a1001st run.
        assertThrows(RuntimeException.class,()->db.update("INSERT INTO action_proposals(id,owner_id,run_id,tool_id,tool_version,payload_ref,payload_hash,status,security_epoch,valid_until) VALUES (?,?,?,'calendar.event.create','1','duplicate supported action',?,'SUCCEEDED',1,clock_timestamp())",UUID.randomUUID(),owner,run,"a".repeat(64)));
        assertEquals(rows,db.queryForObject("SELECT scoped_rows FROM conversation_source_inventory_v18(?,?)",Long.class,owner,targetConversation));assertEquals(before,conversationVersion(targetConversation));
        // Studies/context/slots are injective into runs, answers have exactly2 original questions.
        // Feedback has a fresh run and a prior distinct lesson run per at most2 requests: F+ceil(F/2)<=1000,
        // hence F<=666 and events<=1998. Independent2000/6000 equalities are count-only oracle tests.
    }
    @Test void converseAndForeignSourceIdentityFailsBeforeOversizedSourceCanBeCounted(){
        saveTwo();UUID other=conversation("Converse inventory"),otherRun=answered(other,"Other actual source");
        UUID run=db.queryForObject("SELECT lesson_run_id FROM study_sessions WHERE id=?",UUID.class,studyId);long before=conversationVersion(targetConversation);
        for(UUID foreign:List.of(owner,UUID.randomUUID()))assertThrows(RuntimeException.class,()->flow.tx.executeWithoutResult(t->{
            db.update("INSERT INTO messages(id,owner_id,conversation_id,run_id,client_message_id,body_hash,content_ref,role,trust) VALUES (?,?,?,?,?,?,'invalid ciphertext','USER','USER_DIRECT')",UUID.randomUUID(),foreign,targetConversation,otherRun,UUID.randomUUID(),"a".repeat(64));
        }));
        assertThrows(RuntimeException.class,()->db.update("INSERT INTO inference_contexts(id,owner_id,run_id,conversation_id,session_hash,security_epoch,context_epoch,history_epoch,state) VALUES (?,?,?,?,?,1,1,1,'CAPTURED')",UUID.randomUUID(),owner,run,other,session.hash()));
        assertEquals(before,conversationVersion(targetConversation));assertEquals(0L,db.queryForObject("SELECT count(*) FROM messages WHERE conversation_id=? AND run_id=?",Long.class,targetConversation,otherRun));
        // A cap+1 rejection precedes any source decoding: even deliberately undecodable source remains unopened.
        db.update("UPDATE runs SET result_ref='not-an-encrypted-source' WHERE id=?",otherRun);
        flow.tx.executeWithoutResult(t->{for(int i=1;i<1001;i++)db.update("INSERT INTO runs(id,owner_id,conversation_id,status,result_ref) VALUES (?,?,?,'SUCCEEDED','not-an-encrypted-source')",UUID.randomUUID(),owner,other);});
        var observed=org.mockito.Mockito.spy(db);
        var checked=new ConversationDeletionService(observed,memories,context.getBean(ConversationAccess.class),context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        error(()->checked.state(session,other),413,"CONVERSATION_DELETION_SCOPE_LIMIT");
        org.mockito.Mockito.verify(observed,org.mockito.Mockito.never()).queryForList(org.mockito.ArgumentMatchers.startsWith("SELECT x.*"),org.mockito.ArgumentMatchers.<Object[]>any());
        assertEquals(0L,count("conversation_delete_preparations"));
    }
    @Test void ownerWideHistoricalClosureUsesReservedMaximumAndLeavesFinalDeletionCredit() throws Exception {
        saveTwo();long setup=conversationVersion(targetConversation)-1;nearVersion(MemoryService.MAX-39,setup);UUID id=requestFeedback(0);long before=conversationVersion(targetConversation);
        flow.tx.executeWithoutResult(t->{db.update("UPDATE owners SET context_epoch=context_epoch+1 WHERE id=?",owner);db.queryForList("SELECT revoke_study_feedback_v18(?,?,'OWNER_ALL',NULL,'CONTEXT_INVALIDATED')",owner,id);});
        assertEquals(before+5,conversationVersion(targetConversation));assertEquals(2,db.queryForObject("SELECT invalidated_contexts FROM study_feedback_events WHERE feedback_id=? AND operation='REVOKE'",Integer.class,id));
        proof(id,"UNSTARTED_CORE");var p=prepare(targetConversation);long closed=conversationVersion(targetConversation);commit(p,UUID.randomUUID());assertEquals(closed+1,conversationVersion(targetConversation));assertTrue(conversationVersion(targetConversation)<=MemoryService.MAX);
    }
    @Test void oversizedTerminalResultRollsBackTheWholeTerminalPhaseAndPreservesEscrow(){
        saveTwo();UUID id=requestFeedback(0);var dispatched=dispatch(id);var response=StudyFeedbackResultTest.response(dispatched.body());context.getBean(UsageService.class).record(dispatched,response);
        var before=feedbackRow(id);var inventory=db.queryForMap("SELECT * FROM conversation_source_inventory_v18(?,?)",owner,targetConversation);long version=conversationVersion(targetConversation);
        assertThrows(RuntimeException.class,()->flow.tx.executeWithoutResult(t->{
            db.update("UPDATE study_feedback_requests SET version=3,state='ACCEPTED',output_authority='CLOSED',result_ref=repeat('x',87439) WHERE id=?",id);
            db.update("UPDATE inference_reservations SET status='SUCCEEDED',completed_at=clock_timestamp() WHERE id=?",before.get("reservation_id"));
            db.update("UPDATE inference_contexts SET state='SETTLED' WHERE id=?",before.get("context_id"));db.update("UPDATE runs SET status='SUCCEEDED',version=3 WHERE id=?",before.get("run_id"));
            db.update("INSERT INTO study_feedback_events(owner_id,feedback_id,version,conversation_id,study_session_id,lesson_run_id,question_id,input_id,attempt_id,project_id,operation,prior_version,resulting_state,output_authority,prior_conversation_version,resulting_conversation_version,committed_at,phase_xid,invalidated_contexts) SELECT owner_id,id,3,conversation_id,study_session_id,lesson_run_id,question_id,input_id,attempt_id,project_id,'ACCEPT',2,'ACCEPTED','CLOSED',1,2,clock_timestamp(),'1',0 FROM study_feedback_requests WHERE id=?",id);db.execute("SET CONSTRAINTS ALL IMMEDIATE");
        }));assertEquals(before,feedbackRow(id));assertEquals(version,conversationVersion(targetConversation));assertEquals(inventory,db.queryForMap("SELECT * FROM conversation_source_inventory_v18(?,?)",owner,targetConversation));
        feedback.settle(dispatched,response);assertEquals(version+4,conversationVersion(targetConversation));
    }
    @Test void lateControlFactsAndExactReplaysChangeNoConversationRowsOrSourceBytes() throws Exception {
        saveTwo();UUID id=requestFeedback(0);dispatch(id);feedback.revoke(session,UUID.randomUUID(),id,revokeBody(id));
        long version=conversationVersion(targetConversation);var inventory=db.queryForMap("SELECT * FROM conversation_source_inventory_v18(?,?)",owner,targetConversation);var events=db.queryForList("SELECT * FROM study_feedback_events WHERE feedback_id=? ORDER BY version",id);
        proof(id,"WORKER_STOPPED");var slot=db.queryForMap("SELECT * FROM inference_control_slots WHERE reservation_id=?",feedbackRow(id).get("reservation_id"));
        db.update("UPDATE inference_control_slots SET terminal_kind=terminal_kind WHERE reservation_id=?",feedbackRow(id).get("reservation_id"));
        assertEquals(version,conversationVersion(targetConversation));assertEquals(inventory,db.queryForMap("SELECT * FROM conversation_source_inventory_v18(?,?)",owner,targetConversation));assertEquals(events,db.queryForList("SELECT * FROM study_feedback_events WHERE feedback_id=? ORDER BY version",id));assertEquals(slot,db.queryForMap("SELECT * FROM inference_control_slots WHERE reservation_id=?",feedbackRow(id).get("reservation_id")));assertEquals(3,events.size());
    }

}

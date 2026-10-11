package dev.myjarvis.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Actual transaction/trigger regressions; authored for later PostgreSQL execution. */
class StudyFeedbackSqlTest extends StudyFeedbackFixtures {
    @Test void requestDispatchAcceptHaveExactFourFiveFourCostsAndNoStudyMutation() {
        saveTwo(); var history=db.queryForList("SELECT * FROM study_mutations ORDER BY resulting_version");
        long before=conversationVersion(targetConversation);UUID id=requestFeedback(0);assertEquals(before+4,conversationVersion(targetConversation));
        var dispatch=dispatch(id);assertEquals(before+9,conversationVersion(targetConversation));
        var response=StudyFeedbackResultTest.response(dispatch.body());context.getBean(UsageService.class).record(dispatch,response);feedback.settle(dispatch,response);
        assertEquals(before+13,conversationVersion(targetConversation));assertEquals(history,db.queryForList("SELECT * FROM study_mutations ORDER BY resulting_version"));
        assertEquals(List.of("REQUEST","DISPATCH","ACCEPT"),db.queryForList("SELECT operation FROM study_feedback_events WHERE feedback_id=? ORDER BY version",String.class,id));
        var row=feedbackRow(id);assertEquals("ACCEPTED",row.get("state"));assertNotNull(row.get("result_ref"));
        assertFalse(db.queryForObject("SELECT context_history_eligible FROM runs WHERE id=?",Boolean.class,row.get("run_id")));
        assertNull(db.queryForObject("SELECT result_ref FROM runs WHERE id=?",String.class,row.get("run_id")));
        assertEquals(0L,db.queryForObject("SELECT count(*) FROM messages WHERE run_id=?",Long.class,row.get("run_id")));
    }
    @Test void directVersionFlagsAndSameStateWritesCannotConsumeReservedSource() {
        saveTwo();UUID id=requestFeedback(0);var row=feedbackRow(id);long before=conversationVersion(targetConversation);
        for(String sql:List.of("UPDATE conversations SET content_version=content_version+1 WHERE id=?","UPDATE conversations SET title='changed' WHERE id=?"))
            assertThrows(RuntimeException.class,()->db.update(sql,targetConversation));
        assertThrows(RuntimeException.class,()->db.update("UPDATE runs SET version=version+1 WHERE id=?",row.get("run_id")));
        assertThrows(RuntimeException.class,()->db.update("UPDATE inference_contexts SET profile_version=1 WHERE id=?",row.get("context_id")));
        assertThrows(RuntimeException.class,()->flow.tx.executeWithoutResult(t->{db.execute("SET LOCAL jarvis.feedback_phase='true'");db.update("UPDATE conversations SET title='bypass' WHERE id=?",targetConversation);}));
        assertEquals(before,conversationVersion(targetConversation));assertEquals("QUEUED",feedbackRow(id).get("state"));
    }
    @Test void missingEventAndCallerMintedPhaseDoNotCommit() {
        saveTwo();UUID id=requestFeedback(0);var before=feedbackRow(id);
        assertThrows(RuntimeException.class,()->flow.tx.executeWithoutResult(t->{
            db.update("UPDATE study_feedback_requests SET state='RUNNING',version=2,phase_xid='1',phase_prior_conversation_version=1,phase_started_at=clock_timestamp() WHERE id=?",id);
            db.execute("SET CONSTRAINTS study_feedback_complete IMMEDIATE");
        }));
        assertEquals(before,feedbackRow(id));
        assertThrows(RuntimeException.class,()->db.update("UPDATE study_feedback_requests SET output_authority='CLOSED' WHERE id=?",id));
        assertThrows(RuntimeException.class,()->db.update("UPDATE study_feedback_requests SET admission_xid='1' WHERE id=?",id));
    }
    @Test void terminalEventIsLastAndImmutableIncludingNoopUpdate() {
        saveTwo();UUID id=requestFeedback(0);var body=revokeBody(id);
        assertThrows(RuntimeException.class,()->flow.tx.executeWithoutResult(t->{feedback.revoke(session,UUID.randomUUID(),id,body);db.update("UPDATE conversations SET title='late source' WHERE id=?",targetConversation);}));
        assertEquals("QUEUED",feedbackRow(id).get("state"));
        feedback.revoke(session,UUID.randomUUID(),id,revokeBody(id));
        assertThrows(RuntimeException.class,()->db.update("UPDATE study_feedback_events SET version=version WHERE feedback_id=?",id));
        assertThrows(RuntimeException.class,()->db.update("DELETE FROM study_feedback_events WHERE feedback_id=?",id));
        assertThrows(RuntimeException.class,()->db.update("DELETE FROM study_feedback_requests WHERE id=?",id));
        db.update("UPDATE conversations SET title='new ordinary intent' WHERE id=?",targetConversation);
        assertEquals("REVOKED",feedbackRow(id).get("state"));
    }
    @Test void errorReceiptWithNullCodeStillFailsForLegacyAndFeedbackPurposes() {
        saveTwo();UUID id=requestFeedback(0);
        for(UUID reservation:db.queryForList("SELECT id FROM inference_reservations ORDER BY id",UUID.class))
            assertThrows(RuntimeException.class,()->db.update("INSERT INTO inference_usage_receipts(owner_id,run_id,reservation_id,request_id,model_id,model_manifest_hash,request_digest,budget_revision,max_compute_ms,reserved_output_tokens,reserved_at,receipt_kind,response_kind,error_code,input_tokens,output_tokens,compute_ms,peak_memory_bytes) SELECT owner_id,run_id,id,request_id,model_id,model_manifest_hash,body_hash,budget_revision,max_compute_ms,reserved_output_tokens,created_at,'ERROR','ERROR',NULL,0,0,0,0 FROM inference_reservations WHERE id=?",reservation));
        assertEquals("QUEUED",feedbackRow(id).get("state"));
    }
    @Test void scopeCannotBeWidenedWithoutDatabaseObservedAuthorityLoss() {
        saveTwo();UUID id=requestFeedback(0);var before=feedbackRow(id);
        for(String scope:List.of("OWNER_ALL","PAUSE_ACTIVE","SESSION_ACTIVE"))assertThrows(RuntimeException.class,()->flow.tx.executeWithoutResult(t->db.queryForList("SELECT revoke_study_feedback_v18(?,?,?,?,?)",owner,id,scope,scope.equals("SESSION_ACTIVE")?session.hash():null,"TRANSPORT_UNKNOWN")));
        assertEquals(before,feedbackRow(id));
        assertThrows(RuntimeException.class,()->flow.tx.executeWithoutResult(t->db.queryForList("SELECT revoke_study_feedback_v18(?,?,'RUN_EXACT',NULL,'OWNER_REVOKE')",owner,id)));
    }
    @Test void ownerWideClosureCountsHistoricalContextExactlyAndCannotSealEarly() {
        saveTwo();UUID id=requestFeedback(0);long before=conversationVersion(targetConversation);
        flow.tx.executeWithoutResult(t->{db.update("UPDATE owners SET context_epoch=context_epoch+1 WHERE id=?",owner);db.queryForList("SELECT revoke_study_feedback_v18(?,?,'OWNER_ALL',NULL,'CONTEXT_INVALIDATED')",owner,id);});
        assertEquals(before+5,conversationVersion(targetConversation));assertEquals(2,db.queryForObject("SELECT invalidated_contexts FROM study_feedback_events WHERE feedback_id=? AND operation='REVOKE'",Integer.class,id));
        assertEquals(0L,db.queryForObject("SELECT count(*) FROM inference_contexts WHERE conversation_id=? AND state<>'INVALIDATED'",Long.class,targetConversation));
    }
    @Test void nonfiniteAndBackdatedPhaseMarkersAreRejectedWithoutPartialClosure() {
        saveTwo();UUID id=requestFeedback(0);var row=feedbackRow(id);
        for(String marker:List.of("-infinity","infinity","1900-01-01T00:00:00Z"))assertThrows(RuntimeException.class,()->flow.tx.executeWithoutResult(t->{
            db.update("UPDATE study_feedback_requests SET version=2,state='REVOKED',output_authority='REVOKED',phase_invalidation_scope='RUN_EXACT' WHERE id=?",id);
            db.update("UPDATE inference_contexts SET state='INVALIDATED',invalidated_at=?::timestamptz WHERE id=?",marker,row.get("context_id"));
        }));
        assertEquals(row,feedbackRow(id));
        complete(id);var retained=feedbackRow(id);
        assertThrows(RuntimeException.class,()->db.update("UPDATE inference_contexts SET state='INVALIDATED',invalidated_at='infinity'::timestamptz WHERE id=?",retained.get("context_id")));
        assertThrows(RuntimeException.class,()->db.update("UPDATE runs SET created_at=created_at-interval '1 second' WHERE id=?",retained.get("run_id")));
        assertEquals(retained,feedbackRow(id));
    }

    @Test void forgedScopeCountsCannotAuthorizeAnUnrelatedHistoricalInvalidation() {
        saveTwo();UUID id=requestFeedback(0);var before=feedbackRow(id);
        assertThrows(RuntimeException.class,()->flow.tx.executeWithoutResult(t->{
            db.update("UPDATE study_feedback_requests SET version=2,state='REVOKED',output_authority='REVOKED',phase_invalidation_scope='RUN_EXACT',phase_initial_scope_contexts=1000,phase_initial_invalidatable_contexts=1000,phase_prior_conversation_version=1,phase_xid='1' WHERE id=?",id);
            db.update("UPDATE inference_contexts SET state='INVALIDATED',invalidated_at=clock_timestamp() WHERE conversation_id=? AND id<>?",targetConversation,before.get("context_id"));
        }));assertEquals(before,feedbackRow(id));
        flow.tx.executeWithoutResult(t->{
            db.update("UPDATE study_feedback_requests SET version=2,state='REVOKED',output_authority='REVOKED',phase_invalidation_scope='RUN_EXACT',phase_initial_scope_contexts=1000,phase_initial_invalidatable_contexts=1000,phase_prior_conversation_version=1,phase_xid='1' WHERE id=?",id);
            db.update("UPDATE inference_contexts SET state='INVALIDATED',invalidated_at=clock_timestamp() WHERE id=?",before.get("context_id"));
            db.update("UPDATE inference_reservations SET status='CANCELLED',cancel_requested_at=clock_timestamp(),completed_at=clock_timestamp() WHERE id=?",before.get("reservation_id"));
            db.update("UPDATE runs SET status='CANCELLED',version=version+1,cancel_requested_at=clock_timestamp() WHERE id=?",before.get("run_id"));
            db.update("INSERT INTO study_feedback_events(owner_id,feedback_id,version,conversation_id,study_session_id,lesson_run_id,question_id,input_id,attempt_id,project_id,operation,prior_version,resulting_state,output_authority,reason_code,prior_conversation_version,resulting_conversation_version,committed_at,phase_xid,invalidated_contexts) SELECT owner_id,id,version,conversation_id,study_session_id,lesson_run_id,question_id,input_id,attempt_id,project_id,'REVOKE',1,'REVOKED','REVOKED','TRANSPORT_UNKNOWN',1,2,clock_timestamp(),'1',1000 FROM study_feedback_requests WHERE id=?",id);
        });
        assertEquals(2,((Number)feedbackRow(id).get("phase_initial_invalidatable_contexts")).intValue());assertEquals(1,((Number)feedbackRow(id).get("phase_initial_scope_contexts")).intValue());
        assertEquals(1,db.queryForObject("SELECT invalidated_contexts FROM study_feedback_events WHERE feedback_id=? AND version=2",Integer.class,id));
        assertEquals("SETTLED",db.queryForObject("SELECT state FROM inference_contexts WHERE conversation_id=? AND id<>?",String.class,targetConversation,before.get("context_id")));
    }

    private record BarrierRow(String table,String selector,String change) {}
    private String failures(Throwable error){StringBuilder text=new StringBuilder();for(Throwable at=error;at!=null;at=at.getCause())text.append(at.getMessage()).append('\n');return text.toString();}
    private List<BarrierRow> barrierRows(){
        UUID ordinary=answered(targetConversation,"Synthetic settled barrier source"),action=UUID.randomUUID(),approval=UUID.randomUUID(),authorization=UUID.randomUUID();
        String hash=Json.hash("Synthetic settled action");
        db.update("INSERT INTO action_proposals(id,owner_id,run_id,tool_id,tool_version,payload_ref,payload_hash,status,security_epoch,valid_until) VALUES (?,?,?,'calendar.event.create','1',?,?,'SUCCEEDED',1,clock_timestamp()+interval '10 minutes')",action,owner,ordinary,flow.vault.seal(owner,action,"action","Synthetic settled action"),hash);
        db.update("INSERT INTO approvals(id,owner_id,action_id,payload_hash,status,expires_at,consumed_at,approved_session_hash,action_version) VALUES (?,?,?,?,'CONSUMED',clock_timestamp()+interval '10 minutes',clock_timestamp(),?,1)",approval,owner,action,hash,session.hash());
        db.update("INSERT INTO authorizations(id,owner_id,action_id,approval_id,payload_hash,security_epoch,valid_until) VALUES (?,?,?,?,?,1,clock_timestamp()+interval '10 minutes')",authorization,owner,action,approval,hash);
        db.update("INSERT INTO action_executions(action_id,owner_id,status,lease_generation,started_at,finished_at) VALUES (?,?,'SUCCEEDED',1,clock_timestamp(),clock_timestamp())",action,owner);
        db.update("INSERT INTO mock_effects(action_id,owner_id,tool_id,payload_hash) VALUES (?,?,'calendar.event.create',?)",action,owner,hash);
        UUID lessonContext=db.queryForObject("SELECT id FROM inference_contexts WHERE conversation_id=?",UUID.class,targetConversation);
        UUID message=db.queryForObject("SELECT id FROM messages WHERE run_id=? AND role='USER'",UUID.class,ordinary);
        db.update("INSERT INTO inference_context_history(context_id,ordinal,source_id,message_id,run_id,role) VALUES (?,15,?,?,?,'USER')",lessonContext,UUID.randomUUID(),message,ordinary);
        UUID slot=UUID.randomUUID(),memory=UUID.randomUUID();
        db.update("INSERT INTO memory_slots(id,owner_id,scope,key_digest,generation) VALUES (?,?,'GLOBAL',?,1)",slot,owner,Json.hash(memory.toString()));
        db.update("INSERT INTO memories(id,owner_id,slot_id,kind,generation,version,payload_ref,confirmed_at) VALUES (?,?,?,'PREFERENCE',1,1,'synthetic-only',clock_timestamp())",memory,owner,slot);
        db.update("INSERT INTO inference_context_memories(context_id,source_id,memory_id,slot_id,version,generation) VALUES (?,?,?,?,1,1)",lessonContext,UUID.randomUUID(),memory,slot);
        UUID input=db.queryForObject("SELECT id FROM study_owner_answer_inputs WHERE study_session_id=? AND expected_study_version=1",UUID.class,studyId);
        UUID mutation=db.queryForObject("SELECT idempotency_key FROM study_mutations WHERE study_session_id=? AND resulting_version=1",UUID.class,studyId);
        UUID reservation=db.queryForObject("SELECT id FROM inference_reservations WHERE run_id=(SELECT lesson_run_id FROM study_sessions WHERE id=?)",UUID.class,studyId);
        return List.of(new BarrierRow("conversations","id='"+targetConversation+"'","title=title||' changed'"),
            new BarrierRow("runs","id='"+ordinary+"'","version=version+1"),new BarrierRow("messages","id='"+message+"'","content_ref=content_ref||'changed'"),
            new BarrierRow("action_proposals","id='"+action+"'","version=version+1"),new BarrierRow("approvals","id='"+approval+"'","expires_at=expires_at+interval '1 second'"),
            new BarrierRow("authorizations","id='"+authorization+"'","valid_until=valid_until+interval '1 second'"),new BarrierRow("jobs","run_id='"+ordinary+"'","attempt_count=attempt_count+1"),
            new BarrierRow("action_executions","action_id='"+action+"'","lease_generation=lease_generation+1"),new BarrierRow("mock_effects","action_id='"+action+"'","created_at=created_at+interval '1 second'"),
            new BarrierRow("inference_reservations","id='"+reservation+"'","max_compute_ms=max_compute_ms-1"),new BarrierRow("inference_contexts","id='"+lessonContext+"'","context_epoch=context_epoch+1"),
            new BarrierRow("inference_context_history","context_id='"+lessonContext+"' AND ordinal=15","ordinal=14"),new BarrierRow("inference_context_memories","context_id='"+lessonContext+"' AND memory_id='"+memory+"'","version=version+1"),
            new BarrierRow("study_sessions","id='"+studyId+"'","updated_at=updated_at+interval '1 second'"),new BarrierRow("study_owner_answer_inputs","id='"+input+"'","text_ref=text_ref||'changed'"),
            new BarrierRow("study_mutations","idempotency_key='"+mutation+"'","request_digest=repeat('0',64)"));
    }
    @Test void activeBarrierRejectsEveryParticipantInsertRealUpdateAndDeleteWithoutChangingSource() {
        saveTwo();var rows=barrierRows();UUID other=conversation("Independent writable source"),otherLesson=lesson(other);
        UUID otherRun=answered(other,"Independent settled source"),otherContext=db.queryForObject("SELECT id FROM inference_contexts WHERE run_id=?",UUID.class,otherLesson);
        UUID otherMessage=db.queryForObject("SELECT id FROM messages WHERE run_id=? AND role='USER'",UUID.class,otherRun);
        db.update("INSERT INTO inference_context_history(context_id,ordinal,source_id,message_id,run_id,role) VALUES (?,15,?,?,?,'USER')",otherContext,UUID.randomUUID(),otherMessage,otherRun);
        UUID id=requestFeedback(0);assertEquals(16,rows.size());long before=conversationVersion(targetConversation);
        for(var row:rows){
            var exact=db.queryForList("SELECT * FROM "+row.table()+" WHERE "+row.selector());assertEquals(1,exact.size(),row.table());
            // Clone INSERT deliberately retains a real, complete parent relationship. The exact BEFORE-barrier
            // error (rather than a duplicate-key/legacy immutability error) proves the INSERT was intercepted.
            for(String sql:List.of("INSERT INTO "+row.table()+" OVERRIDING SYSTEM VALUE SELECT * FROM "+row.table()+" WHERE "+row.selector(),
                "UPDATE "+row.table()+" SET "+row.change()+" WHERE "+row.selector(),"DELETE FROM "+row.table()+" WHERE "+row.selector())){
                RuntimeException denied=assertThrows(RuntimeException.class,()->flow.tx.executeWithoutResult(t->db.update(sql)),sql);
                assertTrue(failures(denied).contains("Conversation has active feedback output"),row.table()+": "+failures(denied));
                assertEquals(exact,db.queryForList("SELECT * FROM "+row.table()+" WHERE "+row.selector()),sql);assertEquals(before,conversationVersion(targetConversation),sql);
            }
        }
        UUID independent=UUID.randomUUID();assertEquals(1,db.update("INSERT INTO runs(id,owner_id,conversation_id,status) VALUES (?,?,?,'SUCCEEDED')",independent,owner,other));
        assertEquals(1,db.update("UPDATE runs SET version=version+1 WHERE id=?",independent));
        // Legacy source identities forbid DELETE even without feedback; a deletable history child is the control.
        assertEquals(1,db.queryForObject("SELECT count(*) FROM inference_context_history WHERE context_id=? AND ordinal=15",Integer.class,otherContext));
        assertEquals(1,db.update("DELETE FROM inference_context_history WHERE context_id=? AND ordinal=15",otherContext));
        assertEquals(1,db.queryForObject("SELECT count(*) FROM runs WHERE id=?",Integer.class,otherRun));
        assertEquals(before,conversationVersion(targetConversation));assertEquals("QUEUED",feedbackRow(id).get("state"));
    }
    @Test void duplicateGappedAndMultiplePhasesAndConflictInsertionNeverAdvanceConversation() {
        saveTwo();UUID id=requestFeedback(0);var original=feedbackRow(id);long before=conversationVersion(targetConversation);
        for(String changes:List.of("version=3,state='RUNNING'","version=1,state='RUNNING'","version=2,state='ACCEPTED',output_authority='CLOSED',result_ref='synthetic'"))
            assertThrows(RuntimeException.class,()->flow.tx.executeWithoutResult(t->{db.update("UPDATE study_feedback_requests SET "+changes+" WHERE id=?",id);db.execute("SET CONSTRAINTS study_feedback_complete IMMEDIATE");}));
        assertThrows(RuntimeException.class,()->flow.tx.executeWithoutResult(t->{db.update("UPDATE study_feedback_requests SET version=2,state='RUNNING' WHERE id=?",id);db.update("UPDATE study_feedback_requests SET version=3,state='REVOKED',output_authority='REVOKED',phase_invalidation_scope='RUN_EXACT' WHERE id=?",id);}));
        for(String suffix:List.of(""," ON CONFLICT DO NOTHING"))assertThrows(RuntimeException.class,()->db.update("INSERT INTO study_feedback_events SELECT * FROM study_feedback_events WHERE feedback_id=? AND version=1"+suffix,id));
        for(String changes:List.of("version=3","operation_session_hash='foreign-session'","operation_security_epoch=2","committed_at='infinity'::timestamptz","phase_xid='1'"))
            assertThrows(RuntimeException.class,()->db.update("UPDATE study_feedback_events SET "+changes+" WHERE feedback_id=? AND version=1",id));
        assertEquals(original,feedbackRow(id));assertEquals(before,conversationVersion(targetConversation));assertEquals(1L,count("study_feedback_events"));
    }
    @Test void ownedIdentityProjectNullAndConverseSubstitutionsCannotOpenAnotherPhase() {
        saveTwo();UUID other=conversation("Converse target"),otherRun=answered(other,"Converse source");UUID project=UUID.randomUUID();
        db.update("INSERT INTO projects(id,owner_id,name) VALUES (?,?,'Synthetic unrelated project')",project,owner);
        UUID id=requestFeedback(0);var original=feedbackRow(id);long before=conversationVersion(targetConversation);
        // A second owner cannot exist: the singleton owner constraint remains enabled throughout.
        Map<String,Object> swaps=new LinkedHashMap<>();for(String field:List.of("owner_id","study_session_id","lesson_run_id","input_id","attempt_id","question_id","run_id","reservation_id","request_id","context_id"))swaps.put(field,UUID.randomUUID());
        swaps.put("conversation_id",other);swaps.put("initiating_session_hash","ownerless-synthetic-session");swaps.put("project_id",project);swaps.put("project_version",1L);
        for(var swap:swaps.entrySet())assertThrows(RuntimeException.class,()->flow.tx.executeWithoutResult(t->{db.update("UPDATE study_feedback_requests SET version=2,state='RUNNING',"+swap.getKey()+"=? WHERE id=?",swap.getValue(),id);db.execute("SET CONSTRAINTS ALL IMMEDIATE");}),swap.getKey());
        for(String table:List.of("inference_reservations","inference_contexts"))assertThrows(RuntimeException.class,()->db.update("UPDATE "+table+" SET run_id=? WHERE id=?",otherRun,original.get(table.equals("inference_reservations")?"reservation_id":"context_id")));
        assertEquals(original,feedbackRow(id));assertEquals(before,conversationVersion(targetConversation));
    }
    @Test void dispatchedStartContextCacheAndCommitmentAreImmutableFieldByField() {
        saveTwo();UUID id=requestFeedback(0);dispatch(id);var f=feedbackRow(id);long before=conversationVersion(targetConversation);
        for(String assignment:List.of("started_at=started_at+interval '1 microsecond'","dispatch_context_id='"+UUID.randomUUID()+"'","dispatch_cache_policy=NULL","body_hash=repeat('0',64)","request_id='"+UUID.randomUUID()+"'"))
            assertThrows(RuntimeException.class,()->db.update("UPDATE inference_reservations SET "+assignment+" WHERE id=?",f.get("reservation_id")),assignment);
        for(String assignment:List.of("dispatch_commitment=repeat('0',64)","bound_at=bound_at+interval '1 microsecond'","context_id='"+UUID.randomUUID()+"'","origin_binding=repeat('0',64)"))
            assertThrows(RuntimeException.class,()->db.update("UPDATE inference_control_slots SET "+assignment+" WHERE reservation_id=?",f.get("reservation_id")),assignment);
        assertEquals(before,conversationVersion(targetConversation));assertEquals(f,feedbackRow(id));
    }

    @Test void anonymousSessionLifecycleRemainsValidWhileOwnedSessionsCannotDetach(){
        saveTwo();UUID id=requestFeedback(0);var feedbackBefore=feedbackRow(id);long version=conversationVersion(targetConversation);
        String anonymous="synthetic-anonymous-"+UUID.randomUUID(),owned="synthetic-unreferenced-owned-"+UUID.randomUUID();
        flow.tx.executeWithoutResult(t->{
            assertEquals(1,db.update("INSERT INTO sessions(token_hash,owner_id,csrf_hash) VALUES (?,NULL,'synthetic-csrf')",anonymous));
            db.execute("SET CONSTRAINTS study_feedback_complete IMMEDIATE");
            assertEquals(1,db.update("UPDATE sessions SET last_seen_at=clock_timestamp(),csrf_hash='synthetic-refreshed' WHERE token_hash=? AND owner_id IS NULL",anonymous));
            assertEquals(1,db.update("UPDATE sessions SET revoked_at=clock_timestamp() WHERE token_hash=? AND owner_id IS NULL",anonymous));
        });
        assertEquals(1,db.update("DELETE FROM sessions WHERE token_hash=? AND owner_id IS NULL",anonymous));
        db.update("INSERT INTO sessions(token_hash,owner_id,csrf_hash,security_epoch) VALUES (?,?,'synthetic-owned',1)",owned,owner);
        for(String hash:List.of(session.hash(),owned)){
            var before=db.queryForMap("SELECT * FROM sessions WHERE token_hash=?",hash);
            assertThrows(RuntimeException.class,()->flow.tx.executeWithoutResult(t->{db.update("UPDATE sessions SET owner_id=NULL WHERE token_hash=?",hash);db.execute("SET CONSTRAINTS ALL IMMEDIATE");}));
            assertThrows(RuntimeException.class,()->flow.tx.executeWithoutResult(t->{db.update("UPDATE sessions SET owner_id=? WHERE token_hash=?",UUID.randomUUID(),hash);db.execute("SET CONSTRAINTS ALL IMMEDIATE");}));
            assertEquals(before,db.queryForMap("SELECT * FROM sessions WHERE token_hash=?",hash));
        }
        assertEquals(feedbackBefore,feedbackRow(id));assertEquals(version,conversationVersion(targetConversation));
    }

}

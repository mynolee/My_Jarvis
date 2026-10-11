package dev.myjarvis.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StudyDeletionIntegrationTest extends StudyStorageFixtures {
    @Test void openAndFeedbackPendingStudySourcesDeleteWithoutChangingHistoryOrUsage(){
        Study s=startStudy();var first=answer(s,s.first(),"exact private answer");answer(s,s.second(),"second private answer");
        var original=db.queryForMap("SELECT * FROM study_sessions WHERE id=?",s.id());var inputs=db.queryForList("SELECT * FROM study_owner_answer_inputs WHERE study_session_id=? ORDER BY id",s.id());
        var receipts=db.queryForList("SELECT * FROM study_mutations WHERE study_session_id=? ORDER BY resulting_version",s.id());var usage=db.queryForList("SELECT * FROM inference_usage_receipts ORDER BY reservation_id");
        var p=prepare(s.conversation());assertEquals(1L,((Number)((Map<?,?>)p.get("source_counts")).get("study_sessions")).longValue());assertEquals(2L,((Number)((Map<?,?>)p.get("source_counts")).get("study_answers")).longValue());
        assertEquals(1,db.queryForObject("SELECT study_sessions_count FROM conversation_delete_preparations WHERE id=?",Integer.class,p.get("id")));assertEquals(2,db.queryForObject("SELECT study_answers_count FROM conversation_delete_preparations WHERE id=?",Integer.class,p.get("id")));
        commit(p,UUID.randomUUID());var deleted=db.queryForMap("SELECT * FROM study_sessions WHERE id=?",s.id());Object at=deleted.get("source_deleted_at");assertNotNull(at);assertNull(deleted.remove("lesson_binding_ref"));deleted.remove("source_deleted_at");original.remove("lesson_binding_ref");original.remove("source_deleted_at");assertEquals(original,deleted);
        var redacted=db.queryForList("SELECT * FROM study_owner_answer_inputs WHERE study_session_id=? ORDER BY id",s.id());for(int i=0;i<inputs.size();i++){var before=inputs.get(i);var after=redacted.get(i);assertEquals(at,after.remove("content_deleted_at"));assertNull(after.remove("text_ref"));assertNull(after.remove("source_binding_ref"));before.remove("content_deleted_at");before.remove("text_ref");before.remove("source_binding_ref");assertEquals(before,after);}
        assertEquals(receipts,db.queryForList("SELECT * FROM study_mutations WHERE study_session_id=? ORDER BY resulting_version",s.id()));assertEquals(usage,db.queryForList("SELECT * FROM inference_usage_receipts ORDER BY reservation_id"));assertEquals(1,providerCalls);
        error(()->studies.get(session,s.id()),410,"CONVERSATION_DELETED");assertEquals("COMMITTED",studies.reconcile(session,(UUID)first.get("mutation_id")).get("status"));
        assertNull(db.queryForObject("SELECT result_ref FROM runs WHERE id=?",String.class,s.run()));assertEquals(0L,db.queryForObject("SELECT count(*) FROM messages WHERE conversation_id=? AND (content_ref IS NOT NULL OR body_hash IS NOT NULL)",Long.class,s.conversation()));
    }
    @Test void redactionRequiresBothMatchingTombstonesAndIsOneWay(){Study s=startStudy();var answer=answer(s,s.first(),"kept until deletion");UUID input=(UUID)answer.get("input_id");
        assertThrows(RuntimeException.class,()->db.update("UPDATE study_owner_answer_inputs SET text_ref=NULL,source_binding_ref=NULL,content_deleted_at=clock_timestamp() WHERE id=?",input));
        assertThrows(RuntimeException.class,()->db.update("UPDATE study_sessions SET lesson_binding_ref=NULL,source_deleted_at=clock_timestamp() WHERE id=?",s.id()));
        commit(prepare(s.conversation()),UUID.randomUUID());
        for(String change:List.of("text_ref='restored',source_binding_ref='restored',content_deleted_at=NULL","content_deleted_at=content_deleted_at+interval '1 second'","question_id='"+s.second()+"'"))assertThrows(RuntimeException.class,()->db.update("UPDATE study_owner_answer_inputs SET "+change+" WHERE id=?",input));
        assertThrows(RuntimeException.class,()->db.update("UPDATE study_sessions SET lesson_binding_ref='restored',source_deleted_at=NULL WHERE id=?",s.id()));
    }
    @Test void commonInventoryCountsAllStudyRowsAndCiphertextsIncludingCloseReceipt(){Study s=startStudy();var before=db.queryForMap("SELECT * FROM conversation_source_inventory_v16(?,?)",owner,s.conversation());long rows=((Number)before.get("scoped_rows")).longValue();long bytes=((Number)before.get("source_bytes")).longValue();close(s);var after=db.queryForMap("SELECT * FROM conversation_source_inventory_v16(?,?)",owner,s.conversation());assertEquals(rows+1,((Number)after.get("scoped_rows")).longValue());assertEquals(bytes+64,((Number)after.get("source_bytes")).longValue());assertEquals(1L,((Number)after.get("study_sessions")).longValue());assertEquals(0L,((Number)after.get("study_answers")).longValue());commit(prepare(s.conversation()),UUID.randomUUID());var shell=db.queryForMap("SELECT * FROM conversation_source_inventory_v16(?,?)",owner,s.conversation());assertEquals(((Number)after.get("scoped_rows")).longValue(),((Number)shell.get("scoped_rows")).longValue());assertTrue(((Number)shell.get("source_bytes")).longValue()>=128);}
    @Test void metadataOnlyCloseCannotExceedActualByteCapacityAndReplayCostsNothing(){Study s=startStudy();UUID run=answered(s.conversation(),"capacity filler");long before=conversationVersion(s.conversation());long bytes=((Number)db.queryForMap("SELECT * FROM conversation_source_inventory_v16(?,?)",owner,s.conversation()).get("source_bytes")).longValue();int prior=db.queryForObject("SELECT octet_length(result_ref) FROM runs WHERE id=?",Integer.class,run);long filler=16777216L-bytes+prior;db.update("UPDATE runs SET result_ref=repeat('x',?) WHERE id=?",(int)filler,run);
        assertEquals(16777216L,((Number)db.queryForMap("SELECT * FROM conversation_source_inventory_v16(?,?)",owner,s.conversation()).get("source_bytes")).longValue());long atLimit=conversationVersion(s.conversation());error(()->close(s),413,"STUDY_CAPACITY_LIMIT");assertEquals(atLimit,conversationVersion(s.conversation()));assertEquals(1,studyVersion(s.id()));assertEquals(1,count("study_mutations"));assertTrue(atLimit>before);
        var start=db.queryForMap("SELECT * FROM study_mutations WHERE study_session_id=?",s.id());assertEquals("COMMITTED",studies.reconcile(session,(UUID)start.get("idempotency_key")).get("status"));assertEquals(1,count("study_mutations"));
    }
    @Test void exactSchemaGateRejectsBothOldAndUnreviewedFutureVersion(){Study s=startStudy();for(String version:List.of("15","17")){flow.tx.executeWithoutResult(status->{db.update("UPDATE flyway_schema_history SET version=? WHERE version='16'",version);error(()->deletion.prepare(session,UUID.randomUUID(),s.conversation(),json(Map.of("expected_version",conversationVersion(s.conversation()),"expected_digest","a".repeat(64)))),409,"CONVERSATION_GRAPH_UNVERIFIED");assertThrows(ApiException.class,()->studies.get(session,s.id()));status.setRollbackOnly();});}assertEquals("16",db.queryForObject("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1",String.class));}
    @Test void clearingOnlyOldSourcesCannotCommitTheConversationTombstone(){
        Study s=startStudy();answer(s,s.first(),"retained on rollback");var p=prepare(s.conversation());
        db.execute("CREATE FUNCTION study_fixture_skip_redaction() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN OLD; END $$");
        for(String table:List.of("study_sessions","study_owner_answer_inputs"))db.execute("CREATE TRIGGER aaa_study_fixture_skip_redaction BEFORE UPDATE ON "+table+" FOR EACH ROW EXECUTE FUNCTION study_fixture_skip_redaction()");
        try{assertThrows(RuntimeException.class,()->commit(p,UUID.randomUUID()));
            assertNull(db.queryForObject("SELECT deleted_at FROM conversations WHERE id=?",Object.class,s.conversation()));
            assertNotNull(db.queryForObject("SELECT lesson_binding_ref FROM study_sessions WHERE id=?",String.class,s.id()));
            assertNotNull(db.queryForObject("SELECT text_ref FROM study_owner_answer_inputs WHERE study_session_id=?",String.class,s.id()));
            assertNotNull(db.queryForObject("SELECT result_ref FROM runs WHERE id=?",String.class,s.run()));assertEquals(0,count("conversation_deletion_ledger"));
            assertNotNull(db.queryForObject("SELECT payload_hash_ref FROM conversation_delete_preparations WHERE id=?",String.class,p.get("id")));
        }finally{for(String table:List.of("study_sessions","study_owner_answer_inputs"))db.execute("DROP TRIGGER aaa_study_fixture_skip_redaction ON "+table);db.execute("DROP FUNCTION study_fixture_skip_redaction()");}
        assertEquals("COMMITTED",commit(p,UUID.randomUUID()).get("status"));
    }

    @Test void everyOperationAtMaxMinusTwoPreservesTheFinalDeletionIncrement(){
        for(String operation:List.of("START","ANSWER","CLOSE")){
            Study s;UUID conversation,run;
            if(operation.equals("START")){conversation=conversation("start headroom");run=lesson(conversation);s=null;}
            else{s=startStudy();conversation=s.conversation();run=s.run();}
            flow.tx.executeWithoutResult(status->{
                // Disposable boundary fixture only: restore the normal guard before commit.
                db.execute("ALTER TABLE conversations DISABLE TRIGGER conversation_tombstone_guard");
                db.update("UPDATE conversations SET content_version=? WHERE id=?",MemoryService.MAX-2,conversation);
                db.execute("ALTER TABLE conversations ENABLE TRIGGER conversation_tombstone_guard");
            });
            if(operation.equals("START"))studies.start(session,UUID.randomUUID(),conversation,json(Map.of("lesson_run_id",run,"expected_conversation_version",MemoryService.MAX-2)));
            else if(operation.equals("ANSWER"))answer(s,s.first(),"last permitted source write");else close(s);
            assertEquals(MemoryService.MAX-1,conversationVersion(conversation));var history=db.queryForList("SELECT * FROM study_mutations WHERE conversation_id=? ORDER BY resulting_version",conversation);
            var p=prepare(conversation);assertEquals("COMMITTED",commit(p,UUID.randomUUID()).get("status"));assertEquals(MemoryService.MAX,conversationVersion(conversation));
            assertEquals(history,db.queryForList("SELECT * FROM study_mutations WHERE conversation_id=? ORDER BY resulting_version",conversation));
            assertEquals(0L,db.queryForObject("SELECT count(*) FROM study_sessions WHERE conversation_id=? AND lesson_binding_ref IS NOT NULL",Long.class,conversation));
        }
    }

}

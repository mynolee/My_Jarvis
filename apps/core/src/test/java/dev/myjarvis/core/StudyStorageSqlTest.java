package dev.myjarvis.core;

import java.sql.Timestamp;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** These assertions execute PostgreSQL guards/constraints in CI; source parsing is not a substitute. */
class StudyStorageSqlTest extends StudyStorageFixtures {
    @Test void eachReceiptIsTheOnlyConversationBumpAndHistoricalInputsRemainValid(){
        Study s=startStudy();long c=conversationVersion(s.conversation());var first=answer(s,s.first()," exact first answer \n");assertEquals(c+1,conversationVersion(s.conversation()));
        long observed=db.queryForObject("SELECT observed_conversation_version FROM study_owner_answer_inputs WHERE id=?",Long.class,first.get("input_id"));assertEquals(c,observed);
        answered(s.conversation(),"Intervening ordinary source");long intervening=conversationVersion(s.conversation());answer(s,s.second(),"second");assertEquals(intervening+1,conversationVersion(s.conversation()));
        close(s);assertEquals(4,studyVersion(s.id()));assertEquals(4,count("study_mutations"));assertEquals(2,count("study_owner_answer_inputs"));
        db.queryForList("SELECT verify_study_history_v16(?,?)",owner,s.id());assertEquals(observed,db.queryForObject("SELECT observed_conversation_version FROM study_owner_answer_inputs WHERE id=?",Long.class,first.get("input_id")));
        assertEquals(1,providerCalls);
    }
    @Test void unreceiptedSourceSessionAndTimestampChangesRollback(){
        Study s=startStudy();long c=conversationVersion(s.conversation());Timestamp at=timestamp();
        rejectTransaction(()->input(s,UUID.randomUUID(),UUID.randomUUID(),s.first(),1,c,at));
        rejectTransaction(()->nextSession(s,2,false,at));
        assertThrows(RuntimeException.class,()->db.update("UPDATE study_sessions SET updated_at=updated_at+interval '1 second' WHERE id=?",s.id()));
        assertEquals(0,count("study_owner_answer_inputs"));assertEquals(1,studyVersion(s.id()));assertEquals(c,conversationVersion(s.conversation()));
    }
    @Test void unreceiptedStartCannotCommit(){Study s=startStudy();UUID otherRun=lesson(s.conversation());
        rejectTransaction(()->db.update("WITH t AS (SELECT clock_timestamp() AS at) INSERT INTO study_sessions(id,owner_id,conversation_id,lesson_run_id,lesson_run_version,question_1_id,question_2_id,lesson_binding_ref,version,lifecycle,created_at,updated_at) SELECT ?,owner_id,conversation_id,id,version,?,?,?,1,'OPEN',t.at,t.at FROM runs CROSS JOIN t WHERE id=?",UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"synthetic",otherRun));assertEquals(1,count("study_sessions"));}
    @Test void nullPriorVersionCannotBypassAnswerOrCloseChecks(){
        Study s=startStudy();long c=conversationVersion(s.conversation());Timestamp at=timestamp();UUID input=UUID.randomUUID(),attempt=UUID.randomUUID();
        rejectTransaction(()->{input(s,input,attempt,s.first(),1,c,at);nextSession(s,2,false,at);mutation(s,"ANSWER",input,attempt,s.first(),null,2,c,at);});
        rejectTransaction(()->{nextSession(s,2,true,at);mutation(s,"CLOSE",null,null,null,null,2,c,at);});
        assertEquals(1,studyVersion(s.id()));assertEquals(1,count("study_mutations"));assertEquals(c,conversationVersion(s.conversation()));
    }
    @Test void mismatchedInputReceiptAndHistoricalObservationCannotCommit(){
        Study s=startStudy();long c=conversationVersion(s.conversation());Timestamp at=timestamp();UUID input=UUID.randomUUID(),attempt=UUID.randomUUID();
        rejectTransaction(()->{input(s,input,attempt,s.first(),1,c,at);nextSession(s,2,false,at);mutation(s,"ANSWER",input,attempt,s.second(),1L,2,c,at);});
        rejectTransaction(()->input(s,UUID.randomUUID(),UUID.randomUUID(),s.first(),1,c+1,at));
        rejectTransaction(()->{input(s,input,attempt,s.first(),1,c,at);nextSession(s,2,false,at);mutation(s,"ANSWER",input,attempt,s.first(),1L,2,c-1,at);});
        assertEquals(0,count("study_owner_answer_inputs"));assertEquals(1,count("study_mutations"));
    }
    @Test void sourceIdentityTextCommitmentAndReceiptAreImmutable(){
        Study s=startStudy();var result=answer(s,s.first(),"owner text");UUID input=(UUID)result.get("input_id");
        for(String assignment:List.of("text_ref='replacement'","source_binding_ref='replacement'","question_id='"+s.second()+"'","attempt_id='"+UUID.randomUUID()+"'","expected_study_version=2,resulting_study_version=3","observed_conversation_version=observed_conversation_version+1","created_at=created_at+interval '1 second'"))assertThrows(RuntimeException.class,()->db.update("UPDATE study_owner_answer_inputs SET "+assignment+" WHERE id=?",input));
        assertThrows(RuntimeException.class,()->db.update("UPDATE study_sessions SET lesson_binding_ref='replacement' WHERE id=?",s.id()));
        assertThrows(RuntimeException.class,()->db.update("UPDATE study_mutations SET request_digest=? WHERE study_session_id=?","b".repeat(64),s.id()));
        assertThrows(RuntimeException.class,()->db.update("DELETE FROM study_owner_answer_inputs WHERE id=?",input));assertThrows(RuntimeException.class,()->db.update("DELETE FROM study_sessions WHERE id=?",s.id()));assertThrows(RuntimeException.class,()->db.update("DELETE FROM study_mutations WHERE study_session_id=?",s.id()));
        assertEquals(1,db.update("UPDATE study_owner_answer_inputs SET text_ref=text_ref WHERE id=?",input));
    }
    @Test void wrongEpochAndCrossSessionSourceBindingsFail(){
        Study s=startStudy(),other=startStudy();long c=conversationVersion(s.conversation());Timestamp at=timestamp();
        rejectTransaction(()->{input(s,UUID.randomUUID(),UUID.randomUUID(),other.first(),1,c,at);});
        rejectTransaction(()->db.update("INSERT INTO study_owner_answer_inputs(id,attempt_id,owner_id,conversation_id,study_session_id,question_id,expected_study_version,resulting_study_version,session_hash,security_epoch,observed_conversation_version,created_at,text_ref,source_binding_ref) VALUES (?,?,?,?,?,?,1,2,?,2,?,?,?,?)",UUID.randomUUID(),UUID.randomUUID(),owner,s.conversation(),s.id(),s.first(),session.hash(),c,at,"text","binding"));
        rejectTransaction(()->{nextSession(s,2,true,at);db.update("INSERT INTO study_mutations(owner_id,idempotency_key,operation,session_hash,security_epoch,request_digest,conversation_id,study_session_id,lesson_run_id,prior_version,resulting_version,prior_conversation_version,resulting_conversation_version,committed_at) VALUES (?,?,'CLOSE',?,2,?,?,?,?,1,2,?,?,?)",owner,UUID.randomUUID(),session.hash(),"a".repeat(64),s.conversation(),s.id(),s.run(),c,c+1,at);});
        assertEquals(0,count("study_owner_answer_inputs"));assertEquals(2,count("study_mutations"));
    }
    @Test void duplicateQuestionAttemptAndCloseThenAnswerCannotCommit(){
        Study s=startStudy();var first=answer(s,s.first(),"one");long c=conversationVersion(s.conversation());Timestamp at=timestamp();
        rejectTransaction(()->input(s,UUID.randomUUID(),UUID.randomUUID(),s.first(),2,c,at));
        rejectTransaction(()->input(s,UUID.randomUUID(),(UUID)first.get("attempt_id"),s.second(),2,c,at));
        close(s);rejectTransaction(()->input(s,UUID.randomUUID(),UUID.randomUUID(),s.second(),3,conversationVersion(s.conversation()),timestamp()));
        assertEquals(1,count("study_owner_answer_inputs"));assertEquals(3,count("study_mutations"));
    }
    @Test void gapDuplicateTransitionAndBackwardTimestampCannotCommit(){
        Study s=startStudy();long c=conversationVersion(s.conversation());Timestamp at=timestamp();
        rejectTransaction(()->{nextSession(s,3,true,at);mutation(s,"CLOSE",null,null,null,2L,3,c,at);});
        rejectTransaction(()->{nextSession(s,2,true,at);mutation(s,"CLOSE",null,null,null,1L,2,c,at);mutation(s,"CLOSE",null,null,null,1L,2,c+1,at);});
        Timestamp before=Timestamp.from(AuthService.instant(db.queryForMap("SELECT created_at FROM study_sessions WHERE id=?",s.id()).get("created_at")).minusSeconds(1));
        rejectTransaction(()->{nextSession(s,2,true,before);mutation(s,"CLOSE",null,null,null,1L,2,c,before);});assertEquals(1,studyVersion(s.id()));
    }
    @Test void conflictDoNothingDoesNotFireConversationBump(){Study s=startStudy();long c=conversationVersion(s.conversation());assertEquals(0,db.update("INSERT INTO study_mutations SELECT * FROM study_mutations WHERE study_session_id=? ON CONFLICT DO NOTHING",s.id()));assertEquals(c,conversationVersion(s.conversation()));assertEquals(1,count("study_mutations"));}
    @Test void aggregateRowCapCountsTheFinalCloseReceiptAndRejectsCapPlusOne(){
        // Capacity-only metadata fixture; these approvals are not asserted to authorize deletion.
        Study s=startStudy();UUID action=UUID.randomUUID();db.update("INSERT INTO action_proposals(id,owner_id,run_id,tool_id,tool_version,payload_ref,payload_hash,status,security_epoch,valid_until) VALUES (?,?,?,'calendar.event.create','1','synthetic','synthetic','CANCELLED',1,clock_timestamp())",action,owner,s.run());
        long initial=((Number)db.queryForMap("SELECT * FROM conversation_source_inventory_v16(?,?)",owner,s.conversation()).get("scoped_rows")).longValue();
        db.update("INSERT INTO approvals(id,owner_id,action_id,payload_hash,status,expires_at,action_version) SELECT gen_random_uuid(),?,?,md5(g::text),'REJECTED',clock_timestamp()+interval '1 day',1 FROM generate_series(1,?) g",owner,action,(int)(40000-initial));
        assertEquals(40000L,((Number)db.queryForMap("SELECT * FROM conversation_source_inventory_v16(?,?)",owner,s.conversation()).get("scoped_rows")).longValue());
        long c=conversationVersion(s.conversation());var at=timestamp();rejectTransaction(()->{nextSession(s,2,true,at);mutation(s,"CLOSE",null,null,null,1L,2,c,at);});
        assertEquals(1,studyVersion(s.id()));assertEquals(c,conversationVersion(s.conversation()));assertEquals(1,count("study_mutations"));
        db.update("INSERT INTO approvals(id,owner_id,action_id,payload_hash,status,expires_at,action_version) VALUES (?,?,?,'extra synthetic metadata','REJECTED',clock_timestamp()+interval '1 day',1)",UUID.randomUUID(),owner,action);
        assertThrows(RuntimeException.class,()->db.queryForMap("SELECT * FROM conversation_source_inventory_v16(?,?)",owner,s.conversation()));
    }

    @Test void defensiveSessionAndAnswerCapProbesRejectCorruptCapPlusOneWithoutCommitting(){
        // Valid graphs reach these bounds through the stricter run/identity invariants.
        // Only this rollback-only corruption fixture disables the new user guards;
        // it tests the real inventory function, never authorizes or commits a source.
        for(boolean answerCap:List.of(false,true)){
            UUID target=conversation("defensive capacity"),support=conversation("rollback-only support");
            flow.tx.executeWithoutResult(status->{status.setRollbackOnly();
                db.execute("ALTER TABLE study_sessions DISABLE TRIGGER USER");
                db.execute("ALTER TABLE study_owner_answer_inputs DISABLE TRIGGER USER");
                db.update("INSERT INTO runs(id,owner_id,conversation_id,status) SELECT gen_random_uuid(),?,?,'SUCCEEDED' FROM generate_series(1,1001)",owner,support);
                db.update("WITH t AS (SELECT clock_timestamp() AS at) INSERT INTO study_sessions(id,owner_id,conversation_id,lesson_run_id,lesson_run_version,question_1_id,question_2_id,lesson_binding_ref,version,lifecycle,created_at,updated_at) SELECT gen_random_uuid(),owner_id,?,id,version,gen_random_uuid(),gen_random_uuid(),'synthetic',1,'OPEN',t.at,t.at FROM runs CROSS JOIN t WHERE conversation_id=? ORDER BY id LIMIT 1000",target,support);
                var atLimit=db.queryForMap("SELECT * FROM conversation_source_inventory_v16(?,?)",owner,target);assertEquals(1000L,((Number)atLimit.get("study_sessions")).longValue());
                if(answerCap){
                    db.update("INSERT INTO study_owner_answer_inputs(id,attempt_id,owner_id,conversation_id,study_session_id,question_id,expected_study_version,resulting_study_version,session_hash,security_epoch,observed_conversation_version,created_at,text_ref,source_binding_ref) SELECT gen_random_uuid(),gen_random_uuid(),s.owner_id,s.conversation_id,s.id,q.id,q.version,q.version+1,?,1,1,clock_timestamp(),'synthetic','synthetic' FROM study_sessions s CROSS JOIN LATERAL (VALUES(s.question_1_id,1),(s.question_2_id,2)) q(id,version) WHERE s.conversation_id=?",session.hash(),target);
                    assertEquals(2000L,((Number)db.queryForMap("SELECT * FROM conversation_source_inventory_v16(?,?)",owner,target).get("study_answers")).longValue());
                    db.update("INSERT INTO study_owner_answer_inputs(id,attempt_id,owner_id,conversation_id,study_session_id,question_id,expected_study_version,resulting_study_version,session_hash,security_epoch,observed_conversation_version,created_at,text_ref,source_binding_ref) SELECT gen_random_uuid(),gen_random_uuid(),owner_id,conversation_id,id,gen_random_uuid(),1,2,?,1,1,clock_timestamp(),'synthetic','synthetic' FROM study_sessions WHERE conversation_id=? ORDER BY id LIMIT 1",session.hash(),target);
                }else{
                    db.update("WITH t AS (SELECT clock_timestamp() AS at) INSERT INTO study_sessions(id,owner_id,conversation_id,lesson_run_id,lesson_run_version,question_1_id,question_2_id,lesson_binding_ref,version,lifecycle,created_at,updated_at) SELECT gen_random_uuid(),r.owner_id,?,r.id,r.version,gen_random_uuid(),gen_random_uuid(),'synthetic',1,'OPEN',t.at,t.at FROM runs r CROSS JOIN t WHERE r.conversation_id=? AND NOT EXISTS(SELECT 1 FROM study_sessions s WHERE s.lesson_run_id=r.id) ORDER BY r.id LIMIT 1",target,support);
                }
                assertThrows(RuntimeException.class,()->db.queryForMap("SELECT * FROM conversation_source_inventory_v16(?,?)",owner,target));
            });
            assertEquals(0L,db.queryForObject("SELECT count(*) FROM study_sessions WHERE conversation_id=?",Long.class,target));
            assertEquals(0L,db.queryForObject("SELECT count(*) FROM study_owner_answer_inputs WHERE conversation_id=?",Long.class,target));
            assertEquals(0L,db.queryForObject("SELECT count(*) FROM pg_trigger WHERE tgrelid IN ('study_sessions'::regclass,'study_owner_answer_inputs'::regclass) AND tgenabled='D'",Long.class));
        }
    }

}

package dev.myjarvis.core;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;
import static org.junit.jupiter.api.Assertions.*;

/** Disposable PostgreSQL; lessons are admitted through real settlement with a synthetic provider. */
abstract class StudyStorageFixtures extends ConversationDeletionFixtures {
    StudySessionService studies; LocalInferenceService local; int providerCalls;
    record Study(UUID conversation, UUID run, UUID id, UUID first, UUID second) {}
    @BeforeEach void prepareStudyStorage() {
        studies=context.getBean(StudySessionService.class); providerCalls=0;
        var client=new LocalInferenceClient(new MockEnvironment().withProperty("jarvis.local.manifest-hash","a".repeat(64))){
            @Override boolean ready(){return true;}
            @Override boolean configured(){return true;}
            @Override JsonNode infer(UUID owner,JsonNode request){providerCalls++;return StudyLessonContractTest.response(request);}
        };
        local=new LocalInferenceService(flow,client,context.getBean(InferenceContextService.class),context.getBean(InferenceContextFreshness.class));
    }
    @AfterEach void closeStudyStorage(){local.close();Thread.interrupted();}
    long conversationVersion(UUID conversation){return db.queryForObject("SELECT content_version FROM conversations WHERE id=?",Long.class,conversation);}
    long studyVersion(UUID study){return db.queryForObject("SELECT version FROM study_sessions WHERE id=?",Long.class,study);}
    UUID lesson(UUID conversation){UUID run=(UUID)local.acceptLesson(session,conversation,json(Map.of("client_message_id",UUID.randomUUID(),"text","Synthetic storage lesson"))).get("run_id");assertTrue(local.runOne());assertEquals("SUCCEEDED",db.queryForObject("SELECT status FROM runs WHERE id=?",String.class,run));return run;}
    Study startStudy(){UUID conversation=conversation("durable storage"),run=lesson(conversation);long c=conversationVersion(conversation);
        var result=receipt(studies.start(session,UUID.randomUUID(),conversation,json(Map.of("lesson_run_id",run,"expected_conversation_version",c))));UUID id=(UUID)result.get("study_session_id");
        var row=db.queryForMap("SELECT * FROM study_sessions WHERE id=?",id);assertEquals(c+1,conversationVersion(conversation));return new Study(conversation,run,id,(UUID)row.get("question_1_id"),(UUID)row.get("question_2_id"));}
    Map<String,Object> answer(Study s,UUID question,String text){return receipt(studies.answer(session,UUID.randomUUID(),s.id(),json(Map.of("expected_version",studyVersion(s.id()),"expected_conversation_version",conversationVersion(s.conversation()),"question_id",question,"text",text))));}
    Map<String,Object> close(Study s){return receipt(studies.close(session,UUID.randomUUID(),s.id(),json(Map.of("expected_version",studyVersion(s.id()),"expected_conversation_version",conversationVersion(s.conversation())))));}
    void nextSession(Study s,long version,boolean close,Timestamp at){db.update("UPDATE study_sessions SET version=?,lifecycle=?,updated_at=? WHERE id=?",version,close?"CLOSED_BY_OWNER":"OPEN",at,s.id());}
    void input(Study s,UUID id,UUID attempt,UUID question,long prior,long observed,Timestamp at){db.update("INSERT INTO study_owner_answer_inputs(id,attempt_id,owner_id,conversation_id,study_session_id,question_id,expected_study_version,resulting_study_version,session_hash,security_epoch,observed_conversation_version,created_at,text_ref,source_binding_ref) VALUES (?,?,?,?,?,?,?,?,?,1,?,?,?,?)",id,attempt,owner,s.conversation(),s.id(),question,prior,prior+1,session.hash(),observed,at,"synthetic-sql-text","synthetic-sql-binding");}
    void mutation(Study s,String operation,UUID input,UUID attempt,UUID question,Long prior,long resulting,long conversation,Timestamp at){db.update("INSERT INTO study_mutations(owner_id,idempotency_key,operation,session_hash,security_epoch,request_digest,conversation_id,study_session_id,lesson_run_id,question_id,attempt_id,input_id,prior_version,resulting_version,prior_conversation_version,resulting_conversation_version,committed_at) VALUES (?,?,?,?,1,?,?,?,?,?,?,?,?,?,?,?,?)",owner,UUID.randomUUID(),operation,session.hash(),"a".repeat(64),s.conversation(),s.id(),s.run(),question,attempt,input,prior,resulting,conversation,conversation+1,at);}
    Timestamp timestamp(){return Timestamp.from(memories.databaseNow());}
    void rejectTransaction(Runnable work){assertThrows(RuntimeException.class,()->flow.tx.executeWithoutResult(status->{work.run();db.execute("SET CONSTRAINTS ALL IMMEDIATE");}));}
}

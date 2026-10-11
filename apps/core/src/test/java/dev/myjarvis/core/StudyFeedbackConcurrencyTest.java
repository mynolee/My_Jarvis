package dev.myjarvis.core;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StudyFeedbackConcurrencyTest extends StudyFeedbackFixtures {
    @Test void competingDistinctKeysForOneSavedInputAdmitExactlyOnce() throws Exception {
        saveTwo();var body=requestBody(0);var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            Callable<Boolean> attempt=()->{start.await();try{feedback.request(session,UUID.randomUUID(),studyId,body);return true;}catch(ApiException rejected){assertEquals(409,rejected.status);return false;}};
            var first=pool.submit(attempt);var second=pool.submit(attempt);start.countDown();
            assertNotEquals(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS));
        }
        assertEquals(1L,count("study_feedback_requests"));assertEquals(1L,count("study_feedback_events"));assertEquals(0,feedbackClient.calls);
    }
    @Test void revokeAndDispatchSerializeIntoOneIrreversibleTerminalChain() throws Exception {
        saveTwo();UUID id=requestFeedback(0);var start=new CountDownLatch(1);var before=revokeBody(id);
        try(var pool=Executors.newFixedThreadPool(2)){
            var dispatch=pool.submit(()->{start.await();return dispatch(id)!=null;});
            var revoke=pool.submit(()->{start.await();try{feedback.revoke(session,UUID.randomUUID(),id,before);return true;}catch(ApiException rejected){assertEquals(409,rejected.status);assertEquals("PRECONDITION_CHANGED",rejected.code);return false;}});
            start.countDown();boolean sent=dispatch.get(10,TimeUnit.SECONDS),closed=revoke.get(10,TimeUnit.SECONDS);
            assertNotEquals(sent,closed);if(!closed)feedback.revoke(session,UUID.randomUUID(),id,revokeBody(id));
        }
        var f=feedbackRow(id);assertEquals("REVOKED",f.get("state"));assertNull(f.get("result_ref"));assertEquals(((Number)f.get("version")).longValue(),db.queryForObject("SELECT count(*) FROM study_feedback_events WHERE feedback_id=?",Long.class,id));
        var terminal=terminalState();assertNull(dispatch(id));assertEquals(terminal,terminalState());assertEquals(0,feedbackClient.calls);
    }
    private Map<String,Object> terminalState(){
        Map<String,Object> result=new LinkedHashMap<>();
        for(String table:List.of("study_feedback_requests","study_feedback_events","runs","inference_reservations","inference_contexts","inference_control_slots","inference_usage_receipts")){
            String order=switch(table){case "study_feedback_events"->"feedback_id,version";case "inference_control_slots"->"reservation_id";case "inference_usage_receipts"->"reservation_id,receipt_kind";default->"id";};
            result.put(table,db.queryForList("SELECT * FROM "+table+" WHERE owner_id=? ORDER BY "+order,owner));
        }
        for(String table:List.of("inference_context_memories","inference_context_history")){
            String order=table.equals("inference_context_memories")?"context_id,memory_id":"context_id,ordinal";
            result.put(table,db.queryForList("SELECT * FROM "+table+" WHERE context_id IN (SELECT id FROM inference_contexts WHERE owner_id=?) ORDER BY "+order,owner));
        }
        result.put("conversation_content_version",conversationVersion(targetConversation));return result;
    }
    @Test void replayIsReadOnlyAcrossUnavailableRuntimeAndDoesNotConsumeAnotherPhase() {
        saveTwo();UUID key=UUID.randomUUID();var body=requestBody(0);var first=feedback.request(session,key,studyId,body);long version=conversationVersion(targetConversation);
        var rows=db.queryForList("SELECT * FROM study_feedback_events ORDER BY feedback_id,version");feedbackClient.available=false;
        assertEquals(receipt(first),receipt(feedback.request(session,key,studyId,body)));assertEquals(version,conversationVersion(targetConversation));assertEquals(rows,db.queryForList("SELECT * FROM study_feedback_events ORDER BY feedback_id,version"));assertEquals(0,feedbackClient.calls);
    }
}

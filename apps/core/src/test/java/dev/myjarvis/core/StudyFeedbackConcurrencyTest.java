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
            var dispatch=pool.submit(()->{start.await();try{dispatch(id);return true;}catch(RuntimeException rejected){return false;}});
            var revoke=pool.submit(()->{start.await();try{feedback.revoke(session,UUID.randomUUID(),id,before);return true;}catch(ApiException rejected){assertEquals(409,rejected.status);return false;}});
            start.countDown();boolean sent=dispatch.get(10,TimeUnit.SECONDS),closed=revoke.get(10,TimeUnit.SECONDS);
            assertTrue(sent||closed);if(!closed)feedback.revoke(session,UUID.randomUUID(),id,revokeBody(id));
        }
        var f=feedbackRow(id);assertEquals("REVOKED",f.get("state"));assertNull(f.get("result_ref"));assertEquals(((Number)f.get("version")).longValue(),db.queryForObject("SELECT count(*) FROM study_feedback_events WHERE feedback_id=?",Long.class,id));
        assertThrows(RuntimeException.class,()->dispatch(id));assertEquals(0,feedbackClient.calls);
    }
    @Test void replayIsReadOnlyAcrossUnavailableRuntimeAndDoesNotConsumeAnotherPhase() {
        saveTwo();UUID key=UUID.randomUUID();var body=requestBody(0);var first=feedback.request(session,key,studyId,body);long version=conversationVersion(targetConversation);
        var rows=db.queryForList("SELECT * FROM study_feedback_events ORDER BY feedback_id,version");feedbackClient.available=false;
        assertEquals(receipt(first),receipt(feedback.request(session,key,studyId,body)));assertEquals(version,conversationVersion(targetConversation));assertEquals(rows,db.queryForList("SELECT * FROM study_feedback_events ORDER BY feedback_id,version"));assertEquals(0,feedbackClient.calls);
    }
}

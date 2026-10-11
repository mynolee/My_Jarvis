package dev.myjarvis.core;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import static org.junit.jupiter.api.Assertions.*;

/** Real Core and disposable PostgreSQL with an independently synthetic model boundary. */
abstract class StudyFeedbackFixtures extends DurableStudyFixtures {
    StudyFeedbackService feedback; LocalInferenceService feedbackLocal; FeedbackClient feedbackClient;
    UUID targetConversation,studyId;
    @BeforeEach void feedbackBoundary(){
        local.controls.close();context.getBean(InferenceControlService.class).close();feedbackClient=new FeedbackClient();
        var contexts=context.getBean(InferenceContextService.class);var freshness=context.getBean(InferenceContextFreshness.class);
        feedbackLocal=new LocalInferenceService(flow,feedbackClient,contexts,freshness);feedbackLocal.controls.close();
        feedback=new StudyFeedbackService(db,memories,context.getBean(ConversationAccess.class),study,context.getBean(StudyFeedbackSource.class),
                context.getBean(StudyFeedbackLifecycle.class),contexts,freshness,feedbackLocal.controls,feedbackClient,context.getBean(PlatformTransactionManager.class));
        feedbackLocal.feedbackService(feedback);
    }
    @AfterEach void closeFeedbackBoundary(){feedbackLocal.close();}
    void saveTwo(){
        targetConversation=conversation("Synthetic feedback fixture");studyId=start(targetConversation,lesson(targetConversation));
        study.answer(session,UUID.randomUUID(),studyId,answerBody(studyId,0,"  e\u0301\n😀 selected synthetic answer  "));
        study.answer(session,UUID.randomUUID(),studyId,answerBody(studyId,1,"A different stored synthetic answer"));
    }
    JsonNode requestBody(int index){
        UUID question=question(studyId,index);var input=db.queryForMap("SELECT id,attempt_id FROM study_owner_answer_inputs WHERE owner_id=? AND study_session_id=? AND question_id=?",owner,studyId,question);
        return json(Map.of("expected_study_version",3,"expected_conversation_version",conversationVersion(targetConversation),"question_id",question,"input_id",input.get("id"),"attempt_id",input.get("attempt_id")));
    }
    UUID requestFeedback(int index){return (UUID)receipt(feedback.request(session,UUID.randomUUID(),studyId,requestBody(index))).get("feedback_request_id");}
    Map<String,Object> feedbackRow(UUID id){return db.queryForMap("SELECT * FROM study_feedback_requests WHERE owner_id=? AND id=?",owner,id);}
    LocalInferenceService.Dispatch dispatch(UUID id){return feedback.prepareDispatch(owner,(UUID)feedbackRow(id).get("reservation_id"));}
    void complete(UUID id){var dispatch=dispatch(id);var response=StudyFeedbackResultTest.response(dispatch.body());context.getBean(UsageService.class).record(dispatch,response);feedback.settle(dispatch,response);}
    JsonNode revokeBody(UUID id){return json(Map.of("expected_feedback_version",feedbackRow(id).get("version"),"expected_conversation_version",conversationVersion(targetConversation)));}
    void proof(UUID id,String kind)throws Exception {
        var row=feedbackRow(id);var slot=db.queryForMap("SELECT * FROM inference_control_slots WHERE owner_id=? AND reservation_id=?",owner,row.get("reservation_id"));
        if("UNSTARTED_CORE".equals(kind)){feedbackLocal.controls.reconcileOne(owner,(UUID)row.get("reservation_id"));return;}
        JsonNode request=InferenceControlService.controlRequest(slot,"OBSERVE");
        feedbackLocal.controls.record(request,InferenceControlResult.verify(InferenceControlResultTest.response(request,"TERMINAL",kind),request));
    }
    boolean quiescent(UUID id){return db.queryForObject("SELECT study_feedback_execution_quiescent_v18(?,?)",Boolean.class,owner,feedbackRow(id).get("reservation_id"));}
    final class FeedbackClient extends LocalInferenceClient {
        int calls;boolean available=true,failInfer;String kind="FEEDBACK",proofKind;JsonNode lastBody;Runnable duringInfer=()->{};
        FeedbackClient(){super(new MockEnvironment().withProperty("jarvis.local.manifest-hash","a".repeat(64)));}
        @Override boolean configured(){return true;}
        @Override boolean ready(){return available;}
        @Override boolean feedbackReady(){return available;}
        @Override JsonNode infer(UUID owner,JsonNode request){
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());calls++;lastBody=request;duringInfer.run();
            if(failInfer)throw new IllegalStateException("Synthetic incomplete response");
            if("ERROR".equals(kind))return json(Map.of("kind","ERROR","error_code","MODEL_UNAVAILABLE","retryable",false,"usage",StudyFeedbackResultTest.response(request).get("usage")));
            return StudyFeedbackResultTest.response(request);
        }
        @Override InferenceControlResult control(JsonNode request){
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return InferenceControlResult.verify(InferenceControlResultTest.response(request,proofKind==null?"PENDING":"TERMINAL",proofKind),request);
        }
    }
}

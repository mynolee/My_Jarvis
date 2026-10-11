package dev.myjarvis.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StudyFeedbackSourceTest extends StudyFeedbackFixtures {
    @Test void firstHistoricalAnswerSurvivesLaterAnswerAndNewLogin(){saveTwo();long old=db.queryForObject("SELECT min(observed_conversation_version) FROM study_owner_answer_inputs",Long.class);AuthService auth=context.getBean(AuthService.class);session=auth.find(auth.issue(owner,true,session.hash()).token());assertTrue(conversationVersion(targetConversation)>old);UUID id=requestFeedback(0);assertNotNull(dispatch(id));assertEquals("  e\u0301\n😀 selected synthetic answer  ",dispatchText(id));}
    private String dispatchText(UUID id){return flow.tx.execute(s->context.getBean(StudyFeedbackSource.class).loadForExecution(owner,id).text());}
    @Test void browserTargetCannotSwapQuestionInputAttemptOrStudy(){saveTwo();for(String field:List.of("question_id","input_id","attempt_id")){var body=(tools.jackson.databind.node.ObjectNode)requestBody(0);body.put(field,UUID.randomUUID().toString());assertThrows(ApiException.class,()->feedback.request(session,UUID.randomUUID(),studyId,body));}assertEquals(0L,count("study_feedback_requests"));assertEquals(0,feedbackClient.calls);}
    @Test void admissionRequiresBothGenuineAnswerReceiptsAndOpenVersionThree(){targetConversation=conversation("Incomplete synthetic");studyId=start(targetConversation,lesson(targetConversation));var empty=json(Map.of("expected_study_version",3,"expected_conversation_version",conversationVersion(targetConversation),"question_id",question(studyId,0),"input_id",UUID.randomUUID(),"attempt_id",UUID.randomUUID()));assertThrows(ApiException.class,()->feedback.request(session,UUID.randomUUID(),studyId,empty));study.answer(session,UUID.randomUUID(),studyId,answerBody(studyId,0,"One retained answer"));assertThrows(ApiException.class,()->requestFeedback(0));study.answer(session,UUID.randomUUID(),studyId,answerBody(studyId,1,"Second retained answer"));study.close(session,UUID.randomUUID(),studyId,closeBody(studyId));assertThrows(ApiException.class,()->requestFeedback(0));assertEquals(0L,count("study_feedback_requests"));}
    @Test void sourceHmacBindsEveryExecutionAndHistoricalIdentity(){saveTwo();UUID id=requestFeedback(0);flow.tx.executeWithoutResult(s->{var loader=context.getBean(StudyFeedbackSource.class);var source=loader.loadForExecution(owner,id);var row=feedbackRow(id);String actual=loader.binding(owner,row,source);for(String field:List.of("id","request_id","run_id","reservation_id","context_id","owner_id","conversation_id","project_id","study_session_id","lesson_run_id","question_id","input_id","attempt_id")){var changed=new LinkedHashMap<>(row);changed.put(field,UUID.randomUUID());assertNotEquals(actual,loader.binding(owner,changed,source),field);}for(String field:List.of("admission_conversation_version","security_epoch","context_epoch","history_epoch","profile_version")){var changed=new LinkedHashMap<>(row);changed.put(field,((Number)row.get(field)).longValue()+1);assertNotEquals(actual,loader.binding(owner,changed,source),field);}var input=new LinkedHashMap<>(source.input());input.put("observed_conversation_version",((Number)input.get("observed_conversation_version")).longValue()+1);var altered=new StudyFeedbackSource.ExecutionSource(source.conversation(),source.study(),source.lesson(),input,source.text(),source.originalBinding(),source.owner());assertNotEquals(actual,loader.binding(owner,row,altered));});}
    @Test void sourceBindingAadCannotMoveBetweenFeedbackIdentities(){saveTwo();UUID id=requestFeedback(0);String ciphertext=(String)feedbackRow(id).get("source_binding_ref");assertThrows(RuntimeException.class,()->memories.vault.open(owner,UUID.randomUUID(),"study-feedback-source-binding",ciphertext));assertEquals(142,ciphertext.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);}
    @Test void retainedReadAllowsOwnerCloseProfileChangeAndNewLogin()throws Exception {saveTwo();UUID id=requestFeedback(0);complete(id);proof(id,"COMPLETED");String retained=(String)feedbackRow(id).get("result_ref");study.close(session,UUID.randomUUID(),studyId,closeBody(studyId));context.getBean(OwnerProfileService.class).patch(session,UUID.randomUUID(),json(Map.of("expected_version",db.queryForObject("SELECT version FROM owners WHERE id=?",Long.class,owner),"locale","en-US")));AuthService auth=context.getBean(AuthService.class);session=auth.find(auth.issue(owner,true,session.hash()).token());var result=Json.MAPPER.valueToTree(feedback.read(session,studyId));assertEquals("CLOSED_BY_OWNER",result.path("lifecycle").asText());assertEquals("NOT_RUN",result.path("targets").get(0).path("request").path("result").path("code_execution").asText());assertEquals(retained,feedbackRow(id).get("result_ref"));assertTrue(quiescent(id));}
    @Test void initiatingAuthorityCannotBeRestoredByReadingFromAnotherSession(){saveTwo();UUID id=requestFeedback(0);AuthService auth=context.getBean(AuthService.class);session=auth.find(auth.issue(owner,true,session.hash()).token());assertEquals("REVOKED",feedbackRow(id).get("state"));assertThrows(ApiException.class,()->flow.tx.execute(s->context.getBean(StudyFeedbackSource.class).loadForExecution(owner,id)));assertEquals("REVOKED",Json.MAPPER.valueToTree(feedback.read(session,studyId)).path("targets").get(0).path("request").path("status").asText());}
    @Test void selectedSourceNeverCarriesOtherAnswerProfileOrStoredAuthHashes(){saveTwo();UUID id=requestFeedback(0);var dispatch=dispatch(id);String body=Json.canonical(dispatch.body());assertTrue(body.contains("selected synthetic answer"));assertFalse(body.contains("A different stored synthetic answer"));for(String field:List.of("session_hash","source_binding_ref","text_ref","display_name","locale","learning_profile"))assertFalse(body.contains(field),field);assertEquals("UTC",dispatch.body().path("timezone").asText());}
    private StudyFeedbackSource loaderWithVault(Vault vault) {
        var manager=context.getBean(org.springframework.transaction.PlatformTransactionManager.class);var access=context.getBean(ConversationAccess.class);
        var storage=new MemoryService(db,vault,memories.keys,manager,context.getBean(InferenceContextFreshness.class));
        var studies=new StudySessionService(db,storage,access,deletion,manager);
        return new StudyFeedbackSource(db,studies,storage,access,deletion);
    }
    @Test void actualLoaderRejectsCorruptCopiedBindingsAndChangedLessonOrAnswerPlaintext(){
        saveTwo();UUID id=requestFeedback(0);var row=feedbackRow(id);var events=db.queryForList("SELECT * FROM study_feedback_events WHERE feedback_id=? ORDER BY version",id);long version=conversationVersion(targetConversation);
        for(String mode:List.of("ciphertext","feedback-binding","lesson-binding","answer-binding","answer-text","question-text","question-id","wrong-envelope-kind","noncanonical-envelope")){
            String domain=switch(mode){case "feedback-binding"->"study-feedback-source-binding";case "lesson-binding"->"study-lesson-binding";case "answer-binding"->"study-answer-binding";case "answer-text"->"study-answer-text";default->"result";};
            Vault boundary=spy(memories.vault);
            doAnswer(call->{
                if(mode.equals("ciphertext"))return memories.vault.open(call.getArgument(0),UUID.randomUUID(),domain,call.getArgument(3));
                String original=(String)call.callRealMethod();
                if(mode.endsWith("binding"))return "f".repeat(64);
                if(mode.equals("answer-text"))return original+" altered";
                if(mode.equals("noncanonical-envelope"))return " "+original;
                var encoded=(tools.jackson.databind.node.ObjectNode)Json.parse(original.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                if(mode.equals("wrong-envelope-kind"))encoded.put("kind","ANSWER");
                else {var question=(tools.jackson.databind.node.ObjectNode)encoded.path("lesson").path("questions").get(0);question.put(mode.equals("question-id")?"id":"text",mode.equals("question-id")?UUID.randomUUID().toString():"Changed synthetic question");}
                return Json.canonical(encoded);
            }).when(boundary).open(any(UUID.class),any(UUID.class),eq(domain),anyString());
            var loader=loaderWithVault(boundary);assertThrows(RuntimeException.class,()->flow.tx.execute(t->loader.loadForExecution(owner,id)),mode);
            verify(boundary,atLeastOnce()).open(any(UUID.class),any(UUID.class),eq(domain),anyString());assertEquals(row,feedbackRow(id),mode);assertEquals(events,db.queryForList("SELECT * FROM study_feedback_events WHERE feedback_id=? ORDER BY version",id));assertEquals(version,conversationVersion(targetConversation));
        }
        assertEquals(0,feedbackClient.calls);assertEquals(1L,count("study_feedback_requests"));
    }
    @Test void originalAnswerAuthorityAndCiphertextMutationMatrixRollsBackBeforeAdmission(){
        saveTwo();var body=requestBody(0);UUID input=Json.uuid(body,"input_id");var before=db.queryForMap("SELECT * FROM study_owner_answer_inputs WHERE id=?",input);long version=conversationVersion(targetConversation);
        var changes=new LinkedHashMap<String,Object>();
        for(String field:List.of("id","attempt_id","owner_id","conversation_id","project_id","study_session_id","question_id"))changes.put(field,UUID.randomUUID());
        for(String field:List.of("expected_study_version","resulting_study_version","security_epoch","observed_conversation_version"))changes.put(field,((Number)before.get(field)).longValue()+1);
        changes.put("session_hash",UUID.randomUUID().toString());changes.put("created_at",java.sql.Timestamp.from(AuthService.instant(before.get("created_at")).minusSeconds(1)));changes.put("text_ref",before.get("source_binding_ref"));changes.put("source_binding_ref",before.get("text_ref"));
        for(var change:changes.entrySet()){
            assertThrows(RuntimeException.class,()->flow.tx.executeWithoutResult(t->{db.update("UPDATE study_owner_answer_inputs SET "+change.getKey()+"=? WHERE id=?",change.getValue(),input);feedback.request(session,UUID.randomUUID(),studyId,body);}),change.getKey());
            assertEquals(before,db.queryForMap("SELECT * FROM study_owner_answer_inputs WHERE id=?",input));assertEquals(version,conversationVersion(targetConversation));assertEquals(0L,count("study_feedback_requests"));
        }assertEquals(0,feedbackClient.calls);
    }
    @Test void exactFeedbackExecutionIdentitySwapMatrixCannotRetargetSource(){
        saveTwo();UUID id=requestFeedback(0);var before=feedbackRow(id);long version=conversationVersion(targetConversation);var loader=context.getBean(StudyFeedbackSource.class);
        for(String field:List.of("id","owner_id","conversation_id","project_id","study_session_id","lesson_run_id","request_id","run_id","reservation_id","context_id","question_id","input_id","attempt_id")){
            assertThrows(RuntimeException.class,()->flow.tx.executeWithoutResult(t->{db.update("UPDATE study_feedback_requests SET "+field+"=? WHERE id=?",UUID.randomUUID(),id);loader.loadForExecution(owner,id);}));
            assertEquals(before,feedbackRow(id),field);assertEquals(version,conversationVersion(targetConversation));
        }assertEquals(0,feedbackClient.calls);assertEquals(1L,count("study_feedback_requests"));
    }
    @Test void lessonReceiptStateDigestAndMissingOrExtraSourceRowsCannotAdmit(){
        saveTwo();var body=requestBody(0);UUID lesson=(UUID)db.queryForMap("SELECT lesson_run_id FROM study_sessions WHERE id=?",studyId).get("lesson_run_id");UUID reservation=db.queryForObject("SELECT id FROM inference_reservations WHERE run_id=?",UUID.class,lesson);
        var rows=db.queryForList("SELECT * FROM study_owner_answer_inputs ORDER BY id");var run=db.queryForMap("SELECT * FROM runs WHERE id=?",lesson);var receipts=db.queryForList("SELECT * FROM inference_usage_receipts WHERE reservation_id=?",reservation);long version=conversationVersion(targetConversation);
        List<Runnable> attacks=List.of(
            ()->db.update("UPDATE runs SET status='FAILED' WHERE id=?",lesson),()->db.update("UPDATE runs SET status='UNKNOWN' WHERE id=?",lesson),()->db.update("UPDATE runs SET version=version+1 WHERE id=?",lesson),
            ()->db.update("UPDATE study_sessions SET lesson_run_version=lesson_run_version+1 WHERE id=?",studyId),()->db.update("UPDATE inference_reservations SET purpose='CONVERSATION' WHERE id=?",reservation),
            ()->db.update("UPDATE inference_usage_receipts SET response_kind='ANSWER' WHERE reservation_id=?",reservation),()->db.update("UPDATE inference_usage_receipts SET request_digest=? WHERE reservation_id=?","f".repeat(64),reservation),
            ()->db.update("DELETE FROM inference_usage_receipts WHERE reservation_id=?",reservation),()->db.update("DELETE FROM study_owner_answer_inputs WHERE id=?",Json.uuid(body,"input_id")),
            ()->db.update("DELETE FROM study_mutations WHERE input_id=?",Json.uuid(body,"input_id")),
            ()->db.update("INSERT INTO study_owner_answer_inputs(id,owner_id,conversation_id,project_id,study_session_id,question_id,attempt_id,expected_study_version,resulting_study_version,session_hash,security_epoch,observed_conversation_version,text_ref,source_binding_ref,created_at) SELECT ?,owner_id,conversation_id,project_id,study_session_id,question_id,?,expected_study_version,resulting_study_version,session_hash,security_epoch,observed_conversation_version,text_ref,source_binding_ref,created_at FROM study_owner_answer_inputs WHERE id=?",UUID.randomUUID(),UUID.randomUUID(),Json.uuid(body,"input_id")));
        for(Runnable attack:attacks){assertThrows(RuntimeException.class,()->flow.tx.executeWithoutResult(t->{attack.run();feedback.request(session,UUID.randomUUID(),studyId,body);}));assertEquals(rows,db.queryForList("SELECT * FROM study_owner_answer_inputs ORDER BY id"));assertEquals(run,db.queryForMap("SELECT * FROM runs WHERE id=?",lesson));assertEquals(receipts,db.queryForList("SELECT * FROM inference_usage_receipts WHERE reservation_id=?",reservation));assertEquals(version,conversationVersion(targetConversation));assertEquals(0L,count("study_feedback_requests"));}
        assertEquals(0,feedbackClient.calls);
    }
    @Test void ordinaryUserMessageCannotStandInForStoredAnswerIdentity(){saveTwo();var body=(tools.jackson.databind.node.ObjectNode)requestBody(0);UUID message=db.queryForObject("SELECT id FROM messages WHERE conversation_id=? AND role='USER' ORDER BY created_at LIMIT 1",UUID.class,targetConversation);body.put("input_id",message.toString());assertThrows(ApiException.class,()->feedback.request(session,UUID.randomUUID(),studyId,body));assertEquals(0L,count("study_feedback_requests"));assertEquals(0,feedbackClient.calls);}
    @Test void legacyAnswerUuidAliasIsRejectedBeforeFeedbackAllocation(){
        targetConversation=conversation("Synthetic alias lineage");studyId=start(targetConversation,lesson(targetConversation));UUID question=question(studyId,0),input=UUID.randomUUID(),key=UUID.randomUUID(),audit=UUID.randomUUID();var body=answerBody(studyId,0,"Authentic answer with an aliased legacy attempt");
        try(var uuids=mockStatic(UUID.class,CALLS_REAL_METHODS)){uuids.when(UUID::randomUUID).thenReturn(input,question,audit);study.answer(session,key,studyId,body);}
        study.answer(session,UUID.randomUUID(),studyId,answerBody(studyId,1,"Second authentic answer"));assertEquals(question,db.queryForObject("SELECT attempt_id FROM study_owner_answer_inputs WHERE id=?",UUID.class,input));
        long version=conversationVersion(targetConversation),runs=count("runs"),reservations=count("inference_reservations");assertThrows(ApiException.class,()->requestFeedback(0));assertEquals(version,conversationVersion(targetConversation));assertEquals(runs,count("runs"));assertEquals(reservations,count("inference_reservations"));assertEquals(0L,count("study_feedback_requests"));assertEquals(0,feedbackClient.calls);
    }
    @Test void retainedResultCorruptionForeignOwnerAndOversizeRefuseTheWholeRead()throws Exception {
        saveTwo();UUID id=requestFeedback(0);complete(id);proof(id,"COMPLETED");var original=feedbackRow(id);String encoded=memories.vault.open(owner,id,"study-feedback-result",(String)original.get("result_ref"));
        for(String changed:List.of("{", " "+encoded, "x".repeat(65537),encoded.replace("NOT_RUN","VERIFIED"),encoded.replace(original.get("input_id").toString(),UUID.randomUUID().toString()))){
            Vault boundary=spy(memories.vault);doReturn(changed).when(boundary).open(owner,id,"study-feedback-result",(String)original.get("result_ref"));var loader=loaderWithVault(boundary);
            var service=new StudyFeedbackService(db,memories,context.getBean(ConversationAccess.class),study,loader,context.getBean(StudyFeedbackLifecycle.class),context.getBean(InferenceContextService.class),context.getBean(InferenceContextFreshness.class),feedbackLocal.controls,feedbackClient,context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
            assertThrows(ApiException.class,()->service.read(session,studyId));verify(boundary,atLeastOnce()).open(owner,id,"study-feedback-result",(String)original.get("result_ref"));assertEquals(original,feedbackRow(id));
        }
        var foreign=new AuthService.Session(UUID.randomUUID().toString(),UUID.randomUUID(),java.time.Instant.now(),1);var loader=spy(context.getBean(StudyFeedbackSource.class));var service=new StudyFeedbackService(db,memories,context.getBean(ConversationAccess.class),study,loader,context.getBean(StudyFeedbackLifecycle.class),context.getBean(InferenceContextService.class),context.getBean(InferenceContextFreshness.class),feedbackLocal.controls,feedbackClient,context.getBean(org.springframework.transaction.PlatformTransactionManager.class));assertEquals(401,assertThrows(ApiException.class,()->service.read(foreign,studyId)).status);verify(loader,never()).loadForRead(any(AuthService.Session.class),any(UUID.class));assertEquals(original,feedbackRow(id));assertEquals(1L,count("owners"));assertEquals(0,feedbackClient.calls);
    }
}

package dev.myjarvis.core;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;
import static org.junit.jupiter.api.Assertions.*;

/** Real storage/service fixture with one explicitly synthetic model boundary. */
abstract class DurableStudyFixtures extends ConversationDeletionFixtures {
    StudySessionService study; LocalInferenceService local; int providerCalls; String responseKind;
    @BeforeEach void durableBoundary() {
        study = context.getBean(StudySessionService.class); providerCalls = 0; responseKind = "LESSON";
        LocalInferenceClient client = new LocalInferenceClient(new MockEnvironment().withProperty("jarvis.local.manifest-hash", "a".repeat(64))) {
            @Override boolean ready() { return true; }
            @Override boolean configured() { return true; }
            @Override JsonNode infer(UUID owner, JsonNode request) {
                providerCalls++; var value = StudyLessonContractTest.response(request);
                if ("NEED_INPUT".equals(responseKind)) return json(Map.of("kind", "NEED_INPUT", "question", "Synthetic clarification?", "missing_fields", List.of("topic"), "usage", value.get("usage")));
                return value;
            }
        };
        local = new LocalInferenceService(flow, client, context.getBean(InferenceContextService.class), context.getBean(InferenceContextFreshness.class));
    }
    @AfterEach void closeDurableBoundary() { local.close(); Thread.interrupted(); }
    UUID lesson(UUID conversation) {
        UUID run = (UUID) local.acceptLesson(session, conversation, json(Map.of("client_message_id", UUID.randomUUID(), "text", "Synthetic durable-study topic"))).get("run_id");
        assertTrue(local.runOne()); return run;
    }
    long conversationVersion(UUID conversation) { return db.queryForObject("SELECT content_version FROM conversations WHERE owner_id=? AND id=?", Long.class, owner, conversation); }
    UUID start(UUID conversation, UUID run) {
        return (UUID) receipt(study.start(session, UUID.randomUUID(), conversation, json(Map.of("lesson_run_id", run, "expected_conversation_version", conversationVersion(conversation))))).get("study_session_id");
    }
    Map<String,Object> view(UUID studyId) { return study.get(session, studyId); }
    UUID question(UUID studyId, int index) { return UUID.fromString(Json.MAPPER.valueToTree(view(studyId)).path("lesson").path("questions").get(index).path("id").asText()); }
    JsonNode answerBody(UUID studyId, int index, String text) {
        var view = view(studyId); var lesson = Json.MAPPER.valueToTree(view).path("lesson");
        return json(Map.of("expected_version", view.get("version"), "expected_conversation_version", view.get("conversation_content_version"), "question_id", lesson.path("questions").get(index).path("id").asText(), "text", text));
    }
    JsonNode closeBody(UUID studyId) { var view = view(studyId); return json(Map.of("expected_version", view.get("version"), "expected_conversation_version", view.get("conversation_content_version"))); }
}

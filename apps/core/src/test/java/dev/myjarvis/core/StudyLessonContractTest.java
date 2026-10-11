package dev.myjarvis.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

class StudyLessonContractTest {
    static ObjectNode request() {
        ObjectNode request = (ObjectNode) LocalInferenceContractTest.request();
        request.put("purpose", "STUDY_LESSON"); request.put("prompt_revision", "local-v1");
        request.set("context", Json.MAPPER.valueToTree(Map.of("id", UUID.randomUUID(), "revision", 1)));
        request.set("allowed_tool_ids", Json.MAPPER.valueToTree(List.of()));
        return request;
    }
    static ObjectNode response(JsonNode request) {
        return (ObjectNode) Json.MAPPER.valueToTree(Map.of("kind", "LESSON",
                "concepts", List.of("Synthetic first concept", "Synthetic second concept", "Synthetic third concept"),
                "questions", List.of("Synthetic first question?", "Synthetic second question?"),
                "usage", LocalInferenceContractTest.response(request).get("usage")));
    }
    @Test void exactTypedWireHasNoModelIdentityAndMintsOnlyAtAdmission() {
        var request = request(); var response = response(request);
        assertNull(LocalInferenceService.validate(response, request));
        String encoded = StudyLessonResult.admit(response);
        var lesson = StudyLessonResult.stored(encoded);
        assertEquals(3, lesson.concepts().size()); assertEquals(2, lesson.questions().size());
        assertNotEquals(lesson.questions().get(0).id(), lesson.questions().get(1).id());
        assertEquals(encoded, StudyLessonResult.encode(lesson));
        assertTrue(StudyLessonResult.render(lesson).contains("Synthetic first question?"));
        assertFalse(StudyLessonResult.render(lesson).contains(lesson.questions().get(0).id().toString()));
    }
    @Test void ordinaryAndStudyPurposeCannotSubstituteTheirSuccessKinds() {
        var ordinary = LocalInferenceContractTest.request(); var study = request();
        assertThrows(ApiException.class, () -> LocalInferenceService.validate(response(ordinary), ordinary));
        assertThrows(ApiException.class, () -> LocalInferenceService.validate(LocalInferenceContractTest.response(study), study));
        study.put("purpose", "UNKNOWN");
        assertThrows(ApiException.class, () -> LocalInferenceService.validate(response(study), study));
    }
    @Test void lessonRequiresCurrentContextLocalModeAndNoTools() {
        for (String field : List.of("context", "model_id", "prompt_revision", "allowed_tool_ids")) {
            var request = request(); request.remove(field);
            assertThrows(ApiException.class, () -> LocalInferenceService.validate(response(request), request));
        }
        var request = request(); request.set("allowed_tool_ids", Json.MAPPER.valueToTree(List.of("calendar.event.create")));
        assertThrows(ApiException.class, () -> LocalInferenceService.validate(response(request), request));
    }
    @Test void wrongCountsBlankScalarNulAndOversizeTextFailClosed() {
        var request = request();
        for (Object concepts : List.of(List.of("one", "two"), List.of("one", "two", "three", "four"), List.of("one", "two", "\uFEFF"), List.of("one", "two", "nul\0"), List.of("one", "two", "x".repeat(1001)), List.of("one", "two", 3))) {
            var response = response(request); response.set("concepts", Json.MAPPER.valueToTree(concepts));
            assertThrows(ApiException.class, () -> LocalInferenceService.validate(response, request));
        }
        var response = response(request); response.set("questions", Json.MAPPER.valueToTree(List.of("only")));
        assertThrows(ApiException.class, () -> LocalInferenceService.validate(response, request));
    }
    @Test void unknownAuthorityAndUnboundUsageNeverBecomeAcceptedLessons() {
        var request = request();
        for (String field : List.of("verified", "owner_id", "session_id", "question_ids", "approved")) {
            var response = response(request); response.put(field, "untrusted");
            assertThrows(ApiException.class, () -> LocalInferenceService.validate(response, request));
        }
        for (String field : List.of("reservation_id", "inference_request_id", "model_manifest_hash")) {
            var response = response(request); response.withObject("usage").put(field, "mismatch");
            assertThrows(ApiException.class, () -> LocalInferenceService.validate(response, request));
        }
    }
    @Test void storedEnvelopeIsCanonicalVersionedAndRejectsProseOrExtraFields() {
        String valid = StudyLessonResult.admit(response(request()));
        for (String bad : List.of("arbitrary prose", "{}", valid.replace("\"revision\":1", "\"revision\":2"), " " + valid)) {
            ApiException error = assertThrows(ApiException.class, () -> StudyLessonResult.stored(bad));
            assertEquals("STUDY_LESSON_UNAVAILABLE", error.code); assertFalse(error.getMessage().contains(bad));
        }
        var changed = (ObjectNode) Json.parse(valid.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        changed.put("verified", true);
        assertThrows(ApiException.class, () -> StudyLessonResult.stored(Json.canonical(changed)));
    }
    @Test void needInputAndErrorRemainCompatibleForBothPurposes() {
        for (JsonNode request : List.of(LocalInferenceContractTest.request(), request())) {
            var usage = LocalInferenceContractTest.response(request).get("usage");
            assertEquals("Which topic?", LocalInferenceService.validate(Json.MAPPER.valueToTree(Map.of("kind", "NEED_INPUT", "question", "Which topic?", "missing_fields", List.of("topic"), "usage", usage)), request));
            assertTrue(LocalInferenceService.validate(Json.MAPPER.valueToTree(Map.of("kind", "ERROR", "error_code", "MODEL_UNAVAILABLE", "retryable", false, "usage", usage)), request).contains("MODEL_UNAVAILABLE"));
        }
    }
    @Test void storedDuplicateQuestionIdsAreRejectedRatherThanBecomingTwoTargets() {
        var encoded = (ObjectNode) Json.parse(StudyLessonResult.admit(response(request())).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var questions = encoded.path("lesson").path("questions");
        ((ObjectNode) questions.get(1)).put("id", questions.get(0).path("id").asText());
        assertEquals("STUDY_LESSON_UNAVAILABLE", assertThrows(ApiException.class, () -> StudyLessonResult.stored(Json.canonical(encoded))).code);
    }

}

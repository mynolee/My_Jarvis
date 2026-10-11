package dev.myjarvis.core;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

/** Closed model data, never an instruction or authorization. All retained bytes use existing source stores. */
final class StudyLessonResult {
    static final String PURPOSE = "STUDY_LESSON";
    private StudyLessonResult() {}

    static String purpose(JsonNode request) {
        if (!request.has("purpose")) return "CONVERSATION";
        String purpose = Json.string(request, "purpose", 30);
        if (!Set.of("CONVERSATION", PURPOSE).contains(purpose)) throw ApiException.bad();
        return purpose;
    }

    static List<String> texts(JsonNode values, int size, int limit) {
        if (values == null || !values.isArray() || values.size() != size) throw ApiException.bad();
        var result = new ArrayList<String>();
        for (JsonNode value : values) {
            if (!value.isTextual()) throw ApiException.bad();
            String text = value.asText();
            // Reuse the kernel's Unicode/nonblank contract without minting an identity.
            try { new StudySessionPolicy.Question(new UUID(0, 0), text); }
            catch (IllegalArgumentException | NullPointerException invalid) { throw ApiException.bad(); }
            if (text.codePointCount(0, text.length()) > limit) throw ApiException.bad();
            result.add(text);
        }
        return List.copyOf(result);
    }

    static void validateWire(JsonNode response) {
        Json.fields(response, "kind", "concepts", "questions", "usage");
        if (!"LESSON".equals(Json.string(response, "kind", 30))) throw ApiException.bad();
        texts(response.get("concepts"), 3, 1000);
        texts(response.get("questions"), 2, 2000);
    }

    /** Called only inside accepted settlement, after the current source/session gates. */
    static String admit(JsonNode response) {
        validateWire(response);
        var questions = texts(response.get("questions"), 2, 2000).stream()
                .map(text -> new StudySessionPolicy.Question(UUID.randomUUID(), text)).toList();
        return encode(new StudySessionPolicy.Lesson(texts(response.get("concepts"), 3, 1000), questions));
    }

    static StudySessionPolicy.Lesson stored(String encoded) {
        try {
            if (encoded == null || encoded.getBytes(StandardCharsets.UTF_8).length > 64000) throw ApiException.bad();
            JsonNode envelope = Json.parse(encoded.getBytes(StandardCharsets.UTF_8));
            Json.fields(envelope, "kind", "revision", "lesson");
            if (!"LESSON".equals(Json.string(envelope, "kind", 30)) || Json.integer(envelope, "revision") != 1) throw ApiException.bad();
            JsonNode value = envelope.get("lesson");
            Json.fields(value, "concepts", "questions");
            List<String> concepts = texts(value.get("concepts"), 3, 1000);
            JsonNode source = value.get("questions");
            if (source == null || !source.isArray() || source.size() != 2) throw ApiException.bad();
            var questions = new ArrayList<StudySessionPolicy.Question>();
            for (JsonNode question : source) {
                Json.fields(question, "id", "text");
                questions.add(new StudySessionPolicy.Question(MemoryService.uuid(Json.string(question, "id", 36)), Json.string(question, "text", 2000)));
            }
            StudySessionPolicy.Lesson lesson = new StudySessionPolicy.Lesson(concepts, questions);
            if (!encode(lesson).equals(encoded)) throw ApiException.bad();
            return lesson;
        } catch (RuntimeException invalid) { throw new ApiException(500, "STUDY_LESSON_UNAVAILABLE"); }
    }

    static String encode(StudySessionPolicy.Lesson lesson) {
        return Json.canonical(Map.of("kind", "LESSON", "revision", 1, "lesson", project(lesson)));
    }

    static Map<String,Object> project(StudySessionPolicy.Lesson lesson) {
        return Map.of("concepts", lesson.concepts(), "questions", lesson.questions().stream()
                .map(question -> Map.of("id", question.id(), "text", question.text())).toList());
    }

    static String render(StudySessionPolicy.Lesson lesson) {
        StringBuilder text = new StringBuilder("Study lesson\n\nConcepts\n");
        for (int i = 0; i < 3; i++) text.append(i + 1).append(". ").append(lesson.concepts().get(i)).append('\n');
        text.append("\nQuestions\n");
        for (int i = 0; i < 2; i++) text.append(i + 1).append(". ").append(lesson.questions().get(i).text()).append('\n');
        return text.toString();
    }

    static boolean isLesson(JdbcTemplate db, UUID owner, UUID run) {
        return Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM inference_reservations WHERE owner_id=? AND run_id=? AND purpose='STUDY_LESSON')", Boolean.class, owner, run));
    }

    /** Ordinary run/history/transcript APIs retain their existing plain-text shape. */
    static String text(JdbcTemplate db, Vault vault, UUID owner, Map<String,Object> row) {
        if (row.get("result_ref") == null) return null;
        UUID run = (UUID) row.get("id");
        boolean lesson = "SUCCEEDED".equals(row.get("status")) && isLesson(db, owner, run);
        try {
            String value = vault.open(owner, run, "result", (String) row.get("result_ref"));
            return lesson ? render(stored(value)) : value;
        } catch (RuntimeException invalid) {
            if (lesson) throw new ApiException(500, "STUDY_LESSON_UNAVAILABLE");
            throw invalid;
        }
    }
}

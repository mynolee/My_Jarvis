package dev.myjarvis.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import static dev.myjarvis.core.StudySessionPolicy.*;
import static org.junit.jupiter.api.Assertions.*;

/** Pure synthetic policy checks. No Spring, database, model, or Java-example execution. */
class StudySessionPolicyTest {
    private static UUID id(int n) { return UUID.fromString("00000000-0000-4000-8000-" + String.format(java.util.Locale.ROOT, "%012d", n)); }
    private static Lesson lesson() {
        return new Lesson(List.of("Distance", "Relaxation", "Nonnegative weights"),
                List.of(new Question(id(11), "What edge weights?"), new Question(id(12), "What path distance?")));
    }
    private static State initial() { return start(id(1), id(2), id(3), null, lesson()); }
    private static Feedback feedbackFor(int question, int attempt) {
        return new Feedback(id(question), id(attempt), Outcome.NEEDS_WORK, "Check the condition.",
                "A negative edge changes the argument.", List.of("Nonnegative weights."), List.of("Check graph weights."));
    }
    private static void rejected(String code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(IllegalArgumentException.class, action).getMessage());
    }

    @Test void actualPolicyMatchesEverySharedSyntheticSnapshot() throws Exception {
        JsonNode fixture = Json.parse(Files.readAllBytes(Path.of("../../packages/contracts/fixtures/study-session/synthetic-dijkstra.json")));
        State state = readState(fixture.get("initial"));
        assertEquals(Stage.WAITING_ANSWERS, state.stage());
        for (JsonNode step : fixture.get("steps")) {
            JsonNode event = step.get("event"); UUID session = uuid(event, "session_id");
            long version = event.get("expected_version").asLong();
            state = switch (event.get("kind").asText()) {
                case "ANSWER" -> answer(state, session, version, uuid(event, "question_id"), uuid(event, "attempt_id"), event.get("answer").asText());
                case "FEEDBACK" -> feedback(state, session, version, readFeedback(event.get("feedback")));
                case "CLOSE" -> close(state, session, version);
                case "SOURCE_UNAVAILABLE" -> sourceUnavailable(state, session, version);
                default -> throw new AssertionError("Unknown synthetic event");
            };
            assertEquals(readState(step.get("expected")), state);
        }
        assertEquals(Stage.SOURCE_UNAVAILABLE, state.stage());
    }
    @Test void bothAnswersAndFeedbackAreRequiredForReview() {
        State first = answer(initial(), id(1), 1, id(11), id(21), "Synthetic answer");
        State firstFeedback = feedback(first, id(1), 2, feedbackFor(11, 21));
        assertEquals(Stage.WAITING_ANSWERS, firstFeedback.stage());
        State second = answer(firstFeedback, id(1), 3, id(12), id(22), "Another answer");
        assertEquals(Stage.FEEDBACK_PENDING, second.stage());
        State reviewed = feedback(second, id(1), 4, feedbackFor(12, 22));
        assertEquals(Stage.REVIEW_AVAILABLE, reviewed.stage());
        assertEquals(Lifecycle.OPEN, reviewed.lifecycle());
        assertEquals(Outcome.NEEDS_WORK, reviewed.attempts().getFirst().feedback().outcome());
    }
    @Test void noAnswerCannotReceiveFeedback() {
        rejected("ANSWER_NOT_FOUND", () -> feedback(initial(), id(1), 1, feedbackFor(11, 21)));
    }
    @Test void wrongSessionQuestionOrAttemptCannotAttach() {
        rejected("SESSION_TARGET_MISMATCH", () -> answer(initial(), id(9), 1, id(11), id(21), "Answer"));
        rejected("UNKNOWN_QUESTION", () -> answer(initial(), id(1), 1, id(99), id(21), "Answer"));
        State state = answer(initial(), id(1), 1, id(11), id(21), "Answer");
        rejected("ANSWER_NOT_FOUND", () -> feedback(state, id(1), 2, feedbackFor(12, 21)));
        rejected("ANSWER_NOT_FOUND", () -> feedback(state, id(1), 2, feedbackFor(11, 22)));
    }
    @Test void staleVersionAndDuplicatesNeverOverwrite() {
        State state = answer(initial(), id(1), 1, id(11), id(21), "First");
        rejected("PRECONDITION_CHANGED", () -> answer(state, id(1), 1, id(11), id(21), "First"));
        rejected("ANSWER_ALREADY_RECORDED", () -> answer(state, id(1), 2, id(11), id(22), "Changed"));
        rejected("DUPLICATE_ATTEMPT", () -> answer(state, id(1), 2, id(12), id(21), "Another"));
        State attached = feedback(state, id(1), 2, feedbackFor(11, 21));
        rejected("FEEDBACK_ALREADY_RECORDED", () -> feedback(attached, id(1), 3, feedbackFor(11, 21)));
        assertEquals("First", attached.attempts().getFirst().answer());
    }
    @Test void closingDoesNotRequireLearningOrAnswersAndPreventsLateWrites() {
        State closed = close(initial(), id(1), 1);
        assertEquals(Stage.CLOSED_BY_OWNER, closed.stage());
        assertTrue(closed.attempts().isEmpty());
        rejected("SESSION_NOT_OPEN", () -> answer(closed, id(1), 2, id(11), id(21), "Late"));
        rejected("SESSION_NOT_OPEN", () -> feedback(closed, id(1), 2, feedbackFor(11, 21)));
        rejected("SESSION_NOT_OPEN", () -> close(closed, id(1), 2));
    }
    @Test void unavailableProjectionDropsContentAndNeverReopens() {
        State answered = answer(initial(), id(1), 1, id(11), id(21), "Synthetic private answer");
        State unavailable = sourceUnavailable(answered, id(1), 2);
        assertNull(unavailable.lesson()); assertTrue(unavailable.attempts().isEmpty());
        rejected("SESSION_NOT_OPEN", () -> feedback(unavailable, id(1), 3, feedbackFor(11, 21)));
        rejected("SOURCE_ALREADY_UNAVAILABLE", () -> sourceUnavailable(unavailable, id(1), 3));
        // The old immutable value still exists: this operation is not secure erasure.
        assertEquals("Synthetic private answer", answered.attempts().getFirst().answer());
    }
    @Test void identityIsPreservedAndCollectionsAreDefensivelyCopied() {
        var concepts = new ArrayList<>(List.of("A", "B", "C"));
        var questions = new ArrayList<>(lesson().questions());
        Lesson value = new Lesson(concepts, questions); concepts.clear(); questions.clear();
        State state = start(id(1), id(2), id(3), id(4), value);
        State next = answer(state, id(1), 1, id(11), id(21), "Answer");
        assertEquals(id(2), next.ownerId()); assertEquals(id(3), next.conversationId()); assertEquals(id(4), next.projectId());
        assertEquals(3, value.concepts().size()); assertEquals(2, value.questions().size());
        assertThrows(UnsupportedOperationException.class, () -> next.attempts().clear());
        assertThrows(UnsupportedOperationException.class, () -> value.concepts().clear());
        assertTrue(state.attempts().isEmpty());
    }
    @Test void versionsAreBoundedAndExhaustionCannotWrap() {
        rejected("INVALID_VERSION", () -> new State(id(1), id(2), id(3), null, 0, Lifecycle.OPEN, lesson(), List.of()));
        State exhausted = new State(id(1), id(2), id(3), null, MAX_VERSION, Lifecycle.OPEN, lesson(), List.of());
        rejected("VERSION_EXHAUSTED", () -> close(exhausted, id(1), MAX_VERSION));
        rejected("VERSION_EXHAUSTED", () -> sourceUnavailable(exhausted, id(1), MAX_VERSION));
    }
    @Test void unicodeBoundsAndBlankOrMalformedTextAreRejected() {
        assertEquals(8000, answer(initial(), id(1), 1, id(11), id(21), "😀".repeat(8000)).attempts().getFirst().answer().codePointCount(0, 16000));
        rejected("TEXT_TOO_LONG", () -> answer(initial(), id(1), 1, id(11), id(21), "😀".repeat(8001)));
        for (String blank : List.of("", " \t\n", "\u0085\u00A0\u2000\uFEFF"))
            rejected("TEXT_REQUIRED", () -> answer(initial(), id(1), 1, id(11), id(21), blank));
        for (String malformed : List.of("a\u0000b", "a\uD800b", "a\uDC00b"))
            rejected("INVALID_TEXT", () -> answer(initial(), id(1), 1, id(11), id(21), malformed));
    }
    @Test void invalidRecordGraphsCannotBeConstructed() {
        rejected("DUPLICATE_QUESTION", () -> new Lesson(List.of("A", "B", "C"), List.of(new Question(id(11), "One"), new Question(id(11), "Two"))));
        rejected("FEEDBACK_TARGET_MISMATCH", () -> new Attempt(id(11), id(21), "Answer", feedbackFor(12, 21)));
        rejected("UNKNOWN_QUESTION", () -> new State(id(1), id(2), id(3), null, 1, Lifecycle.OPEN, lesson(), List.of(new Attempt(id(99), id(21), "Answer", null))));
        rejected("UNAVAILABLE_CONTENT", () -> new State(id(1), id(2), id(3), null, 1, Lifecycle.SOURCE_UNAVAILABLE, lesson(), List.of()));
        assertThrows(NullPointerException.class, () -> start(id(1), null, id(3), null, lesson()));
    }
    @Test void clarificationIsFeedbackNotMasteryOrAnAutomaticRetry() {
        Feedback clarification = new Feedback(id(11), id(21), Outcome.NEEDS_CLARIFICATION,
                "Please explain the edge assumption.", "State whether negative edges exist.",
                List.of("Graph weights were unspecified."), List.of("Clarify the graph."));
        State state = feedback(answer(initial(), id(1), 1, id(11), id(21), "Maybe"), id(1), 2, clarification);
        assertEquals(Outcome.NEEDS_CLARIFICATION, state.attempts().getFirst().feedback().outcome());
        assertEquals(Stage.WAITING_ANSWERS, state.stage());
        rejected("ANSWER_ALREADY_RECORDED", () -> answer(state, id(1), 3, id(11), id(22), "Try again"));
    }

    private static UUID uuid(JsonNode node, String field) { return UUID.fromString(node.get(field).asText()); }
    private static List<String> strings(JsonNode node) {
        List<String> values = new ArrayList<>(); for (JsonNode item : node) values.add(item.asText()); return values;
    }
    private static Feedback readFeedback(JsonNode node) {
        if (node.isNull()) return null;
        return new Feedback(uuid(node, "question_id"), uuid(node, "attempt_id"), Outcome.valueOf(node.get("outcome").asText()),
                node.get("explanation").asText(), node.get("example").asText(), strings(node.get("assumptions")), strings(node.get("checklist")));
    }
    private static State readState(JsonNode node) {
        Lesson lesson = null;
        if (!node.get("lesson").isNull()) {
            JsonNode value = node.get("lesson"); List<Question> questions = new ArrayList<>();
            for (JsonNode q : value.get("questions")) questions.add(new Question(uuid(q, "id"), q.get("text").asText()));
            lesson = new Lesson(strings(value.get("concepts")), questions);
        }
        List<Attempt> attempts = new ArrayList<>();
        for (JsonNode a : node.get("attempts")) attempts.add(new Attempt(uuid(a, "question_id"), uuid(a, "id"), a.get("answer").asText(), readFeedback(a.get("feedback"))));
        return new State(uuid(node, "id"), uuid(node, "owner_id"), uuid(node, "conversation_id"), node.get("project_id").isNull() ? null : uuid(node, "project_id"),
                node.get("version").asLong(), Lifecycle.valueOf(node.get("lifecycle").asText()), lesson, attempts);
    }
}

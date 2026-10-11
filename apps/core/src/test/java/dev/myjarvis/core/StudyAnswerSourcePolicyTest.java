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

/** Real pure-policy calls with fabricated sources; no authentication or database execution. */
class StudyAnswerSourcePolicyTest {
    private static final String UNAVAILABLE = "STUDY_SOURCE_UNAVAILABLE";
    private static UUID id(int n) { return UUID.fromString("00000000-0000-4000-8000-" + String.format(java.util.Locale.ROOT, "%012d", n)); }
    private static State initial() {
        return start(id(1), id(2), id(3), null, new Lesson(List.of("Distance", "Relaxation", "Nonnegative weights"),
                List.of(new Question(id(11), "What assumption?"), new Question(id(12), "What distance?"))));
    }
    private static StudyAnswerSourcePolicy.Target target() { return target(id(1), 1, id(11), id(21)); }
    private static StudyAnswerSourcePolicy.Target target(UUID session, long version, UUID question, UUID attempt) {
        return new StudyAnswerSourcePolicy.Target(session, version, question, attempt);
    }
    private static StudyAnswerSourcePolicy.Selection selection(StudyAnswerSourcePolicy.Target target) {
        return new StudyAnswerSourcePolicy.Selection(id(31), 7, target);
    }
    private static StudyAnswerSourcePolicy.Source source(String text) {
        return new StudyAnswerSourcePolicy.Source(id(31), id(2), id(3), null, 7, "USER", "USER_DIRECT", text);
    }
    private static State apply(State state, StudyAnswerSourcePolicy.Target target, String text) {
        return StudyAnswerSourcePolicy.answer(state, id(2), selection(target), source(text), target, text);
    }
    private static void rejected(String code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(IllegalArgumentException.class, action).getMessage());
    }

    @Test void sharedSyntheticSourceCasesInvokeActualGuard() throws Exception {
        JsonNode fixture = Json.parse(Files.readAllBytes(Path.of("../../packages/contracts/fixtures/study-answer-source/synthetic-source-cases.json")));
        assertTrue(fixture.get("synthetic").asBoolean()); assertEquals(25, fixture.get("cases").size());
        for (JsonNode item : fixture.get("cases")) {
            JsonNode input = item.get("input"), event = input.get("event"), selected = input.get("selection");
            State state = readState(input.get("state"));
            var selection = new StudyAnswerSourcePolicy.Selection(uuid(selected, "message_id"), selected.get("conversation_content_version").asLong(), readTarget(selected.get("target")));
            var current = readSource(input.get("current_source"));
            var command = readTarget(event);
            if (item.get("expected").has("error")) {
                rejected(item.get("expected").get("error").asText(), () -> StudyAnswerSourcePolicy.answer(state,
                        uuid(input, "actor_owner_id"), selection, current, command, event.get("answer").asText()));
            } else {
                assertEquals(readState(item.get("expected").get("state")), StudyAnswerSourcePolicy.answer(state,
                        uuid(input, "actor_owner_id"), selection, current, command, event.get("answer").asText()));
            }
        }
    }

    @Test void otherwiseValidCommandTargetSubstitutionsAreRejected() {
        var alternatives = List.of(target(id(99), 1, id(11), id(21)), target(id(1), 2, id(11), id(21)),
                target(id(1), 1, id(12), id(21)), target(id(1), 1, id(11), id(22)));
        for (var alternative : alternatives)
            rejected(UNAVAILABLE, () -> StudyAnswerSourcePolicy.answer(initial(), id(2), selection(target()), source("Answer"), alternative, "Answer"));
        State otherSession = new State(id(99), id(2), id(3), null, 1, Lifecycle.OPEN, initial().lesson(), List.of());
        rejected(UNAVAILABLE, () -> StudyAnswerSourcePolicy.answer(otherSession, id(2), selection(target()), source("Answer"), alternatives.getFirst(), "Answer"));
        State newer = new State(id(1), id(2), id(3), null, 2, Lifecycle.OPEN, initial().lesson(), List.of());
        rejected(UNAVAILABLE, () -> StudyAnswerSourcePolicy.answer(newer, id(2), selection(target()), source("Answer"), alternatives.get(1), "Answer"));
    }

    @Test void trustedSelectionTargetCannotBeReplacedOrOmitted() {
        for (var alternative : List.of(target(id(99), 1, id(11), id(21)), target(id(1), 2, id(11), id(21)),
                target(id(1), 1, id(12), id(21)), target(id(1), 1, id(11), id(22))))
            rejected(UNAVAILABLE, () -> StudyAnswerSourcePolicy.answer(initial(), id(2), selection(alternative), source("Answer"), target(), "Answer"));
        rejected(UNAVAILABLE, () -> StudyAnswerSourcePolicy.answer(initial(), id(2), selection(null), source("Answer"), target(), "Answer"));
        rejected(UNAVAILABLE, () -> StudyAnswerSourcePolicy.answer(initial(), id(2), selection(target()), source("Answer"), null, "Answer"));
    }

    @Test void unavailableAndCrossOwnerInputsFailBeforeKernelProjection() {
        rejected(UNAVAILABLE, () -> StudyAnswerSourcePolicy.answer(initial(), id(2), selection(target()), null, target(), "Answer"));
        rejected(UNAVAILABLE, () -> StudyAnswerSourcePolicy.answer(initial(), id(99), selection(target()), source("Answer"), target(), "Answer"));
        rejected(UNAVAILABLE, () -> StudyAnswerSourcePolicy.answer(initial(), null, selection(target()), source("Answer"), target(), "Answer"));
        rejected(UNAVAILABLE, () -> StudyAnswerSourcePolicy.answer(null, id(2), selection(target()), source("Answer"), target(), "Answer"));
        rejected(UNAVAILABLE, () -> StudyAnswerSourcePolicy.answer(initial(), id(2), null, source("Answer"), target(), "Answer"));
        // Wrong owner and absent source win over an invalid question and stale study version.
        rejected(UNAVAILABLE, () -> StudyAnswerSourcePolicy.answer(initial(), id(99), selection(target()), null, target(id(1), 99, id(99), id(21)), "Private text"));
    }

    @Test void sourceMessageConversationAndNullableProjectMustMatchExactly() {
        var values = List.of(new StudyAnswerSourcePolicy.Source(id(99), id(2), id(3), null, 7, "USER", "USER_DIRECT", "Answer"),
                new StudyAnswerSourcePolicy.Source(id(31), id(99), id(3), null, 7, "USER", "USER_DIRECT", "Answer"),
                new StudyAnswerSourcePolicy.Source(id(31), id(2), id(99), null, 7, "USER", "USER_DIRECT", "Answer"),
                new StudyAnswerSourcePolicy.Source(id(31), id(2), id(3), id(4), 7, "USER", "USER_DIRECT", "Answer"));
        for (var current : values)
            rejected(UNAVAILABLE, () -> StudyAnswerSourcePolicy.answer(initial(), id(2), selection(target()), current, target(), "Answer"));
        State project = new State(id(1), id(2), id(3), id(4), 1, Lifecycle.OPEN, initial().lesson(), List.of());
        rejected(UNAVAILABLE, () -> StudyAnswerSourcePolicy.answer(project, id(2), selection(target()), source("Answer"), target(), "Answer"));
        assertEquals(id(4), StudyAnswerSourcePolicy.answer(project, id(2), selection(target()), values.get(3), target(), "Answer").projectId());
    }

    @Test void sourceRevisionBoundsAndChangesFailClosed() {
        for (long version : new long[] {0, -1, 6, 8, MAX_VERSION + 1, Long.MAX_VALUE}) {
            var current = new StudyAnswerSourcePolicy.Source(id(31), id(2), id(3), null, version, "USER", "USER_DIRECT", "Answer");
            rejected(UNAVAILABLE, () -> StudyAnswerSourcePolicy.answer(initial(), id(2), selection(target()), current, target(), "Answer"));
            var selected = new StudyAnswerSourcePolicy.Selection(id(31), version, target());
            rejected(UNAVAILABLE, () -> StudyAnswerSourcePolicy.answer(initial(), id(2), selected, source("Answer"), target(), "Answer"));
        }
        var maximum = new StudyAnswerSourcePolicy.Source(id(31), id(2), id(3), null, MAX_VERSION, "USER", "USER_DIRECT", "Answer");
        assertEquals(2, StudyAnswerSourcePolicy.answer(initial(), id(2), new StudyAnswerSourcePolicy.Selection(id(31), MAX_VERSION, target()), maximum, target(), "Answer").version());
    }

    @Test void onlyStoredUserDirectProvenanceCanSupplyAnAnswer() {
        for (String role : new String[] {"ASSISTANT", "TOOL", "user", "", null}) {
            var current = new StudyAnswerSourcePolicy.Source(id(31), id(2), id(3), null, 7, role, "USER_DIRECT", "Answer");
            rejected(UNAVAILABLE, () -> StudyAnswerSourcePolicy.answer(initial(), id(2), selection(target()), current, target(), "Answer"));
        }
        for (String trust : new String[] {"MODEL_PROPOSAL", "EXTERNAL_UNTRUSTED", "TOOL_RESULT", "USER_GRANTED", "", null}) {
            var current = new StudyAnswerSourcePolicy.Source(id(31), id(2), id(3), null, 7, "USER", trust, "Answer");
            rejected(UNAVAILABLE, () -> StudyAnswerSourcePolicy.answer(initial(), id(2), selection(target()), current, target(), "Answer"));
        }
    }

    @Test void exactSourceTextCannotBeTrimmedNormalizedOrSubstituted() {
        for (String[] pair : new String[][] {{"a", "a "}, {"é", "e\u0301"}, {"Ａ", "A"}, {"x\ny", "x\r\ny"}, {"Answer", "Changed"}})
            rejected(UNAVAILABLE, () -> StudyAnswerSourcePolicy.answer(initial(), id(2), selection(target()), source(pair[0]), target(), pair[1]));
        rejected(UNAVAILABLE, () -> StudyAnswerSourcePolicy.answer(initial(), id(2), selection(target()), source(null), target(), "Answer"));
        rejected(UNAVAILABLE, () -> StudyAnswerSourcePolicy.answer(initial(), id(2), selection(target()), source("Answer"), target(), null));
        String preserved = "  e\u0301\n😀  ";
        assertEquals(preserved, apply(initial(), target(), preserved).attempts().getFirst().answer());
    }

    @Test void existingKernelRemainsTheStudyTargetAndVersionAuthority() {
        rejected("SESSION_TARGET_MISMATCH", () -> apply(initial(), target(id(99), 1, id(11), id(21)), "Answer"));
        rejected("PRECONDITION_CHANGED", () -> apply(initial(), target(id(1), 2, id(11), id(21)), "Answer"));
        rejected("UNKNOWN_QUESTION", () -> apply(initial(), target(id(1), 1, id(99), id(21)), "Answer"));
        State exhausted = new State(id(1), id(2), id(3), null, MAX_VERSION, Lifecycle.OPEN, initial().lesson(), List.of());
        rejected("VERSION_EXHAUSTED", () -> apply(exhausted, target(id(1), MAX_VERSION, id(11), id(21)), "Answer"));
    }

    @Test void repeatedAndDuplicateAttemptsNeverOverwriteExistingAnswers() {
        State once = apply(initial(), target(), "First");
        rejected("PRECONDITION_CHANGED", () -> apply(once, target(), "First"));
        rejected("ANSWER_ALREADY_RECORDED", () -> apply(once, target(id(1), 2, id(11), id(22)), "Changed"));
        rejected("DUPLICATE_ATTEMPT", () -> apply(once, target(id(1), 2, id(12), id(21)), "Another"));
        assertEquals("First", once.attempts().getFirst().answer());
    }

    @Test void closedAndSourceUnavailableStudyStatesCannotReopen() {
        State closed = close(initial(), id(1), 1);
        State unavailable = sourceUnavailable(initial(), id(1), 1);
        var currentTarget = target(id(1), 2, id(11), id(21));
        rejected("SESSION_NOT_OPEN", () -> apply(closed, currentTarget, "Answer"));
        rejected("SESSION_NOT_OPEN", () -> apply(unavailable, currentTarget, "Answer"));
        assertNull(unavailable.lesson()); assertTrue(unavailable.attempts().isEmpty());
    }

    @Test void unavailableErrorsAndSourceDiagnosticsContainNoPrivateValues() {
        String privateText = "SYNTHETIC_PRIVATE_SOURCE";
        var current = new StudyAnswerSourcePolicy.Source(id(31), id(99), id(3), null, 7, "USER", "USER_DIRECT", privateText);
        var exception = assertThrows(IllegalArgumentException.class, () -> StudyAnswerSourcePolicy.answer(initial(), id(2), selection(target()), current, target(), privateText));
        assertEquals(UNAVAILABLE, exception.getMessage());
        assertEquals("StudyAnswerSourcePolicy.Source[redacted]", current.toString());
        assertFalse(exception.toString().contains(privateText));
        assertFalse(current.toString().contains(id(99).toString()));
    }

    @Test void acceptedAnswerPreservesIdentityAndInputValuesWithoutSideEffects() {
        State state = initial(); var selected = selection(target()); var current = source("Answer");
        State next = StudyAnswerSourcePolicy.answer(state, id(2), selected, current, target(), "Answer");
        assertEquals(state.id(), next.id()); assertEquals(state.ownerId(), next.ownerId());
        assertEquals(state.conversationId(), next.conversationId()); assertEquals(state.projectId(), next.projectId());
        assertTrue(state.attempts().isEmpty()); assertEquals(state.lesson(), next.lesson());
        assertEquals(1, next.attempts().size()); assertNull(next.attempts().getFirst().feedback());
        assertEquals(Stage.WAITING_ANSWERS, next.stage()); assertEquals("Answer", current.text());
        assertEquals(target(), selected.target());
    }

    @Test void existingUnicodeAndTargetBoundsStillApplyAfterSourceBinding() {
        assertEquals(8000, apply(initial(), target(), "😀".repeat(8000)).attempts().getFirst().answer().codePointCount(0, 16000));
        rejected("TEXT_TOO_LONG", () -> apply(initial(), target(), "😀".repeat(8001)));
        for (String text : List.of("", " \t\n", "\u0085\u00A0\uFEFF")) rejected("TEXT_REQUIRED", () -> apply(initial(), target(), text));
        for (String text : List.of("a\u0000b", "a\uD800b", "a\uDC00b")) rejected("INVALID_TEXT", () -> apply(initial(), target(), text));
        for (var value : List.of(target(null, 1, id(11), id(21)), target(id(1), 1, null, id(21)),
                target(id(1), 1, id(11), null), target(id(1), 0, id(11), id(21)), target(id(1), MAX_VERSION + 1, id(11), id(21))))
            rejected(UNAVAILABLE, () -> apply(initial(), value, "Answer"));
    }

    private static UUID uuid(JsonNode node, String field) { return UUID.fromString(node.get(field).asText()); }
    private static UUID project(JsonNode node) { return node.get("project_id").isNull() ? null : uuid(node, "project_id"); }
    private static StudyAnswerSourcePolicy.Target readTarget(JsonNode node) {
        return target(uuid(node, "session_id"), node.get("expected_version").asLong(), uuid(node, "question_id"), uuid(node, "attempt_id"));
    }
    private static StudyAnswerSourcePolicy.Source readSource(JsonNode node) {
        if (node.isNull()) return null;
        return new StudyAnswerSourcePolicy.Source(uuid(node, "message_id"), uuid(node, "owner_id"), uuid(node, "conversation_id"), project(node),
                node.get("conversation_content_version").asLong(), node.get("role").asText(), node.get("trust").asText(), node.get("text").asText());
    }
    private static State readState(JsonNode node) {
        List<String> concepts = new ArrayList<>();
        for (JsonNode text : node.get("lesson").get("concepts")) concepts.add(text.asText());
        List<Question> questions = new ArrayList<>();
        for (JsonNode question : node.get("lesson").get("questions")) questions.add(new Question(uuid(question, "id"), question.get("text").asText()));
        List<Attempt> attempts = new ArrayList<>();
        for (JsonNode attempt : node.get("attempts")) {
            assertTrue(attempt.get("feedback").isNull());
            attempts.add(new Attempt(uuid(attempt, "question_id"), uuid(attempt, "id"), attempt.get("answer").asText(), null));
        }
        return new State(uuid(node, "id"), uuid(node, "owner_id"), uuid(node, "conversation_id"), project(node),
                node.get("version").asLong(), Lifecycle.valueOf(node.get("lifecycle").asText()), new Lesson(concepts, questions), attempts);
    }
}

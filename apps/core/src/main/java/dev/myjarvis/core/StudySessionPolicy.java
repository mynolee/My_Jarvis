package dev.myjarvis.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Pure, unregistered workflow rules. Caller-supplied identities are not authentication. */
final class StudySessionPolicy {
    static final long MAX_VERSION = 9007199254740991L;
    private StudySessionPolicy() {}

    enum Lifecycle { OPEN, CLOSED_BY_OWNER, SOURCE_UNAVAILABLE }
    enum Stage { WAITING_ANSWERS, FEEDBACK_PENDING, REVIEW_AVAILABLE, CLOSED_BY_OWNER, SOURCE_UNAVAILABLE }
    enum Outcome { CORRECT, NEEDS_WORK, NEEDS_CLARIFICATION }

    record Question(UUID id, String text) {
        Question { Objects.requireNonNull(id); text = StudySessionPolicy.text(text, 2000); }
    }
    record Lesson(List<String> concepts, List<Question> questions) {
        Lesson {
            concepts = texts(concepts, 3, 3, 1000);
            questions = List.copyOf(questions);
            require(questions.size() == 2, "TWO_QUESTIONS_REQUIRED");
            require(!questions.get(0).id().equals(questions.get(1).id()), "DUPLICATE_QUESTION");
        }
    }
    record Feedback(UUID questionId, UUID attemptId, Outcome outcome, String explanation,
                    String example, List<String> assumptions, List<String> checklist) {
        Feedback {
            Objects.requireNonNull(questionId); Objects.requireNonNull(attemptId);
            Objects.requireNonNull(outcome);
            explanation = text(explanation, 4000); example = text(example, 4000);
            assumptions = texts(assumptions, 1, 5, 1000);
            checklist = texts(checklist, 1, 5, 1000);
        }
    }
    record Attempt(UUID questionId, UUID id, String answer, Feedback feedback) {
        Attempt {
            Objects.requireNonNull(questionId); Objects.requireNonNull(id);
            answer = text(answer, 8000);
            if (feedback != null) require(feedback.questionId().equals(questionId)
                    && feedback.attemptId().equals(id), "FEEDBACK_TARGET_MISMATCH");
        }
    }
    record State(UUID id, UUID ownerId, UUID conversationId, UUID projectId, long version,
                 Lifecycle lifecycle, Lesson lesson, List<Attempt> attempts) {
        State {
            Objects.requireNonNull(id); Objects.requireNonNull(ownerId);
            Objects.requireNonNull(conversationId); Objects.requireNonNull(lifecycle);
            require(version >= 1 && version <= MAX_VERSION, "INVALID_VERSION");
            attempts = List.copyOf(attempts);
            if (lifecycle == Lifecycle.SOURCE_UNAVAILABLE) {
                require(lesson == null && attempts.isEmpty(), "UNAVAILABLE_CONTENT");
            } else {
                Objects.requireNonNull(lesson);
                require(attempts.size() <= 2, "TOO_MANY_ATTEMPTS");
                var questions = new HashSet<UUID>();
                var identifiers = new HashSet<UUID>();
                for (Attempt attempt : attempts) {
                    require(lesson.questions().stream().anyMatch(q -> q.id().equals(attempt.questionId())), "UNKNOWN_QUESTION");
                    require(questions.add(attempt.questionId()), "ANSWER_ALREADY_RECORDED");
                    require(identifiers.add(attempt.id()), "DUPLICATE_ATTEMPT");
                }
            }
        }
        Stage stage() {
            if (lifecycle == Lifecycle.SOURCE_UNAVAILABLE) return Stage.SOURCE_UNAVAILABLE;
            if (lifecycle == Lifecycle.CLOSED_BY_OWNER) return Stage.CLOSED_BY_OWNER;
            if (attempts.size() < 2) return Stage.WAITING_ANSWERS;
            if (attempts.stream().anyMatch(a -> a.feedback() == null)) return Stage.FEEDBACK_PENDING;
            return Stage.REVIEW_AVAILABLE;
        }
    }

    static State start(UUID id, UUID ownerId, UUID conversationId, UUID projectId, Lesson lesson) {
        return new State(id, ownerId, conversationId, projectId, 1, Lifecycle.OPEN, lesson, List.of());
    }

    /** One answer per question in this bounded slice; no hidden overwrite or retry history. */
    static State answer(State state, UUID sessionId, long expectedVersion, UUID questionId,
                        UUID attemptId, String answer) {
        editable(state, sessionId, expectedVersion);
        require(state.lesson().questions().stream().anyMatch(q -> q.id().equals(questionId)), "UNKNOWN_QUESTION");
        require(state.attempts().stream().noneMatch(a -> a.questionId().equals(questionId)), "ANSWER_ALREADY_RECORDED");
        require(state.attempts().stream().noneMatch(a -> a.id().equals(attemptId)), "DUPLICATE_ATTEMPT");
        var attempts = new ArrayList<>(state.attempts());
        attempts.add(new Attempt(questionId, attemptId, answer, null));
        return next(state, Lifecycle.OPEN, state.lesson(), attempts);
    }

    /** Data attachment only. A future adapter must establish accepted model/source provenance. */
    static State feedback(State state, UUID sessionId, long expectedVersion, Feedback feedback) {
        editable(state, sessionId, expectedVersion); Objects.requireNonNull(feedback);
        var attempts = new ArrayList<>(state.attempts());
        int found = -1;
        for (int i = 0; i < attempts.size(); i++) {
            Attempt attempt = attempts.get(i);
            if (attempt.questionId().equals(feedback.questionId()) && attempt.id().equals(feedback.attemptId())) found = i;
        }
        require(found >= 0, "ANSWER_NOT_FOUND");
        Attempt attempt = attempts.get(found);
        require(attempt.feedback() == null, "FEEDBACK_ALREADY_RECORDED");
        attempts.set(found, new Attempt(attempt.questionId(), attempt.id(), attempt.answer(), feedback));
        return next(state, Lifecycle.OPEN, state.lesson(), attempts);
    }

    /** Closing a session does not assert mastery or complete any task. */
    static State close(State state, UUID sessionId, long expectedVersion) {
        editable(state, sessionId, expectedVersion);
        return next(state, Lifecycle.CLOSED_BY_OWNER, state.lesson(), state.attempts());
    }

    /** Removes content from the returned value only; not erasure of earlier values or storage. */
    static State sourceUnavailable(State state, UUID sessionId, long expectedVersion) {
        target(state, sessionId, expectedVersion);
        require(state.lifecycle() != Lifecycle.SOURCE_UNAVAILABLE, "SOURCE_ALREADY_UNAVAILABLE");
        return next(state, Lifecycle.SOURCE_UNAVAILABLE, null, List.of());
    }

    private static State next(State state, Lifecycle lifecycle, Lesson lesson, List<Attempt> attempts) {
        return new State(state.id(), state.ownerId(), state.conversationId(), state.projectId(),
                state.version() + 1, lifecycle, lesson, attempts);
    }
    private static void editable(State state, UUID id, long expectedVersion) {
        target(state, id, expectedVersion);
        require(state.lifecycle() == Lifecycle.OPEN, "SESSION_NOT_OPEN");
    }
    private static void target(State state, UUID id, long expectedVersion) {
        Objects.requireNonNull(state);
        require(state.id().equals(id), "SESSION_TARGET_MISMATCH");
        require(state.version() == expectedVersion, "PRECONDITION_CHANGED");
        require(state.version() < MAX_VERSION, "VERSION_EXHAUSTED");
    }
    private static List<String> texts(List<String> values, int min, int max, int limit) {
        List<String> result = List.copyOf(values);
        require(result.size() >= min && result.size() <= max, "INVALID_LIST_SIZE");
        result.forEach(value -> text(value, limit));
        return result;
    }
    private static String text(String value, int max) {
        Objects.requireNonNull(value);
        require(value.codePointCount(0, value.length()) <= max, "TEXT_TOO_LONG");
        boolean nonblank = false;
        for (int i = 0; i < value.length();) {
            int cp = value.codePointAt(i); i += Character.charCount(cp);
            require(cp != 0 && !(cp >= 0xD800 && cp <= 0xDFFF), "INVALID_TEXT");
            if (!blank(cp)) nonblank = true;
        }
        require(nonblank, "TEXT_REQUIRED");
        return value;
    }
    private static boolean blank(int cp) {
        return (cp >= 9 && cp <= 13) || cp == 0x20 || cp == 0x85 || cp == 0xA0 || cp == 0x1680
                || (cp >= 0x2000 && cp <= 0x200A) || cp == 0x2028 || cp == 0x2029
                || cp == 0x202F || cp == 0x205F || cp == 0x3000 || cp == 0xFEFF;
    }
    private static void require(boolean condition, String code) {
        if (!condition) throw new IllegalArgumentException(code);
    }
}

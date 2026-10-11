package dev.myjarvis.core;

import java.util.Objects;
import java.util.UUID;

/** Pure relationship checks on trusted adapter observations; not authentication. */
final class StudyAnswerSourcePolicy {
    private StudyAnswerSourcePolicy() {}

    /** Projection of the existing ANSWER event's target fields, not a new event kind. */
    record Target(UUID sessionId, long expectedVersion, UUID questionId, UUID attemptId) {}

    /** A server-observed explicit selection, not a capability or an owner assertion. */
    record Selection(UUID messageId, long conversationContentVersion, Target target) {}

    /**
     * Resolved from the current owned conversation and source message together.
     * Missing, deleted or inaccessible sources must be represented by null instead.
     * No constructor or instance of this data record establishes trusted provenance.
     */
    record Source(UUID messageId, UUID ownerId, UUID conversationId, UUID projectId,
                  long conversationContentVersion, String role, String trust, String text) {
        @Override public String toString() { return "StudyAnswerSourcePolicy.Source[redacted]"; }
    }

    /**
     * The adapter supplies the current authenticated owner and current observations
     * under its existing locks. Do not deserialize these values from a browser/model.
     * No source text or identity is included in an unavailable result or exception.
     */
    static StudySessionPolicy.State answer(StudySessionPolicy.State state, UUID actorOwnerId,
            Selection selection, Source current, Target commandTarget, String answer) {
        require(state != null && actorOwnerId != null && state.ownerId().equals(actorOwnerId));
        require(selection != null && current != null);
        require(commandTarget != null && commandTarget.sessionId() != null
                && commandTarget.questionId() != null && commandTarget.attemptId() != null
                && version(commandTarget.expectedVersion()) && commandTarget.equals(selection.target()));
        require(selection.messageId() != null && selection.messageId().equals(current.messageId()));
        require(version(selection.conversationContentVersion()) && version(current.conversationContentVersion())
                && selection.conversationContentVersion() == current.conversationContentVersion());
        require(actorOwnerId.equals(current.ownerId())
                && state.conversationId().equals(current.conversationId())
                && Objects.equals(state.projectId(), current.projectId()));
        require("USER".equals(current.role()) && "USER_DIRECT".equals(current.trust()));
        require(answer != null && answer.equals(current.text()));
        // The existing kernel owns study identity, version, question/attempt, lifecycle
        // and text validation. Source text is never substituted for the explicit answer.
        return StudySessionPolicy.answer(state, commandTarget.sessionId(), commandTarget.expectedVersion(),
                commandTarget.questionId(), commandTarget.attemptId(), answer);
    }

    private static boolean version(long value) { return value >= 1 && value <= StudySessionPolicy.MAX_VERSION; }
    private static void require(boolean condition) {
        if (!condition) throw new IllegalArgumentException("STUDY_SOURCE_UNAVAILABLE");
    }
}

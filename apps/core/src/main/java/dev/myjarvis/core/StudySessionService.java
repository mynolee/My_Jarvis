package dev.myjarvis.core;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** Atomic durable owner answers. No provider, inference reservation, job, or transcript write. */
@Service
final class StudySessionService {
    private static final long BUDGET_NANOS = 5_000_000_000L;
    private final JdbcTemplate db;
    private final MemoryService memories;
    private final ConversationAccess access;
    private final ConversationDeletionService deletion;
    private final TransactionTemplate tx;

    StudySessionService(JdbcTemplate db, MemoryService memories, ConversationAccess access,
            ConversationDeletionService deletion, PlatformTransactionManager manager) {
        this.db = db; this.memories = memories; this.access = access; this.deletion = deletion;
        tx = new TransactionTemplate(manager); tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED); tx.setTimeout(5);
    }
    private <T> T transaction(Supplier<T> work) {
        try { return tx.execute(status -> {
            db.execute("SET LOCAL statement_timeout='5s'"); db.execute("SET LOCAL lock_timeout='5s'");
            db.execute("SET LOCAL idle_in_transaction_session_timeout='5s'");
            long started = System.nanoTime(); T value = work.get();
            if (System.nanoTime() - started >= BUDGET_NANOS) throw new ApiException(503, "STUDY_TIME_LIMIT");
            return value;
        }); } catch (TransactionTimedOutException invalid) { throw new ApiException(503, "STUDY_TIME_LIMIT"); }
        catch (DataAccessException invalid) {
            for (Throwable cause = invalid; cause != null; cause = cause.getCause()) if (cause instanceof java.sql.SQLException sql) {
                if (Set.of("57014", "55P03", "25P03").contains(sql.getSQLState())) throw new ApiException(503, "STUDY_TIME_LIMIT");
                if ("54000".equals(sql.getSQLState())) throw new ApiException(413, "STUDY_CAPACITY_LIMIT");
            }
            throw invalid;
        }
    }
    static void exact(JsonNode body, String... fields) {
        if (body == null) throw ApiException.bad(); Json.validate(body); Json.fields(body, fields);
        if (body.size() != fields.length) throw ApiException.bad();
        if (Json.canonical(body).getBytes(StandardCharsets.UTF_8).length > 65536) throw new ApiException(413, "BODY_TOO_LARGE");
    }
    private static long number(Map<String,Object> row, String field) { return ((Number) row.get(field)).longValue(); }
    private static UUID id(Map<String,Object> row, String field) { return (UUID) row.get(field); }
    private Instant now() { return memories.databaseNow(); }
    private String digest(UUID owner, String domain, Object value) { return memories.keys.digest(owner, "durable-study:" + domain, Json.canonical(value)); }
    private static void require(boolean condition) { if (!condition) throw new ApiException(409, "STUDY_SOURCE_UNAVAILABLE"); }
    private static void chronology(Instant earlier, Instant later) { if (later.isBefore(earlier)) throw new ApiException(403, "AUTHORITY_NOT_VALID"); }
    private Instant finish(AuthService.Session session, Instant observed, Instant committed, boolean mutation) {
        var owner = access.lockSession(session); if (mutation) MemoryService.unpaused(owner);
        Instant checked = now(); chronology(observed, checked); if (committed != null) chronology(committed, checked);
        return checked;
    }
    private void schema() {
        if (!"18".equals(db.queryForObject("SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1", String.class)))
            throw new ApiException(409, "CONVERSATION_GRAPH_UNVERIFIED");
    }
    private Map<String,Object> live(AuthService.Session session, UUID conversation) {
        var row = access.requireLive(session, conversation); memories.project(session.owner(), id(row, "project_id")); return row;
    }
    private static void expected(Map<String,Object> conversation, long expected) {
        if (number(conversation, "content_version") != expected) throw new ApiException(409, "PRECONDITION_CHANGED");
        if (expected > MemoryService.MAX - 2) throw new ApiException(409, "VERSION_EXHAUSTED");
    }
    private Map<String,Object> existing(UUID owner, UUID key) {
        var rows = db.queryForList("SELECT * FROM study_mutations WHERE owner_id=? AND idempotency_key=?", owner, key);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    private Map<String,Object> replay(AuthService.Session session, UUID key, String request, Instant observed) {
        var previous = existing(session.owner(), key); if (previous == null) return null;
        MemoryService.binding(session, previous);
        live(session, id(previous, "conversation_id"));
        if (!Json.equal(request, (String) previous.get("request_digest"))) throw new ApiException(409, "IDEMPOTENCY_CONFLICT");
        return outcome(previous, finish(session, observed, AuthService.instant(previous.get("committed_at")), false));
    }
    private static Map<String,Object> receipt(Map<String,Object> row) {
        var result = new LinkedHashMap<String,Object>(); result.put("mutation_id", row.get("idempotency_key"));
        for (String field : List.of("operation", "conversation_id", "study_session_id", "lesson_run_id", "prior_version", "resulting_version", "prior_conversation_version", "resulting_conversation_version")) result.put(field, row.get(field));
        result.put("committed_at", AuthService.instant(row.get("committed_at")).toString());
        if ("ANSWER".equals(row.get("operation"))) for (String field : List.of("question_id", "attempt_id", "input_id")) result.put(field, row.get(field));
        return result;
    }
    private static Map<String,Object> outcome(Map<String,Object> row, Instant checked) {
        var result = new LinkedHashMap<String,Object>(); result.put("status", row == null ? "NOT_OBSERVED" : "COMMITTED");
        result.put("receipt", row == null ? null : receipt(row)); result.put("checked_at", checked.toString()); return result;
    }
    Map<String,Object> reconcile(AuthService.Session session, UUID key) { return transaction(() -> {
        access.lockSession(session); schema(); Instant observed = now(); var row = existing(session.owner(), key);
        if (row != null) MemoryService.binding(session, row);
        return outcome(row, finish(session, observed, row == null ? null : AuthService.instant(row.get("committed_at")), false));
    }); }

    record Lesson(Map<String,Object> run, StudySessionPolicy.Lesson lesson, String encoded) {}
    Lesson lesson(UUID owner, UUID conversation, UUID project, UUID runId) {
        var runs = db.queryForList("SELECT * FROM runs WHERE owner_id=? AND id=? FOR UPDATE", owner, runId);
        if (runs.isEmpty()) throw ApiException.missing(); var run = runs.getFirst();
        require(conversation.equals(run.get("conversation_id")) && "SUCCEEDED".equals(run.get("status"))
                && Boolean.TRUE.equals(run.get("context_history_eligible")) && run.get("content_deleted_at") == null
                && run.get("cancel_requested_at") == null && run.get("result_ref") != null);
        var reservations = db.queryForList("SELECT * FROM inference_reservations WHERE owner_id=? AND run_id=? FOR UPDATE", owner, runId);
        require(reservations.size() == 1); var reservation = reservations.getFirst();
        require("STUDY_LESSON".equals(reservation.get("purpose")) && "SUCCEEDED".equals(reservation.get("status"))
                && number(reservation, "accounting_version") == 1 && reservation.get("started_at") != null
                && reservation.get("completed_at") != null && reservation.get("cancel_requested_at") == null
                && reservation.get("dispatch_context_id") != null && "CONTEXT_V1_NO_RESPONSE_CACHE".equals(reservation.get("dispatch_cache_policy")));
        var contexts = db.queryForList("SELECT * FROM inference_contexts WHERE owner_id=? AND run_id=? ORDER BY id FOR UPDATE", owner, runId);
        require(contexts.size() == 1); var context = contexts.getFirst();
        require(conversation.equals(context.get("conversation_id")) && Objects.equals(project, context.get("project_id"))
                && reservation.get("dispatch_context_id").equals(context.get("id"))
                && Objects.equals(reservation.get("security_epoch"), context.get("security_epoch"))
                && Set.of("SETTLED", "INVALIDATED").contains(context.get("state"))
                && (!"INVALIDATED".equals(context.get("state")) || context.get("invalidated_at") != null));
        chronology(AuthService.instant(reservation.get("created_at")), AuthService.instant(reservation.get("started_at")));
        chronology(AuthService.instant(reservation.get("started_at")), AuthService.instant(reservation.get("completed_at")));
        chronology(AuthService.instant(reservation.get("completed_at")), now());
        require(Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM inference_usage_receipts WHERE owner_id=? AND run_id=? AND reservation_id=? AND request_id=? AND request_digest=? AND receipt_kind='MEASURED' AND response_kind='LESSON')",
                Boolean.class, owner, runId, reservation.get("id"), reservation.get("request_id"), reservation.get("body_hash"))));
        try {
            String encoded = memories.vault.open(owner, runId, "result", (String) run.get("result_ref"));
            return new Lesson(run, StudyLessonResult.stored(encoded), encoded);
        } catch (RuntimeException invalid) { throw new ApiException(500, "STUDY_LESSON_UNAVAILABLE"); }
    }
    String lessonBinding(UUID owner, UUID conversation, UUID project, Lesson lesson) {
        var value = new LinkedHashMap<String,Object>(); value.put("owner_id", owner); value.put("conversation_id", conversation);
        value.put("project_id", project); value.put("run_id", lesson.run().get("id")); value.put("run_version", lesson.run().get("version"));
        value.put("envelope", lesson.encoded()); return digest(owner, "lesson-binding-v1", value);
    }
    private Map<String,Object> sessionIdentity(UUID owner, UUID study) {
        var rows = db.queryForList("SELECT * FROM study_sessions WHERE owner_id=? AND id=?", owner, study);
        if (rows.isEmpty()) throw ApiException.missing(); return rows.getFirst();
    }
    record Hydrated(Map<String,Object> row, StudySessionPolicy.State state, List<Map<String,Object>> inputs) {}
    Hydrated hydrate(UUID owner, Map<String,Object> conversation, UUID study) {
        var identity = sessionIdentity(owner, study);
        require(id(conversation, "id").equals(identity.get("conversation_id")) && Objects.equals(conversation.get("project_id"), identity.get("project_id")));
        Lesson lesson = lesson(owner, id(conversation, "id"), id(conversation, "project_id"), id(identity, "lesson_run_id"));
        var row = db.queryForMap("SELECT * FROM study_sessions WHERE owner_id=? AND id=? FOR UPDATE", owner, study);
        require(row.get("source_deleted_at") == null && row.get("lesson_binding_ref") != null
                && number(row, "lesson_run_version") == number(lesson.run(), "version")
                && row.get("question_1_id").equals(lesson.lesson().questions().get(0).id())
                && row.get("question_2_id").equals(lesson.lesson().questions().get(1).id()));
        try { require(Json.equal(lessonBinding(owner, id(conversation, "id"), id(conversation, "project_id"), lesson),
                memories.vault.open(owner, study, "study-lesson-binding", (String) row.get("lesson_binding_ref")))); }
        catch (RuntimeException invalid) { throw new ApiException(409, "STUDY_SOURCE_UNAVAILABLE"); }
        var inputs = db.queryForList("SELECT * FROM study_owner_answer_inputs WHERE owner_id=? AND study_session_id=? ORDER BY resulting_study_version,id LIMIT 3 FOR UPDATE", owner, study);
        require(inputs.size() <= 2 && number(row, "version") >= 1 && number(row, "version") <= 4);
        var attempts = new ArrayList<StudySessionPolicy.Attempt>();
        for (var input : inputs) {
            require(input.get("study_session_id").equals(study) && input.get("conversation_id").equals(conversation.get("id")) && Objects.equals(input.get("project_id"), conversation.get("project_id")));
            String text = inputText(owner, input); attempts.add(new StudySessionPolicy.Attempt(id(input, "question_id"), id(input, "attempt_id"), text, null));
        }
        try { return new Hydrated(row, new StudySessionPolicy.State(study, owner, id(conversation, "id"), id(conversation, "project_id"),
                number(row, "version"), StudySessionPolicy.Lifecycle.valueOf((String) row.get("lifecycle")), lesson.lesson(), attempts), inputs); }
        catch (RuntimeException invalid) { throw new ApiException(409, "STUDY_SOURCE_UNAVAILABLE"); }
    }
    String inputBinding(UUID owner, Map<String,Object> input, String text) {
        var value = new LinkedHashMap<String,Object>(); value.put("source_kind", "OWNER_STUDY_ANSWER_INPUT");
        for (String field : List.of("id", "attempt_id", "owner_id", "conversation_id", "project_id", "study_session_id", "question_id", "expected_study_version", "resulting_study_version", "session_hash", "security_epoch", "observed_conversation_version")) value.put(field, input.get(field));
        value.put("created_at", AuthService.instant(input.get("created_at")).toString()); value.put("role", "USER"); value.put("trust", "USER_DIRECT"); value.put("text", text);
        return digest(owner, "owner-answer-binding-v1", value);
    }
    String inputText(UUID owner, Map<String,Object> input) {
        require(input.get("content_deleted_at") == null && input.get("text_ref") != null && input.get("source_binding_ref") != null);
        try {
            String text = memories.vault.open(owner, id(input, "id"), "study-answer-text", (String) input.get("text_ref"));
            require(Json.equal(inputBinding(owner, input, text), memories.vault.open(owner, id(input, "id"), "study-answer-binding", (String) input.get("source_binding_ref"))));
            return text;
        } catch (RuntimeException invalid) { throw new ApiException(409, "STUDY_SOURCE_UNAVAILABLE"); }
    }
    private static StudyAnswerSourcePolicy.Target storedTarget(Map<String,Object> input) {
        return new StudyAnswerSourcePolicy.Target(id(input, "study_session_id"), number(input, "expected_study_version"), id(input, "question_id"), id(input, "attempt_id"));
    }
    private static StudyAnswerSourcePolicy.SourceRef sourceRef(Map<String,Object> input) {
        return new StudyAnswerSourcePolicy.SourceRef(StudyAnswerSourcePolicy.SourceKind.OWNER_STUDY_ANSWER_INPUT, id(input, "id"));
    }
    private StudyAnswerSourcePolicy.Source observeInput(AuthService.Session session, UUID inputId) {
        // This independent row projection owns its target; neither the command nor selection is an argument.
        var row = db.queryForMap("SELECT * FROM study_owner_answer_inputs WHERE owner_id=? AND id=? FOR UPDATE", session.owner(), inputId);
        MemoryService.binding(session, row); var conversation = live(session, id(row, "conversation_id"));
        require(number(row, "observed_conversation_version") == number(conversation, "content_version"));
        return new StudyAnswerSourcePolicy.Source(sourceRef(row), id(row, "owner_id"), id(row, "conversation_id"), id(row, "project_id"),
                number(conversation, "content_version"), "USER", "USER_DIRECT", inputText(session.owner(), row), storedTarget(row));
    }
    private Map<String,Object> writeReceipt(AuthService.Session session, UUID key, String request, String operation,
            Map<String,Object> study, Long prior, long result, long conversationVersion, UUID question, UUID attempt, UUID input, Instant committed) {
        db.update("INSERT INTO study_mutations(owner_id,idempotency_key,operation,session_hash,security_epoch,request_digest,conversation_id,project_id,study_session_id,lesson_run_id,question_id,attempt_id,input_id,prior_version,resulting_version,prior_conversation_version,resulting_conversation_version,committed_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                session.owner(), key, operation, session.hash(), session.epoch(), request, study.get("conversation_id"), study.get("project_id"), study.get("id"), study.get("lesson_run_id"), question, attempt, input, prior, result, conversationVersion, conversationVersion + 1, Timestamp.from(committed));
        db.update("INSERT INTO audit_events(id,owner_id,event_type,resource_id) VALUES (?,?,?,?)", UUID.randomUUID(), session.owner(), "STUDY_" + operation, study.get("id"));
        db.execute("SET CONSTRAINTS ALL IMMEDIATE");
        return existing(session.owner(), key);
    }

    Map<String,Object> start(AuthService.Session session, UUID key, UUID conversationId, JsonNode body) {
        exact(body, "lesson_run_id", "expected_conversation_version"); UUID runId = MemoryService.uuid(Json.string(body, "lesson_run_id", 36));
        long expected = MemoryService.number(body, "expected_conversation_version", 1); AuthService.requireOwner(session);
        String request = digest(session.owner(), "request-v1", Map.of("operation", "START", "target", conversationId, "body", body));
        return transaction(() -> {
            var owner = access.lockSession(session); schema(); Instant observed = now();
            var replay = replay(session, key, request, observed); if (replay != null) return replay;
            MemoryService.unpaused(owner); var conversation = live(session, conversationId); expected(conversation, expected);
            new StudyFeedbackLifecycle(db).requireWritableConversation(session.owner(),conversationId);
            deletion.validateStudyGraph(session.owner(), conversationId);
            Lesson lesson = lesson(session.owner(), conversationId, id(conversation, "project_id"), runId);
            if (!db.queryForList("SELECT id FROM study_sessions WHERE owner_id=? AND lesson_run_id=?", session.owner(), runId).isEmpty()) throw new ApiException(409, "STUDY_SESSION_ALREADY_EXISTS");
            UUID study = UUID.randomUUID();
            String binding = memories.vault.seal(session.owner(), study, "study-lesson-binding", lessonBinding(session.owner(), conversationId, id(conversation, "project_id"), lesson));
            Instant committed = now(); chronology(observed, committed);
            db.update("INSERT INTO study_sessions(id,owner_id,conversation_id,project_id,lesson_run_id,lesson_run_version,question_1_id,question_2_id,lesson_binding_ref,version,lifecycle,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,1,'OPEN',?,?)",
                    study, session.owner(), conversationId, conversation.get("project_id"), runId, lesson.run().get("version"), lesson.lesson().questions().get(0).id(), lesson.lesson().questions().get(1).id(), binding, Timestamp.from(committed), Timestamp.from(committed));
            var row = sessionIdentity(session.owner(), study);
            var receipt = writeReceipt(session, key, request, "START", row, null, 1, expected, null, null, null, committed);
            return outcome(receipt, finish(session, observed, committed, true));
        });
    }
    Map<String,Object> answer(AuthService.Session session, UUID key, UUID study, JsonNode body) {
        exact(body, "expected_version", "expected_conversation_version", "question_id", "text");
        long version = MemoryService.number(body, "expected_version", 1), conversationVersion = MemoryService.number(body, "expected_conversation_version", 1);
        UUID question = MemoryService.uuid(Json.string(body, "question_id", 36)); String text = MemoryService.text(body, "text", 8000, 32000);
        return mutate(session, key, study, body, version, conversationVersion, question, text);
    }
    Map<String,Object> close(AuthService.Session session, UUID key, UUID study, JsonNode body) {
        exact(body, "expected_version", "expected_conversation_version");
        return mutate(session, key, study, body, MemoryService.number(body, "expected_version", 1), MemoryService.number(body, "expected_conversation_version", 1), null, null);
    }
    private Map<String,Object> mutate(AuthService.Session session, UUID key, UUID study, JsonNode body, long version, long conversationVersion, UUID question, String text) {
        AuthService.requireOwner(session); if (version > 4) throw ApiException.bad(); String operation = question == null ? "CLOSE" : "ANSWER";
        String request = digest(session.owner(), "request-v1", Map.of("operation", operation, "target", study, "body", body));
        return transaction(() -> {
            var owner = access.lockSession(session); schema(); Instant observed = now();
            var replay = replay(session, key, request, observed); if (replay != null) return replay;
            MemoryService.unpaused(owner); var identity = sessionIdentity(session.owner(), study);
            var conversation = live(session, id(identity, "conversation_id")); expected(conversation, conversationVersion);
            new StudyFeedbackLifecycle(db).requireWritableConversation(session.owner(),id(conversation,"id"));
            deletion.validateStudyGraph(session.owner(), id(conversation, "id")); Hydrated current = hydrate(session.owner(), conversation, study);
            if (current.state().version() != version) throw new ApiException(409, "PRECONDITION_CHANGED");
            if (current.state().lifecycle() != StudySessionPolicy.Lifecycle.OPEN) throw new ApiException(409, "SESSION_NOT_OPEN");
            UUID inputId = null, attemptId = null; StudySessionPolicy.State next;
            if (question == null) next = StudySessionPolicy.close(current.state(), study, version);
            else {
                if (current.state().lesson().questions().stream().noneMatch(q -> q.id().equals(question))) throw new ApiException(409, "STUDY_SOURCE_UNAVAILABLE");
                if (current.state().attempts().stream().anyMatch(a -> a.questionId().equals(question))) throw new ApiException(409, "ANSWER_ALREADY_RECORDED");
                inputId = UUID.randomUUID(); attemptId = UUID.randomUUID(); Instant created = now(); chronology(observed, created);
                var input = new LinkedHashMap<String,Object>(); input.put("id", inputId); input.put("attempt_id", attemptId); input.put("owner_id", session.owner());
                input.put("conversation_id", conversation.get("id")); input.put("project_id", conversation.get("project_id")); input.put("study_session_id", study);
                input.put("question_id", question); input.put("expected_study_version", version); input.put("resulting_study_version", version + 1);
                input.put("session_hash", session.hash()); input.put("security_epoch", session.epoch()); input.put("observed_conversation_version", conversationVersion); input.put("created_at", Timestamp.from(created));
                String textRef = memories.vault.seal(session.owner(), inputId, "study-answer-text", text);
                String sourceRef = memories.vault.seal(session.owner(), inputId, "study-answer-binding", inputBinding(session.owner(), input, text));
                db.update("INSERT INTO study_owner_answer_inputs(id,attempt_id,owner_id,conversation_id,project_id,study_session_id,question_id,expected_study_version,resulting_study_version,session_hash,security_epoch,observed_conversation_version,created_at,text_ref,source_binding_ref) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        inputId, attemptId, session.owner(), conversation.get("id"), conversation.get("project_id"), study, question, version, version + 1, session.hash(), session.epoch(), conversationVersion, Timestamp.from(created), textRef, sourceRef);
                var selected = db.queryForMap("SELECT * FROM study_owner_answer_inputs WHERE owner_id=? AND id=? FOR UPDATE", session.owner(), inputId);
                var selection = new StudyAnswerSourcePolicy.Selection(sourceRef(selected), number(selected, "observed_conversation_version"), storedTarget(selected));
                var command = new StudyAnswerSourcePolicy.Target(study, version, question, attemptId);
                try { next = StudyAnswerSourcePolicy.answer(current.state(), session.owner(), selection, observeInput(session, inputId), command, text); }
                catch (IllegalArgumentException invalid) { throw new ApiException(409, "STUDY_SOURCE_UNAVAILABLE"); }
            }
            Instant committed = now(); chronology(observed, committed); chronology(AuthService.instant(current.row().get("updated_at")), committed);
            db.update("UPDATE study_sessions SET version=?,lifecycle=?,updated_at=? WHERE owner_id=? AND id=?", next.version(), next.lifecycle().name(), Timestamp.from(committed), session.owner(), study);
            var receipt = writeReceipt(session, key, request, operation, current.row(), version, next.version(), conversationVersion, question, attemptId, inputId, committed);
            return outcome(receipt, finish(session, observed, committed, true));
        });
    }
    Map<String,Object> get(AuthService.Session session, UUID study) { return transaction(() -> {
        access.lockSession(session); schema(); Instant observed = now(); var identity = sessionIdentity(session.owner(), study);
        var conversation = live(session, id(identity, "conversation_id")); deletion.validateStudyGraph(session.owner(), id(conversation, "id"));
        Hydrated current = hydrate(session.owner(), conversation, study); var value = new LinkedHashMap<String,Object>();
        for (String field : List.of("id", "conversation_id", "project_id", "lesson_run_id", "version", "lifecycle")) value.put(field, current.row().get(field));
        value.put("conversation_content_version", conversation.get("content_version")); value.put("stage", current.state().stage().name());
        value.put("lesson", StudyLessonResult.project(current.state().lesson())); var answers = new ArrayList<Map<String,Object>>();
        for (int i = 0; i < current.inputs().size(); i++) { var answer = new LinkedHashMap<String,Object>(); var input = current.inputs().get(i);
            answer.put("input_id", input.get("id")); answer.put("attempt_id", input.get("attempt_id")); answer.put("question_id", input.get("question_id"));
            answer.put("text", current.state().attempts().get(i).answer()); answer.put("feedback", null); answers.add(answer);
        }
        value.put("attempts", answers); value.put("feedback_availability", "UNAVAILABLE");
        for (String field : List.of("created_at", "updated_at")) value.put(field, AuthService.instant(current.row().get(field)).toString());
        chronology(AuthService.instant(current.row().get("created_at")), AuthService.instant(current.row().get("updated_at")));
        value.put("checked_at", finish(session, observed, AuthService.instant(current.row().get("updated_at")), false).toString()); bounded(value, 262144); return value;
    }); }
    private static void bounded(Map<String,Object> value, int limit) {
        if (Json.canonical(value).getBytes(StandardCharsets.UTF_8).length > limit - 1024) throw new ApiException(409, "STUDY_SOURCE_UNAVAILABLE");
    }
    Map<String,Object> index(AuthService.Session session, UUID conversationId, String cursor, int limit) {
        if (limit < 1 || limit > 50) throw ApiException.bad();
        return transaction(() -> {
            access.lockSession(session); schema(); Instant observed = now(); var conversation = live(session, conversationId);
            deletion.validateStudyGraph(session.owner(), conversationId); String context = "study-index-v1|" + conversationId;
            var params = new ArrayList<Object>(); params.add(session.owner()); params.add(conversationId); String boundary = "";
            if (cursor != null) {
                String[] parts = memories.keys.boundary(session.owner(), context, cursor).split("\\|", -1);
                if (parts.length != 2) throw ApiException.bad();
                try { params.add(Timestamp.from(Instant.parse(parts[0]))); params.add(MemoryService.uuid(parts[1])); }
                catch (RuntimeException invalid) { throw ApiException.bad(); }
                boundary = " AND (r.created_at,r.id)<(?,?)";
            }
            params.add(limit + 1);
            var rows = db.queryForList("SELECT r.id,r.created_at FROM runs r JOIN inference_reservations q ON q.owner_id=r.owner_id AND q.run_id=r.id WHERE r.owner_id=? AND r.conversation_id=? AND r.status='SUCCEEDED' AND q.purpose='STUDY_LESSON'" + boundary + " ORDER BY r.created_at DESC,r.id DESC LIMIT ?", params.toArray());
            var items = new ArrayList<Map<String,Object>>(); Instant latest = observed;
            for (var candidate : rows.stream().limit(limit).toList()) {
                Lesson accepted = lesson(session.owner(), conversationId, id(conversation, "project_id"), id(candidate, "id"));
                var studies = db.queryForList("SELECT id,version,lifecycle,updated_at FROM study_sessions WHERE owner_id=? AND lesson_run_id=?", session.owner(), candidate.get("id"));
                require(studies.size() <= 1); var item = new LinkedHashMap<String,Object>(); item.put("lesson_run_id", candidate.get("id"));
                item.put("lesson_run_version", accepted.run().get("version")); item.put("created_at", AuthService.instant(candidate.get("created_at")).toString());
                for (String field : List.of("study_session_id", "study_version", "lifecycle")) item.put(field, null);
                if (!studies.isEmpty()) { var study = studies.getFirst(); item.put("study_session_id", study.get("id")); item.put("study_version", study.get("version")); item.put("lifecycle", study.get("lifecycle"));
                    Instant updated = AuthService.instant(study.get("updated_at")); if (updated.isAfter(latest)) latest = updated;
                }
                Instant created = AuthService.instant(candidate.get("created_at")); if (created.isAfter(latest)) latest = created; items.add(item);
            }
            var result = new LinkedHashMap<String,Object>(); result.put("conversation_id", conversationId); result.put("conversation_content_version", conversation.get("content_version")); result.put("items", items);
            result.put("next_cursor", rows.size() > limit ? memories.keys.cursor(session.owner(), context, AuthService.instant(rows.get(limit - 1).get("created_at")) + "|" + rows.get(limit - 1).get("id")) : null);
            result.put("checked_at", finish(session, observed, latest, false).toString()); bounded(result, 65536); return result;
        });
    }
}

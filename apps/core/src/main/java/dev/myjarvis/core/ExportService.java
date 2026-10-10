package dev.myjarvis.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** Metadata-only jobs; every download serializes the current explicit-memory scope. */
@Service
final class ExportService {
    static final int MAX_ROWS = 1000, MAX_BYTES = 8 * 1024 * 1024, MAX_JOBS = 100;
    static final long MAX_NANOS = 5_000_000_000L;
    static final String SEMANTICS = "CURRENT_AT_DOWNLOAD";
    private final MemoryService memories;
    private final TransactionTemplate tx;

    ExportService(MemoryService memories, PlatformTransactionManager manager) {
        this.memories = memories;
        tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(5);
    }

    static void validate(JsonNode body) {
        MemoryService.exact(body, "kind", "scope", "project_id", "format");
        if (!"EXPLICIT_MEMORIES".equals(Json.string(body, "kind", 17))
                || !"JSON_UTF8".equals(Json.string(body, "format", 9))) throw ApiException.bad();
        String scope = Json.string(body, "scope", 7);
        if (!Set.of("GLOBAL", "PROJECT").contains(scope)) throw ApiException.bad();
        if (scope.equals("GLOBAL")) { if (!body.get("project_id").isNull()) throw ApiException.bad(); }
        else Json.uuid(body, "project_id");
        if (Json.canonical(body).getBytes(StandardCharsets.UTF_8).length > 4096) throw new ApiException(413, "BODY_TOO_LARGE");
    }

    /** Database statement/lock limits and a monotonic serialization budget, not a performance claim. */
    private <T> T transaction(Function<Budget,T> work) {
        Budget budget = new Budget();
        try {
            return tx.execute(status -> {
                budget.check();
                memories.db.execute("SET LOCAL statement_timeout='5s'");
                memories.db.execute("SET LOCAL lock_timeout='5s'");
                T result = work.apply(budget);
                budget.check();
                return result;
            });
        } catch (TransactionTimedOutException e) {
            throw timeLimit();
        } catch (DataAccessException e) {
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql && ("57014".equals(sql.getSQLState()) || "55P03".equals(sql.getSQLState()))) throw timeLimit();
            }
            throw e;
        }
    }

    Map<String,Object> create(AuthService.Session session, UUID key, JsonNode body) {
        AuthService.requireOwner(session); validate(body);
        UUID project = MemoryService.projectId(body);
        String digest = memories.keys.digest(session.owner(), "export-request-v1", Json.canonical(body));
        return transaction(budget -> {
            var owner = memories.session(session, true, true); budget.check();
            var prior = memories.db.queryForList("SELECT * FROM memory_export_jobs WHERE owner_id=? AND idempotency_key=?", session.owner(), key);
            if (!prior.isEmpty()) {
                var row = prior.getFirst(); MemoryService.binding(session, row);
                if (!Json.equal(digest, (String) row.get("request_digest"))) throw new ApiException(409, "IDEMPOTENCY_CONFLICT");
                memories.project(session.owner(), (UUID) row.get("project_id"));
                return metadata(row, memories.databaseNow());
            }
            MemoryService.unpaused(owner); memories.project(session.owner(), project); budget.check();
            // The owner lock serializes cleanup, cap checks and inserts. Reads never clean up jobs.
            memories.db.update("DELETE FROM memory_export_jobs WHERE owner_id=? AND created_at<=clock_timestamp()-interval '24 hours'", session.owner());
            if (memories.db.queryForObject("SELECT count(*) FROM memory_export_jobs WHERE owner_id=?", Long.class, session.owner()) >= MAX_JOBS)
                throw new ApiException(429, "EXPORT_JOB_LIMIT");
            UUID id = UUID.randomUUID(); Instant now = memories.databaseNow();
            memories.db.update("INSERT INTO memory_export_jobs(id,owner_id,kind,scope,project_id,format,session_hash,security_epoch,idempotency_key,request_digest,created_at,expires_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    id, session.owner(), "EXPLICIT_MEMORIES", body.get("scope").asText(), project, "JSON_UTF8", session.hash(), session.epoch(), key, digest, Timestamp.from(now), Timestamp.from(now.plusSeconds(900)));
            memories.audit(session.owner(), "MEMORY_EXPORT_CREATED", id);
            return metadata(owned(session, id), memories.databaseNow());
        });
    }

    Map<String,Object> status(AuthService.Session session, UUID id) {
        return transaction(budget -> {
            memories.session(session, true, false); budget.check();
            return metadata(owned(session, id), memories.databaseNow());
        });
    }

    Map<String,Object> reconcile(AuthService.Session session, UUID key) {
        return transaction(budget -> {
            memories.session(session, true, false); budget.check();
            var rows = memories.db.queryForList("SELECT * FROM memory_export_jobs WHERE owner_id=? AND idempotency_key=?", session.owner(), key);
            Map<String,Object> result = new LinkedHashMap<>();
            result.put("status", rows.isEmpty() ? "NOT_OBSERVED" : "COMMITTED");
            if (rows.isEmpty()) result.put("job", null);
            else {
                var row = rows.getFirst(); MemoryService.binding(session, row);
                memories.project(session.owner(), (UUID) row.get("project_id"));
                result.put("job", metadata(row, memories.databaseNow()));
            }
            return result;
        });
    }

    private Map<String,Object> owned(AuthService.Session session, UUID id) {
        var rows = memories.db.queryForList("SELECT * FROM memory_export_jobs WHERE owner_id=? AND id=?", session.owner(), id);
        if (rows.isEmpty()) throw ApiException.missing();
        var row = rows.getFirst(); MemoryService.binding(session, row);
        memories.project(session.owner(), (UUID) row.get("project_id"));
        return row;
    }

    private static Map<String,Object> metadata(Map<String,Object> row, Instant now) {
        Map<String,Object> result = new LinkedHashMap<>();
        for (String field : List.of("id", "kind", "scope", "project_id", "format")) result.put(field, row.get(field));
        result.put("semantics", SEMANTICS);
        result.put("status", AuthService.instant(row.get("expires_at")).isAfter(now) ? "AVAILABLE" : "EXPIRED");
        for (String field : List.of("created_at", "expires_at")) result.put(field, AuthService.instant(row.get(field)).toString());
        result.put("checked_at", now.toString());
        return result;
    }

    byte[] download(AuthService.Session session, UUID id) {
        return transaction(budget -> {
            // All memory mutations, deletion, session rotation and logout acquire this same owner lock.
            // READ_COMMITTED observes commits preceding lock acquisition, including a prior deletion.
            memories.session(session, true, true); budget.check();
            var job = owned(session, id); requireAvailable(job, memories.databaseNow());
            var rows = memories.db.queryForList("SELECT m.id,m.owner_id,m.kind,m.version,m.payload_ref,m.confirmed_at,m.created_at,m.updated_at FROM memories m JOIN memory_slots s ON s.owner_id=m.owner_id AND s.id=m.slot_id WHERE m.owner_id=? AND s.scope=? AND s.project_id IS NOT DISTINCT FROM ? AND m.deleted_at IS NULL AND s.current_memory_id=m.id AND s.tombstone=false ORDER BY m.id LIMIT ?",
                    session.owner(), job.get("scope"), job.get("project_id"), MAX_ROWS + 1);
            budget.check();
            if (rows.size() > MAX_ROWS) throw new ApiException(413, "EXPORT_ROW_LIMIT");
            Instant exportedAt = memories.databaseNow();
            byte[] result = document(job, exportedAt, rows, budget);
            // Time-based validity is checked again after serialization, while the locks remain held.
            memories.session(session, true, true);
            memories.project(session.owner(), (UUID) job.get("project_id"));
            requireAvailable(job, memories.databaseNow()); budget.check();
            // Commit releases the locks before HTTP transmission. A later deletion cannot recall
            // bytes already generated/in flight or a file saved by the owner; later downloads rebuild.
            return result;
        });
    }

    private static void requireAvailable(Map<String,Object> row, Instant now) {
        if (!AuthService.instant(row.get("expires_at")).isAfter(now)) throw new ApiException(410, "EXPORT_EXPIRED");
    }

    private Map<String,Object> item(Map<String,Object> row) {
        if (!(row.get("payload_ref") instanceof String ref) || ref.length() > 65536) throw new IllegalStateException("Invalid memory record");
        var payload = Json.MAPPER.valueToTree(memories.payload(row));
        String key = Json.string(payload, "key", 96);
        if (!key.matches("[a-z][a-z0-9_.-]{0,95}")) throw new IllegalStateException("Invalid memory record");
        Map<String,Object> result = new TreeMap<>();
        result.put("key", key);
        result.put("value", MemoryService.text(payload, "value", 2000, 8000));
        result.put("change_reason", MemoryService.text(payload, "change_reason", 300, 1200));
        for (String field : List.of("id", "kind", "version")) result.put(field, row.get(field));
        for (String field : List.of("confirmed_at", "created_at", "updated_at")) result.put(field, AuthService.instant(row.get(field)).toString());
        JsonNode source = payload.get("source");
        if (source == null || !source.isObject() || !"EXPLICIT_OWNER_FORM".equals(Json.string(source, "type", 19))) throw new IllegalStateException("Invalid memory source");
        String submitted = Json.string(source, "submitted_at", 32);
        Instant instant = Instant.parse(submitted);
        if (!submitted.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,6})?Z") || instant.atOffset(java.time.ZoneOffset.UTC).getYear() < 1) throw new IllegalStateException("Invalid memory source");
        Instant confirmed = AuthService.instant(row.get("confirmed_at")), updated = AuthService.instant(row.get("updated_at"));
        if (!instant.equals(confirmed) || !confirmed.equals(updated) || AuthService.instant(row.get("created_at")).isAfter(updated)) throw new IllegalStateException("Invalid memory timestamps");
        result.put("source", Map.of("type", "EXPLICIT_OWNER_FORM", "submitted_at", submitted));
        // Select each field explicitly: future private payload fields are never implicitly exported.
        return result;
    }

    private byte[] document(Map<String,Object> job, Instant time, List<Map<String,Object>> rows, Budget budget) {
        TreeMap<String,Object> fields = new TreeMap<>();
        fields.put("schema_version", 1); fields.put("kind", "EXPLICIT_MEMORIES");
        fields.put("scope", job.get("scope")); fields.put("project_id", job.get("project_id"));
        fields.put("export_job_id", job.get("id")); fields.put("exported_at", time.toString());
        fields.put("semantics", SEMANTICS); fields.put("items", null);
        BoundedBytes output = new BoundedBytes(); output.append("{");
        boolean first = true;
        for (var field : fields.entrySet()) {
            budget.check(); if (!first) output.append(","); first = false;
            output.append(Json.canonical(field.getKey())); output.append(":");
            if (field.getKey().equals("items")) {
                output.append("["); boolean firstItem = true;
                for (var row : rows) {
                    budget.check(); if (!firstItem) output.append(","); firstItem = false;
                    output.append(Json.canonical(item(row)));
                }
                output.append("]");
            } else output.append(Json.canonical(field.getValue()));
        }
        output.append("}"); budget.check(); return output.bytes();
    }

    static ApiException timeLimit() { return new ApiException(503, "EXPORT_TIME_LIMIT"); }
    static final class Budget {
        private final long started = System.nanoTime();
        void check() { if (System.nanoTime() - started >= MAX_NANOS) throw timeLimit(); }
    }
    static final class BoundedBytes {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        void append(String value) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_BYTES - out.size()) throw new ApiException(413, "EXPORT_BYTE_LIMIT");
            out.writeBytes(bytes);
        }
        byte[] bytes() { return out.toByteArray(); }
    }
}

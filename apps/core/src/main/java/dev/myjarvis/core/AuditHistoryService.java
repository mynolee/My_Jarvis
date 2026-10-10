package dev.myjarvis.core;

import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Value-free owner audit projection. No vault, workers, providers, authority issuance or writes. */
@Service
final class AuditHistoryService {
    static final Set<String> RUN_EVENTS = Set.of(
            "RUN_ACCEPTED", "RUN_CANCELLED", "RUN_WAITING_INPUT", "RUN_SUCCEEDED", "RUN_WAITING_APPROVAL",
            "RUN_BLOCKED", "RUN_UNKNOWN", "WORKER_UNCERTAIN_OR_FAILED", "MOCK_EXECUTION_STARTED",
            "LEASE_EXPIRED_UNKNOWN", "MOCK_RECONCILED", "APPROVAL_REJECTED", "APPROVAL_CONSUMED",
            "CANCEL_REQUESTED", "LOCAL_INFERENCE_RESERVED", "LOCAL_INFERENCE_STARTED",
            "LOCAL_INFERENCE_UNKNOWN", "LOCAL_INFERENCE_SUCCEEDED", "LOCAL_INFERENCE_WAITING_INPUT",
            "LOCAL_INFERENCE_FAILED", "LOCAL_CONTEXT_BLOCKED");
    static final Set<String> RUN_STATUSES = Set.of("ACCEPTED", "PLANNING", "WAITING_INPUT", "WAITING_APPROVAL",
            "QUEUED", "RUNNING", "SUCCEEDED", "PARTIAL_SUCCESS", "FAILED", "CANCEL_REQUESTED",
            "CANCELLED", "UNKNOWN", "EXPIRED", "BLOCKED");
    static final Map<String,String> EVENT_KINDS = eventKinds();
    private static final String RUN_SQL = RUN_EVENTS.stream().sorted().map(value -> "'" + value + "'")
            .collect(java.util.stream.Collectors.joining(","));
    private static final long CURSOR_SECONDS = 900;
    private final JdbcTemplate db;
    private final TransactionTemplate readTx;
    private final byte[] cursorKey;

    AuditHistoryService(JdbcTemplate db, PlatformTransactionManager manager,
                        @Value("${jarvis.key-file}") String keyFile) throws Exception {
        this.db = db;
        readTx = new TransactionTemplate(manager);
        readTx.setReadOnly(true);
        readTx.setTimeout(15);
        readTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        readTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Path path = Path.of(keyFile);
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path)
                || Files.getPosixFilePermissions(path).stream().anyMatch(p -> p.name().startsWith("GROUP_") || p.name().startsWith("OTHERS_")))
            throw new IllegalStateException("Invalid private key file");
        byte[] key = Files.readAllBytes(path);
        try {
            if (key.length != 32) throw new IllegalStateException("Expected 32-byte key");
            cursorKey = mac(key, "jarvis:audit-history:cursor:v1");
        } finally { Arrays.fill(key, (byte) 0); }
    }

    Map<String,Object> get(AuthService.Session session, String scope, String cursor, int limit) {
        AuthService.requireOwner(session);
        if (scope == null || !Set.of("ALL", "RUNS").contains(scope) || limit < 1 || limit > 100) throw ApiException.bad();
        return readTx.execute(transaction -> {
            // Authentication and all returned metadata share one read-only MVCC snapshot and DB clock.
            var owners = db.queryForList("WITH checked AS MATERIALIZED (SELECT clock_timestamp() AS at) SELECT checked.at AS checked_at FROM owners o JOIN sessions s ON s.owner_id=o.id CROSS JOIN checked WHERE o.id=? AND o.security_epoch=? AND s.token_hash=? AND s.security_epoch=o.security_epoch AND s.revoked_at IS NULL AND s.created_at>checked.at-interval '12 hours' AND s.last_seen_at>checked.at-interval '2 hours'", session.owner(), session.epoch(), session.hash());
            if (owners.isEmpty()) throw new ApiException(401, "SESSION_REVOKED");
            Instant checked = AuthService.instant(owners.getFirst().get("checked_at"));
            outputInstant(checked);
            Boundary boundary = decode(session, scope, cursor, checked);
            List<Object> args = new ArrayList<>(List.of(session.owner()));
            String where = scope.equals("RUNS") ? " AND e.event_type IN (" + RUN_SQL + ")" : "";
            if (boundary != null) {
                where += " AND (e.happened_at,e.id)<(?,?)";
                args.add(Timestamp.from(boundary.time())); args.add(boundary.id());
            }
            args.add(limit + 1);
            // resource_id is polymorphic. A UUID match alone must never hydrate a run.
            var rows = db.queryForList("SELECT e.id,e.event_type,e.resource_id,e.happened_at,r.id AS run_id,r.status AS current_status FROM audit_events e LEFT JOIN runs r ON r.owner_id=e.owner_id AND r.id=e.resource_id AND e.event_type IN (" + RUN_SQL + ") WHERE e.owner_id=?" + where + " ORDER BY e.happened_at DESC,e.id DESC LIMIT ?", args.toArray());
            boolean more = rows.size() > limit;
            if (more) rows = new ArrayList<>(rows.subList(0, limit));
            List<Map<String,Object>> items = rows.stream().map(AuditHistoryService::item).toList();
            Map<String,Object> result = new LinkedHashMap<>();
            result.put("checked_at", outputInstant(checked)); result.put("scope", scope); result.put("items", items);
            result.put("next_cursor", more ? encode(session, scope, rows.getLast(), boundary == null ? checked : boundary.issued()) : null);
            return result;
        });
    }

    private static Map<String,Object> item(Map<String,Object> row) {
        if (!(row.get("id") instanceof UUID) || !(row.get("resource_id") instanceof UUID)
                || !(row.get("event_type") instanceof String event)) throw new IllegalStateException("Invalid audit identity");
        String kind = EVENT_KINDS.get(event);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("id", row.get("id")); result.put("event_type", kind == null ? "OTHER" : event);
        result.put("happened_at", outputInstant(AuthService.instant(row.get("happened_at"))));
        result.put("resource_kind", kind == null ? "OTHER" : kind);
        // An owned historical reference is not proof that its resource still exists or is accessible.
        result.put("resource_id", kind == null ? null : row.get("resource_id"));
        Map<String,Object> run = null;
        if ("RUN".equals(kind) && row.get("run_id") != null) {
            if (!(row.get("run_id") instanceof UUID id) || !id.equals(row.get("resource_id"))
                    || !(row.get("current_status") instanceof String status) || !RUN_STATUSES.contains(status)) throw new IllegalStateException("Invalid current run metadata");
            run = Map.of("id", id, "current_status", row.get("current_status"));
        }
        result.put("run", run);
        return result;
    }

    private record Boundary(Instant time, UUID id, Instant issued) {}

    private String encode(AuthService.Session session, String scope, Map<String,Object> row, Instant issued) {
        String body = String.join("|", "1", session.owner().toString(), scope, sessionTag(session),
                Long.toString(session.epoch()), outputInstant(issued), outputInstant(issued.plusSeconds(CURSOR_SECONDS)),
                outputInstant(AuthService.instant(row.get("happened_at"))), row.get("id").toString());
        String encoded = base64(body.getBytes(StandardCharsets.UTF_8));
        String cursor = encoded + "." + base64(mac(cursorKey, encoded));
        if (cursor.length() > 1024) throw new IllegalStateException("Unrepresentable audit cursor");
        return cursor;
    }

    private Boundary decode(AuthService.Session session, String scope, String cursor, Instant checked) {
        if (cursor == null) return null;
        try {
            if (cursor.length() > 1024 || !cursor.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]{43}")) throw new IllegalArgumentException();
            String[] token = cursor.split("\\.", -1);
            byte[] signature = Base64.getUrlDecoder().decode(token[1]);
            if (!base64(signature).equals(token[1]) || !MessageDigest.isEqual(mac(cursorKey, token[0]), signature)) throw new IllegalArgumentException();
            byte[] bytes = Base64.getUrlDecoder().decode(token[0]);
            if (!base64(bytes).equals(token[0])) throw new IllegalArgumentException();
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (!text.matches("[A-Za-z0-9_:|.\\-]+")) throw new IllegalArgumentException();
            String[] fields = text.split("\\|", -1);
            if (fields.length != 9 || !fields[0].equals("1") || !fields[1].equals(session.owner().toString())
                    || !fields[2].equals(scope) || !Json.equal(fields[3], sessionTag(session))
                    || !fields[4].equals(Long.toString(session.epoch()))) throw new IllegalArgumentException();
            Instant issued = canonicalInstant(fields[5]), expires = canonicalInstant(fields[6]), time = canonicalInstant(fields[7]);
            UUID id = UUID.fromString(fields[8]);
            if (!id.toString().equals(fields[8]) || issued.isAfter(checked) || !expires.equals(issued.plusSeconds(CURSOR_SECONDS))) throw new IllegalArgumentException();
            if (!checked.isBefore(expires)) throw new ApiException(410, "AUDIT_CURSOR_EXPIRED");
            return new Boundary(time, id, issued);
        } catch (ApiException error) { throw error; }
        catch (Exception error) { throw new ApiException(400, "INVALID_CURSOR"); }
    }

    private String sessionTag(AuthService.Session session) { return base64(mac(cursorKey, "session:" + session.hash())); }
    private static Instant canonicalInstant(String value) {
        Instant instant = Instant.parse(value);
        if (!outputInstant(instant).equals(value)) throw new IllegalArgumentException();
        return instant;
    }
    private static String outputInstant(Instant value) {
        if (value == null) throw new IllegalStateException("Missing audit timestamp");
        int year = value.atOffset(ZoneOffset.UTC).getYear();
        if (year < 1 || year > 9999 || value.getNano() % 1000 != 0) throw new IllegalStateException("Unrepresentable audit timestamp");
        return value.toString();
    }
    private static String base64(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static byte[] mac(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) { throw new IllegalStateException("Cursor authentication unavailable"); }
    }
    @PreDestroy void close() { Arrays.fill(cursorKey, (byte) 0); }

    private static Map<String,String> eventKinds() {
        Map<String,String> events = new LinkedHashMap<>();
        kinds(events, "RUN", RUN_EVENTS.toArray(String[]::new));
        kinds(events, "OWNER", "OWNER_CREATED", "OWNER_PROFILE_UPDATED", "EXECUTION_PAUSED", "EXECUTION_RESUMED");
        kinds(events, "PROJECT", "PROJECT_CREATED");
        kinds(events, "TASK", "TASK_CREATED", "TASK_UPDATED");
        kinds(events, "TASK_INTENT", "TASK_INTENT_ISSUED", "TASK_INTENT_CONSUMED");
        kinds(events, "MEMORY", "MEMORY_CREATED", "MEMORY_REVISED", "MEMORY_DELETED");
        kinds(events, "MEMORY_INTENT", "MEMORY_INTENT_ISSUED");
        kinds(events, "MEMORY_DELETE_PREPARATION", "MEMORY_DELETE_PREPARED");
        kinds(events, "MAIL_DRAFT", "MAIL_DRAFT_CREATED", "MAIL_DRAFT_UPDATED");
        kinds(events, "SCHEDULE", "SCHEDULE_CREATE_CONFIRMED", "SCHEDULE_REPLACE_CONFIRMED", "SCHEDULE_DISABLED");
        kinds(events, "SCHEDULE_PREPARATION", "SCHEDULE_PREPARED");
        kinds(events, "SCHEDULE_OCCURRENCE", "REMINDER_PLANNED", "REMINDER_BLOCKED", "REMINDER_SKIPPED_MISSED", "REMINDER_CANCELLED");
        kinds(events, "NOTIFICATION", "REMINDER_INBOX_CREATED", "NOTIFICATION_OPENED");
        return Map.copyOf(events);
    }
    private static void kinds(Map<String,String> events, String kind, String... types) {
        for (String type : types) if (events.put(type, kind) != null) throw new IllegalStateException("Duplicate audit event mapping");
    }
}

package dev.myjarvis.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Owner-filtered canonical reads. Cursors never authorize access or allocate write records. */
@Service
final class ConversationHistoryService {
    private static final int PAGE_BYTES = 512 * 1024;
    private final JdbcTemplate db;
    private final Vault vault;
    private final TransactionTemplate snapshot;
    private final ConversationAccess access;
    private final byte[] cursorKey;

    ConversationHistoryService(JdbcTemplate db, Vault vault, PlatformTransactionManager manager,
                               @Value("${jarvis.key-file}") String keyFile) throws Exception {
        this.db = db; this.vault = vault; this.access = new ConversationAccess(db);
        snapshot = new TransactionTemplate(manager);
        // SELECT FOR UPDATE is intentional: the lock is the content read linearization boundary.
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        snapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        snapshot.setTimeout(5);
        Path path = Path.of(keyFile);
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path)
                || Files.getPosixFilePermissions(path).stream().anyMatch(p -> p.name().startsWith("GROUP_") || p.name().startsWith("OTHERS_")))
            throw new IllegalStateException("Invalid private key file");
        byte[] key = Files.readAllBytes(path);
        try {
            if (key.length != 32) throw new IllegalStateException("Expected 32-byte key");
            cursorKey = mac(key, "jarvis:conversation-history:cursor:v1");
        } finally { Arrays.fill(key, (byte) 0); }
    }

    Map<String,Object> conversations(UUID owner, String cursor, int limit) { return conversations(null, owner, cursor, limit); }
    Map<String,Object> conversations(AuthService.Session session, String cursor, int limit) { return conversations(session, session.owner(), cursor, limit); }
    private Map<String,Object> conversations(AuthService.Session session, UUID owner, String cursor, int limit) {
        checkLimit(limit);
        Boundary boundary = decode(owner, "conversations", null, cursor);
        return snapshot.execute(s -> {
            if (session == null) access.lockOwner(owner); else access.lockSession(session);
            List<Object> args = new ArrayList<>(List.of(owner));
            String where = older(boundary, args); args.add(limit + 1);
            var rows = db.queryForList("SELECT id,title,project_id,created_at FROM conversations WHERE owner_id=? AND deleted_at IS NULL" + where + " ORDER BY created_at DESC,id DESC LIMIT ?", args.toArray());
            boolean more = rows.size() > limit;
            if (more) rows = new ArrayList<>(rows.subList(0, limit));
            var items = rows.stream().map(this::conversation).toList();
            return page(items, more ? encode(owner, "conversations", null, rows.getLast()) : null);
        });
    }

    Map<String,Object> messages(UUID owner, UUID conversation, String cursor, int limit) { return messages(null, owner, conversation, cursor, limit); }
    Map<String,Object> messages(AuthService.Session session, UUID conversation, String cursor, int limit) { return messages(session, session.owner(), conversation, cursor, limit); }
    private Map<String,Object> messages(AuthService.Session session, UUID owner, UUID conversation, String cursor, int limit) {
        checkLimit(limit);
        return snapshot.execute(s -> {
            if (session == null) access.requireLive(owner, conversation); else access.requireLive(session, conversation);
            Boundary boundary = decode(owner, "messages", conversation, cursor);
            List<Object> args = new ArrayList<>(List.of(owner, conversation));
            String where = older(boundary, args); args.add(limit + 1);
            var rows = db.queryForList("SELECT m.*, EXISTS(SELECT 1 FROM inference_reservations i WHERE i.owner_id=m.owner_id AND i.run_id=m.run_id) AS local_mode FROM messages m WHERE owner_id=? AND conversation_id=?" + where + " ORDER BY created_at DESC,id DESC LIMIT ?", args.toArray());
            List<Map<String,Object>> items = new ArrayList<>();
            int bytes = 0;
            for (var row : rows) {
                if (items.size() == limit) break;
                Map<String,Object> item = new LinkedHashMap<>();
                for (String key : List.of("id", "run_id", "role")) item.put(key, row.get(key));
                item.put("text", vault.open(owner, (UUID) row.get("id"), "message", (String) row.get("content_ref")));
                item.put("created_at", instant(row));
                item.put("execution_mode", Boolean.TRUE.equals(row.get("local_mode")) ? "LOCAL_INFERENCE" : "MOCK");
                int length = Json.MAPPER.writeValueAsString(item).getBytes(StandardCharsets.UTF_8).length;
                if (bytes + length > PAGE_BYTES && !items.isEmpty()) break;
                items.add(item); bytes += length;
            }
            String next = items.size() < rows.size() ? encode(owner, "messages", conversation, rows.get(items.size() - 1)) : null;
            Collections.reverse(items);
            Map<String,Object> result = page(items, next);
            result.put("current_run", currentRun(owner, conversation));
            var event = db.queryForList("SELECT event_id,sequence FROM stream_events WHERE owner_id=? AND conversation_id=? ORDER BY sequence DESC LIMIT 1", owner, conversation);
            result.put("stream_cursor", event.isEmpty() ? null : event.getFirst());
            return result;
        });
    }

    String events(UUID owner, UUID conversation, String cursor) { return events(null, owner, conversation, cursor); }
    String events(AuthService.Session session, UUID conversation, String cursor) { return events(session, session.owner(), conversation, cursor); }
    private String events(AuthService.Session session, UUID owner, UUID conversation, String cursor) {
        return snapshot.execute(s -> {
            if (session == null) access.requireLive(owner, conversation); else access.requireLive(session, conversation);
            long sequence = 0;
            if (cursor != null) {
                UUID id;
                try { id = UUID.fromString(cursor); if (!id.toString().equals(cursor)) throw new IllegalArgumentException(); }
                catch (Exception e) { throw ApiException.bad(); }
                var prior = db.queryForList("SELECT sequence FROM stream_events WHERE owner_id=? AND conversation_id=? AND event_id=?", owner, conversation, id);
                if (prior.isEmpty()) throw new ApiException(410, "EVENT_CURSOR_UNAVAILABLE");
                sequence = ((Number) prior.getFirst().get("sequence")).longValue();
            }
            StringBuilder out = new StringBuilder("retry: 2000\n\n");
            for (var row : db.queryForList("SELECT event_id,sequence,run_id,created_at FROM stream_events WHERE owner_id=? AND conversation_id=? AND sequence>? ORDER BY sequence LIMIT 100", owner, conversation, sequence)) {
                Map<String,Object> event = new LinkedHashMap<>();
                for (String key : List.of("event_id", "sequence", "run_id")) event.put(key, row.get(key));
                event.put("conversation_id", conversation); event.put("created_at", instant(row));
                out.append("id: ").append(row.get("event_id")).append("\nevent: run.status\ndata: ").append(Json.MAPPER.writeValueAsString(event)).append("\n\n");
            }
            return out.toString();
        });
    }

    private Map<String,Object> currentRun(UUID owner, UUID conversation) {
        var rows = db.queryForList("SELECT * FROM runs WHERE owner_id=? AND conversation_id=? ORDER BY created_at DESC,id DESC LIMIT 1", owner, conversation);
        if (rows.isEmpty()) return null;
        var row = rows.getFirst(); UUID run = (UUID) row.get("id");
        Map<String,Object> result = new LinkedHashMap<>();
        for (String key : List.of("id", "status", "version")) result.put(key, row.get(key));
        result.put("execution_mode", Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM inference_reservations WHERE owner_id=? AND run_id=?)", Boolean.class, owner, run)) ? "LOCAL_INFERENCE" : "MOCK");
        result.put("result", row.get("result_ref") == null ? null : vault.open(owner, run, "result", (String) row.get("result_ref")));
        result.put("approval_id", db.queryForList("SELECT p.id FROM approvals p JOIN action_proposals a ON a.id=p.action_id AND a.owner_id=p.owner_id WHERE a.owner_id=? AND a.run_id=? ORDER BY p.id LIMIT 1", owner, run).stream().findFirst().map(r -> r.get("id")).orElse(null));
        result.put("job_id", db.queryForList("SELECT id FROM jobs WHERE owner_id=? AND run_id=? ORDER BY created_at DESC,id DESC LIMIT 1", owner, run).stream().findFirst().map(r -> r.get("id")).orElse(null));
        return result;
    }

    private Map<String,Object> conversation(Map<String,Object> row) {
        Map<String,Object> result = new LinkedHashMap<>();
        for (String key : List.of("id", "title", "project_id")) result.put(key, row.get(key));
        result.put("created_at", instant(row)); return result;
    }
    private static Map<String,Object> page(List<Map<String,Object>> items, String cursor) {
        Map<String,Object> result = new LinkedHashMap<>(); result.put("items", items); result.put("next_cursor", cursor); return result;
    }
    private static String instant(Map<String,Object> row) { return AuthService.instant(row.get("created_at")).toString(); }
    private static void checkLimit(int limit) { if (limit < 1 || limit > 100) throw ApiException.bad(); }
    private record Boundary(Instant time, UUID id) {}
    private static String older(Boundary boundary, List<Object> args) {
        if (boundary == null) return "";
        args.add(Timestamp.from(boundary.time())); args.add(boundary.id());
        return " AND (created_at,id)<(?,?)";
    }
    private String encode(UUID owner, String kind, UUID conversation, Map<String,Object> row) {
        String data = String.join("|", "1", owner.toString(), kind, conversation == null ? "-" : conversation.toString(), instant(row), row.get("id").toString(), Long.toString(Instant.now().plusSeconds(86400).getEpochSecond()));
        String body = Base64.getUrlEncoder().withoutPadding().encodeToString(data.getBytes(StandardCharsets.UTF_8));
        return body + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(mac(cursorKey, body));
    }
    private Boundary decode(UUID owner, String kind, UUID conversation, String cursor) {
        if (cursor == null) return null;
        try {
            if (cursor.length() > 1024 || !cursor.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]{43}")) throw new IllegalArgumentException();
            String[] token = cursor.split("\\.", -1);
            byte[] signature = Base64.getUrlDecoder().decode(token[1]);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(signature).equals(token[1])) throw new IllegalArgumentException();
            if (!java.security.MessageDigest.isEqual(mac(cursorKey, token[0]), signature)) throw new IllegalArgumentException();
            String[] fields = new String(Base64.getUrlDecoder().decode(token[0]), StandardCharsets.UTF_8).split("\\|", -1);
            if (fields.length != 7 || !fields[0].equals("1") || !fields[1].equals(owner.toString()) || !fields[2].equals(kind) || !fields[3].equals(conversation == null ? "-" : conversation.toString())) throw new IllegalArgumentException();
            if (Long.parseLong(fields[6]) <= Instant.now().getEpochSecond()) throw new ApiException(410, "HISTORY_CURSOR_EXPIRED");
            return new Boundary(Instant.parse(fields[4]), UUID.fromString(fields[5]));
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw new ApiException(400, "INVALID_CURSOR"); }
    }
    private static byte[] mac(byte[] key, String value) {
        try { Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256")); return mac.doFinal(value.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception e) { throw new IllegalStateException("Cursor authentication unavailable"); }
    }
    @PreDestroy void close() { Arrays.fill(cursorKey, (byte) 0); }
}

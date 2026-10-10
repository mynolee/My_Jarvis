package dev.myjarvis.core;

import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** Owner-scoped metadata, with no capability to execute model/tool instructions. */
@Service
final class ProjectService {
    private final JdbcTemplate db;
    private final Vault vault;
    private final TransactionTemplate tx;
    private final byte[] cursorKey;
    ProjectService(JdbcTemplate db, Vault vault, PlatformTransactionManager manager,
                   @Value("${jarvis.key-file}") String keyFile) throws Exception {
        this.db = db; this.vault = vault; tx = new TransactionTemplate(manager); tx.setTimeout(15);
        Path path = Path.of(keyFile);
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path)
                || Files.getPosixFilePermissions(path).stream().anyMatch(p -> p.name().startsWith("GROUP_") || p.name().startsWith("OTHERS_")))
            throw new IllegalStateException("Invalid private key file");
        byte[] key = Files.readAllBytes(path);
        try {
            if (key.length != 32) throw new IllegalStateException("Expected 32-byte key");
            cursorKey = mac(key, "jarvis:projects:cursor:v1");
        } finally { Arrays.fill(key, (byte) 0); }
    }
    Map<String,Object> create(AuthService.Session session, UUID key, JsonNode body) {
        AuthService.requireOwner(session);
        Json.fields(body, "name", "description");
        if (body.size() != 2) throw ApiException.bad();
        String name = Json.string(body, "name", 200);
        if (name.isBlank() || name.codePoints().anyMatch(Character::isISOControl)) throw ApiException.bad();
        JsonNode description = body.get("description");
        if (description == null || !description.isTextual() || description.asText().codePointCount(0, description.asText().length()) > 4000 || description.asText().indexOf('\0') >= 0) throw ApiException.bad();
        String hash = Json.hash(Json.canonical(body));
        return tx.execute(s -> {
            var owner = lockSession(session);
            var prior = db.queryForList("SELECT request_hash,project_id FROM project_creations WHERE owner_id=? AND idempotency_key=?", session.owner(), key);
            if (!prior.isEmpty()) {
                if (!Json.equal(hash, (String) prior.getFirst().get("request_hash"))) throw new ApiException(409, "IDEMPOTENCY_CONFLICT");
                return owned(session.owner(), (UUID) prior.getFirst().get("project_id"));
            }
            if (Boolean.TRUE.equals(owner.get("execution_paused"))) throw new ApiException(409, "EXECUTION_PAUSED");
            UUID id = UUID.randomUUID();
            db.update("INSERT INTO projects(id,owner_id,name,description_ref) VALUES (?,?,?,?)", id, session.owner(), name, vault.seal(session.owner(), id, "project-description", description.asText()));
            db.update("INSERT INTO project_creations(owner_id,idempotency_key,request_hash,project_id) VALUES (?,?,?,?)", session.owner(), key, hash, id);
            db.update("INSERT INTO audit_events(id,owner_id,event_type,resource_id) VALUES (?,?,?,?)", UUID.randomUUID(), session.owner(), "PROJECT_CREATED", id);
            return owned(session.owner(), id);
        });
    }
    Map<String,Object> get(AuthService.Session session, UUID id) {
        AuthService.requireOwner(session); return owned(session.owner(), id);
    }
    Map<String,Object> reconcile(AuthService.Session session, UUID key) {
        AuthService.requireOwner(session);
        var rows = db.queryForList("SELECT project_id FROM project_creations WHERE owner_id=? AND idempotency_key=?", session.owner(), key);
        if (rows.isEmpty()) {
            Map<String,Object> result = new LinkedHashMap<>(); result.put("status", "NOT_OBSERVED"); result.put("project", null); return result;
        }
        return Map.of("status", "COMMITTED", "project", owned(session.owner(), (UUID) rows.getFirst().get("project_id")));
    }
    Map<String,Object> list(AuthService.Session session, String cursor, int limit) {
        AuthService.requireOwner(session);
        if (limit < 1 || limit > 100) throw ApiException.bad();
        Boundary boundary = decode(session.owner(), cursor);
        List<Object> args = new ArrayList<>(List.of(session.owner()));
        String where = "";
        if (boundary != null) { where = " AND (created_at,id)<(?,?)"; args.add(Timestamp.from(boundary.time())); args.add(boundary.id()); }
        args.add(limit + 1);
        var rows = db.queryForList("SELECT * FROM projects WHERE owner_id=?" + where + " ORDER BY created_at DESC,id DESC LIMIT ?", args.toArray());
        List<Map<String,Object>> items = new ArrayList<>(); int bytes = 0;
        for (var row : rows) {
            if (items.size() == limit) break;
            var item = view(row); int size = Json.MAPPER.writeValueAsString(item).getBytes(StandardCharsets.UTF_8).length;
            if (!items.isEmpty() && bytes + size > 512 * 1024) break;
            items.add(item); bytes += size;
        }
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("next_cursor", items.size() < rows.size() ? encode(session.owner(), rows.get(items.size() - 1)) : null);
        return result;
    }
    private Map<String,Object> owned(UUID owner, UUID id) {
        var rows = db.queryForList("SELECT * FROM projects WHERE owner_id=? AND id=?", owner, id);
        if (rows.isEmpty()) throw ApiException.missing(); return view(rows.getFirst());
    }
    private Map<String,Object> view(Map<String,Object> row) {
        Map<String,Object> result = new LinkedHashMap<>();
        for (String field : List.of("id", "name", "active", "version")) result.put(field, row.get(field));
        result.put("created_at", AuthService.instant(row.get("created_at")).toString());
        result.put("description", row.get("description_ref") == null ? "" : vault.open((UUID) row.get("owner_id"), (UUID) row.get("id"), "project-description", (String) row.get("description_ref")));
        return result;
    }
    private Map<String,Object> lockSession(AuthService.Session session) {
        var owners = db.queryForList("SELECT * FROM owners WHERE id=? FOR UPDATE", session.owner());
        if (owners.isEmpty()) throw new ApiException(401, "SESSION_REVOKED");
        var owner = owners.getFirst();
        if (session.epoch() != ((Number) owner.get("security_epoch")).longValue()) throw new ApiException(401, "SESSION_REVOKED");
        var sessions = db.queryForList("SELECT token_hash FROM sessions WHERE token_hash=? AND owner_id=? AND security_epoch=? AND revoked_at IS NULL AND created_at>clock_timestamp()-interval '12 hours' AND last_seen_at>clock_timestamp()-interval '2 hours' FOR UPDATE", session.hash(), session.owner(), session.epoch());
        if (sessions.isEmpty()) throw new ApiException(401, "SESSION_REVOKED");
        return owner;
    }
    static UUID uuid(String value) {
        try { UUID id = UUID.fromString(value); if (!id.toString().equals(value)) throw ApiException.bad(); return id; }
        catch (Exception e) { throw ApiException.bad(); }
    }
    private record Boundary(Instant time, UUID id) {}
    private String encode(UUID owner, Map<String,Object> row) {
        String data = String.join("|", "1", owner.toString(), AuthService.instant(row.get("created_at")).toString(), row.get("id").toString(), Long.toString(Instant.now().plusSeconds(86400).getEpochSecond()));
        String body = Base64.getUrlEncoder().withoutPadding().encodeToString(data.getBytes(StandardCharsets.UTF_8));
        return body + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(mac(cursorKey, body));
    }
    private Boundary decode(UUID owner, String cursor) {
        if (cursor == null) return null;
        try {
            if (cursor.length() > 1024 || !cursor.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]{43}")) throw new IllegalArgumentException();
            String[] parts = cursor.split("\\.", -1);
            byte[] signature = Base64.getUrlDecoder().decode(parts[1]);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(signature).equals(parts[1]) || !java.security.MessageDigest.isEqual(mac(cursorKey, parts[0]), signature)) throw new IllegalArgumentException();
            byte[] data = Base64.getUrlDecoder().decode(parts[0]);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(data).equals(parts[0])) throw new IllegalArgumentException();
            String[] fields = new String(data, StandardCharsets.UTF_8).split("\\|", -1);
            if (fields.length != 5 || !fields[0].equals("1") || !fields[1].equals(owner.toString())) throw new IllegalArgumentException();
            if (Long.parseLong(fields[4]) <= Instant.now().getEpochSecond()) throw new ApiException(410, "PROJECT_CURSOR_EXPIRED");
            return new Boundary(Instant.parse(fields[2]), uuid(fields[3]));
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw new ApiException(400, "INVALID_CURSOR"); }
    }
    private static byte[] mac(byte[] key, String value) {
        try { Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256")); return mac.doFinal(value.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception e) { throw new IllegalStateException("Cursor authentication unavailable"); }
    }
    @PreDestroy void close() { Arrays.fill(cursorKey, (byte) 0); }
}

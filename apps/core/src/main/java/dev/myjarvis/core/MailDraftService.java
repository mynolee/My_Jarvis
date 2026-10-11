package dev.myjarvis.core;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** Encrypted, internal editable drafts. No external transport or model dependency. */
@Service
final class MailDraftService {
    static final long MAX = 9007199254740991L;
    static final List<String> FIELDS = List.of("sender_candidate", "to", "cc", "bcc", "subject", "body", "project_id", "attachments");
    private final JdbcTemplate db;
    private final Vault vault;
    private final MailDraftKeyCodec keys;
    private final TransactionTemplate tx;
    MailDraftService(JdbcTemplate db, Vault vault, MailDraftKeyCodec keys, PlatformTransactionManager manager) {
        this.db = db; this.vault = vault; this.keys = keys;
        tx = new TransactionTemplate(manager); tx.setTimeout(15); tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    static void validate(JsonNode body, boolean patch) {
        if (body == null) throw ApiException.bad();
        Json.validate(body);
        Json.fields(body, "sender_candidate", "to", "cc", "bcc", "subject", "body", "project_id", "attachments", "expected_version");
        if (patch) {
            if (body.size() < 2 || !body.has("expected_version")) throw ApiException.bad();
            long expected = Json.integer(body, "expected_version"); if (expected < 1 || expected > MAX) throw ApiException.bad();
        } else if (body.size() != FIELDS.size() || body.has("expected_version") || !FIELDS.stream().allMatch(body::has)) throw ApiException.bad();
        if (Json.canonical(body).getBytes(StandardCharsets.UTF_8).length > 65536) throw ApiException.bad();
        if (body.has("sender_candidate") && !body.get("sender_candidate").isNull()) text(body.get("sender_candidate"), 320, 1280, true, false);
        int recipients = 0;
        for (String field : List.of("to", "cc", "bcc")) if (body.has(field)) {
            JsonNode array = body.get(field); if (!array.isArray()) throw ApiException.bad(); recipients += array.size();
            for (JsonNode item : array) text(item, 320, 1280, true, false);
        }
        if (recipients > 30) throw ApiException.bad();
        if (body.has("subject")) text(body.get("subject"), 200, 800, false, false);
        if (body.has("body")) text(body.get("body"), 16000, 64000, false, true);
        if (body.has("project_id") && !body.get("project_id").isNull()) Json.uuid(body, "project_id");
        if (body.has("attachments") && (!body.get("attachments").isArray() || !body.get("attachments").isEmpty())) throw ApiException.bad();
    }
    private static void text(JsonNode node, int points, int bytes, boolean nonblank, boolean multiline) {
        if (!node.isTextual()) throw ApiException.bad(); String value = node.asText();
        if (value.codePointCount(0, value.length()) > points || value.getBytes(StandardCharsets.UTF_8).length > bytes
                || nonblank && value.codePoints().allMatch(OwnerProfileService::blankCodePoint)
                || value.codePoints().anyMatch(cp -> Character.isISOControl(cp) && !(multiline && (cp == 9 || cp == 10 || cp == 13)))) throw ApiException.bad();
    }
    private Map<String,Object> session(AuthService.Session session, boolean write) {
        AuthService.requireOwner(session);
        String lock = write ? " FOR UPDATE" : " FOR SHARE";
        var owners = db.queryForList("SELECT * FROM owners WHERE id=?" + lock, session.owner());
        if (owners.isEmpty() || session.epoch() != ((Number) owners.getFirst().get("security_epoch")).longValue()) throw new ApiException(401, "SESSION_REVOKED");
        var sessions = db.queryForList("SELECT token_hash FROM sessions WHERE token_hash=? AND owner_id=? AND security_epoch=? AND revoked_at IS NULL AND created_at>clock_timestamp()-interval '12 hours' AND last_seen_at>clock_timestamp()-interval '2 hours'" + lock, session.hash(), session.owner(), session.epoch());
        if (sessions.isEmpty()) throw new ApiException(401, "SESSION_REVOKED"); return owners.getFirst();
    }
    private <T> T read(AuthService.Session session, Supplier<T> action) { return tx.execute(status -> { session(session, false); return action.get(); }); }
    private Map<String,Object> owned(UUID owner, UUID id) {
        var rows = db.queryForList("SELECT * FROM mail_drafts WHERE owner_id=? AND id=?", owner, id);
        if (rows.isEmpty()) throw ApiException.missing(); return rows.getFirst();
    }
    private void project(UUID owner, UUID project, boolean active) {
        if (project == null) return;
        var rows = db.queryForList("SELECT active FROM projects WHERE owner_id=? AND id=?", owner, project);
        if (rows.isEmpty()) throw ApiException.missing();
        if (active && !Boolean.TRUE.equals(rows.getFirst().get("active"))) throw ApiException.bad();
    }
    private Map<String,Object> content(Map<String,Object> row) {
        var node = Json.parse(vault.open((UUID) row.get("owner_id"), (UUID) row.get("id"), "mail-draft-content", (String) row.get("content_ref")).getBytes(StandardCharsets.UTF_8));
        validate(node, false);
        Map<String,Object> result = new LinkedHashMap<>(); for (String field : FIELDS) result.put(field, Json.MAPPER.convertValue(node.get(field), Object.class));
        if (!Objects.equals(row.get("project_id") == null ? null : row.get("project_id").toString(), result.get("project_id"))) throw new IllegalStateException("Invalid draft record");
        return result;
    }
    private Map<String,Object> metadata(Map<String,Object> row) {
        Map<String,Object> result = new LinkedHashMap<>();
        for (String field : List.of("id", "project_id", "version", "delivery_status")) result.put(field, row.get(field));
        for (String field : List.of("created_at", "updated_at")) result.put(field, AuthService.instant(row.get(field)).toString());
        return result;
    }
    private Map<String,Object> view(Map<String,Object> row) { Map<String,Object> result = content(row); result.putAll(metadata(row)); return result; }
    private Map<String,Object> receipt(Map<String,Object> row) {
        Map<String,Object> result = new LinkedHashMap<>(); result.put("status", row == null ? "NOT_OBSERVED" : "COMMITTED");
        for (String field : List.of("draft_id", "version", "operation")) result.put(field, row == null ? null : row.get(field)); return result;
    }
    private void binding(AuthService.Session session, Map<String,Object> row) {
        if (!Json.equal(session.hash(), (String) row.get("session_hash")) || session.epoch() != ((Number) row.get("security_epoch")).longValue()) throw new ApiException(403, "MUTATION_SESSION_CHANGED");
    }
    Map<String,Object> get(AuthService.Session session, UUID id) { return read(session, () -> view(owned(session.owner(), id))); }
    Map<String,Object> reconcile(AuthService.Session session, UUID key) {
        return read(session, () -> {
            var rows = db.queryForList("SELECT * FROM mail_draft_mutations WHERE owner_id=? AND idempotency_key=?", session.owner(), key);
            if (rows.isEmpty()) return receipt(null); binding(session, rows.getFirst()); return receipt(rows.getFirst());
        });
    }
    Map<String,Object> list(AuthService.Session session, UUID project, String cursor, int limit) {
        if (limit < 1 || limit > 100) throw ApiException.bad();
        return read(session, () -> {
            var boundary = keys.decode(session.owner(), project, cursor); project(session.owner(), project, false);
            List<Object> args = new ArrayList<>(List.of(session.owner())); String where = "";
            if (project != null) { where += " AND project_id=?"; args.add(project); }
            if (boundary != null) { where += " AND (created_at,id)<(?,?)"; args.add(Timestamp.from(boundary.time())); args.add(boundary.id()); }
            args.add(limit + 1);
            var rows = db.queryForList("SELECT id,project_id,version,delivery_status,created_at,updated_at FROM mail_drafts WHERE owner_id=?" + where + " ORDER BY created_at DESC,id DESC LIMIT ?", args.toArray());
            var page = rows.subList(0, Math.min(limit, rows.size()));
            Map<String,Object> result = new LinkedHashMap<>(); result.put("items", page.stream().map(this::metadata).toList());
            result.put("next_cursor", rows.size() > limit ? keys.encode(session.owner(), project, page.getLast()) : null); return result;
        });
    }
    Map<String,Object> create(AuthService.Session session, UUID key, JsonNode body) { return mutate(session, null, key, body); }
    Map<String,Object> patch(AuthService.Session session, UUID id, UUID key, JsonNode body) { return mutate(session, Objects.requireNonNull(id), key, body); }
    private Map<String,Object> mutate(AuthService.Session session, UUID id, UUID key, JsonNode body) {
        AuthService.requireOwner(session); boolean patch = id != null; validate(body, patch);
        String digest = keys.request(session.owner(), patch ? "PATCH" : "POST", "/api/v1/mail-drafts" + (patch ? "/" + id : ""), body);
        return tx.execute(status -> {
            var owner = session(session, true);
            var prior = db.queryForList("SELECT * FROM mail_draft_mutations WHERE owner_id=? AND idempotency_key=?", session.owner(), key);
            if (!prior.isEmpty()) {
                var row = prior.getFirst(); binding(session, row);
                if (!Json.equal(digest, (String) row.get("request_digest"))) throw new ApiException(409, "IDEMPOTENCY_CONFLICT");
                return Map.of("draft", view(owned(session.owner(), (UUID) row.get("draft_id"))), "mutation", receipt(row));
            }
            Map<String,Object> old = patch ? owned(session.owner(), id) : null;
            if (Boolean.TRUE.equals(owner.get("execution_paused"))) throw new ApiException(409, "EXECUTION_PAUSED");
            long version = patch ? ((Number) old.get("version")).longValue() : 0;
            if (patch && version != Json.integer(body, "expected_version")) throw new ApiException(409, "PRECONDITION_CHANGED");
            if (version >= MAX) throw new ApiException(409, "VERSION_EXHAUSTED");
            Map<String,Object> value = patch ? content(old) : new LinkedHashMap<>();
            for (String field : FIELDS) if (body.has(field)) value.put(field, Json.MAPPER.convertValue(body.get(field), Object.class));
            // Validate the resulting record too: omitted recipient arrays still count toward the aggregate cap.
            var merged = Json.MAPPER.valueToTree(value); validate(merged, false);
            UUID project = merged.get("project_id").isNull() ? null : Json.uuid(merged, "project_id"); project(session.owner(), project, true);
            UUID draft = patch ? id : UUID.randomUUID();
            String sealed = vault.seal(session.owner(), draft, "mail-draft-content", Json.canonical(merged));
            Instant now = AuthService.instant(db.queryForObject("SELECT clock_timestamp()", Object.class));
            if (patch) db.update("UPDATE mail_drafts SET project_id=?,content_ref=?,version=?,updated_at=GREATEST(updated_at,?) WHERE owner_id=? AND id=?", project, sealed, version + 1, Timestamp.from(now), session.owner(), draft);
            else db.update("INSERT INTO mail_drafts(id,owner_id,project_id,content_ref,created_at,updated_at) VALUES (?,?,?,?,?,?)", draft, session.owner(), project, sealed, Timestamp.from(now), Timestamp.from(now));
            String operation = patch ? "UPDATE" : "CREATE";
            db.update("INSERT INTO mail_draft_mutations(owner_id,idempotency_key,session_hash,security_epoch,request_digest,draft_id,version,operation) VALUES (?,?,?,?,?,?,?,?)", session.owner(), key, session.hash(), session.epoch(), digest, draft, version + 1, operation);
            db.update("INSERT INTO audit_events(id,owner_id,event_type,resource_id) VALUES (?,?,?,?)", UUID.randomUUID(), session.owner(), patch ? "MAIL_DRAFT_UPDATED" : "MAIL_DRAFT_CREATED", draft);
            var row = db.queryForMap("SELECT * FROM mail_draft_mutations WHERE owner_id=? AND idempotency_key=?", session.owner(), key);
            return Map.of("draft", view(owned(session.owner(), draft)), "mutation", receipt(row));
        });
    }
}

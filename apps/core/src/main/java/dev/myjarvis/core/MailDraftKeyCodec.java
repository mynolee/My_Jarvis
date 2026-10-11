package dev.myjarvis.core;

import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Separate keyed commitments and opaque, owner/filter-bound pagination tokens. */
@Component
final class MailDraftKeyCodec {
    private final byte[] requestKey, cursorKey;
    MailDraftKeyCodec(@Value("${jarvis.key-file}") String file) throws Exception {
        Path path = Path.of(file);
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path)
                || Files.getPosixFilePermissions(path).stream().anyMatch(p -> p.name().startsWith("GROUP_") || p.name().startsWith("OTHERS_")))
            throw new IllegalStateException("Invalid private key file");
        byte[] raw = Files.readAllBytes(path);
        try {
            if (raw.length != 32) throw new IllegalStateException("Expected private key");
            requestKey = mac(raw, "jarvis:mail-drafts:request:v1".getBytes(StandardCharsets.UTF_8));
            cursorKey = mac(raw, "jarvis:mail-drafts:cursor:v1".getBytes(StandardCharsets.UTF_8));
        } finally { Arrays.fill(raw, (byte) 0); }
    }
    String request(UUID owner, String method, String target, tools.jackson.databind.JsonNode body) {
        return HexFormat.of().formatHex(mac(requestKey, Json.canonical(Map.of("owner_id", owner.toString(), "method", method, "target", target, "body", body)).getBytes(StandardCharsets.UTF_8)));
    }
    record Boundary(Instant time, UUID id) {}
    String encode(UUID owner, UUID project, Map<String,Object> row) {
        Map<String,Object> payload = new LinkedHashMap<>();
        payload.put("owner_id", owner.toString()); payload.put("project_id", project == null ? null : project.toString());
        payload.put("created_at", AuthService.instant(row.get("created_at")).toString()); payload.put("id", row.get("id").toString());
        byte[] bytes = Json.canonical(payload).getBytes(StandardCharsets.UTF_8);
        return encoded(bytes) + "." + encoded(mac(cursorKey, bytes));
    }
    Boundary decode(UUID owner, UUID project, String cursor) {
        if (cursor == null) return null;
        try {
            if (cursor.length() > 1024 || !cursor.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]{43}")) throw ApiException.bad();
            String[] parts = cursor.split("\\.", -1);
            byte[] payload = Base64.getUrlDecoder().decode(parts[0]), signature = Base64.getUrlDecoder().decode(parts[1]);
            if (!encoded(payload).equals(parts[0]) || !encoded(signature).equals(parts[1]) || !MessageDigest.isEqual(mac(cursorKey, payload), signature)) throw ApiException.bad();
            var node = Json.parse(payload); Json.fields(node, "owner_id", "project_id", "created_at", "id");
            if (node.size() != 4 || !Json.uuid(node, "owner_id").equals(owner)
                    || !Objects.equals(project, node.get("project_id").isNull() ? null : Json.uuid(node, "project_id"))
                    || !Json.canonical(node).equals(new String(payload, StandardCharsets.UTF_8))) throw ApiException.bad();
            String timestamp = Json.string(node, "created_at", 40);
            if (!timestamp.matches("(?!0000)[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,6})?Z")) throw ApiException.bad();
            Instant instant = java.time.OffsetDateTime.parse(timestamp).toInstant();
            return new Boundary(instant, Json.uuid(node, "id"));
        } catch (Exception e) { throw ApiException.bad(); }
    }
    private static String encoded(byte[] value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    private static byte[] mac(byte[] key, byte[] value) {
        try { Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256")); return mac.doFinal(value); }
        catch (Exception e) { throw new IllegalStateException("Mail draft key unavailable"); }
    }
    @PreDestroy void close() { Arrays.fill(requestKey, (byte) 0); Arrays.fill(cursorKey, (byte) 0); }
}

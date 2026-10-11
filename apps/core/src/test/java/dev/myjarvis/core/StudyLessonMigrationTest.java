package dev.myjarvis.core;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** CI-only actual V14-to-V15 migration; no production history rewriting or test bypass. */
class StudyLessonMigrationTest {
    @Test void v14PreparedAuthorityIsRevokedAndConsumedPreparationRemainsUnchanged() throws Exception {
        Path key = Files.createTempFile("study-migration-", ".key"); byte[] bytes = new byte[32]; Vault.RNG.nextBytes(bytes); Files.write(key, bytes);
        Files.setPosixFilePermissions(key, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        try (EmbeddedPostgres postgres = EmbeddedPostgres.builder().setPort(0).start()) {
            String url = postgres.getJdbcUrl("postgres", "postgres");
            try (ConfigurableApplicationContext app = new SpringApplicationBuilder(CoreApplication.class).web(WebApplicationType.NONE).run(
                    "--spring.datasource.url=" + url, "--spring.datasource.username=postgres", "--spring.datasource.password=",
                    "--jarvis.key-file=" + key, "--jarvis.mode=mock", "--jarvis.worker.enabled=false", "--jarvis.local.enabled=false",
                    "--spring.flyway.target=14", "--spring.main.banner-mode=off")) {
                var db = app.getBean(JdbcTemplate.class); var vault = app.getBean(Vault.class);
                assertEquals("14", db.queryForObject("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1", String.class));
                UUID owner = UUID.randomUUID(), conversation = UUID.randomUUID(), prepared = UUID.randomUUID(), consumed = UUID.randomUUID();
                String sessionHash = "synthetic-study-migration-session", digest = "a".repeat(64), payload = "b".repeat(64);
                db.update("INSERT INTO owners(id,login_name,password_hash) VALUES (?,'study-migration','unused')", owner);
                db.update("INSERT INTO sessions(token_hash,owner_id,csrf_hash,security_epoch,reauthenticated_at) VALUES (?,?,'fixture',1,clock_timestamp())", sessionHash, owner);
                db.update("INSERT INTO conversations(id,owner_id,title) VALUES (?,?,'Synthetic old conversation')", conversation, owner);
                String snapshot = vault.seal(owner, prepared, "conversation-delete-snapshot", digest), authority = vault.seal(owner, prepared, "conversation-delete-payload", payload);
                db.update("WITH t AS (SELECT clock_timestamp() AS at) INSERT INTO conversation_delete_preparations(id,owner_id,conversation_id,session_hash,security_epoch,issuance_key,issuance_digest,expected_version,expected_digest_ref,payload_hash_ref,messages_count,runs_count,run_results_count,action_payloads_count,inference_contexts_count,prepared_at,expires_at) SELECT ?,?,?,?,1,?,?,1,?,?,0,0,0,0,0,t.at,t.at+interval '300 seconds' FROM t", prepared, owner, conversation, sessionHash, UUID.randomUUID(), "c".repeat(64), snapshot, authority);
                db.update("WITH t AS (SELECT clock_timestamp() AS at) INSERT INTO conversation_delete_preparations(id,owner_id,conversation_id,session_hash,security_epoch,issuance_key,issuance_digest,expected_version,messages_count,runs_count,run_results_count,action_payloads_count,inference_contexts_count,prepared_at,expires_at,consumed_at) SELECT ?,?,?,?,1,?,?,1,0,0,0,0,0,t.at,t.at+interval '300 seconds',t.at FROM t", consumed, owner, conversation, sessionHash, UUID.randomUUID(), "d".repeat(64));
                UUID oldRun = UUID.randomUUID(), oldReservation = UUID.randomUUID();
                db.update("INSERT INTO runs(id,owner_id,conversation_id,status) VALUES (?,?,?,'SUCCEEDED')", oldRun, owner, conversation);
                db.update("INSERT INTO inference_reservations(id,owner_id,run_id,request_id,body_hash,model_manifest_hash,status,max_compute_ms,reserved_output_tokens,security_epoch) VALUES (?,?,?,?,?,?,'SUCCEEDED',60000,512,1)", oldReservation, owner, oldRun, UUID.randomUUID(), "e".repeat(64), "f".repeat(64));
                db.update("INSERT INTO inference_usage_receipts(owner_id,run_id,reservation_id,request_id,model_id,model_manifest_hash,request_digest,budget_revision,max_compute_ms,reserved_output_tokens,reserved_at,receipt_kind,response_kind,input_tokens,output_tokens,compute_ms,peak_memory_bytes) SELECT owner_id,run_id,id,request_id,model_id,model_manifest_hash,body_hash,budget_revision,max_compute_ms,reserved_output_tokens,created_at,'MEASURED','ANSWER',2,3,4,5 FROM inference_reservations WHERE id=?", oldReservation);
                var oldReservationBefore = db.queryForMap("SELECT * FROM inference_reservations WHERE id=?", oldReservation);
                var oldReceiptBefore = db.queryForMap("SELECT * FROM inference_usage_receipts WHERE reservation_id=?", oldReservation);
                var consumedBefore = db.queryForMap("SELECT * FROM conversation_delete_preparations WHERE id=?", consumed);
                assertEquals(digest, vault.open(owner, prepared, "conversation-delete-snapshot", snapshot));
                var oldBody = Json.MAPPER.valueToTree(Map.of("expected_version", 1, "expected_digest", digest, "payload_hash", payload, "confirm", true));
                Flyway.configure().dataSource(url, "postgres", "").locations("classpath:db/migration").load().migrate();
                assertEquals("15", db.queryForObject("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1", String.class));
                var migratedReservation = db.queryForMap("SELECT * FROM inference_reservations WHERE id=?", oldReservation);
                assertEquals("CONVERSATION", migratedReservation.remove("purpose")); assertEquals(oldReservationBefore, migratedReservation);
                assertEquals(oldReceiptBefore, db.queryForMap("SELECT * FROM inference_usage_receipts WHERE reservation_id=?", oldReservation));
                var revoked = db.queryForMap("SELECT * FROM conversation_delete_preparations WHERE id=?", prepared);
                assertNotNull(revoked.get("revoked_at")); assertNull(revoked.get("expected_digest_ref")); assertNull(revoked.get("payload_hash_ref"));
                assertEquals(consumedBefore, db.queryForMap("SELECT * FROM conversation_delete_preparations WHERE id=?", consumed));
                var session = new AuthService.Session(sessionHash, owner, Instant.now(), 1);
                ApiException denied = assertThrows(ApiException.class, () -> app.getBean(ConversationDeletionService.class).confirm(session, UUID.randomUUID(), prepared, oldBody));
                assertEquals("AUTHORITY_NOT_VALID", denied.code);
                assertNull(db.queryForObject("SELECT deleted_at FROM conversations WHERE id=?", Object.class, conversation));
                assertEquals(0L, db.queryForObject("SELECT count(*) FROM conversation_deletion_ledger", Long.class));
            }
        } finally { Files.deleteIfExists(key); }
    }
}

package dev.myjarvis.core;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.InputStream;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.util.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Actual disposable PostgreSQL/Flyway checks, including a transactional mid-migration failure. */
class DurableStudyMigrationTest {
    private static Flyway migration(DataSource ds,String target){return Flyway.configure().dataSource(ds).locations("classpath:db/migration").target(target).load();}
    private static UUID owner(JdbcTemplate db){UUID id=UUID.randomUUID();db.update("INSERT INTO owners(id,login_name,password_hash) VALUES (?,'durable-migration','unused')",id);db.update("INSERT INTO sessions(token_hash,owner_id,csrf_hash,security_epoch,reauthenticated_at) VALUES ('durable-migration',?,'fixture',1,clock_timestamp())",id);return id;}
    private static UUID conversation(JdbcTemplate db,UUID owner){UUID id=UUID.randomUUID();db.update("INSERT INTO conversations(id,owner_id,title) VALUES (?,?,'Synthetic migration source')",id,owner);return id;}
    private static UUID preparation(JdbcTemplate db,UUID owner,UUID conversation){UUID id=UUID.randomUUID();db.update("WITH t AS (SELECT clock_timestamp() AS at) INSERT INTO conversation_delete_preparations(id,owner_id,conversation_id,session_hash,security_epoch,issuance_key,issuance_digest,expected_version,expected_digest_ref,payload_hash_ref,messages_count,runs_count,run_results_count,action_payloads_count,inference_contexts_count,prepared_at,expires_at) SELECT ?,?,?,'durable-migration',1,?,?,1,'synthetic snapshot','synthetic authority',0,0,0,0,0,t.at,t.at+interval '300 seconds' FROM t",id,owner,conversation,UUID.randomUUID(),"a".repeat(64));return id;}
    @Test void everyHistoricalMigrationRetainsItsPublishedBytes() throws Exception {
        Properties expected=new Properties();try(InputStream input=getClass().getResourceAsStream("/migrations/pre-v16-sha256.properties")){assertNotNull(input);expected.load(input);}assertEquals(15,expected.size());
        for(String name:expected.stringPropertyNames())try(InputStream input=getClass().getResourceAsStream("/db/migration/"+name)){assertNotNull(input);assertEquals(expected.getProperty(name),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes())),name);}
    }
    @Test void cleanV1ThroughV16CreatesExactlyThreeStudyRelations() throws Exception {
        try(EmbeddedPostgres postgres=EmbeddedPostgres.builder().setPort(0).start()){
            migration(postgres.getPostgresDatabase(),"16").migrate();var db=new JdbcTemplate(postgres.getPostgresDatabase());
            assertEquals("16",db.queryForObject("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1",String.class));
            assertEquals(Set.of("study_sessions","study_owner_answer_inputs","study_mutations"),new HashSet<>(db.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema='public' AND table_name LIKE 'study_%'",String.class)));
            assertEquals(3L,db.queryForObject("SELECT count(*) FROM pg_trigger WHERE tgname='study_history_complete'",Long.class));
            assertEquals(1L,db.queryForObject("SELECT count(*) FROM pg_trigger WHERE tgname='study_receipt_commit'",Long.class));
        }
    }
    @Test void v15PreparationsAreRevokedWhileConsumedLedgerAndUsageRemainExact() throws Exception {
        try(EmbeddedPostgres postgres=EmbeddedPostgres.builder().setPort(0).start()){
            DataSource ds=postgres.getPostgresDatabase();migration(ds,"15").migrate();var db=new JdbcTemplate(ds);UUID owner=owner(db),live=conversation(db,owner),deleted=conversation(db,owner),pending=preparation(db,owner,live),consumed=preparation(db,owner,deleted);
            UUID approval=UUID.randomUUID(),deletion=UUID.randomUUID(),key=UUID.randomUUID();var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
            tx.executeWithoutResult(status->{Timestamp at=db.queryForObject("SELECT clock_timestamp()",Timestamp.class);
                db.update("INSERT INTO conversation_delete_approvals(id,owner_id,preparation_id,conversation_id,session_hash,security_epoch,consumed_at) VALUES (?,?,?,?,'durable-migration',1,?)",approval,owner,consumed,deleted,at);
                db.update("INSERT INTO conversation_deletion_ledger(id,owner_id,conversation_id,preparation_id,approval_id,deleted_version,tombstone_version,deleted_at) VALUES (?,?,?,?,?,1,2,?)",deletion,owner,deleted,consumed,approval,at);
                db.update("UPDATE conversations SET title=NULL,deleted_at=? WHERE id=?",at,deleted);
                db.update("UPDATE conversation_delete_preparations SET consumed_at=?,expected_digest_ref=NULL,payload_hash_ref=NULL WHERE id=?",at,consumed);
                db.update("INSERT INTO conversation_deletion_mutations(owner_id,idempotency_key,session_hash,security_epoch,request_digest,deletion_id,preparation_id) VALUES (?,?,'durable-migration',1,?,?,?)",owner,key,"b".repeat(64),deletion,consumed);
            });
            UUID run=UUID.randomUUID(),reservation=UUID.randomUUID();db.update("INSERT INTO runs(id,owner_id,conversation_id,status) VALUES (?,?,?,'SUCCEEDED')",run,owner,live);
            db.update("INSERT INTO inference_reservations(id,owner_id,run_id,request_id,body_hash,model_manifest_hash,status,max_compute_ms,reserved_output_tokens,security_epoch) VALUES (?,?,?,?,?,?,'SUCCEEDED',60000,512,1)",reservation,owner,run,UUID.randomUUID(),"c".repeat(64),"d".repeat(64));
            db.update("INSERT INTO inference_usage_receipts(owner_id,run_id,reservation_id,request_id,model_id,model_manifest_hash,request_digest,budget_revision,max_compute_ms,reserved_output_tokens,reserved_at,receipt_kind,response_kind,input_tokens,output_tokens,compute_ms,peak_memory_bytes) SELECT owner_id,run_id,id,request_id,model_id,model_manifest_hash,body_hash,budget_revision,max_compute_ms,reserved_output_tokens,created_at,'MEASURED','ANSWER',2,3,4,5 FROM inference_reservations WHERE id=?",reservation);
            var historical=db.queryForMap("SELECT * FROM conversation_delete_preparations WHERE id=?",consumed);var ledger=db.queryForList("SELECT * FROM conversation_deletion_ledger");var mutations=db.queryForList("SELECT * FROM conversation_deletion_mutations");var approvals=db.queryForList("SELECT * FROM conversation_delete_approvals");var usage=db.queryForList("SELECT * FROM inference_usage_receipts");var accounting=db.queryForList("SELECT * FROM inference_reservations");var tombstone=db.queryForMap("SELECT * FROM conversations WHERE id=?",deleted);
            migration(ds,"16").migrate();var revoked=db.queryForMap("SELECT * FROM conversation_delete_preparations WHERE id=?",pending);assertNotNull(revoked.get("revoked_at"));assertNull(revoked.get("expected_digest_ref"));assertNull(revoked.get("payload_hash_ref"));
            var after=db.queryForMap("SELECT * FROM conversation_delete_preparations WHERE id=?",consumed);assertEquals(0,after.remove("study_sessions_count"));assertEquals(0,after.remove("study_answers_count"));assertEquals(historical,after);
            assertEquals(ledger,db.queryForList("SELECT * FROM conversation_deletion_ledger"));assertEquals(mutations,db.queryForList("SELECT * FROM conversation_deletion_mutations"));assertEquals(approvals,db.queryForList("SELECT * FROM conversation_delete_approvals"));assertEquals(usage,db.queryForList("SELECT * FROM inference_usage_receipts"));assertEquals(accounting,db.queryForList("SELECT * FROM inference_reservations"));assertEquals(tombstone,db.queryForMap("SELECT * FROM conversations WHERE id=?",deleted));
            assertEquals(0,revoked.get("study_sessions_count"));assertEquals(0,revoked.get("study_answers_count"));
        }
    }
    @Test void failedV16RollsBackEarlierDdlAndDoesNotRevokeOldAuthority() throws Exception {
        try(EmbeddedPostgres postgres=EmbeddedPostgres.builder().setPort(0).start()){
            DataSource ds=postgres.getPostgresDatabase();migration(ds,"15").migrate();var db=new JdbcTemplate(ds);UUID owner=owner(db),conversation=conversation(db,owner),pending=preparation(db,owner,conversation);var before=db.queryForMap("SELECT * FROM conversation_delete_preparations WHERE id=?",pending);
            db.execute("CREATE TABLE study_mutations (synthetic_failure integer)");assertThrows(RuntimeException.class,()->migration(ds,"16").migrate());
            assertNull(db.queryForObject("SELECT to_regclass('public.study_sessions')",String.class));assertNull(db.queryForObject("SELECT to_regclass('public.study_owner_answer_inputs')",String.class));assertEquals(before,db.queryForMap("SELECT * FROM conversation_delete_preparations WHERE id=?",pending));
            assertEquals("15",db.queryForObject("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1",String.class));
        }
    }
}

package dev.myjarvis.core;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Real disposable database migration oracles for CI, not local source-parse evidence. */
class InferenceControlMigrationTest {
    Flyway migration(DataSource ds,String target){return Flyway.configure().dataSource(ds).locations("classpath:db/migration").target(target).load();}
    UUID owner(JdbcTemplate db){UUID id=UUID.randomUUID();db.update("INSERT INTO owners(id,login_name,password_hash) VALUES (?,'control-migration','unused')",id);db.update("INSERT INTO sessions(token_hash,owner_id,csrf_hash,security_epoch) VALUES ('control-migration',?,'fixture',1)",id);return id;}
    UUID conversation(JdbcTemplate db,UUID owner){UUID id=UUID.randomUUID();db.update("INSERT INTO conversations(id,owner_id,title) VALUES (?,?,'Synthetic migration source')",id,owner);return id;}
    UUID preparation(JdbcTemplate db,UUID owner,UUID target){UUID id=UUID.randomUUID();db.update("WITH t AS (SELECT clock_timestamp() AS at) INSERT INTO conversation_delete_preparations(id,owner_id,conversation_id,session_hash,security_epoch,issuance_key,issuance_digest,expected_version,expected_digest_ref,payload_hash_ref,messages_count,runs_count,run_results_count,action_payloads_count,inference_contexts_count,prepared_at,expires_at) SELECT ?,?,?,'control-migration',1,?,?,1,'synthetic snapshot','synthetic authority',0,0,0,0,0,t.at,t.at+interval '300 seconds' FROM t",id,owner,target,UUID.randomUUID(),"a".repeat(64));return id;}
    @Test void allSixteenPublishedMigrationsKeepTheirExactBytes() throws Exception {
        Properties expected=new Properties();try(InputStream in=getClass().getResourceAsStream("/migrations/pre-v17-sha256.properties")){assertNotNull(in);expected.load(in);}assertEquals(16,expected.size());
        for(String name:expected.stringPropertyNames())try(InputStream in=getClass().getResourceAsStream("/db/migration/"+name)){assertNotNull(in);assertEquals(expected.getProperty(name),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes())),name);}
    }
    @Test void cleanMigrationCreatesControlClosureWithoutFeedbackPurpose() throws Exception {
        try(EmbeddedPostgres pg=EmbeddedPostgres.builder().setPort(0).start()){
            migration(pg.getPostgresDatabase(),"17").migrate();var db=new JdbcTemplate(pg.getPostgresDatabase());
            assertEquals("17",db.queryForObject("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1",String.class));
            assertEquals(4L,db.queryForObject("SELECT count(*) FROM pg_trigger WHERE tgname='inference_control_complete'",Long.class));
            assertEquals(0L,db.queryForObject("SELECT count(*) FROM inference_control_slots",Long.class));
            String purpose=db.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname='inference_reservation_purpose'",String.class);assertTrue(purpose.contains("STUDY_LESSON"));assertFalse(purpose.contains("STUDY_FEEDBACK"));
            assertEquals(3L,db.queryForObject("SELECT count(*) FROM pg_trigger WHERE tgname='study_history_complete'",Long.class));
        }
    }
    @Test void upgradeDoesNotBackfillHistoricalControlAndRevokesOldPreparations() throws Exception {
        try(EmbeddedPostgres pg=EmbeddedPostgres.builder().setPort(0).start()){
            DataSource ds=pg.getPostgresDatabase();migration(ds,"16").migrate();var db=new JdbcTemplate(ds);UUID owner=owner(db),target=conversation(db,owner),prep=preparation(db,owner,target),run=UUID.randomUUID(),reservation=UUID.randomUUID(),context=UUID.randomUUID();
            db.update("INSERT INTO runs(id,owner_id,conversation_id,status) VALUES (?,?,?,'QUEUED')",run,owner,target);
            db.update("INSERT INTO inference_reservations(id,owner_id,run_id,request_id,body_hash,model_manifest_hash,status,max_compute_ms,reserved_output_tokens,security_epoch) VALUES (?,?,?,?,?,?,'QUEUED',60000,512,1)",reservation,owner,run,UUID.randomUUID(),"b".repeat(64),"c".repeat(64));
            db.update("INSERT INTO inference_contexts(id,owner_id,run_id,conversation_id,session_hash,security_epoch,context_epoch,history_epoch,state) VALUES (?,?,?,?,'control-migration',1,1,1,'CAPTURED')",context,owner,run,target);
            var oldRun=db.queryForMap("SELECT * FROM runs WHERE id=?",run);var oldContext=db.queryForMap("SELECT * FROM inference_contexts WHERE id=?",context);var oldReservation=db.queryForMap("SELECT * FROM inference_reservations WHERE id=?",reservation);var oldTarget=db.queryForMap("SELECT * FROM conversations WHERE id=?",target);
            migration(ds,"17").migrate();var newRun=db.queryForMap("SELECT * FROM runs WHERE id=?",run);var newContext=db.queryForMap("SELECT * FROM inference_contexts WHERE id=?",context);var newReservation=db.queryForMap("SELECT * FROM inference_reservations WHERE id=?",reservation);
            assertNull(newRun.remove("control_admission_xid"));assertNull(newContext.remove("control_admission_xid"));assertEquals(0,((Number)newReservation.remove("control_revision")).intValue());assertEquals(oldRun,newRun);assertEquals(oldContext,newContext);assertEquals(oldReservation,newReservation);assertEquals(oldTarget,db.queryForMap("SELECT * FROM conversations WHERE id=?",target));assertEquals(0L,db.queryForObject("SELECT count(*) FROM inference_control_slots",Long.class));
            assertThrows(RuntimeException.class,()->db.update("UPDATE runs SET control_admission_xid=pg_current_xact_id()::text WHERE id=?",run));assertThrows(RuntimeException.class,()->db.update("UPDATE inference_contexts SET control_admission_xid=pg_current_xact_id()::text WHERE id=?",context));assertThrows(RuntimeException.class,()->db.update("UPDATE inference_reservations SET control_revision=1 WHERE id=?",reservation));
            var after=db.queryForMap("SELECT * FROM conversation_delete_preparations WHERE id=?",prep);assertNotNull(after.get("revoked_at"));assertNull(after.get("expected_digest_ref"));assertNull(after.get("payload_hash_ref"));
        }
    }
    @Test void failedMigrationRollsBackMarkersRevisionAndPreparationRevocation() throws Exception {
        try(EmbeddedPostgres pg=EmbeddedPostgres.builder().setPort(0).start()){
            DataSource ds=pg.getPostgresDatabase();migration(ds,"16").migrate();var db=new JdbcTemplate(ds);UUID owner=owner(db),target=conversation(db,owner),prep=preparation(db,owner,target);var before=db.queryForMap("SELECT * FROM conversation_delete_preparations WHERE id=?",prep);
            db.execute("CREATE TABLE inference_control_slots (synthetic_failure integer)");assertThrows(RuntimeException.class,()->migration(ds,"17").migrate());
            assertEquals(0L,db.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND column_name IN ('control_admission_xid','control_revision')",Long.class));assertEquals(before,db.queryForMap("SELECT * FROM conversation_delete_preparations WHERE id=?",prep));
            assertEquals("16",db.queryForObject("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1",String.class));
        }
    }
}

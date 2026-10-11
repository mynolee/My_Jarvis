package dev.myjarvis.core;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Actual clean and populated forward migration tests. Execution is a later gate. */
class StudyFeedbackMigrationTest {
    @Test void cleanMigrationInstallsDeferredOwnedFeedbackRelationshipsAndAllLegacyVersions() throws Exception {
        try(var postgres=EmbeddedPostgres.builder().setPort(0).start()){
            String url=postgres.getJdbcUrl("postgres","postgres");Flyway.configure().dataSource(url,"postgres","").locations("classpath:db/migration").load().migrate();
            var db=new JdbcTemplate(new DriverManagerDataSource(url,"postgres",""));
            assertEquals("18",db.queryForObject("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1",String.class));
            assertEquals(18L,db.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success AND version IS NOT NULL",Long.class));
            for(String name:List.of("feedback_owner_conversation_fk","feedback_project_fk","feedback_study_fk","feedback_lesson_fk","feedback_input_target_fk","feedback_context_fk","feedback_reservation_fk","feedback_session_fk"))
                assertEquals(1L,db.queryForObject("SELECT count(*) FROM pg_constraint WHERE conname=?",Long.class,name));
            assertEquals(2L,db.queryForObject("SELECT count(*) FROM pg_constraint WHERE conname IN ('feedback_context_fk','feedback_reservation_fk') AND condeferrable AND condeferred",Long.class));
            assertTrue(db.queryForObject("SELECT count(*) FROM pg_trigger WHERE tgname='study_feedback_complete' AND tgdeferrable AND tginitdeferred",Long.class)>=20);
            for(String[] expected:List.of(
                    new String[]{"feedback_initiating_session","study_feedback_requests","initiating_session_hash",null},
                    new String[]{"feedback_scope_session","study_feedback_requests","phase_invalidation_session_hash","(phase_invalidation_session_hash IS NOT NULL)"},
                    new String[]{"feedback_event_operation_session","study_feedback_events","operation_session_hash","(operation_session_hash IS NOT NULL)"})){
                var index=db.queryForMap("SELECT c.relname AS table_name,i.indnatts,pg_get_indexdef(i.indexrelid,1,true) AS first_column,pg_get_expr(i.indpred,i.indrelid) AS predicate FROM pg_index i JOIN pg_class c ON c.oid=i.indrelid WHERE i.indexrelid=to_regclass(?)",expected[0]);
                assertEquals(expected[1],index.get("table_name"));assertEquals(1,((Number)index.get("indnatts")).intValue());assertEquals(expected[2],index.get("first_column"));assertEquals(expected[3],index.get("predicate"));
            }
            assertEquals(0L,db.queryForObject("SELECT count(*) FROM study_feedback_requests",Long.class));
        }
    }
    @Test void populatedV17KeepsStudyControlAccountingAndConsumedAuthorityBytes() throws Exception {
        try(var postgres=EmbeddedPostgres.builder().setPort(0).start()){
            String url=postgres.getJdbcUrl("postgres","postgres");Flyway.configure().dataSource(url,"postgres","").locations("classpath:db/migration").target("17").load().migrate();
            var source=new DriverManagerDataSource(url,"postgres","");var db=new JdbcTemplate(source);var tx=new TransactionTemplate(new DataSourceTransactionManager(source));
            UUID owner=UUID.randomUUID(),conversation=UUID.randomUUID(),run=UUID.randomUUID(),reservation=UUID.randomUUID(),request=UUID.randomUUID(),context=UUID.randomUUID();String session="synthetic-feedback-migration-session";
            db.update("INSERT INTO owners(id,login_name,password_hash) VALUES (?,'feedback-migration','unused')",owner);
            db.update("INSERT INTO sessions(token_hash,owner_id,csrf_hash,security_epoch) VALUES (?,?,'synthetic',1)",session,owner);
            db.update("INSERT INTO conversations(id,owner_id,title) VALUES (?,?,'Synthetic populated migration')",conversation,owner);
            tx.executeWithoutResult(t->{
                db.update("INSERT INTO runs(id,owner_id,conversation_id,status) VALUES (?,?,?,'QUEUED')",run,owner,conversation);
                db.update("INSERT INTO inference_reservations(id,owner_id,run_id,request_id,body_hash,model_manifest_hash,status,max_compute_ms,reserved_output_tokens,security_epoch,control_revision) VALUES (?,?,?,?,?,?,'QUEUED',60000,512,1,1)",reservation,owner,run,request,"a".repeat(64),"b".repeat(64));
                db.update("INSERT INTO inference_contexts(id,owner_id,run_id,conversation_id,session_hash,security_epoch,context_epoch,history_epoch,state) VALUES (?,?,?,?,?,1,1,1,'CAPTURED')",context,owner,run,conversation,session);
                db.update("INSERT INTO inference_control_slots(reservation_id,owner_id,run_id,request_id,conversation_id,context_id,revision,purpose,origin_binding,security_epoch,created_at) VALUES (?,?,?,?,?,?,1,'CONVERSATION',?,1,clock_timestamp())",reservation,owner,run,request,conversation,context,"c".repeat(64));
            });
            UUID lesson=UUID.randomUUID(),lessonReservation=UUID.randomUUID(),lessonRequest=UUID.randomUUID(),lessonContext=UUID.randomUUID(),study=UUID.randomUUID(),q1=UUID.randomUUID(),q2=UUID.randomUUID();
            tx.executeWithoutResult(t->{
                db.update("INSERT INTO runs(id,owner_id,conversation_id,status,result_ref) VALUES (?,?,?,'SUCCEEDED','synthetic-sealed-lesson')",lesson,owner,conversation);
                db.update("INSERT INTO inference_reservations(id,owner_id,run_id,request_id,body_hash,model_manifest_hash,status,max_compute_ms,reserved_output_tokens,security_epoch,purpose) VALUES (?,?,?,?,?,?,'QUEUED',60000,512,1,'STUDY_LESSON')",lessonReservation,owner,lesson,lessonRequest,"d".repeat(64),"e".repeat(64));
                db.update("INSERT INTO inference_contexts(id,owner_id,run_id,conversation_id,session_hash,security_epoch,context_epoch,history_epoch,state) VALUES (?,?,?,?,?,1,1,1,'CAPTURED')",lessonContext,owner,lesson,conversation,session);
                db.update("UPDATE inference_contexts SET state='DISPATCHED' WHERE id=?",lessonContext);
                db.update("UPDATE inference_reservations SET status='STARTED',started_at=clock_timestamp(),dispatch_context_id=?,dispatch_cache_policy='CONTEXT_V1_NO_RESPONSE_CACHE' WHERE id=?",lessonContext,lessonReservation);
                db.update("UPDATE inference_reservations SET status='SUCCEEDED',completed_at=clock_timestamp() WHERE id=?",lessonReservation);
                db.update("UPDATE inference_contexts SET state='SETTLED' WHERE id=?",lessonContext);
                db.update("INSERT INTO inference_usage_receipts(owner_id,run_id,reservation_id,request_id,model_id,model_manifest_hash,request_digest,budget_revision,max_compute_ms,reserved_output_tokens,reserved_at,receipt_kind,response_kind,input_tokens,output_tokens,compute_ms,peak_memory_bytes) SELECT owner_id,run_id,id,request_id,model_id,model_manifest_hash,body_hash,budget_revision,max_compute_ms,reserved_output_tokens,created_at,'MEASURED','LESSON',2,3,4,5 FROM inference_reservations WHERE id=?",lessonReservation);
                db.update("WITH t AS (SELECT clock_timestamp() AS at) INSERT INTO study_sessions(id,owner_id,conversation_id,lesson_run_id,lesson_run_version,question_1_id,question_2_id,lesson_binding_ref,version,lifecycle,created_at,updated_at) SELECT ?,?,?,?,1,?,?,'synthetic-sealed-binding',1,'OPEN',t.at,t.at FROM t",study,owner,conversation,lesson,q1,q2);
                db.update("INSERT INTO study_mutations(owner_id,idempotency_key,operation,session_hash,security_epoch,request_digest,conversation_id,study_session_id,lesson_run_id,resulting_version,prior_conversation_version,resulting_conversation_version,committed_at) SELECT ?,?,'START',?,1,?,?,?, ?,1,c.content_version,c.content_version+1,s.created_at FROM conversations c JOIN study_sessions s ON s.conversation_id=c.id WHERE c.id=? AND s.id=?",owner,UUID.randomUUID(),session,"f".repeat(64),conversation,study,lesson,conversation,study);
            });
            UUID prepared=UUID.randomUUID(),consumed=UUID.randomUUID();
            db.update("WITH t AS (SELECT clock_timestamp() AS at) INSERT INTO conversation_delete_preparations(id,owner_id,conversation_id,session_hash,security_epoch,issuance_key,issuance_digest,expected_version,expected_digest_ref,payload_hash_ref,messages_count,runs_count,run_results_count,action_payloads_count,inference_contexts_count,prepared_at,expires_at) SELECT ?,?,?,?,1,?,?,1,'synthetic-snapshot','synthetic-authority',0,0,0,0,0,t.at,t.at+interval '300 seconds' FROM t",prepared,owner,conversation,session,UUID.randomUUID(),"a".repeat(64));
            db.update("WITH t AS (SELECT clock_timestamp() AS at) INSERT INTO conversation_delete_preparations(id,owner_id,conversation_id,session_hash,security_epoch,issuance_key,issuance_digest,expected_version,messages_count,runs_count,run_results_count,action_payloads_count,inference_contexts_count,prepared_at,expires_at,consumed_at) SELECT ?,?,?,?,1,?,?,1,0,0,0,0,0,t.at,t.at+interval '300 seconds',t.at FROM t",consumed,owner,conversation,session,UUID.randomUUID(),"b".repeat(64));
            Map<String,List<Map<String,Object>>> before=new LinkedHashMap<>();for(String table:List.of("runs","inference_reservations","inference_contexts","inference_control_slots","study_sessions","study_mutations","inference_usage_receipts"))before.put(table,db.queryForList("SELECT * FROM "+table));
            var consumedBefore=db.queryForMap("SELECT * FROM conversation_delete_preparations WHERE id=?",consumed);
            Flyway.configure().dataSource(url,"postgres","").locations("classpath:db/migration").target("18").load().migrate();
            for(var entry:before.entrySet())assertEquals(entry.getValue(),db.queryForList("SELECT * FROM "+entry.getKey()));
            var retained=db.queryForMap("SELECT * FROM conversation_delete_preparations WHERE id=?",consumed);assertEquals(0,retained.remove("feedback_requests_count"));assertEquals(0,retained.remove("feedback_results_count"));assertEquals(consumedBefore,retained);
            var revoked=db.queryForMap("SELECT * FROM conversation_delete_preparations WHERE id=?",prepared);assertNotNull(revoked.get("revoked_at"));assertNull(revoked.get("expected_digest_ref"));assertNull(revoked.get("payload_hash_ref"));
        }
    }
}

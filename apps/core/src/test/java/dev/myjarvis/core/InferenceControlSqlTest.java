package dev.myjarvis.core;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.sql.Timestamp;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Disposable SQL fixtures only. These rows are not evidence that a real worker stopped. */
class InferenceControlSqlTest {
    EmbeddedPostgres postgres; JdbcTemplate db; TransactionTemplate tx; UUID owner,conversation;
    record Bound(UUID run,UUID reservation,UUID request,UUID context) {}
    @BeforeEach void open() throws Exception {
        postgres=EmbeddedPostgres.builder().setPort(0).start();var ds=postgres.getPostgresDatabase();
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("17").load().migrate();
        db=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));owner=UUID.randomUUID();conversation=UUID.randomUUID();
        db.update("INSERT INTO owners(id,login_name,password_hash) VALUES (?,'control-sql','unused')",owner);
        db.update("INSERT INTO sessions(token_hash,owner_id,csrf_hash,security_epoch) VALUES ('control-sql',?,'synthetic',1)",owner);
        db.update("INSERT INTO conversations(id,owner_id,title) VALUES (?,?,'Synthetic control source')",conversation,owner);
    }
    @AfterEach void close() throws Exception {if(postgres!=null)postgres.close();}
    Timestamp now(){return db.queryForObject("SELECT clock_timestamp()",Timestamp.class);}
    long version(){return db.queryForObject("SELECT content_version FROM conversations WHERE id=?",Long.class,conversation);}
    long slots(){return db.queryForObject("SELECT count(*) FROM inference_control_slots",Long.class);}
    void reject(Runnable work){assertThrows(RuntimeException.class,()->tx.executeWithoutResult(s->{work.run();db.execute("SET CONSTRAINTS ALL IMMEDIATE");}));}
    Bound rows(int revision,boolean slot){
        Bound b=new Bound(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());
        db.update("INSERT INTO runs(id,owner_id,conversation_id,status) VALUES (?,?,?,'QUEUED')",b.run(),owner,conversation);
        db.update("INSERT INTO inference_reservations(id,owner_id,run_id,request_id,body_hash,model_manifest_hash,status,max_compute_ms,reserved_output_tokens,security_epoch,control_revision) VALUES (?,?,?,?,?,?,'QUEUED',60000,512,1,?)",b.reservation(),owner,b.run(),b.request(),"a".repeat(64),"b".repeat(64),revision);
        db.update("INSERT INTO inference_contexts(id,owner_id,run_id,conversation_id,session_hash,security_epoch,context_epoch,history_epoch,state) VALUES (?,?,?,?,'control-sql',1,1,1,'CAPTURED')",b.context(),owner,b.run(),conversation);
        if(slot)slot(b,conversation);return b;
    }
    void slot(Bound b,UUID target){db.update("INSERT INTO inference_control_slots(reservation_id,owner_id,run_id,request_id,conversation_id,context_id,revision,purpose,origin_binding,security_epoch,created_at) VALUES (?,?,?,?,?,?,1,'CONVERSATION',?,1,clock_timestamp())",b.reservation(),owner,b.run(),b.request(),target,b.context(),"c".repeat(64));}
    Bound queued(){return tx.execute(s->{Bound b=rows(1,true);db.execute("SET CONSTRAINTS ALL IMMEDIATE");return b;});}
    void dispatch(Bound b,boolean bind){tx.executeWithoutResult(s->{
        Timestamp at=now();db.update("UPDATE inference_contexts SET state='PREPARED' WHERE id=?",b.context());db.update("UPDATE inference_contexts SET state='DISPATCHED' WHERE id=?",b.context());
        db.update("UPDATE inference_reservations SET status='STARTED',started_at=?,body_hash=?,dispatch_context_id=?,dispatch_cache_policy='CONTEXT_V1_NO_RESPONSE_CACHE' WHERE id=?",at,"d".repeat(64),b.context(),b.reservation());
        db.update("UPDATE runs SET status='RUNNING',version=version+1 WHERE id=?",b.run());
        if(bind)db.update("UPDATE inference_control_slots SET dispatch_commitment=?,bound_at=? WHERE reservation_id=?","e".repeat(64),at,b.reservation());
        db.execute("SET CONSTRAINTS ALL IMMEDIATE");
    });}
    void decision(Bound b){db.update("UPDATE inference_control_slots SET decision='OWNER_CANCEL',decision_at=clock_timestamp() WHERE reservation_id=?",b.reservation());}
    void cancel(Bound b){tx.executeWithoutResult(s->{db.update("UPDATE inference_reservations SET status=CASE WHEN started_at IS NULL THEN 'CANCELLED' ELSE 'UNKNOWN' END,cancel_requested_at=clock_timestamp() WHERE id=?",b.reservation());db.update("UPDATE runs SET status=?,cancel_requested_at=clock_timestamp(),version=version+1 WHERE id=?",db.queryForObject("SELECT status FROM inference_reservations WHERE id=?",String.class,b.reservation()),b.run());decision(b);});}
    void fact(Bound b,UUID generation,UUID ledger){db.update("UPDATE inference_control_slots SET terminal_kind='WORKER_STOPPED',generation_id=?,ledger_instance_id=?,terminal_ordinal=1,recorded_at=clock_timestamp() WHERE reservation_id=?",generation,ledger,b.reservation());}

    @Test void newControlledAdmissionRequiresExactlyOnePreallocatedSlot(){
        long before=version();reject(()->rows(1,false));assertEquals(0,slots());assertEquals(before,version());
        Bound b=queued();assertEquals(1,slots());assertEquals(1,db.queryForObject("SELECT control_revision FROM inference_reservations WHERE id=?",Integer.class,b.reservation()));
    }
    @Test void historicalRevisionCannotBeBackfilledOrGivenASlot(){
        Bound b=tx.execute(s->rows(0,false));reject(()->db.update("UPDATE inference_reservations SET control_revision=1 WHERE id=?",b.reservation()));reject(()->slot(b,conversation));assertEquals(0,slots());
    }
    @Test void contextRunAndConversationIdentityCannotBeSubstituted(){
        UUID other=UUID.randomUUID();db.update("INSERT INTO conversations(id,owner_id,title) VALUES (?,?,'Other synthetic target')",other,owner);
        reject(()->{Bound b=rows(1,false);slot(b,other);});Bound b=queued();
        for(String assignment:List.of("origin_binding='"+"f".repeat(64)+"'","security_epoch=2","purpose='STUDY_LESSON'","conversation_id='"+other+"'"))reject(()->db.update("UPDATE inference_control_slots SET "+assignment+" WHERE reservation_id=?",b.reservation()));
        reject(()->db.update("DELETE FROM inference_control_slots WHERE reservation_id=?",b.reservation()));
    }
    @Test void dispatchWithoutItsProtectedCommitmentRollsBack(){
        Bound b=queued();long before=version();assertThrows(RuntimeException.class,()->dispatch(b,false));assertEquals(before,version());assertNull(db.queryForObject("SELECT started_at FROM inference_reservations WHERE id=?",Timestamp.class,b.reservation()));
        dispatch(b,true);assertNotNull(db.queryForObject("SELECT bound_at FROM inference_control_slots WHERE reservation_id=?",Timestamp.class,b.reservation()));reject(()->db.update("UPDATE inference_control_slots SET dispatch_commitment=? WHERE reservation_id=?","f".repeat(64),b.reservation()));
    }
    @Test void nullableFactFieldsCannotBypassACompleteTerminalTuple(){
        Bound b=queued();dispatch(b,true);
        for(String assignment:List.of("terminal_kind='WORKER_STOPPED'","terminal_kind='COMPLETED',recorded_at=clock_timestamp()","generation_id='"+UUID.randomUUID()+"'","terminal_ordinal=0","terminal_kind='UNSTARTED_CORE',recorded_at=clock_timestamp()"))reject(()->db.update("UPDATE inference_control_slots SET "+assignment+" WHERE reservation_id=?",b.reservation()));
        assertNull(db.queryForObject("SELECT terminal_kind FROM inference_control_slots WHERE reservation_id=?",String.class,b.reservation()));
    }
    @Test void aDecisionRequiresExistingAuthorityLossAndIsImmutable(){
        Bound b=queued();reject(()->decision(b));cancel(b);long before=version();
        assertEquals("OWNER_CANCEL",db.queryForObject("SELECT decision FROM inference_control_slots WHERE reservation_id=?",String.class,b.reservation()));reject(()->db.update("UPDATE inference_control_slots SET decision=NULL,decision_at=NULL WHERE reservation_id=?",b.reservation()));assertEquals(before,version());
    }
    @Test void localUnstartedFactRequiresLockedUndispatchedCancellation(){
        Bound b=queued();reject(()->db.update("UPDATE inference_control_slots SET decision='OWNER_CANCEL',decision_at=clock_timestamp(),terminal_kind='UNSTARTED_CORE',recorded_at=clock_timestamp() WHERE reservation_id=?",b.reservation()));
        cancel(b);long before=version();db.update("UPDATE inference_control_slots SET terminal_kind='UNSTARTED_CORE',recorded_at=clock_timestamp() WHERE reservation_id=?",b.reservation());assertEquals(before,version());
        reject(()->db.update("UPDATE runs SET status='RUNNING' WHERE id=?",b.run()));
    }
    @Test void immutableLateFactsAddNoRowsBytesOrConversationVersion(){
        Bound b=queued();dispatch(b,true);cancel(b);long before=version();var inventory=db.queryForMap("SELECT * FROM conversation_source_inventory_v17(?,?)",owner,conversation);UUID generation=UUID.randomUUID(),ledger=UUID.randomUUID();
        fact(b,generation,ledger);var recorded=db.queryForObject("SELECT recorded_at FROM inference_control_slots WHERE reservation_id=?",Timestamp.class,b.reservation());
        assertEquals(before,version());assertEquals(inventory,db.queryForMap("SELECT * FROM conversation_source_inventory_v17(?,?)",owner,conversation));assertEquals(1,slots());
        assertEquals("UNKNOWN",db.queryForObject("SELECT status FROM inference_reservations WHERE id=?",String.class,b.reservation()));assertEquals(0L,db.queryForObject("SELECT count(*) FROM inference_usage_receipts",Long.class));
        db.update("UPDATE inference_control_slots SET terminal_kind=terminal_kind WHERE reservation_id=?",b.reservation());assertEquals(recorded,db.queryForObject("SELECT recorded_at FROM inference_control_slots WHERE reservation_id=?",Timestamp.class,b.reservation()));
        for(String assignment:List.of("terminal_ordinal=2","terminal_kind='COMPLETED'","generation_id='"+UUID.randomUUID()+"'","recorded_at=recorded_at+interval '1 second'"))reject(()->db.update("UPDATE inference_control_slots SET "+assignment+" WHERE reservation_id=?",b.reservation()));
    }
    @Test void protectedBindingCapacityIsReservedBeforeDispatch(){
        Bound b=queued();var before=db.queryForMap("SELECT * FROM conversation_source_inventory_v17(?,?)",owner,conversation);
        assertEquals(1L,((Number)before.get("control_slots")).longValue());assertEquals(128L+"Synthetic control source".length(),((Number)before.get("source_bytes")).longValue());
        dispatch(b,true);assertEquals(before,db.queryForMap("SELECT * FROM conversation_source_inventory_v17(?,?)",owner,conversation));
        assertEquals(before.get("source_bytes"),db.queryForMap("SELECT * FROM conversation_source_inventory_v16(?,?)",owner,conversation).get("source_bytes"));
    }
    @Test void terminalMetadataDoesNotNeedUnreservedFutureSourceCapacity(){
        // Capacity-only oversized-title fixture, not a public title-admission or deletion claim.
        Bound b=queued();dispatch(b,true);cancel(b);db.update("UPDATE conversations SET title=repeat('x',16777216-128) WHERE id=?",conversation);
        assertEquals(16777216L,((Number)db.queryForMap("SELECT * FROM conversation_source_inventory_v17(?,?)",owner,conversation).get("source_bytes")).longValue());long before=version();
        fact(b,UUID.randomUUID(),UUID.randomUUID());assertEquals(before,version());assertEquals(1,slots());
    }
    @Test void skippedPreparedEdgeAndCancelledFirstBindingAreRejected(){
        Bound b=queued();reject(()->db.update("UPDATE inference_contexts SET state='DISPATCHED' WHERE id=?",b.context()));
        reject(()->{Timestamp at=now();db.update("UPDATE inference_contexts SET state='PREPARED' WHERE id=?",b.context());db.update("UPDATE inference_contexts SET state='DISPATCHED' WHERE id=?",b.context());
            db.update("UPDATE inference_reservations SET status='STARTED',started_at=?,dispatch_context_id=?,dispatch_cache_policy='CONTEXT_V1_NO_RESPONSE_CACHE',cancel_requested_at=? WHERE id=?",at,b.context(),at,b.reservation());
            db.update("UPDATE runs SET status='RUNNING',version=version+1 WHERE id=?",b.run());
            db.update("UPDATE inference_control_slots SET dispatch_commitment=?,bound_at=? WHERE reservation_id=?","e".repeat(64),at,b.reservation());});
        assertNull(db.queryForObject("SELECT bound_at FROM inference_control_slots WHERE reservation_id=?",Timestamp.class,b.reservation()));
    }
    @Test void decisionPrecedenceIsDerivedFromActualLockedAuthorityState(){
        Bound b=queued();db.update("UPDATE owners SET security_epoch=security_epoch+1 WHERE id=?",owner);
        reject(()->decision(b));assertEquals("SECURITY_REVOKED",db.queryForObject("SELECT inference_control_observe_decision(?,?)",String.class,owner,b.reservation()));
        assertEquals("SECURITY_REVOKED",db.queryForObject("SELECT decision FROM inference_control_slots WHERE reservation_id=?",String.class,b.reservation()));
    }
    @Test void newObservationsRejectFutureAndInfiniteTimes(){
        Bound b=queued();dispatch(b,true);cancel(b);
        for(String at:List.of("clock_timestamp()+interval '1 day'","'infinity'::timestamptz","'-infinity'::timestamptz"))
            reject(()->db.update("UPDATE inference_control_slots SET terminal_kind='WORKER_STOPPED',generation_id=?,ledger_instance_id=?,terminal_ordinal=1,recorded_at="+at+" WHERE reservation_id=?",UUID.randomUUID(),UUID.randomUUID(),b.reservation()));
        assertNull(db.queryForObject("SELECT recorded_at FROM inference_control_slots WHERE reservation_id=?",Timestamp.class,b.reservation()));
        reject(()->{Bound fresh=rows(1,false);db.update("INSERT INTO inference_control_slots(reservation_id,owner_id,run_id,request_id,conversation_id,context_id,revision,purpose,origin_binding,security_epoch,created_at) VALUES (?,?,?,?,?,?,1,'CONVERSATION',?,1,clock_timestamp()+interval '1 day')",fresh.reservation(),owner,fresh.run(),fresh.request(),conversation,fresh.context(),"c".repeat(64));});
    }
    @Test void admissionMarkersCannotRefreshHistoricalRunsOrSpoofTransactions(){
        UUID old=UUID.randomUUID();db.update("INSERT INTO runs(id,owner_id,conversation_id,status,control_admission_xid) VALUES (?,?,?,'QUEUED','0')",old,owner,conversation);
        String minted=db.queryForObject("SELECT control_admission_xid FROM runs WHERE id=?",String.class,old);assertNotEquals("0",minted);assertTrue(minted.matches("[0-9]{1,20}"));
        reject(()->db.update("UPDATE runs SET control_admission_xid=pg_current_xact_id()::text WHERE id=?",old));
        reject(()->{Bound b=new Bound(old,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());
            db.update("INSERT INTO inference_reservations(id,owner_id,run_id,request_id,body_hash,model_manifest_hash,status,max_compute_ms,reserved_output_tokens,security_epoch,control_revision) VALUES (?,?,?,?,?,?,'QUEUED',60000,512,1,1)",b.reservation(),owner,old,b.request(),"a".repeat(64),"b".repeat(64));
            db.update("INSERT INTO inference_contexts(id,owner_id,run_id,conversation_id,session_hash,security_epoch,context_epoch,history_epoch,state) VALUES (?,?,?,?,'control-sql',1,1,1,'CAPTURED')",b.context(),owner,old,conversation);slot(b,conversation);});
        assertEquals(minted,db.queryForObject("SELECT control_admission_xid FROM runs WHERE id=?",String.class,old));assertEquals(0,slots());
    }

    @Test void blockedUnstartedProofRequiresInvalidatedContextAndEveryCancelMarker(){
        Bound b=queued();cancel(b);db.update("UPDATE runs SET status='BLOCKED',version=version+1 WHERE id=?",b.run());
        reject(()->db.update("UPDATE inference_control_slots SET terminal_kind='UNSTARTED_CORE',recorded_at=clock_timestamp() WHERE reservation_id=?",b.reservation()));
        db.update("UPDATE inference_contexts SET state='INVALIDATED',invalidated_at=clock_timestamp() WHERE id=?",b.context());long before=version();
        db.update("UPDATE inference_control_slots SET terminal_kind='UNSTARTED_CORE',recorded_at=clock_timestamp() WHERE reservation_id=?",b.reservation());
        assertEquals(before,version());assertEquals("BLOCKED",db.queryForObject("SELECT status FROM runs WHERE id=?",String.class,b.run()));
        reject(()->db.update("UPDATE runs SET cancel_requested_at=NULL WHERE id=?",b.run()));reject(()->db.update("UPDATE inference_reservations SET cancel_requested_at=NULL WHERE id=?",b.reservation()));
    }

    @Test void aggregateRowCapacityIncludesNewSlotAndRollsBackOverflowAdmission(){
        // Capacity-only approvals, never a claim that this synthetic graph permits deletion.
        UUID supportingRun=UUID.randomUUID(),action=UUID.randomUUID();
        db.update("INSERT INTO runs(id,owner_id,conversation_id,status) VALUES (?,?,?,'SUCCEEDED')",supportingRun,owner,conversation);
        db.update("INSERT INTO action_proposals(id,owner_id,run_id,tool_id,tool_version,payload_ref,payload_hash,status,security_epoch,valid_until) VALUES (?,?,?,'calendar.event.create','1','synthetic','synthetic','CANCELLED',1,clock_timestamp())",action,owner,supportingRun);
        long initial=((Number)db.queryForMap("SELECT * FROM conversation_source_inventory_v17(?,?)",owner,conversation).get("scoped_rows")).longValue();
        db.update("INSERT INTO approvals(id,owner_id,action_id,payload_hash,status,expires_at,action_version) SELECT gen_random_uuid(),?,?,md5(g::text),'REJECTED',clock_timestamp()+interval '1 day',1 FROM generate_series(1,?) g",owner,action,(int)(40000-initial-4));
        queued();var before=db.queryForMap("SELECT * FROM conversation_source_inventory_v17(?,?)",owner,conversation);long c=version();
        assertEquals(40000L,((Number)before.get("scoped_rows")).longValue());assertEquals(1L,((Number)before.get("control_slots")).longValue());
        reject(()->rows(1,true));assertEquals(before,db.queryForMap("SELECT * FROM conversation_source_inventory_v17(?,?)",owner,conversation));assertEquals(c,version());
        assertEquals(1L,slots());assertEquals(1L,db.queryForObject("SELECT count(*) FROM inference_contexts",Long.class));assertEquals(1L,db.queryForObject("SELECT count(*) FROM inference_reservations",Long.class));assertEquals(2L,db.queryForObject("SELECT count(*) FROM runs",Long.class));
    }
    @Test void defensiveControlSlotCapRejectsCorruptThousandAndOneWithoutCommit(){
        // Real owned graphs reach equal run/context bounds first. This independent
        // inventory probe deliberately uses invalid identities only inside rollback.
        // No disabled guard, corrupt slot, proof, or source allocation is committed.
        long before=version();var triggers=db.queryForList("SELECT oid,tgname,tgenabled FROM pg_trigger WHERE tgrelid='inference_control_slots'::regclass ORDER BY oid");tx.executeWithoutResult(status->{status.setRollbackOnly();
            db.execute("ALTER TABLE inference_control_slots DISABLE TRIGGER ALL");
            db.update("INSERT INTO inference_control_slots(reservation_id,owner_id,run_id,request_id,conversation_id,context_id,revision,purpose,origin_binding,security_epoch,created_at) SELECT gen_random_uuid(),?,gen_random_uuid(),gen_random_uuid(),?,gen_random_uuid(),1,'CONVERSATION',?,1,clock_timestamp() FROM generate_series(1,1000)",owner,conversation,"c".repeat(64));
            var exact=db.queryForMap("SELECT * FROM conversation_source_inventory_v17(?,?)",owner,conversation);assertEquals(1000L,((Number)exact.get("control_slots")).longValue());assertEquals(1000L,((Number)exact.get("scoped_rows")).longValue());assertEquals(128000L+"Synthetic control source".length(),((Number)exact.get("source_bytes")).longValue());
            db.update("INSERT INTO inference_control_slots(reservation_id,owner_id,run_id,request_id,conversation_id,context_id,revision,purpose,origin_binding,security_epoch,created_at) VALUES (?,?,?, ?,?,?,1,'CONVERSATION',?,1,clock_timestamp())",UUID.randomUUID(),owner,UUID.randomUUID(),UUID.randomUUID(),conversation,UUID.randomUUID(),"c".repeat(64));
            assertThrows(RuntimeException.class,()->db.queryForMap("SELECT * FROM conversation_source_inventory_v17(?,?)",owner,conversation));
        });
        assertEquals(0L,slots());assertEquals(before,version());assertEquals(triggers,db.queryForList("SELECT oid,tgname,tgenabled FROM pg_trigger WHERE tgrelid='inference_control_slots'::regclass ORDER BY oid"));
    }
    @Test void decisionAfterTerminalPreservesFirstFactAndAddsNoCapacityOrVersion(){
        Bound b=queued();dispatch(b,true);UUID generation=UUID.randomUUID(),ledger=UUID.randomUUID();fact(b,generation,ledger);
        var recorded=db.queryForMap("SELECT terminal_kind,generation_id,ledger_instance_id,terminal_ordinal,recorded_at FROM inference_control_slots WHERE reservation_id=?",b.reservation());
        tx.executeWithoutResult(status->{db.update("UPDATE inference_reservations SET status='UNKNOWN',cancel_requested_at=clock_timestamp() WHERE id=?",b.reservation());db.update("UPDATE runs SET status='UNKNOWN',cancel_requested_at=clock_timestamp(),version=version+1 WHERE id=?",b.run());});
        long before=version();var inventory=db.queryForMap("SELECT * FROM conversation_source_inventory_v17(?,?)",owner,conversation);decision(b);
        assertEquals(recorded,db.queryForMap("SELECT terminal_kind,generation_id,ledger_instance_id,terminal_ordinal,recorded_at FROM inference_control_slots WHERE reservation_id=?",b.reservation()));
        assertEquals(Boolean.TRUE,db.queryForObject("SELECT decision_at>=recorded_at FROM inference_control_slots WHERE reservation_id=?",Boolean.class,b.reservation()));assertEquals(before,version());assertEquals(inventory,db.queryForMap("SELECT * FROM conversation_source_inventory_v17(?,?)",owner,conversation));
        assertEquals("UNKNOWN",db.queryForObject("SELECT status FROM runs WHERE id=?",String.class,b.run()));assertEquals(0L,db.queryForObject("SELECT count(*) FROM inference_usage_receipts",Long.class));
    }


}

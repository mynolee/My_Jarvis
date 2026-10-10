package dev.myjarvis.core;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import static org.junit.jupiter.api.Assertions.*;

/** PostgreSQL-backed explicit memory authority/deletion tests; all content synthetic. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemoryServiceTest {
    EmbeddedPostgres postgres;ConfigurableApplicationContext context;JdbcTemplate db;Path key;
    AuthService auth;MemoryService memories;MemoryIntentService intents;MemoryDeletionService deletion;MemoryContextSelector selector;
    AuthService.Session session;UUID owner;
    @BeforeAll void start() throws Exception{
        postgres=EmbeddedPostgres.builder().setPort(0).start();key=Files.createTempFile("jarvis-memory-test-",".bin");byte[] bytes=new byte[32];Vault.RNG.nextBytes(bytes);Files.write(key,bytes);Files.setPosixFilePermissions(key,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));open();
    }
    void open(){context=new SpringApplicationBuilder(CoreApplication.class).web(WebApplicationType.NONE).run("--spring.datasource.url="+postgres.getJdbcUrl("postgres","postgres"),"--spring.datasource.username=postgres","--spring.datasource.password=","--jarvis.key-file="+key,"--jarvis.mode=mock","--jarvis.worker.enabled=false","--spring.main.banner-mode=off");db=context.getBean(JdbcTemplate.class);auth=context.getBean(AuthService.class);memories=context.getBean(MemoryService.class);intents=context.getBean(MemoryIntentService.class);deletion=context.getBean(MemoryDeletionService.class);selector=context.getBean(MemoryContextSelector.class);}
    @AfterAll void stop() throws Exception{if(context!=null)context.close();if(postgres!=null)postgres.close();if(key!=null)Files.deleteIfExists(key);}
    @BeforeEach void reset(){db.execute("TRUNCATE owners,bootstrap_tokens,login_limits,crypto_nonces CASCADE");owner=UUID.randomUUID();db.update("INSERT INTO owners(id,login_name,password_hash) VALUES (?,'memory-owner','synthetic-unused')",owner);session=auth.find(auth.issue(owner,true,null).token());}
    JsonNode node(Object value){return Json.MAPPER.valueToTree(value);}
    UUID id(Map<String,Object> row){return UUID.fromString(row.get("id").toString());}
    long count(String table){return db.queryForObject("SELECT count(*) FROM "+table,Long.class);}
    Map<String,Object> identity(String name,UUID project){Map<String,Object> result=new LinkedHashMap<>();result.put("scope",project==null?"GLOBAL":"PROJECT");result.put("project_id",project);result.put("key",name);return result;}
    JsonNode createArgs(String name,String value,UUID project,long generation,boolean revive){Map<String,Object> result=identity(name,project);result.put("kind","PREFERENCE");result.put("value",value);result.put("change_reason","Synthetic explicit owner form");result.put("expected_generation",generation);result.put("revive_deleted",revive);return node(result);}
    JsonNode reviseArgs(String value,long version,long generation){return node(Map.of("value",value,"change_reason","Synthetic revision reason","expected_version",version,"expected_generation",generation));}
    JsonNode intentBody(UUID target,JsonNode args){Map<String,Object> body=new LinkedHashMap<>();body.put("tool_id","memory.write");body.put("operation",target==null?"CREATE":"REVISE");body.put("target_id",target);body.put("arguments",args);return node(body);}
    JsonNode writeBody(Map<String,Object> intent,JsonNode args){return node(Map.of("intent_id",intent.get("id"),"arguments",args));}
    Map<String,Object> create(String name,String value,UUID project){var args=createArgs(name,value,project,0,false);var intent=intents.issue(session,UUID.randomUUID(),intentBody(null,args));var result=memories.save(session,UUID.randomUUID(),null,writeBody(intent,args),true);return memories.get(session,UUID.fromString(result.get("memory_id").toString()));}
    Map<String,Object> prepare(Map<String,Object> memory){return deletion.prepare(session,UUID.randomUUID(),id(memory),node(Map.of("expected_version",memory.get("version"),"expected_generation",memory.get("generation"))));}
    JsonNode confirmBody(Map<String,Object> prepared){return node(Map.of("payload_hash",prepared.get("payload_hash"),"expected_version",prepared.get("expected_version"),"expected_generation",prepared.get("expected_generation"),"confirm",true));}
    Map<String,Object> remove(Map<String,Object> memory){var prepared=prepare(memory);return deletion.confirm(session,UUID.randomUUID(),id(prepared),confirmBody(prepared));}
    UUID project(){UUID id=UUID.randomUUID();db.update("INSERT INTO projects(id,owner_id,name) VALUES (?,?,?)",id,owner,"Synthetic project");return id;}
    void code(String expected,org.junit.jupiter.api.function.Executable call){assertEquals(expected,assertThrows(ApiException.class,call).code);}

    @Test void createReadEncryptedProvenanceAndMetadataReceipts(){
        var args=createArgs("learning.language","synthetic-secret-value",null,0,false);UUID issuance=UUID.randomUUID(),write=UUID.randomUUID();var body=intentBody(null,args);var intent=intents.issue(session,issuance,body);assertEquals(intent,intents.issue(session,issuance,body));var request=writeBody(intent,args);
        var receipt=memories.save(session,write,null,request,true);assertEquals("COMMITTED",receipt.get("status"));assertFalse(Json.canonical(receipt).contains("synthetic-secret"));assertEquals(receipt,memories.save(session,write,null,request,true));assertEquals(receipt,memories.reconcile(session,write));
        UUID id=UUID.fromString(receipt.get("memory_id").toString());var memory=memories.get(session,id);assertEquals("synthetic-secret-value",memory.get("value"));assertEquals(1L,memory.get("version"));assertEquals(1L,memory.get("generation"));assertEquals("EXPLICIT_OWNER_FORM",((Map<?,?>)memory.get("source")).get("type"));
        for(String table:List.of("memories","memory_revisions","memory_write_intents","memory_mutations","audit_events")){String dump=Json.canonical(db.queryForList("SELECT * FROM "+table));assertFalse(dump.contains("synthetic-secret-value"));assertFalse(dump.contains("learning.language"));}
        assertEquals(1,count("memories"));assertEquals(1,count("memory_revisions"));assertEquals(0,count("task_user_intents"));assertEquals(0,count("runs"));assertEquals(0,count("jobs"));
    }
    @Test void exactArgumentsOneUseAndNoGeneralTaskAuthority(){
        var args=createArgs("one.key","synthetic-one",null,0,false);var intent=intents.issue(session,UUID.randomUUID(),intentBody(null,args));var changed=createArgs("one.key","synthetic-two",null,0,false);
        code("AUTHORITY_NOT_VALID",()->memories.save(session,UUID.randomUUID(),null,writeBody(intent,changed),true));assertEquals(0,count("memories"));
        memories.save(session,UUID.randomUUID(),null,writeBody(intent,args),true);code("INTENT_CONSUMED",()->memories.save(session,UUID.randomUUID(),null,writeBody(intent,args),true));
        var bad=node(Map.of("tool_id","memory.delete","operation","CREATE","target_id",UUID.randomUUID(),"arguments",args));code("INVALID_REQUEST",()->intents.issue(session,UUID.randomUUID(),bad));
        code("INVALID_REQUEST",()->context.getBean(TaskIntentService.class).issue(session,UUID.randomUUID(),node(Map.of("tool_id","memory.write","target_id",UUID.randomUUID(),"arguments",args))));
    }
    @Test void revisePreservesEncryptedHistoryAndStaleVersionFails(){
        var memory=create("revision.key","synthetic-first",null);var args=reviseArgs("synthetic-second",1,1);var intent=intents.issue(session,UUID.randomUUID(),intentBody(id(memory),args));memories.save(session,UUID.randomUUID(),id(memory),writeBody(intent,args),false);
        var current=memories.get(session,id(memory));assertEquals(2L,current.get("version"));assertEquals("synthetic-second",current.get("value"));assertEquals(2,((List<?>)memories.revisions(session,id(memory),null,20).get("items")).size());
        code("PRECONDITION_CHANGED",()->intents.issue(session,UUID.randomUUID(),intentBody(id(memory),args)));assertEquals(2,count("memory_revisions"));
    }
    @Test void deletePurgesAllContentAndOldIntentReplayWithoutLosingMetadata(){
        var args=createArgs("delete.key","synthetic-delete-sentinel",null,0,false);UUID issuance=UUID.randomUUID(),write=UUID.randomUUID();var intent=intents.issue(session,issuance,intentBody(null,args));var writeBody=writeBody(intent,args);var saved=memories.save(session,write,null,writeBody,true);UUID id=UUID.fromString(saved.get("memory_id").toString());var memory=memories.get(session,id);
        var pendingArgs=reviseArgs("synthetic-pending-delete-sentinel",1,1);UUID pendingKey=UUID.randomUUID();var pending=intents.issue(session,pendingKey,intentBody(id,pendingArgs));var prepared=prepare(memory);UUID deleteKey=UUID.randomUUID();var confirmation=confirmBody(prepared);var receipt=deletion.confirm(session,deleteKey,id(prepared),confirmation);
        assertEquals("REDACTED_DELETED",receipt.get("status"));assertEquals(receipt,deletion.confirm(session,deleteKey,id(prepared),confirmation));code("APPROVAL_CONSUMED",()->deletion.confirm(session,UUID.randomUUID(),id(prepared),confirmation));
        assertEquals("REDACTED_DELETED",memories.save(session,write,null,writeBody,true).get("status"));assertEquals("REDACTED_DELETED",memories.reconcile(session,write).get("status"));
        code("NOT_FOUND",()->memories.get(session,id));code("NOT_FOUND",()->memories.revisions(session,id,null,20));code("MEMORY_DELETED",()->intents.issue(session,issuance,intentBody(null,args)));code("MEMORY_DELETED",()->intents.issue(session,pendingKey,intentBody(id,pendingArgs)));code("MEMORY_DELETED",()->memories.save(session,UUID.randomUUID(),id,writeBody(pending,pendingArgs),false));
        assertNull(db.queryForObject("SELECT payload_ref FROM memories WHERE id=?",String.class,id));assertEquals(0,count("memory_revisions"));assertEquals(0,db.queryForObject("SELECT count(*) FROM memory_write_intents WHERE arguments_ref IS NOT NULL OR payload_hash_ref IS NOT NULL",Long.class));assertEquals(0,db.queryForObject("SELECT count(*) FROM memory_delete_preparations WHERE payload_hash_ref IS NOT NULL",Long.class));assertEquals(1,count("memory_deletion_ledger"));assertEquals(1,count("memory_delete_approvals"));
        assertEquals(List.of(),selector.select(session,null));var state=memories.keyState(session,node(identity("delete.key",null)));assertEquals("DELETED",state.get("status"));assertEquals(2L,state.get("generation"));assertNull(state.get("memory_id"));assertNull(state.get("version"));
    }
    @Test void revivalNeedsFreshExplicitSaveAndNeverRestoresPriorGeneration(){
        var old=create("revival.key","synthetic-old",null);remove(old);var stale=createArgs("revival.key","synthetic-new",null,0,false);code("PRECONDITION_CHANGED",()->intents.issue(session,UUID.randomUUID(),intentBody(null,stale)));
        var denied=createArgs("revival.key","synthetic-new",null,2,false);code("PRECONDITION_CHANGED",()->intents.issue(session,UUID.randomUUID(),intentBody(null,denied)));
        var args=createArgs("revival.key","synthetic-new",null,2,true);var intent=intents.issue(session,UUID.randomUUID(),intentBody(null,args));var receipt=memories.save(session,UUID.randomUUID(),null,writeBody(intent,args),true);UUID replacement=UUID.fromString(receipt.get("memory_id").toString());assertNotEquals(id(old),replacement);assertEquals(3L,receipt.get("generation"));assertEquals(1,count("memory_revisions"));code("NOT_FOUND",()->memories.get(session,id(old)));assertEquals("synthetic-new",memories.get(session,replacement).get("value"));
    }
    @Test void projectOverridesAndTombstonesMaskGlobalBeforeLimit(){
        UUID project=project(),other=project();create("same.key","synthetic-global",null);var scoped=create("same.key","synthetic-project",project);create("other.key","synthetic-other-project",other);
        var selected=selector.select(session,project);assertEquals(1,selected.size());assertEquals("synthetic-project",selected.getFirst().get("value"));assertEquals("synthetic-global",selector.select(session,null).getFirst().get("value"));
        remove(scoped);assertEquals(List.of(),selector.select(session,project));assertEquals(1,selector.select(session,null).size());
        for(int i=0;i<25;i++)create("cap.key"+i,"synthetic-cap-"+i,null);assertEquals(20,selector.select(session,project).size());assertEquals(20,selector.select(session,null).size());
        var digests=db.queryForList("SELECT key_digest FROM memory_slots WHERE owner_id=? AND key_digest=?",owner,memories.keys.slot(owner,"same.key"));assertEquals(2,digests.size());
    }
    @Test void ownerProjectFiltersPrecedeDecryption(){
        UUID project=project();var memory=create("owner.key","synthetic-private",project);UUID second=UUID.randomUUID();
        // Only the synthetic test fixture relaxes singleton ownership to exercise isolation.
        db.execute("ALTER TABLE owners DROP CONSTRAINT owners_singleton_key");
        try {
            db.update("INSERT INTO owners(id,login_name,password_hash) VALUES (?,'second-memory-owner','synthetic-unused')",second);var foreign=auth.find(auth.issue(second,true,null).token());
            code("NOT_FOUND",()->memories.get(foreign,id(memory)));code("NOT_FOUND",()->memories.list(foreign,"PROJECT",project,null,20));code("NOT_FOUND",()->selector.select(foreign,project));code("NOT_FOUND",()->intents.issue(foreign,UUID.randomUUID(),intentBody(null,createArgs("owner.key","synthetic",project,0,false))));assertEquals(List.of(),memories.list(foreign,"GLOBAL",null,null,20).get("items"));
            code("INVALID_REQUEST",()->memories.list(session,"GLOBAL",project,null,20));code("INVALID_REQUEST",()->memories.list(session,"PROJECT",null,null,20));assertNotEquals(memories.keys.slot(owner,"owner.key"),memories.keys.slot(second,"owner.key"));
        } finally {
            try { db.update("DELETE FROM sessions WHERE owner_id=?",second); }
            finally {
                try { db.update("DELETE FROM owners WHERE id=?",second); }
                finally { db.execute("ALTER TABLE owners ADD CONSTRAINT owners_singleton_key UNIQUE(singleton)"); }
            }
        }
    }
    @Test void revokedEpochAndSessionCannotReadOrConsume(){
        var args=createArgs("epoch.key","synthetic",null,0,false);var intent=intents.issue(session,UUID.randomUUID(),intentBody(null,args));db.update("UPDATE owners SET security_epoch=security_epoch+1 WHERE id=?",owner);
        code("SESSION_REVOKED",()->memories.save(session,UUID.randomUUID(),null,writeBody(intent,args),true));code("SESSION_REVOKED",()->memories.keyState(session,node(identity("epoch.key",null))));assertEquals(0,count("memories"));
    }
    @Test void reauthenticationMustBeCurrentDatabaseStateAndSameSession(){
        var memory=create("reauth.key","synthetic",null);db.update("UPDATE sessions SET reauthenticated_at=now()-interval '6 minutes' WHERE token_hash=?",session.hash());code("REAUTHENTICATION_REQUIRED",()->prepare(memory));
        db.update("UPDATE sessions SET reauthenticated_at=now() WHERE token_hash=?",session.hash());var prepared=prepare(memory);db.update("UPDATE sessions SET reauthenticated_at=now()-interval '6 minutes' WHERE token_hash=?",session.hash());code("REAUTHENTICATION_REQUIRED",()->deletion.confirm(session,UUID.randomUUID(),id(prepared),confirmBody(prepared)));
        var replacement=auth.find(auth.issue(owner,true,session.hash()).token());code("AUTHORITY_NOT_VALID",()->deletion.confirm(replacement,UUID.randomUUID(),id(prepared),confirmBody(prepared)));assertEquals("synthetic",memories.get(replacement,id(memory)).get("value"));
    }
    @Test void changedVersionInvalidatesDeletePreviewAndConfirm(){
        var memory=create("changed.key","synthetic-v1",null);UUID prepareKey=UUID.randomUUID();var preparationBody=node(Map.of("expected_version",1,"expected_generation",1));var prepared=deletion.prepare(session,prepareKey,id(memory),preparationBody);
        var args=reviseArgs("synthetic-v2",1,1);var intent=intents.issue(session,UUID.randomUUID(),intentBody(id(memory),args));memories.save(session,UUID.randomUUID(),id(memory),writeBody(intent,args),false);
        code("PRECONDITION_CHANGED",()->deletion.prepare(session,prepareKey,id(memory),preparationBody));code("PRECONDITION_CHANGED",()->deletion.confirm(session,UUID.randomUUID(),id(prepared),confirmBody(prepared)));assertEquals(0,count("memory_deletion_ledger"));
    }
    @Test void expiredAndTamperedAuthorityCannotMutate(){
        var args=createArgs("expired.key","synthetic",null,0,false);var intent=intents.issue(session,UUID.randomUUID(),intentBody(null,args));db.update("UPDATE memory_write_intents SET expires_at=now()-interval '1 minute' WHERE id=?",id(intent));code("INTENT_EXPIRED",()->memories.save(session,UUID.randomUUID(),null,writeBody(intent,args),true));
        var memory=create("expires.delete","synthetic",null);var prepared=prepare(memory);var bad=node(Map.of("payload_hash","0".repeat(64),"expected_version",1,"expected_generation",1,"confirm",true));code("PRECONDITION_CHANGED",()->deletion.confirm(session,UUID.randomUUID(),id(prepared),bad));db.update("UPDATE memory_delete_preparations SET expires_at=now()-interval '1 minute' WHERE id=?",id(prepared));code("APPROVAL_EXPIRED",()->deletion.confirm(session,UUID.randomUUID(),id(prepared),confirmBody(prepared)));assertEquals(0,count("memory_deletion_ledger"));
    }
    @Test void pausedOwnerBlocksNewAuthorityAndDeleteButAllowsReadsAndReceipt(){
        var memory=create("pause.key","synthetic",null);var prepared=prepare(memory);db.update("UPDATE owners SET execution_paused=true WHERE id=?",owner);code("EXECUTION_PAUSED",()->deletion.confirm(session,UUID.randomUUID(),id(prepared),confirmBody(prepared)));code("EXECUTION_PAUSED",()->create("new.key","synthetic",null));assertEquals("synthetic",memories.get(session,id(memory)).get("value"));
    }
    @Test void deletionAuditFailureRollsBackPurgeApprovalAndLedger(){
        var memory=create("rollback.key","synthetic-preserved",null);var pendingArgs=reviseArgs("synthetic-pending",1,1);intents.issue(session,UUID.randomUUID(),intentBody(id(memory),pendingArgs));var prepared=prepare(memory);
        db.execute("ALTER TABLE audit_events ADD CONSTRAINT memory_test_failure CHECK(event_type<>'MEMORY_DELETED')");try{assertThrows(Exception.class,()->deletion.confirm(session,UUID.randomUUID(),id(prepared),confirmBody(prepared)));assertEquals("synthetic-preserved",memories.get(session,id(memory)).get("value"));assertEquals(1,count("memory_revisions"));assertEquals(0,count("memory_delete_approvals"));assertEquals(0,count("memory_deletion_ledger"));assertEquals(2,db.queryForObject("SELECT count(*) FROM memory_write_intents WHERE arguments_ref IS NOT NULL",Long.class));}finally{db.execute("ALTER TABLE audit_events DROP CONSTRAINT memory_test_failure");}
        assertEquals("REDACTED_DELETED",deletion.confirm(session,UUID.randomUUID(),id(prepared),confirmBody(prepared)).get("status"));
    }
    @Test void restartDoesNotReviveDeletedDataOrReplays(){
        var args=createArgs("restart.key","synthetic-restart-sentinel",null,0,false);UUID write=UUID.randomUUID();var intent=intents.issue(session,UUID.randomUUID(),intentBody(null,args));var receipt=memories.save(session,write,null,writeBody(intent,args),true);UUID id=UUID.fromString(receipt.get("memory_id").toString());remove(memories.get(session,id));context.close();open();
        code("NOT_FOUND",()->memories.get(session,id));assertEquals(List.of(),selector.select(session,null));assertEquals("REDACTED_DELETED",memories.reconcile(session,write).get("status"));assertEquals("DELETED",memories.keyState(session,node(identity("restart.key",null))).get("status"));assertEquals(0,count("memory_revisions"));
    }
    @Test void concurrentSameKeyHasExactlyOneLogicalMutation() throws Exception{
        var args=createArgs("concurrent.key","synthetic",null,0,false);var intent=intents.issue(session,UUID.randomUUID(),intentBody(null,args));UUID write=UUID.randomUUID();try(var pool=Executors.newFixedThreadPool(6)){List<Callable<Map<String,Object>>> jobs=new ArrayList<>();for(int i=0;i<6;i++)jobs.add(()->memories.save(session,write,null,writeBody(intent,args),true));Object first=null;for(var future:pool.invokeAll(jobs,30,TimeUnit.SECONDS)){assertFalse(future.isCancelled());var result=future.get();if(first==null)first=result;else assertEquals(first,result);}}assertEquals(1,count("memories"));assertEquals(1,count("memory_mutations"));assertEquals(1,count("memory_revisions"));
    }
    @Test void deleteVersusReviseRaceHasOneWinnerWithoutResurrection() throws Exception{
        var memory=create("race.key","synthetic-original",null);var prepared=prepare(memory);var args=reviseArgs("synthetic-revised",1,1);var intent=intents.issue(session,UUID.randomUUID(),intentBody(id(memory),args));
        try(var pool=Executors.newFixedThreadPool(2)){var jobs=List.<Callable<String>>of(()->{try{deletion.confirm(session,UUID.randomUUID(),id(prepared),confirmBody(prepared));return "DELETE";}catch(ApiException e){return e.code;}},()->{try{memories.save(session,UUID.randomUUID(),id(memory),writeBody(intent,args),false);return "REVISE";}catch(ApiException e){return e.code;}});List<String> results=new ArrayList<>();for(var future:pool.invokeAll(jobs,30,TimeUnit.SECONDS)){assertFalse(future.isCancelled());results.add(future.get());}assertEquals(1,results.stream().filter(x->x.equals("DELETE")||x.equals("REVISE")).count());if(results.contains("DELETE")){code("NOT_FOUND",()->memories.get(session,id(memory)));assertEquals(0,count("memory_revisions"));}else assertEquals("synthetic-revised",memories.get(session,id(memory)).get("value"));}
    }
    @Test void strictUnicodeLimitsAndSafeIntegers(){
        for(String value:List.of(""," ","x\u0000y","x".repeat(2001),"\ud800")){var args=createArgs("valid.key",value,null,0,false);code("INVALID_REQUEST",()->intents.issue(session,UUID.randomUUID(),intentBody(null,args)));}
        for(String name:List.of("Invalid.key","contains space","é.key","x".repeat(97))){var args=createArgs(name,"synthetic",null,0,false);code("INVALID_REQUEST",()->intents.issue(session,UUID.randomUUID(),intentBody(null,args)));}
        for(long generation:List.of(-1L,9007199254740992L)){var args=createArgs("valid.key","synthetic",null,generation,false);code("INVALID_REQUEST",()->intents.issue(session,UUID.randomUUID(),intentBody(null,args)));}
        var args=createArgs("valid.key","😀".repeat(2000),null,0,false);assertDoesNotThrow(()->intents.issue(session,UUID.randomUUID(),intentBody(null,args)));assertEquals(0,count("memories"));
    }
    @SuppressWarnings("unchecked") @Test void signedKeysetPagesCannotCrossOwnerScopeOrResource(){
        for(int i=0;i<7;i++)create("page.key"+i,"synthetic",null);db.update("UPDATE memories SET created_at='2026-01-01T00:00:00Z'");Set<Object> ids=new HashSet<>();String cursor=null,first=null;do{var page=memories.list(session,"GLOBAL",null,cursor,2);for(var row:(List<Map<String,Object>>)page.get("items"))assertTrue(ids.add(row.get("id")));cursor=(String)page.get("next_cursor");if(first==null)first=cursor;}while(cursor!=null);assertEquals(7,ids.size());final String valid=first;UUID project=project();code("INVALID_REQUEST",()->memories.list(session,"PROJECT",project,valid,2));code("INVALID_REQUEST",()->memories.list(session,"GLOBAL",null,"x"+valid,2));
    }
    @Test void authorityCommitmentsAreEncryptedAndPurged(){
        var args=createArgs("commitment.key","synthetic-low-entropy",null,0,false);var intent=intents.issue(session,UUID.randomUUID(),intentBody(null,args));
        String stored=db.queryForObject("SELECT payload_hash_ref FROM memory_write_intents WHERE id=?",String.class,id(intent));assertNotEquals(intent.get("payload_hash"),stored);assertFalse(stored.contains(intent.get("payload_hash").toString()));
        var receipt=memories.save(session,UUID.randomUUID(),null,writeBody(intent,args),true);var memory=memories.get(session,UUID.fromString(receipt.get("memory_id").toString()));var prepared=prepare(memory);
        stored=db.queryForObject("SELECT payload_hash_ref FROM memory_delete_preparations WHERE id=?",String.class,id(prepared));assertNotEquals(prepared.get("payload_hash"),stored);assertFalse(stored.contains(prepared.get("payload_hash").toString()));
        deletion.confirm(session,UUID.randomUUID(),id(prepared),confirmBody(prepared));assertNull(db.queryForObject("SELECT payload_hash_ref FROM memory_write_intents WHERE id=?",String.class,id(intent)));assertNull(db.queryForObject("SELECT payload_hash_ref FROM memory_delete_preparations WHERE id=?",String.class,id(prepared)));
    }
    @Test void oldWriteReceiptRemainsRedactedAfterExplicitRevival(){
        var args=createArgs("receipt.key","synthetic-old",null,0,false);var intent=intents.issue(session,UUID.randomUUID(),intentBody(null,args));UUID key=UUID.randomUUID();var body=writeBody(intent,args);var receipt=memories.save(session,key,null,body,true);UUID old=UUID.fromString(receipt.get("memory_id").toString());remove(memories.get(session,old));
        var freshArgs=createArgs("receipt.key","synthetic-new",null,2,true);var fresh=intents.issue(session,UUID.randomUUID(),intentBody(null,freshArgs));memories.save(session,UUID.randomUUID(),null,writeBody(fresh,freshArgs),true);
        var replay=memories.save(session,key,null,body,true);assertEquals("REDACTED_DELETED",replay.get("status"));assertEquals(old,replay.get("memory_id"));assertEquals(1L,replay.get("generation"));assertFalse(Json.canonical(replay).contains("synthetic"));
    }
    @Test void ownerLockSerializesRotationAndLogoutBeforeDeleteConfirmation() throws Exception{
        for(boolean rotate:List.of(false,true)){
            session=auth.find(auth.issue(owner,true,null).token());final AuthService.Session current=session;var memory=create(rotate?"race.rotation":"race.logout","synthetic",null);var prepared=prepare(memory);CountDownLatch started=new CountDownLatch(1);
            try(var pool=Executors.newSingleThreadExecutor()){
                java.util.concurrent.atomic.AtomicReference<Future<String>> pending=new java.util.concurrent.atomic.AtomicReference<>();
                memories.writeTx.executeWithoutResult(tx->{
                    db.queryForObject("SELECT id FROM owners WHERE id=? FOR UPDATE",UUID.class,owner);
                    pending.set(pool.submit(()->{started.countDown();try{deletion.confirm(current,UUID.randomUUID(),id(prepared),confirmBody(prepared));return "DELETED";}catch(ApiException e){return e.code;}}));
                    try{assertTrue(started.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                    if(rotate)auth.issue(owner,true,current.hash());else auth.logout(current);
                });
                assertEquals("SESSION_REVOKED",pending.get().get(15,TimeUnit.SECONDS));assertEquals(0,count("memory_deletion_ledger"));assertNotNull(db.queryForObject("SELECT payload_ref FROM memories WHERE id=?",String.class,id(memory)));
            }
        }
    }
    @SuppressWarnings("unchecked") @Test void sharedCanonicalHashVectorsMatchBothAuthorityBuilders() throws Exception{
        var vectors=Json.parse(Files.readAllBytes(Path.of("../../packages/contracts/fixtures/memories/hash-vectors.json"))).get("vectors");
        for(var vector:vectors){var payload=vector.get("payload");assertEquals(vector.get("canonical").asText(),Json.canonical(payload));assertEquals(vector.get("sha256").asText(),Json.hash(Json.canonical(payload)));
            var bound=new AuthService.Session(payload.get("session_hash").asText(),Json.uuid(payload,"owner_id"),Instant.now(),Json.integer(payload,"security_epoch"));Instant expiry=Instant.parse(payload.get("expires_at").asText());String actual;
            if(vector.get("name").asText().equals("write"))actual=MemoryIntentService.hash(bound,Json.uuid(payload,"intent_id"),Json.uuid(payload,"target_id"),payload.get("operation").asText(),payload.get("arguments"),expiry);
            else actual=MemoryDeletionService.payloadHash(bound,Json.uuid(payload,"preparation_id"),Json.uuid(payload,"memory_id"),Json.uuid(payload,"slot_id"),Json.integer(payload,"expected_version"),Json.integer(payload,"expected_generation"),Json.MAPPER.convertValue(vector.get("snapshot"),Map.class),expiry);
            assertEquals(vector.get("sha256").asText(),actual);
        }
    }

    @Test void explicitUnicodeWhitespaceMatchesBothTextFieldsAndPreservesNonblankControls(){
        int[] blanks={0x09,0x0A,0x0B,0x0C,0x0D,0x20,0x85,0xA0,0x1680,0x2000,0x2001,0x2002,0x2003,0x2004,0x2005,0x2006,0x2007,0x2008,0x2009,0x200A,0x2028,0x2029,0x202F,0x205F,0x3000,0xFEFF};
        for(int cp:blanks){String text=new String(Character.toChars(cp));for(String field:List.of("value","change_reason")){var args=(tools.jackson.databind.node.ObjectNode)createArgs("whitespace.key","synthetic",null,0,false);args.put(field,text);code("INVALID_REQUEST",()->MemoryService.arguments(args,true));args.put(field,text+"x"+text);assertDoesNotThrow(()->MemoryService.arguments(args,true));assertEquals(text+"x"+text,args.get(field).asText());}}
        for(int cp=0x1C;cp<=0x1F;cp++){String text=new String(Character.toChars(cp));var args=(tools.jackson.databind.node.ObjectNode)createArgs("control.key",text,null,0,false);args.put("change_reason",text);assertDoesNotThrow(()->MemoryService.arguments(args,true));}
    }
    @Test void databaseClockControlsReauthExpiryAndTimestampsDespiteApplicationClockSkew(){
        Instant real=memories.databaseNow();int index=0;
        for(Instant skew:List.of(real.minusSeconds(86400),real.plusSeconds(86400))){
            int suffix=index++;db.update("UPDATE sessions SET reauthenticated_at=clock_timestamp() WHERE token_hash=?",session.hash());
            try(var mocked=org.mockito.Mockito.mockStatic(Instant.class,org.mockito.Mockito.CALLS_REAL_METHODS)){
                mocked.when(Instant::now).thenReturn(skew);
                var args=createArgs("clock.key"+suffix,"synthetic-clock",null,0,false);var intent=intents.issue(session,UUID.randomUUID(),intentBody(null,args));Instant expiry=Instant.parse(intent.get("expires_at").toString());long remaining=java.time.Duration.between(memories.databaseNow(),expiry).toSeconds();assertTrue(remaining>=290&&remaining<=300);
                Instant before=memories.databaseNow();var saved=memories.save(session,UUID.randomUUID(),null,writeBody(intent,args),true);var memory=memories.get(session,UUID.fromString(saved.get("memory_id").toString()));Instant confirmed=Instant.parse(memory.get("confirmed_at").toString());assertFalse(confirmed.isBefore(before));assertFalse(confirmed.isAfter(memories.databaseNow()));
                var prepared=prepare(memory);expiry=Instant.parse(prepared.get("expires_at").toString());remaining=java.time.Duration.between(memories.databaseNow(),expiry).toSeconds();assertTrue(remaining>=290&&remaining<=300);
                db.update("UPDATE sessions SET reauthenticated_at=clock_timestamp()-interval '301 seconds' WHERE token_hash=?",session.hash());code("REAUTHENTICATION_REQUIRED",()->deletion.confirm(session,UUID.randomUUID(),id(prepared),confirmBody(prepared)));
                db.update("UPDATE sessions SET reauthenticated_at=clock_timestamp()+interval '301 seconds' WHERE token_hash=?",session.hash());code("REAUTHENTICATION_REQUIRED",()->prepare(memory));
                db.update("UPDATE sessions SET reauthenticated_at=clock_timestamp() WHERE token_hash=?",session.hash());db.update("UPDATE memory_delete_preparations SET expires_at=clock_timestamp()-interval '1 second' WHERE id=?",id(prepared));code("APPROVAL_EXPIRED",()->deletion.confirm(session,UUID.randomUUID(),id(prepared),confirmBody(prepared)));
                var nextArgs=createArgs("clock.pending"+suffix,"synthetic-pending",null,0,false);var pending=intents.issue(session,UUID.randomUUID(),intentBody(null,nextArgs));db.update("UPDATE memory_write_intents SET expires_at=clock_timestamp()-interval '1 second' WHERE id=?",id(pending));code("INTENT_EXPIRED",()->memories.save(session,UUID.randomUUID(),null,writeBody(pending,nextArgs),true));
            }
        }
    }

}

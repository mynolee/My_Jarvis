package dev.myjarvis.core;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL transactions; all content is synthetic and no native model is used. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TaskSecurityTest {
    EmbeddedPostgres postgres;ConfigurableApplicationContext context;JdbcTemplate db;AuthService auth;
    TaskService tasks;TaskIntentService intents;WorkflowService flow;Path key;AuthService.Session session;UUID owner;
    @BeforeAll void start()throws Exception{
        postgres=EmbeddedPostgres.builder().setPort(0).start();
        key=Files.createTempFile("jarvis-task-test-key-",".bin");byte[] bytes=new byte[32];Vault.RNG.nextBytes(bytes);Files.write(key,bytes);Files.setPosixFilePermissions(key,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        context=new SpringApplicationBuilder(CoreApplication.class).web(WebApplicationType.NONE).run("--spring.datasource.url="+postgres.getJdbcUrl("postgres","postgres"),"--spring.datasource.username=postgres","--spring.datasource.password=","--jarvis.key-file="+key,"--jarvis.mode=mock","--jarvis.worker.enabled=false","--spring.main.banner-mode=off");
        db=context.getBean(JdbcTemplate.class);auth=context.getBean(AuthService.class);tasks=context.getBean(TaskService.class);intents=context.getBean(TaskIntentService.class);flow=context.getBean(WorkflowService.class);
        int version=Integer.parseInt(db.queryForObject("SHOW server_version_num",String.class));assertTrue(version>=170000&&version<180000);
    }
    @AfterAll void stop()throws Exception{if(context!=null)context.close();if(postgres!=null)postgres.close();if(key!=null)Files.deleteIfExists(key);}
    @BeforeEach void reset(){
        db.execute("TRUNCATE owners,bootstrap_tokens,login_limits,crypto_nonces CASCADE");owner=UUID.randomUUID();db.update("INSERT INTO owners(id,login_name,password_hash) VALUES (?,'task-owner','unused-synthetic')",owner);
        session=auth.find(auth.issue(owner,true,null).token());
    }
    JsonNode json(Object value){return Json.MAPPER.valueToTree(value);}
    Map<String,Object> args(){var a=new LinkedHashMap<String,Object>();a.put("title","Synthetic task");a.put("description","synthetic private description");a.put("project_id",null);a.put("due_at",null);a.put("priority","NORMAL");a.put("estimated_minutes",null);return a;}
    JsonNode issueBody(UUID target,Map<String,Object> args){var body=new LinkedHashMap<String,Object>();body.put("tool_id",target==null?"task.create":"task.update");body.put("target_id",target==null?null:target.toString());body.put("arguments",args);return json(body);}
    Map<String,Object> issue(UUID target,Map<String,Object> args){return intents.issue(session,UUID.randomUUID(),issueBody(target,args));}
    JsonNode mutation(Map<String,Object> intent,Map<String,Object> args){var body=new LinkedHashMap<>(args);body.put("user_intent_id",intent.get("id").toString());return json(body);}
    UUID id(Map<String,Object> row){return UUID.fromString(row.get("id").toString());}
    Map<String,Object> create(){var a=args();var intent=issue(null,a);return tasks.create(session,UUID.randomUUID(),mutation(intent,a));}
    long count(String table){return db.queryForObject("SELECT count(*) FROM "+table,Long.class);}
    void same(Object a,Object b){assertEquals(Json.canonical(a),Json.canonical(b));}

    @Test void canonicalIntentMatchesSharedFixture()throws Exception{
        Path fixture=Path.of("../../packages/contracts/fixtures/tasks/intent-hash-vector.json");
        var vector=Json.parse(Files.readAllBytes(fixture));var payload=vector.get("payload");
        assertEquals(vector.get("canonical").asText(),Json.canonical(payload));
        assertEquals(vector.get("sha256").asText(),TaskIntentService.payloadHash(payload.get("tool_id").asText(),UUID.fromString(payload.get("target_id").asText()),payload.get("arguments")));
    }
    @Test void createsEncryptedTaskAndNeverCreatesRunOrGenericAuthority(){
        var a=args();var intent=issue(null,a);assertEquals("ISSUED",intent.get("status"));assertEquals(0,count("tasks"));assertEquals(0,count("authorizations"));
        var task=tasks.create(session,UUID.randomUUID(),mutation(intent,a));assertEquals(intent.get("target_id"),task.get("id"));assertEquals("TODO",task.get("status"));assertEquals(1L,task.get("version"));assertEquals(a.get("description"),task.get("description"));
        assertFalse(db.queryForObject("SELECT description_ref FROM tasks",String.class).contains("synthetic private"));
        assertFalse(db.queryForObject("SELECT response_ref FROM task_user_intents",String.class).contains("synthetic private"));
        assertFalse(db.queryForObject("SELECT response_ref FROM task_mutations",String.class).contains("synthetic private"));assertEquals(0,count("runs"));assertEquals(0,count("jobs"));
        assertEquals(3,count("audit_events"));
    }
    @Test void issuanceAndMutationHaveIndependentExactIdempotencyAndOriginalSnapshots(){
        var a=args();UUID issuanceKey=UUID.randomUUID();var intent=intents.issue(session,issuanceKey,issueBody(null,a));same(intent,intents.issue(session,issuanceKey,issueBody(null,a)));
        var changed=new LinkedHashMap<>(a);changed.put("title","Changed");assertEquals(409,assertThrows(ApiException.class,()->intents.issue(session,issuanceKey,issueBody(null,changed))).status);
        UUID mutationKey=UUID.randomUUID();JsonNode body=mutation(intent,a);var original=tasks.create(session,mutationKey,body);same(original,tasks.create(session,mutationKey,body));
        var patch=new LinkedHashMap<String,Object>();patch.put("expected_version",1);patch.put("status","DONE");var update=issue(id(original),patch);tasks.update(session,UUID.randomUUID(),id(original),mutation(update,patch));
        db.update("UPDATE task_user_intents SET expires_at=now()-interval '1 minute'");same(original,tasks.create(session,mutationKey,body));
        var receipt=tasks.reconcile(session,mutationKey);assertEquals("COMMITTED",receipt.get("status"));same(original,receipt.get("task"));assertEquals("DONE",tasks.get(session,id(original)).get("status"));
        assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(ApiException.class,()->tasks.create(session,mutationKey,mutation(intent,changed))).code);
        assertEquals("INTENT_CONSUMED",assertThrows(ApiException.class,()->tasks.create(session,UUID.randomUUID(),body)).code);assertEquals(1,count("tasks"));
    }
    @Test void exactBindingRejectsContentTargetAndToolTampering(){
        var a=args();var intent=issue(null,a);var tampered=new LinkedHashMap<>(a);tampered.put("description","tampered");assertEquals(403,assertThrows(ApiException.class,()->tasks.create(session,UUID.randomUUID(),mutation(intent,tampered))).status);assertEquals(0,count("tasks"));
        var task=tasks.create(session,UUID.randomUUID(),mutation(intent,a));var other=create();var patch=Map.<String,Object>of("expected_version",1,"status","DONE");var update=issue(id(task),patch);
        assertEquals(403,assertThrows(ApiException.class,()->tasks.update(session,UUID.randomUUID(),id(other),mutation(update,patch))).status);
        assertEquals("TODO",tasks.get(session,id(other)).get("status"));
    }
    @Test void concurrentIdenticalCreateCommitsExactlyOnce()throws Exception{
        var a=args();var intent=issue(null,a);UUID key=UUID.randomUUID();JsonNode body=mutation(intent,a);
        try(var pool=Executors.newFixedThreadPool(12)){
            List<Callable<Map<String,Object>>> work=new ArrayList<>();for(int i=0;i<12;i++)work.add(()->tasks.create(session,key,body));
            Object first=null;for(var result:pool.invokeAll(work,30,TimeUnit.SECONDS)){assertFalse(result.isCancelled());Object value=result.get();if(first==null)first=value;else same(first,value);}
        }
        assertEquals(1,count("tasks"));assertEquals(1,count("task_mutations"));assertEquals(1,db.queryForObject("SELECT count(*) FROM task_user_intents WHERE consumed_at IS NOT NULL",Long.class));
        assertEquals(1,db.queryForObject("SELECT count(*) FROM audit_events WHERE event_type='TASK_CREATED'",Long.class));
    }
    @Test void concurrentDifferentKeysCannotReuseIntent()throws Exception{
        var a=args();JsonNode body=mutation(issue(null,a),a);
        try(var pool=Executors.newFixedThreadPool(8)){List<Callable<Boolean>> work=new ArrayList<>();for(int i=0;i<8;i++)work.add(()->{try{tasks.create(session,UUID.randomUUID(),body);return true;}catch(ApiException e){assertEquals("INTENT_CONSUMED",e.code);return false;}});
            long success=0;for(var result:pool.invokeAll(work))if(result.get())success++;assertEquals(1,success);}
        assertEquals(1,count("tasks"));assertEquals(1,count("task_mutations"));
    }
    @Test void twoDevicesConflictReturnsOwnedLatestAndDoesNotConsumeLosingIntent()throws Exception{
        var task=create();UUID id=id(task);var first=Map.<String,Object>of("expected_version",1,"title","first");var second=Map.<String,Object>of("expected_version",1,"title","second");
        var a=issue(id,first);AuthService.Session secondSession=auth.find(auth.issue(owner,true,null).token());var b=intents.issue(secondSession,UUID.randomUUID(),issueBody(id,second));
        try(var pool=Executors.newFixedThreadPool(2)){var work=List.<Callable<Boolean>>of(
            ()->{try{tasks.update(session,UUID.randomUUID(),id,mutation(a,first));return true;}catch(TaskService.TaskConflict conflict){assertEquals(2L,conflict.currentTask.get("version"));return false;}},
            ()->{try{tasks.update(secondSession,UUID.randomUUID(),id,mutation(b,second));return true;}catch(TaskService.TaskConflict conflict){assertEquals(2L,conflict.currentTask.get("version"));return false;}});
            int success=0;for(var result:pool.invokeAll(work))if(result.get())success++;assertEquals(1,success);}
        assertEquals(2L,tasks.get(session,id).get("version"));assertEquals(1,db.queryForObject("SELECT count(*) FROM task_user_intents WHERE tool_id='task.update' AND consumed_at IS NULL",Long.class));
    }
    @Test void explicitCompleteCancelAndReopenRemainIndependentOfRuns(){
        var task=create();UUID id=id(task);long version=1;
        for(String status:List.of("IN_PROGRESS","DONE","TODO","CANCELLED","IN_PROGRESS")){var args=Map.<String,Object>of("expected_version",version,"status",status);var intent=issue(id,args);task=tasks.update(session,UUID.randomUUID(),id,mutation(intent,args));assertEquals(status,task.get("status"));assertEquals(++version,task.get("version"));}
        assertEquals(0,count("runs"));assertEquals(0,count("jobs"));
    }
    @Test void foreignOwnerSessionExpiryLogoutAndEpochFailClosed(){
        var a=args();var intent=issue(null,a);JsonNode body=mutation(intent,a);AuthService.Session other=auth.find(auth.issue(owner,true,null).token());
        assertEquals(403,assertThrows(ApiException.class,()->tasks.create(other,UUID.randomUUID(),body)).status);
        var forged=new AuthService.Session(session.hash(),UUID.randomUUID(),session.reauth(),session.epoch());assertEquals(401,assertThrows(ApiException.class,()->tasks.create(forged,UUID.randomUUID(),body)).status);
        db.update("UPDATE task_user_intents SET expires_at=now()-interval '1 second'");assertEquals("INTENT_EXPIRED",assertThrows(ApiException.class,()->tasks.create(session,UUID.randomUUID(),body)).code);
        db.update("UPDATE task_user_intents SET expires_at=now()+interval '5 minutes'");auth.logout(session);assertEquals(401,assertThrows(ApiException.class,()->tasks.create(session,UUID.randomUUID(),body)).status);assertEquals(0,count("tasks"));
    }
    @Test void pauseRevokesPreviouslyIssuedAuthorityEvenAfterResume(){
        var a=args();var intent=issue(null,a);var stale=session;flow.pause(session,true);
        assertEquals(401,assertThrows(ApiException.class,()->tasks.create(stale,UUID.randomUUID(),mutation(intent,a))).status);
        session=new AuthService.Session(stale.hash(),owner,stale.reauth(),stale.epoch()+1);
        assertEquals("EXECUTION_PAUSED",assertThrows(ApiException.class,()->issue(null,a)).code);
        flow.pause(session,false);session=new AuthService.Session(stale.hash(),owner,stale.reauth(),stale.epoch()+2);
        assertEquals("INTENT_NOT_VALID",assertThrows(ApiException.class,()->tasks.create(session,UUID.randomUUID(),mutation(intent,a))).code);assertEquals(0,count("tasks"));
    }
    @Test void auditFailureRollsBackTaskIntentConsumptionAndReceipt(){
        var a=args();var intent=issue(null,a);UUID key=UUID.randomUUID();db.execute("ALTER TABLE audit_events ADD CONSTRAINT task_test_fail CHECK(event_type <> 'TASK_CREATED')");
        try{assertThrows(RuntimeException.class,()->tasks.create(session,key,mutation(intent,a)));assertEquals(0,count("tasks"));assertEquals(0,count("task_mutations"));assertEquals(0,db.queryForObject("SELECT count(*) FROM task_user_intents WHERE consumed_at IS NOT NULL",Long.class));}
        finally{db.execute("ALTER TABLE audit_events DROP CONSTRAINT task_test_fail");}
        assertEquals("NOT_OBSERVED",tasks.reconcile(session,key).get("status"));assertEquals("TODO",tasks.create(session,key,mutation(intent,a)).get("status"));
    }
    @Test void absoluteAndIdleSessionExpiryPreventWritesAndReceiptDisclosure(){
        var a=args();var intent=issue(null,a);UUID key=UUID.randomUUID();var body=mutation(intent,a);tasks.create(session,key,body);
        db.update("UPDATE sessions SET last_seen_at=now()-interval '3 hours' WHERE token_hash=?",session.hash());
        assertEquals(401,assertThrows(ApiException.class,()->tasks.create(session,key,body)).status);
        assertEquals(401,assertThrows(ApiException.class,()->tasks.reconcile(session,key)).status);
        db.update("UPDATE sessions SET last_seen_at=now(),created_at=now()-interval '13 hours' WHERE token_hash=?",session.hash());
        assertEquals(401,assertThrows(ApiException.class,()->tasks.get(session,UUID.fromString(intent.get("target_id").toString()))).status);
    }
    @Test void mutationReceiptCannotCrossSessionsOrSecurityEpoch(){
        var a=args();var intent=issue(null,a);UUID key=UUID.randomUUID();var body=mutation(intent,a);tasks.create(session,key,body);
        var second=auth.find(auth.issue(owner,true,null).token());
        assertEquals(403,assertThrows(ApiException.class,()->tasks.reconcile(second,key)).status);
        assertEquals(403,assertThrows(ApiException.class,()->tasks.create(second,key,body)).status);
        var old=session;flow.pause(session,true);session=new AuthService.Session(old.hash(),owner,old.reauth(),old.epoch()+1);
        assertEquals(403,assertThrows(ApiException.class,()->tasks.reconcile(session,key)).status);
    }
    @Test void updateAuditFailureRollsBackVersionDescriptionIntentAndReceipt(){
        var task=create();UUID id=id(task);var args=Map.<String,Object>of("expected_version",1,"description","new synthetic description");var intent=issue(id,args);UUID key=UUID.randomUUID();
        db.execute("ALTER TABLE audit_events ADD CONSTRAINT task_update_test_fail CHECK(event_type <> 'TASK_UPDATED')");
        try{assertThrows(RuntimeException.class,()->tasks.update(session,key,id,mutation(intent,args)));same(task,tasks.get(session,id));assertEquals(1,count("task_mutations"));assertEquals(1,db.queryForObject("SELECT count(*) FROM task_user_intents WHERE consumed_at IS NULL",Long.class));}
        finally{db.execute("ALTER TABLE audit_events DROP CONSTRAINT task_update_test_fail");}
        assertEquals("NOT_OBSERVED",tasks.reconcile(session,key).get("status"));
    }
    @Test void strictFieldsBoundsAndExistingProjectOnly(){
        for(String bad:List.of("grant_id","owner_id","run_id","status")){var a=args();a.put(bad,"x");assertThrows(ApiException.class,()->issue(null,a));}
        var a=args();a.put("project_id",UUID.randomUUID().toString());assertEquals(404,assertThrows(ApiException.class,()->issue(null,a)).status);
        UUID project=UUID.randomUUID();db.update("INSERT INTO projects(id,owner_id,name) VALUES (?,?,?)",project,owner,"Synthetic project");a.put("project_id",project.toString());assertNotNull(issue(null,a));
        for(Object bad:List.of(0,525601,"10")){var invalid=args();invalid.put("estimated_minutes",bad);assertThrows(ApiException.class,()->issue(null,invalid));}
        for(String bad:List.of("2026-10-10","2026-10-10T09:00:00","2026-02-30T09:00:00Z","2026-10-10T09:00:00.1234567Z")){var invalid=args();invalid.put("due_at",bad);assertThrows(ApiException.class,()->issue(null,invalid));}
        var invalid=args();invalid.put("description","x".repeat(8001));assertThrows(ApiException.class,()->issue(null,invalid));
        assertThrows(ApiException.class,()->TaskService.validateArguments(json(Map.of("expected_version",1)),false));
        assertThrows(ApiException.class,()->TaskService.parseUuid("1-1-1-1-1"));
    }
    @Test void unicodeBlankTitleAndVersionExhaustionMatchSharedContract(){
        for(int cp:new int[]{0x00,0x09,0x0D,0x20,0x85,0xA0,0x1680,0x2000,0x200A,0x2028,0x2029,0x202F,0x205F,0x3000,0xFEFF}){var a=args();a.put("title",new String(Character.toChars(cp)));assertThrows(ApiException.class,()->issue(null,a));}
        for(int cp:new int[]{0x200B,0x180E}){var a=args();String title=new String(Character.toChars(cp));a.put("title",title);var intent=issue(null,a);assertEquals(title,((JsonNode)intent.get("arguments")).get("title").asText());}
        var task=create();var patch=issue(id(task),Map.of("expected_version",1,"status","DONE"));
        assertThrows(RuntimeException.class,()->db.update("UPDATE task_user_intents SET expected_version=NULL WHERE id=?",id(patch)));
        assertThrows(RuntimeException.class,()->db.update("UPDATE task_user_intents SET expected_version=9007199254740992 WHERE id=?",id(patch)));
        db.update("UPDATE tasks SET version=9007199254740991 WHERE id=?",id(task));
        assertEquals("VERSION_EXHAUSTED",assertThrows(ApiException.class,()->issue(id(task),Map.of("expected_version",9007199254740991L,"status","DONE"))).code);
        assertThrows(RuntimeException.class,()->db.update("UPDATE tasks SET version=9007199254740992 WHERE id=?",id(task)));
        for(String value:List.of("0001-01-01T00:00:00+01:00","9999-12-31T23:59:59-01:00"))assertThrows(ApiException.class,()->TaskService.timestamp(value));
    }
    @Test void paginatedReadsAndDueFiltersAreBoundedAndDoNotMutate(){
        var a=args();a.put("due_at","2026-10-10T00:00:00Z");var intent=issue(null,a);var dated=tasks.create(session,UUID.randomUUID(),mutation(intent,a));create();create();long audit=count("audit_events");
        var first=tasks.list(session,Map.of("limit",new String[]{"2"}));assertEquals(2,((List<?>)first.get("items")).size());assertNotNull(first.get("next_cursor"));
        var next=tasks.list(session,Map.of("limit",new String[]{"2"},"cursor",new String[]{first.get("next_cursor").toString()}));assertEquals(1,((List<?>)next.get("items")).size());assertNull(next.get("next_cursor"));
        assertThrows(ApiException.class,()->tasks.list(session,Map.of("from",new String[]{"2026-10-10T00:00:00.0000001Z"})));
        var filtered=tasks.list(session,Map.of("from",new String[]{"2026-10-10T00:00:00Z"},"to",new String[]{"2026-10-11T00:00:00Z"}));assertEquals(1,((List<?>)filtered.get("items")).size());
        assertEquals(0,((List<?>)tasks.list(session,Map.of("to",new String[]{"2026-10-10T00:00:00Z"})).get("items")).size());
        assertEquals(404,assertThrows(ApiException.class,()->tasks.get(session,UUID.randomUUID())).status);same(dated,tasks.get(session,id(dated)));
        assertThrows(ApiException.class,()->tasks.list(session,Map.of("limit",new String[]{"101"})));assertThrows(ApiException.class,()->tasks.list(session,Map.of("status",new String[]{"TODO","DONE"})));assertEquals(audit,count("audit_events"));
    }
}

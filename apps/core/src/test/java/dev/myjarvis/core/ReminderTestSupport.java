package dev.myjarvis.core;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Shared synthetic fixture; the real PostgreSQL database and encryption key survive restart. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class ReminderTestSupport {
    EmbeddedPostgres postgres;ConfigurableApplicationContext context;JdbcTemplate db;Path key;
    AuthService auth;ScheduleService schedules;NotificationService notifications;WorkflowService flow;Vault vault;
    AuthService.Session session;UUID owner;String token;
    @BeforeAll void startReminderDatabase() throws Exception{postgres=EmbeddedPostgres.builder().setPort(0).start();key=Files.createTempFile("jarvis-reminder-test-",".bin");byte[] bytes=new byte[32];Vault.RNG.nextBytes(bytes);Files.write(key,bytes);Files.setPosixFilePermissions(key,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));open();}
    void open(){context=new SpringApplicationBuilder(CoreApplication.class).web(WebApplicationType.NONE).run("--spring.datasource.url="+postgres.getJdbcUrl("postgres","postgres"),"--spring.datasource.username=postgres","--spring.datasource.password=","--jarvis.key-file="+key,"--jarvis.mode=mock","--jarvis.worker.enabled=false","--jarvis.reminders.worker-enabled=false","--spring.main.banner-mode=off");db=context.getBean(JdbcTemplate.class);auth=context.getBean(AuthService.class);schedules=context.getBean(ScheduleService.class);notifications=context.getBean(NotificationService.class);flow=context.getBean(WorkflowService.class);vault=context.getBean(Vault.class);}
    @AfterAll void stopReminderDatabase() throws Exception{if(context!=null)context.close();if(postgres!=null)postgres.close();if(key!=null)Files.deleteIfExists(key);}
    @BeforeEach void resetReminderDatabase(){db.execute("TRUNCATE owners,bootstrap_tokens,login_limits,crypto_nonces CASCADE");owner=UUID.randomUUID();db.update("INSERT INTO owners(id,login_name,password_hash) VALUES (?,'reminder-owner','synthetic-unused')",owner);token=auth.issue(owner,true,null).token();session=auth.find(token);}
    JsonNode body(Object value){return Json.MAPPER.valueToTree(value);}
    long count(String table){return db.queryForObject("SELECT count(*) FROM "+table,Long.class);}
    void code(String expected,org.junit.jupiter.api.function.Executable action){assertEquals(expected,assertThrows(ApiException.class,action).code);}
    Map<String,Object> definition(String kind){Instant now=schedules.databaseNow();Instant fire=now.truncatedTo(java.time.temporal.ChronoUnit.MINUTES).plusSeconds(120);Map<String,Object> result=new LinkedHashMap<>();result.put("title","Synthetic reminder sentinel");result.put("text","Synthetic private content sentinel <script>inert</script>");result.put("project_id",null);result.put("recurrence",kind);result.put("start_date",fire.atOffset(ZoneOffset.UTC).toLocalDate().toString());result.put("local_time",fire.atOffset(ZoneOffset.UTC).toLocalTime().toString());result.put("timezone","UTC");result.put("weekdays",List.of());result.put("authorization_expires_at",now.plusSeconds(29*86400).toString());result.put("max_deliveries",kind.equals("ONCE")?1:31);return result;}
    Map<String,Object> preview(String operation,UUID id,Long version,Map<String,Object> definition){Map<String,Object> request=new LinkedHashMap<>();request.put("operation",operation);request.put("target_id",id);request.put("expected_version",version);request.put("definition",definition);return schedules.prepare(session,body(request));}
    JsonNode confirmation(Map<String,Object> preview){return body(Map.of("expected_preparation_version",1,"payload_hash",preview.get("payload_hash"),"confirm",true));}
    Map<String,Object> confirm(Map<String,Object> preview,UUID key){return schedules.confirm(session,key,UUID.fromString(preview.get("id").toString()),confirmation(preview));}
    UUID create(String kind){var receipt=confirm(preview("CREATE",null,null,definition(kind)),UUID.randomUUID());return (UUID)receipt.get("schedule_id");}
    Instant due(UUID id){return AuthService.instant(db.queryForObject("SELECT next_fire_at FROM schedules WHERE owner_id=? AND id=?",Object.class,owner,id));}
    ScheduleWorker workerAt(Instant now){ScheduleService controlled=spy(schedules);doReturn(now).when(controlled).databaseNow();return new ScheduleWorker(controlled,false,false);}
    UUID deliver(UUID id){ScheduleWorker worker=workerAt(due(id).plusSeconds(1));assertEquals(1,worker.plan(owner));var leases=worker.claim(owner,10);assertEquals(1,leases.size());assertTrue(worker.settle(leases.getFirst()));return db.queryForObject("SELECT id FROM notifications WHERE schedule_id=?",UUID.class,id);}
    NotificationService inboxAt(Instant now){ScheduleService controlled=spy(schedules);doReturn(now).when(controlled).databaseNow();return new NotificationService(controlled);}
    UUID createAt(JsonNode definition,Instant issued){
        db.update("UPDATE sessions SET created_at=?,last_seen_at=?,reauthenticated_at=? WHERE token_hash=?",ScheduleService.stamp(issued),ScheduleService.stamp(issued),ScheduleService.stamp(issued),session.hash());
        ScheduleService controlled=spy(schedules);doReturn(issued).when(controlled).databaseNow();Map<String,Object> request=new LinkedHashMap<>();request.put("operation","CREATE");request.put("target_id",null);request.put("expected_version",null);request.put("definition",definition);
        var preview=controlled.prepare(session,body(request));return (UUID)controlled.confirm(session,UUID.randomUUID(),(UUID)preview.get("id"),confirmation(preview)).get("schedule_id");
    }
}

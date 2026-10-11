package dev.myjarvis.core;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import static org.junit.jupiter.api.Assertions.*;

/** Every row and key belongs to a new disposable synthetic PostgreSQL instance. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class ConversationDeletionFixtures {
    EmbeddedPostgres postgres;ConfigurableApplicationContext context;Path key;JdbcTemplate db;
    WorkflowService flow;ConversationDeletionService deletion;MemoryService memories;AuthService.Session session;UUID owner;
    @BeforeAll void startDeletionFixture() throws Exception {
        postgres=EmbeddedPostgres.builder().setPort(0).start();key=Files.createTempFile("jarvis-deletion-fixture-",".key");byte[] bytes=new byte[32];Vault.RNG.nextBytes(bytes);Files.write(key,bytes);Files.setPosixFilePermissions(key,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));openContext();
    }
    void openContext(){context=new SpringApplicationBuilder(CoreApplication.class).web(WebApplicationType.NONE).run("--spring.datasource.url="+postgres.getJdbcUrl("postgres","postgres"),"--spring.datasource.username=postgres","--spring.datasource.password=","--jarvis.key-file="+key,"--jarvis.mode=mock","--jarvis.worker.enabled=false","--jarvis.local.enabled=false","--spring.main.banner-mode=off");db=context.getBean(JdbcTemplate.class);flow=context.getBean(WorkflowService.class);deletion=context.getBean(ConversationDeletionService.class);memories=context.getBean(MemoryService.class);}
    @AfterAll void closeDeletionFixture() throws Exception {try{if(context!=null)context.close();}finally{try{if(postgres!=null)postgres.close();}finally{if(key!=null)Files.deleteIfExists(key);}}}
    @BeforeEach void freshDeletionOwner(){db.execute("TRUNCATE owners,bootstrap_tokens,login_limits,crypto_nonces CASCADE");owner=UUID.randomUUID();db.update("INSERT INTO owners(id,login_name,password_hash) VALUES (?,'deletion-fixture','unused')",owner);session=new AuthService.Session(UUID.randomUUID().toString(),owner,Instant.now(),1);db.update("INSERT INTO sessions(token_hash,owner_id,csrf_hash,security_epoch,reauthenticated_at) VALUES (?,?,'fixture',1,clock_timestamp())",session.hash(),owner);}
    UUID conversation(String title){return (UUID)flow.createConversation(owner,json(Map.of("title",title))).get("id");}
    UUID answered(UUID conversation,String sentinel){UUID run=(UUID)flow.accept(owner,conversation,json(Map.of("client_message_id",UUID.randomUUID().toString(),"text",sentinel,"attachment_ids",List.of()))).get("run_id");flow.tx.executeWithoutResult(t->{db.update("UPDATE jobs SET status='SUCCEEDED' WHERE run_id=?",run);flow.setRun(owner,run,"SUCCEEDED",sentinel+" answer");});return run;}
    static JsonNode json(Object value){return Json.MAPPER.valueToTree(value);}
    Map<String,Object> prepare(UUID conversation){var state=deletion.state(session,conversation);assertEquals("ELIGIBLE",((Map<?,?>)state.get("eligibility")).get("status"));return deletion.prepare(session,UUID.randomUUID(),conversation,json(Map.of("expected_version",state.get("content_version"),"expected_digest",state.get("content_digest"))));}
    static JsonNode confirmation(Map<String,Object> p){return json(Map.of("expected_version",p.get("expected_version"),"expected_digest",p.get("expected_digest"),"payload_hash",p.get("payload_hash"),"confirm",true));}
    Map<String,Object> commit(Map<String,Object> p,UUID key){return deletion.confirm(session,key,(UUID)p.get("id"),confirmation(p));}
    ApiException error(Runnable work,int status,String code){ApiException e=assertThrows(ApiException.class,work::run);assertEquals(status,e.status);assertEquals(code,e.code);return e;}
    long count(String table){return db.queryForObject("SELECT count(*) FROM "+table,Long.class);}
    @SuppressWarnings("unchecked") static Map<String,Object> receipt(Map<String,Object> outcome){return (Map<String,Object>)outcome.get("receipt");}
}

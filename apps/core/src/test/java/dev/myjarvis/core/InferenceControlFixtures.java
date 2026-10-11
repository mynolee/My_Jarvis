package dev.myjarvis.core;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class InferenceControlFixtures {
    EmbeddedPostgres postgres;ConfigurableApplicationContext app;JdbcTemplate db;Path key;
    AuthService auth;WorkflowService flow;InferenceContextService contexts;InferenceContextFreshness freshness;AuthService.Session session;
    LocalInferenceService local;ControlClient client;UUID owner,conversation;
    @BeforeAll void start()throws Exception {
        postgres=EmbeddedPostgres.builder().setPort(0).start();key=Files.createTempFile("synthetic-control-",".bin");byte[] bytes=new byte[32];Vault.RNG.nextBytes(bytes);Files.write(key,bytes);Files.setPosixFilePermissions(key,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        app=new SpringApplicationBuilder(CoreApplication.class).web(WebApplicationType.NONE).run("--spring.datasource.url="+postgres.getJdbcUrl("postgres","postgres"),"--spring.datasource.username=postgres","--spring.datasource.password=","--jarvis.key-file="+key,"--jarvis.mode=mock","--jarvis.worker.enabled=false","--jarvis.local.enabled=false","--spring.main.banner-mode=off");
        db=app.getBean(JdbcTemplate.class);auth=app.getBean(AuthService.class);flow=app.getBean(WorkflowService.class);contexts=app.getBean(InferenceContextService.class);freshness=app.getBean(InferenceContextFreshness.class);
        app.getBean(InferenceControlService.class).close();
    }
    @AfterAll void stop()throws Exception {if(app!=null)app.close();if(postgres!=null)postgres.close();if(key!=null)Files.deleteIfExists(key);}
    @BeforeEach void reset(){
        db.execute("TRUNCATE owners,bootstrap_tokens,login_limits,crypto_nonces CASCADE");owner=UUID.randomUUID();conversation=UUID.randomUUID();db.update("INSERT INTO owners(id,login_name,password_hash) VALUES (?,'control-fixture','synthetic-unused')",owner);session=auth.find(auth.issue(owner,true,null).token());db.update("INSERT INTO conversations(id,owner_id,title) VALUES (?,?,'Synthetic control')",conversation,owner);
        client=new ControlClient();local=new LocalInferenceService(flow,client,contexts,freshness);local.controls.close();
    }
    @AfterEach void closeLocal(){local.close();Thread.interrupted();}
    static final class ControlClient extends LocalInferenceClient {
        final AtomicInteger infers=new AtomicInteger(),controls=new AtomicInteger();final List<String> operations=new ArrayList<>();
        JsonNode body;String kind,state="PENDING";boolean failInfer,failControl,available=true;Runnable duringInfer=()->{};
        ControlClient(){super(new MockEnvironment().withProperty("jarvis.local.manifest-hash","a".repeat(64)));}
        @Override boolean configured(){return true;}
        @Override boolean ready(){return available;}
        @Override JsonNode infer(UUID owner,JsonNode body){assertFalse(TransactionSynchronizationManager.isActualTransactionActive());infers.incrementAndGet();this.body=body;duringInfer.run();if(failInfer)throw new IllegalStateException("Synthetic unknown");return LocalInferenceContractTest.response(body);}
        @Override InferenceControlResult control(JsonNode request){assertFalse(TransactionSynchronizationManager.isActualTransactionActive());controls.incrementAndGet();operations.add(request.get("operation").asText());if(failControl)throw new IllegalStateException("Synthetic timeout");return InferenceControlResult.verify(InferenceControlResultTest.response(request,kind==null?state:"TERMINAL",kind),request);}
    }
    JsonNode node(Object body){return Json.MAPPER.valueToTree(body);}
    JsonNode input(UUID key){return node(Map.of("client_message_id",key,"text","Synthetic question"));}
    UUID accept(){return (UUID)local.accept(session,conversation,input(UUID.randomUUID())).get("run_id");}
    Map<String,Object> slot(UUID run){return db.queryForMap("SELECT * FROM inference_control_slots WHERE run_id=?",run);}
    String status(UUID run){return db.queryForObject("SELECT status FROM runs WHERE id=?",String.class,run);}
    long count(String table){return db.queryForObject("SELECT count(*) FROM "+table,Long.class);}
    long version(){return db.queryForObject("SELECT content_version FROM conversations WHERE id=?",Long.class,conversation);}
    Map<String,Object> inventory(){return db.queryForMap("SELECT * FROM conversation_source_inventory_v17(?,?)",owner,conversation);}
    void cancel(UUID run){flow.cancel(session,run,db.queryForObject("SELECT version FROM runs WHERE id=?",Long.class,run));}
    void reconcile(UUID run)throws Exception {local.controls.reconcileOne(owner,(UUID)slot(run).get("reservation_id"));}
}

package dev.myjarvis.core;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;

/** Durable mock-only worker. No external client, credentials, model, or provider exists here. */
@Component
final class MockWorker {
    private final WorkflowService flow;private final JdbcTemplate db;private final TransactionTemplate tx;private final UUID worker=UUID.randomUUID();private final boolean enabled;
    MockWorker(WorkflowService flow,@Value("${jarvis.worker.enabled:true}")boolean enabled){this.flow=flow;this.db=flow.db;this.tx=flow.tx;this.enabled=enabled;}
    @Scheduled(fixedDelay=500) void tick(){if(enabled){recover();runOne();}}
    Map<String,Object> claim(){return tx.execute(s->{
        var rows=db.queryForList("WITH picked AS (SELECT id FROM jobs WHERE status='QUEUED' AND available_at<=now() ORDER BY available_at,created_at FOR UPDATE SKIP LOCKED LIMIT 1) UPDATE jobs j SET status='RUNNING',lease_owner=?,lease_until=now()+interval '60 seconds',lease_generation=lease_generation+1,attempt_count=attempt_count+1 FROM picked WHERE j.id=picked.id RETURNING j.*",worker);return rows.isEmpty()?null:rows.getFirst();
    });}
    boolean runOne(){var job=claim();if(job==null)return false;
        try {if("PLAN".equals(job.get("kind")))plan(job);else execute(job);}catch(RuntimeException e){
            // Never include payloads, SQL arguments, or exception messages in logs/errors.
            tx.executeWithoutResult(s->{flow.lockOwner((UUID)job.get("owner_id"));if(!current(job))return;int changed=db.update("UPDATE jobs SET status=CASE WHEN started_effect THEN 'UNKNOWN' ELSE 'FAILED' END WHERE id=? AND lease_owner=? AND lease_generation=? AND status='RUNNING'",job.get("id"),worker,job.get("lease_generation"));if(changed==1){var row=db.queryForMap("SELECT status FROM jobs WHERE id=?",job.get("id"));flow.setRun((UUID)job.get("owner_id"),(UUID)job.get("run_id"),(String)row.get("status"),null);flow.event((UUID)job.get("owner_id"),(UUID)job.get("run_id"),"WORKER_UNCERTAIN_OR_FAILED");}});
        }return true;
    }
    private boolean current(Map<String,Object> job){return Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM jobs WHERE id=? AND lease_owner=? AND lease_generation=? AND status='RUNNING' AND lease_until>clock_timestamp())",Boolean.class,job.get("id"),worker,job.get("lease_generation")));}
    private void plan(Map<String,Object> job){tx.executeWithoutResult(s->{UUID owner=(UUID)job.get("owner_id"),run=(UUID)job.get("run_id");flow.lockOwner(owner);if(!current(job))return;
        var o=flow.owned("owners",owner,owner);var r=flow.locked("runs",owner,run);if(Boolean.TRUE.equals(o.get("execution_paused"))||r.get("cancel_requested_at")!=null){finish(job,"CANCELLED",null);return;}
        var m=db.queryForMap("SELECT id,content_ref FROM messages WHERE owner_id=? AND run_id=? AND role='USER'",owner,run);String text=flow.vault.open(owner,(UUID)m.get("id"),"message",(String)m.get("content_ref"));
        // Synthetic fixture selector is deliberately explicit, not model interpretation or authorization.
        if(Set.of("/deny","/mail","/mail-timeout").contains(text)){finish(job,"BLOCKED","Mock policy rejected an unregistered tool. No external action occurred.");return;}
        if(text.equals("/need-input")){finish(job,"WAITING_INPUT","Mock fixture needs more information. No action was prepared.");return;}
        if(!Set.of("/calendar","/calendar-timeout").contains(text)){finish(job,"SUCCEEDED","Mock answer: your message was saved. No model or external provider was called.");return;}
        String tool="calendar.event.create";
        UUID action=UUID.randomUUID(),approval=UUID.randomUUID();Instant now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS),expires=now.plusSeconds(600);
        Map<String,Object> args=tool.equals("calendar.event.create")?Map.of("calendar_id","00000000-0000-4000-8000-000000000001","title","Synthetic study event","start_at","2030-01-01T10:00:00Z","end_at","2030-01-01T11:00:00Z","timezone","UTC","attendees",List.of()):Map.of("to",List.of("synthetic@example.invalid"),"cc",List.of(),"bcc",List.of(),"subject","Synthetic mock message","body","Mock only. This is never sent.","attachment_hashes",List.of());
        Map<String,Object> payload=new LinkedHashMap<>();payload.put("owner_id",owner.toString());payload.put("action_id",action.toString());payload.put("tool_id",tool);payload.put("tool_version","1");payload.put("arguments",args);payload.put("policy_revision",WorkflowService.POLICY);payload.put("security_epoch",o.get("security_epoch"));payload.put("issued_at",now.toString());payload.put("expires_at",expires.toString());payload.put("max_executions",1);payload.put("max_cost_usd_micro",0);
        ToolPolicy.validateSnapshot(Json.MAPPER.valueToTree(payload));
        String canonical=Json.canonical(payload),hash=Json.hash(canonical);
        db.update("INSERT INTO action_proposals(id,owner_id,run_id,tool_id,tool_version,payload_ref,payload_hash,status,security_epoch,valid_until,fault_mode) VALUES (?,?,?,?,?,?,?,'WAITING_APPROVAL',?,?,?)",action,owner,run,tool,"1",flow.vault.seal(owner,action,"action",canonical),hash,o.get("security_epoch"),java.sql.Timestamp.from(expires),text.endsWith("-timeout")?"TIMEOUT_AFTER_EFFECT":"SUCCESS");
        db.update("INSERT INTO approvals(id,owner_id,action_id,payload_hash,status,expires_at,action_version) VALUES (?,?,?,?,'PENDING',?,1)",approval,owner,action,hash,java.sql.Timestamp.from(expires));finish(job,"WAITING_APPROVAL",null);
    });}
    private void execute(Map<String,Object> job){
        // Commit execution intent before invoking the adapter. A crash here is conservatively UNKNOWN.
        Boolean authorized=tx.execute(s->{UUID owner=(UUID)job.get("owner_id"),action=(UUID)job.get("action_id");flow.lockOwner(owner);if(!current(job))return false;
            var o=flow.owned("owners",owner,owner);var a=flow.locked("action_proposals",owner,action);var r=flow.owned("runs",owner,(UUID)job.get("run_id"));
            var auth=db.queryForList("SELECT * FROM authorizations WHERE owner_id=? AND action_id=? AND valid_until>now()",owner,action);
            String snapshot=flow.vault.open(owner,action,"action",(String)a.get("payload_ref"));
            ToolPolicy.validateSnapshot(Json.parse(snapshot.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            boolean valid=auth.size()==1&&"calendar.event.create".equals(a.get("tool_id"))&&"1".equals(a.get("tool_version"))&&"QUEUED".equals(a.get("status"))&&!Boolean.TRUE.equals(o.get("execution_paused"))&&r.get("cancel_requested_at")==null&&((Number)o.get("security_epoch")).longValue()==((Number)a.get("security_epoch")).longValue()&&Json.equal(Json.hash(snapshot),(String)a.get("payload_hash"))&&Json.equal((String)auth.getFirst().get("payload_hash"),(String)a.get("payload_hash"));
            if(!valid){finish(job,"BLOCKED",null);return false;}
            db.update("INSERT INTO action_executions(action_id,owner_id,status,lease_generation) VALUES (?,?,'STARTED',?)",action,owner,job.get("lease_generation"));
            db.update("UPDATE jobs SET started_effect=true WHERE id=?",job.get("id"));db.update("UPDATE action_proposals SET status='EXECUTING',version=version+1 WHERE id=?",action);flow.setRun(owner,(UUID)job.get("run_id"),"RUNNING",null);flow.event(owner,(UUID)job.get("run_id"),"MOCK_EXECUTION_STARTED");return true;
        });if(!Boolean.TRUE.equals(authorized))return;
        // Synthetic adapter boundary. Owner lock serializes pause against the start of each effect.
        String outcome=tx.execute(s->{UUID owner=(UUID)job.get("owner_id"),action=(UUID)job.get("action_id");flow.lockOwner(owner);
            var a=flow.owned("action_proposals",owner,action);var o=flow.owned("owners",owner,owner);var r=flow.owned("runs",owner,(UUID)job.get("run_id"));
            if(!current(job)||Boolean.TRUE.equals(o.get("execution_paused"))||r.get("cancel_requested_at")!=null||!o.get("security_epoch").equals(a.get("security_epoch")))return "UNKNOWN";
            var payload=Json.parse(flow.vault.open(owner,action,"action",(String)a.get("payload_ref")).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            ToolPolicy.validateSnapshot(payload);
            if(!Json.equal(Json.hash(Json.canonical(payload)),(String)a.get("payload_hash")))return "UNKNOWN";
            db.update("INSERT INTO mock_effects(action_id,owner_id,tool_id,payload_hash) VALUES (?,?,?,?) ON CONFLICT(action_id) DO NOTHING",action,owner,a.get("tool_id"),a.get("payload_hash"));
            return "TIMEOUT_AFTER_EFFECT".equals(a.get("fault_mode"))?"UNKNOWN":"SUCCEEDED";
        });
        tx.executeWithoutResult(s->{flow.lockOwner((UUID)job.get("owner_id"));if(!current(job))return;UUID action=(UUID)job.get("action_id");db.update("UPDATE action_executions SET status=?,finished_at=now() WHERE action_id=? AND lease_generation=?",outcome,action,job.get("lease_generation"));db.update("UPDATE action_proposals SET status=?,version=version+1 WHERE id=?",outcome,action);finish(job,outcome,outcome.equals("SUCCEEDED")?"Verified synthetic mock effect. No external provider was called.":"Mock execution outcome is UNKNOWN. It was not sent again.");});
    }
    private void finish(Map<String,Object> job,String runStatus,String result){String jobStatus=Set.of("WAITING_INPUT","WAITING_APPROVAL").contains(runStatus)?"SUCCEEDED":runStatus;int changed=db.update("UPDATE jobs SET status=? WHERE id=? AND lease_owner=? AND lease_generation=? AND status='RUNNING' AND lease_until>clock_timestamp()",jobStatus,job.get("id"),worker,job.get("lease_generation"));if(changed!=1)throw new LeaseLost();UUID owner=(UUID)job.get("owner_id"),run=(UUID)job.get("run_id");flow.setRun(owner,run,runStatus,result);flow.event(owner,run,"RUN_"+runStatus);}
    /** Roll back any preceding proposal/action writes when final lease settlement loses its fence. */
    private static final class LeaseLost extends RuntimeException {}
    void recover(){
        var candidates=db.queryForList("SELECT id,owner_id FROM jobs WHERE status='RUNNING' AND lease_until<=now() ORDER BY created_at LIMIT 100");
        for(var candidate:candidates)tx.executeWithoutResult(s->{
            flow.lockOwner((UUID)candidate.get("owner_id"));
            var expired=db.queryForList("SELECT * FROM jobs WHERE id=? AND status='RUNNING' AND lease_until<=now() FOR UPDATE SKIP LOCKED",candidate.get("id"));
            if(expired.isEmpty())return;var j=expired.getFirst();String next=Boolean.TRUE.equals(j.get("started_effect"))?"UNKNOWN":"QUEUED";
            db.update("UPDATE jobs SET status=?,lease_owner=NULL,lease_until=NULL,lease_generation=lease_generation+1 WHERE id=?",next,j.get("id"));
            if(next.equals("UNKNOWN")){flow.setRun((UUID)j.get("owner_id"),(UUID)j.get("run_id"),"UNKNOWN",null);db.update("UPDATE action_proposals SET status='UNKNOWN',version=version+1 WHERE id=?",j.get("action_id"));flow.event((UUID)j.get("owner_id"),(UUID)j.get("run_id"),"LEASE_EXPIRED_UNKNOWN");}
        });
    }
    /** Read-only reconciliation. Never calls the mock effect insertion path. */
    Map<String,Object> reconcile(UUID owner,UUID run){return tx.execute(s->{flow.lockOwner(owner);if(Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM inference_reservations WHERE owner_id=? AND run_id=?)",Boolean.class,owner,run)))throw new ApiException(409,"LOCAL_RECONCILIATION_DISABLED");var r=flow.locked("runs",owner,run);if(!"UNKNOWN".equals(r.get("status")))throw new ApiException(409,"NOT_UNKNOWN");var rows=db.queryForList("SELECT a.id,a.payload_hash,e.payload_hash AS observed_hash FROM action_proposals a LEFT JOIN mock_effects e ON e.action_id=a.id AND e.owner_id=a.owner_id WHERE a.owner_id=? AND a.run_id=?",owner,run);if(rows.size()==1&&Json.equal((String)rows.getFirst().get("payload_hash"),(String)rows.getFirst().get("observed_hash"))){UUID action=(UUID)rows.getFirst().get("id");db.update("UPDATE action_proposals SET status='SUCCEEDED',version=version+1 WHERE id=?",action);db.update("UPDATE action_executions SET status='SUCCEEDED',finished_at=now() WHERE action_id=?",action);db.update("UPDATE jobs SET status='SUCCEEDED',lease_generation=lease_generation+1 WHERE owner_id=? AND action_id=? AND status='UNKNOWN'",owner,action);flow.setRun(owner,run,"SUCCEEDED","Read-only reconciliation verified the existing synthetic effect. No retry occurred.");flow.event(owner,run,"MOCK_RECONCILED");}return flow.run(owner,run);});}
}

package dev.myjarvis.core;

import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Dedicated pure-database queue. Lock order is always owner, schedule, occurrence, job. */
@Component
final class ScheduleWorker {
    record Lease(UUID owner,UUID schedule,UUID occurrence,UUID job,UUID worker,long generation){}
    private final ScheduleService s;private final boolean enabled;private final UUID worker=UUID.randomUUID();
    ScheduleWorker(ScheduleService schedules,@Value("${jarvis.worker.enabled:true}") boolean workers,@Value("${jarvis.reminders.worker-enabled:true}") boolean reminders){s=schedules;enabled=workers&&reminders;}
    @Scheduled(fixedDelay=5000) void poll(){if(!enabled)return;for(UUID owner:s.db.queryForList("SELECT id FROM owners ORDER BY id",UUID.class)){
        try{plan(owner);for(Lease lease:claim(owner,10))try{settle(lease);}catch(RuntimeException failure){/* Lease expires; bounded retry. Never log private contents. */}}catch(RuntimeException failure){/* A failed DB transaction has no partial inbox effect. */}
    }}
    Map<String,Object> lockOwner(UUID owner){var rows=s.db.queryForList("SELECT * FROM owners WHERE id=? FOR UPDATE SKIP LOCKED",owner);return rows.isEmpty()?null:rows.getFirst();}
    String invalid(Map<String,Object> owner,Map<String,Object> schedule,Map<String,Object> authority,Instant now){
        if(Boolean.TRUE.equals(owner.get("execution_paused")))return "PAUSED";
        if(ScheduleService.value(owner,"security_epoch")!=ScheduleService.value(schedule,"security_epoch"))return "EPOCH_CHANGED";
        if(!Boolean.TRUE.equals(schedule.get("enabled")))return "DISABLED";
        if(authority==null||authority.get("revoked_at")!=null)return "REPLACED";
        if(!authority.get("owner_id").equals(schedule.get("owner_id"))||!authority.get("recipient_owner_id").equals(schedule.get("owner_id"))||!authority.get("schedule_id").equals(schedule.get("id"))||ScheduleService.value(authority,"schedule_version")!=ScheduleService.value(schedule,"version")||ScheduleService.value(authority,"security_epoch")!=ScheduleService.value(owner,"security_epoch")||!"inbox.reminder.create".equals(authority.get("action"))||!Json.equal((String)authority.get("definition_digest"),(String)schedule.get("definition_digest")))return "OPERATIONAL_FAILURE";
        if(!ScheduleService.time(authority,"expires_at").isAfter(now))return "AUTHORIZATION_EXPIRED";
        if(ScheduleService.value(authority,"delivered_count")>=ScheduleService.value(authority,"max_deliveries"))return "QUOTA_EXHAUSTED";
        return null;
    }
    boolean definitionBound(Map<String,Object> schedule,Map<String,Object> authority,tools.jackson.databind.JsonNode definition){return Json.equal(s.definitionDigest((UUID)schedule.get("owner_id"),definition),(String)authority.get("definition_digest"))&&ScheduleService.utc(definition.get("authorization_expires_at").asText()).equals(ScheduleService.time(authority,"expires_at"))&&definition.get("max_deliveries").asInt()==ScheduleService.value(authority,"max_deliveries")&&Objects.equals(ScheduleService.projectId(definition),schedule.get("project_id"));}
    static long lateness(Instant planned,Instant now,boolean ceil){Duration elapsed=Duration.between(planned,now);if(elapsed.isNegative())return 0;return elapsed.getSeconds()+(ceil&&elapsed.getNano()!=0?1:0);}
    void finishOccurrence(UUID owner,UUID occurrence,String status,String reason,Instant planned,Instant now){s.db.update("UPDATE schedule_occurrences SET status=?,reason_code=?,lateness_seconds=?,settled_at=? WHERE owner_id=? AND id=? AND status='PLANNED'",status,reason,lateness(planned,now,status.equals("SKIPPED_MISSED")),ScheduleService.stamp(now),owner,occurrence);}
    void blockPending(UUID owner,UUID schedule,String reason,Instant now){
        // All publishers/revokers hold this same owner lock. Reads here never lock jobs before schedules.
        var rows=s.db.queryForList("SELECT * FROM schedule_occurrences WHERE owner_id=? AND schedule_id=? AND status='PLANNED' ORDER BY id FOR UPDATE",owner,schedule);
        for(var row:rows){finishOccurrence(owner,(UUID)row.get("id"),"BLOCKED",reason,ScheduleService.time(row,"planned_fire_at"),now);s.audit(owner,"REMINDER_BLOCKED",(UUID)row.get("id"),now);}
        s.db.update("UPDATE reminder_jobs SET status='BLOCKED',lease_generation=LEAST(lease_generation+1,9007199254740991),lease_owner=NULL,lease_until=NULL,finished_at=? WHERE owner_id=? AND schedule_id=? AND status IN ('QUEUED','RUNNING')",ScheduleService.stamp(now),owner,schedule);
        if(Set.of("PAUSED","EPOCH_CHANGED").contains(reason))s.db.update("UPDATE schedule_authorizations SET revoked_at=? WHERE owner_id=? AND schedule_id=? AND revoked_at IS NULL",ScheduleService.stamp(now),owner,schedule);
    }
    void supersedePending(UUID owner,UUID schedule,long version,Instant latest,Instant now){
        var rows=s.db.queryForList("SELECT * FROM schedule_occurrences WHERE owner_id=? AND schedule_id=? AND schedule_version=? AND status='PLANNED' AND planned_fire_at<? ORDER BY id FOR UPDATE",owner,schedule,version,ScheduleService.stamp(latest));
        for(var row:rows){Instant planned=ScheduleService.time(row,"planned_fire_at");boolean missed=!ReminderRecurrence.withinGrace(planned,now);String status=missed?"SKIPPED_MISSED":"CANCELLED";finishOccurrence(owner,(UUID)row.get("id"),status,missed?null:"SUPERSEDED_MISSED",planned,now);s.db.update("UPDATE reminder_jobs SET status=?,lease_generation=LEAST(lease_generation+1,9007199254740991),lease_owner=NULL,lease_until=NULL,finished_at=? WHERE owner_id=? AND occurrence_id=? AND status IN ('QUEUED','RUNNING')",status,ScheduleService.stamp(now),owner,row.get("id"));s.audit(owner,"REMINDER_"+status,(UUID)row.get("id"),now);}
    }
    int plan(UUID ownerId){return s.writeTx.execute(tx->{var owner=lockOwner(ownerId);if(owner==null)return 0;Instant now=s.databaseNow();int planned=0;
        var schedules=s.db.queryForList("SELECT * FROM schedules s WHERE owner_id=? AND enabled AND (next_fire_at<=? OR EXISTS(SELECT 1 FROM schedule_occurrences o WHERE o.owner_id=s.owner_id AND o.schedule_id=s.id AND o.status='PLANNED')) ORDER BY next_fire_at NULLS LAST,id LIMIT 100 FOR UPDATE SKIP LOCKED",ownerId,ScheduleService.stamp(now));
        for(var schedule:schedules){UUID id=(UUID)schedule.get("id");var authority=s.authorization(schedule);String reason=invalid(owner,schedule,authority,now);
            if(reason!=null&&Set.of("PAUSED","EPOCH_CHANGED","REPLACED","OPERATIONAL_FAILURE").contains(reason)){blockPending(ownerId,id,reason,now);s.db.update("UPDATE schedules SET next_fire_at=NULL WHERE owner_id=? AND id=?",ownerId,id);continue;}
            Instant due=ScheduleService.time(schedule,"next_fire_at");if(due==null||due.isAfter(now))continue;
            var definition=s.content(schedule);if(authority==null||!definitionBound(schedule,authority,definition)){blockPending(ownerId,id,"OPERATIONAL_FAILURE",now);s.db.update("UPDATE schedules SET next_fire_at=NULL WHERE owner_id=? AND id=?",ownerId,id);continue;}
            var rule=s.definition(definition);Instant expiry=ScheduleService.time(authority,"expires_at");int slots=0;
            while(due!=null&&!due.isAfter(now)&&slots++<34){
                String status=reason!=null?"BLOCKED":ReminderRecurrence.withinGrace(due,now)?"PLANNED":"SKIPPED_MISSED";UUID occurrence=UUID.randomUUID();
                int inserted=s.db.update("INSERT INTO schedule_occurrences(id,owner_id,schedule_id,schedule_version,authorization_id,planned_fire_at,status,reason_code,lateness_seconds,created_at,settled_at) VALUES (?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(owner_id,schedule_id,schedule_version,planned_fire_at) DO NOTHING",occurrence,ownerId,id,schedule.get("version"),authority.get("id"),ScheduleService.stamp(due),status,reason,status.equals("PLANNED")?null:lateness(due,now,status.equals("SKIPPED_MISSED")),ScheduleService.stamp(now),status.equals("PLANNED")?null:ScheduleService.stamp(now));
                if(inserted==1){planned++;if(status.equals("PLANNED"))supersedePending(ownerId,id,ScheduleService.value(schedule,"version"),due,now);if(status.equals("PLANNED"))s.db.update("INSERT INTO reminder_jobs(id,owner_id,schedule_id,occurrence_id,status,available_at,created_at) VALUES (?,?,?,?,'QUEUED',?,?)",UUID.randomUUID(),ownerId,id,occurrence,ScheduleService.stamp(now),ScheduleService.stamp(now));s.audit(ownerId,"REMINDER_"+status,occurrence,now);}
                var next=reason==null?ReminderRecurrence.next(rule,due,expiry):null;due=next==null?null:next.instant();
                s.db.update("UPDATE schedules SET next_fire_at=? WHERE owner_id=? AND id=?",due==null?null:ScheduleService.stamp(due),ownerId,id);
            }
        }return planned;
    });}
    List<Lease> claim(UUID ownerId,int limit){if(limit<1||limit>10)throw new IllegalArgumentException("Invalid batch size");return s.writeTx.execute(tx->{var owner=lockOwner(ownerId);if(owner==null)return List.of();Instant now=s.databaseNow();List<Lease> result=new ArrayList<>();
        // Candidate selection does not acquire locks. Owner lock serializes every reminder path.
        var candidates=s.db.queryForList("SELECT id,schedule_id,occurrence_id FROM reminder_jobs WHERE owner_id=? AND ((status='QUEUED' AND available_at<=?) OR (status='RUNNING' AND lease_until<=?)) ORDER BY available_at,id LIMIT ?",ownerId,ScheduleService.stamp(now),ScheduleService.stamp(now),limit);
        for(var candidate:candidates){var schedule=s.record(ownerId,(UUID)candidate.get("schedule_id"),true);var occurrences=s.db.queryForList("SELECT * FROM schedule_occurrences WHERE owner_id=? AND id=? FOR UPDATE",ownerId,candidate.get("occurrence_id"));if(occurrences.isEmpty())continue;var occurrence=occurrences.getFirst();
            var jobs=s.db.queryForList("SELECT * FROM reminder_jobs WHERE owner_id=? AND id=? FOR UPDATE SKIP LOCKED",ownerId,candidate.get("id"));if(jobs.isEmpty())continue;var job=jobs.getFirst();String reason=invalid(owner,schedule,s.authorization(schedule),now);
            if(ScheduleService.value(occurrence,"schedule_version")!=ScheduleService.value(schedule,"version"))reason="REPLACED";
            if(reason==null&&(ScheduleService.value(job,"attempt_count")>=3||ScheduleService.value(job,"lease_generation")>=ScheduleService.MAX))reason="OPERATIONAL_FAILURE";
            if(reason!=null){finishOccurrence(ownerId,(UUID)occurrence.get("id"),"BLOCKED",reason,ScheduleService.time(occurrence,"planned_fire_at"),now);terminalJob(job,"BLOCKED",now);s.audit(ownerId,"REMINDER_BLOCKED",(UUID)occurrence.get("id"),now);continue;}
            if(!"PLANNED".equals(occurrence.get("status"))){terminalJob(job,"CANCELLED",now);continue;}
            if(!ReminderRecurrence.withinGrace(ScheduleService.time(occurrence,"planned_fire_at"),now)){finishOccurrence(ownerId,(UUID)occurrence.get("id"),"SKIPPED_MISSED",null,ScheduleService.time(occurrence,"planned_fire_at"),now);terminalJob(job,"SKIPPED_MISSED",now);s.audit(ownerId,"REMINDER_SKIPPED_MISSED",(UUID)occurrence.get("id"),now);continue;}
            long generation=ScheduleService.advance(ScheduleService.value(job,"lease_generation"));s.db.update("UPDATE reminder_jobs SET status='RUNNING',lease_owner=?,lease_until=?,lease_generation=?,attempt_count=attempt_count+1 WHERE owner_id=? AND id=?",worker,ScheduleService.stamp(now.plusSeconds(30)),generation,ownerId,job.get("id"));result.add(new Lease(ownerId,(UUID)schedule.get("id"),(UUID)occurrence.get("id"),(UUID)job.get("id"),worker,generation));
        }return List.copyOf(result);
    });}
    void terminalJob(Map<String,Object> job,String status,Instant now){s.db.update("UPDATE reminder_jobs SET status=?,lease_owner=NULL,lease_until=NULL,finished_at=? WHERE owner_id=? AND id=?",status,ScheduleService.stamp(now),job.get("owner_id"),job.get("id"));}
    boolean settle(Lease lease){return s.writeTx.execute(tx->{
        // Settlement waits for the owner lock: a committed pause/disable wins before rechecks.
        var owner=s.lockOwner(lease.owner());Instant now=s.databaseNow();var schedule=s.record(lease.owner(),lease.schedule(),true);
        var occurrences=s.db.queryForList("SELECT * FROM schedule_occurrences WHERE owner_id=? AND id=? FOR UPDATE",lease.owner(),lease.occurrence());if(occurrences.isEmpty())return false;var occurrence=occurrences.getFirst();
        var jobs=s.db.queryForList("SELECT * FROM reminder_jobs WHERE owner_id=? AND id=? FOR UPDATE",lease.owner(),lease.job());if(jobs.isEmpty())return false;var job=jobs.getFirst();
        if(!"RUNNING".equals(job.get("status"))||!lease.worker().equals(job.get("lease_owner"))||lease.generation()!=ScheduleService.value(job,"lease_generation")||!ScheduleService.time(job,"lease_until").isAfter(now)||!lease.schedule().equals(job.get("schedule_id"))||!lease.occurrence().equals(job.get("occurrence_id"))||!"PLANNED".equals(occurrence.get("status")))return false;
        var authority=s.authorization(schedule);String reason=invalid(owner,schedule,authority,now);if(ScheduleService.value(occurrence,"schedule_version")!=ScheduleService.value(schedule,"version"))reason="REPLACED";
        if(reason==null&&!occurrence.get("authorization_id").equals(authority.get("id")))reason="OPERATIONAL_FAILURE";
        Instant planned=ScheduleService.time(occurrence,"planned_fire_at");
        if(reason!=null){finishOccurrence(lease.owner(),lease.occurrence(),"BLOCKED",reason,planned,now);terminalJob(job,"BLOCKED",now);s.audit(lease.owner(),"REMINDER_BLOCKED",lease.occurrence(),now);return false;}
        if(!ReminderRecurrence.withinGrace(planned,now)){finishOccurrence(lease.owner(),lease.occurrence(),"SKIPPED_MISSED",null,planned,now);terminalJob(job,"SKIPPED_MISSED",now);s.audit(lease.owner(),"REMINDER_SKIPPED_MISSED",lease.occurrence(),now);return false;}
        var definition=s.content(schedule);if(!definitionBound(schedule,authority,definition)){finishOccurrence(lease.owner(),lease.occurrence(),"BLOCKED","OPERATIONAL_FAILURE",planned,now);terminalJob(job,"BLOCKED",now);s.audit(lease.owner(),"REMINDER_BLOCKED",lease.occurrence(),now);return false;}
        // Revalidate recurrence as well as keyed immutable definition before committing the sole effect.
        s.storedSlot(definition,planned);var later=ReminderRecurrence.next(s.definition(definition),planned,ScheduleService.time(authority,"expires_at"));
        if(later!=null&&!later.instant().isAfter(now)){finishOccurrence(lease.owner(),lease.occurrence(),"CANCELLED","SUPERSEDED_MISSED",planned,now);terminalJob(job,"CANCELLED",now);s.audit(lease.owner(),"REMINDER_CANCELLED",lease.occurrence(),now);return false;}
        UUID notification=UUID.randomUUID();String content=Json.canonical(Map.of("title",definition.get("title").asText(),"text",definition.get("text").asText()));
        s.db.update("INSERT INTO notifications(id,owner_id,project_id,schedule_id,schedule_version,occurrence_id,content_ref,planned_fire_at,lateness_seconds,created_at) VALUES (?,?,?,?,?,?,?,?,?,?)",notification,lease.owner(),schedule.get("project_id"),lease.schedule(),schedule.get("version"),lease.occurrence(),s.vault.seal(lease.owner(),notification,"notification-content",content),ScheduleService.stamp(planned),lateness(planned,now,false),ScheduleService.stamp(now));
        s.db.update("UPDATE schedule_authorizations SET delivered_count=delivered_count+1 WHERE owner_id=? AND id=?",lease.owner(),authority.get("id"));finishOccurrence(lease.owner(),lease.occurrence(),"DELIVERED",null,planned,now);terminalJob(job,"SUCCEEDED",now);s.audit(lease.owner(),"REMINDER_INBOX_CREATED",notification,now);return true;
    });}
}

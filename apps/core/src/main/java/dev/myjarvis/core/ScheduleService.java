package dev.myjarvis.core;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** Explicit bounded same-owner inbox authority. No generic tool, model, or provider dispatch. */
@Service
final class ScheduleService {
    static final long MAX=9007199254740991L;
    static final DateTimeFormatter LOCAL=DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss");
    final JdbcTemplate db;final Vault vault;final ReminderKeyCodec keys;final TransactionTemplate writeTx,readTx;
    private final Set<String> zones;
    ScheduleService(JdbcTemplate db,Vault vault,ReminderKeyCodec keys,PlatformTransactionManager manager){
        this.db=db;this.vault=vault;this.keys=keys;
        writeTx=new TransactionTemplate(manager);writeTx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);writeTx.setTimeout(15);
        readTx=new TransactionTemplate(manager);readTx.setReadOnly(true);readTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);readTx.setTimeout(15);
        Set<String> supported=new HashSet<>();supported.add("UTC");
        for(String zone:ZoneId.getAvailableZoneIds())if(zone.matches("[A-Za-z0-9._+-]+(?:/[A-Za-z0-9._+-]+)+")&&!zone.startsWith("SystemV/")&&zone.length()<=80)supported.add(zone);
        zones=Set.copyOf(supported);
    }
    static void exact(JsonNode body,String... fields){if(body==null)throw ApiException.bad();Json.validate(body);Json.fields(body,fields);if(body.size()!=fields.length)throw ApiException.bad();if(Json.canonical(body).getBytes(StandardCharsets.UTF_8).length>16384)throw new ApiException(413,"BODY_TOO_LARGE");}
    static UUID uuid(String value){return OwnerProfileService.uuid(value);}
    static long number(JsonNode body,String key,long min,long max){long value=Json.integer(body,key);if(value<min||value>max)throw ApiException.bad();return value;}
    static long value(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    static Timestamp stamp(Instant now){return Timestamp.from(now);}
    static Instant time(Map<String,Object> row,String field){return AuthService.instant(row.get(field));}
    static String iso(Object value){return value==null?null:AuthService.instant(value).toString();}
    Instant databaseNow(){return AuthService.instant(db.queryForObject("SELECT clock_timestamp()",Object.class));}
    static Instant utc(String text){
        if(!text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,6})?Z")||text.startsWith("0000-"))throw ApiException.bad();
        try{Instant result=Instant.parse(text);if(!result.atOffset(ZoneOffset.UTC).toLocalDateTime().format(DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss")).equals(text.substring(0,19)))throw ApiException.bad();return result;}catch(DateTimeException e){throw ApiException.bad();}
    }
    static UUID projectId(JsonNode body){return body.get("project_id").isNull()?null:Json.uuid(body,"project_id");}
    void project(UUID owner,UUID project){if(project!=null&&db.queryForList("SELECT id FROM projects WHERE owner_id=? AND id=?",owner,project).isEmpty())throw ApiException.missing();}
    ReminderRecurrence.Rule definition(JsonNode body){
        exact(body,"title","text","project_id","recurrence","start_date","local_time","timezone","weekdays","authorization_expires_at","max_deliveries");
        for(String field:List.of("title","text")){int max=field.equals("title")?200:2000;String text=Json.string(body,field,max);if(text.codePoints().allMatch(OwnerProfileService::blankCodePoint)||text.codePoints().anyMatch(Character::isISOControl)||text.getBytes(StandardCharsets.UTF_8).length>max*4)throw ApiException.bad();}
        projectId(body);String kind=Json.string(body,"recurrence",11);if(!Set.of("ONCE","DAILY","WEEKDAYS","WEEKLY","CUSTOM_DAYS").contains(kind))throw ApiException.bad();
        String dateText=Json.string(body,"start_date",10),timeText=Json.string(body,"local_time",5),zone=Json.string(body,"timezone",80);
        if(!dateText.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")||dateText.startsWith("0000-")||!timeText.matches("[0-9]{2}:[0-9]{2}"))throw ApiException.bad();
        LocalDate date;LocalTime local;try{date=LocalDate.parse(dateText);local=LocalTime.parse(timeText);}catch(DateTimeException e){throw ApiException.bad();}
        if(!zones.contains(zone))throw new ApiException(400,"INVALID_TIMEZONE");
        JsonNode input=body.get("weekdays");if(!input.isArray()||input.size()>7)throw ApiException.bad();List<Integer> days=new ArrayList<>();int last=0;
        for(JsonNode day:input){if(!day.isIntegralNumber()||day.longValue()<1||day.longValue()>7||day.longValue()<=last)throw ApiException.bad();last=day.intValue();days.add(last);}
        if(Set.of("ONCE","DAILY","WEEKDAYS").contains(kind)&&!days.isEmpty()||kind.equals("WEEKLY")&&(days.size()!=1||days.getFirst()!=date.getDayOfWeek().getValue())||kind.equals("CUSTOM_DAYS")&&days.isEmpty())throw ApiException.bad();
        long limit=number(body,"max_deliveries",1,31);if(kind.equals("ONCE")&&limit!=1)throw ApiException.bad();utc(Json.string(body,"authorization_expires_at",27));return new ReminderRecurrence.Rule(kind,date,local,ZoneId.of(zone),days);
    }
    Map<String,Object> session(AuthService.Session s,boolean lock,boolean recent,Instant now){
        AuthService.requireOwner(s);var owners=db.queryForList("SELECT * FROM owners WHERE id=?"+(lock?" FOR UPDATE":""),s.owner());
        if(owners.isEmpty()||s.epoch()!=value(owners.getFirst(),"security_epoch"))throw new ApiException(401,"SESSION_REVOKED");
        var rows=db.queryForList("SELECT * FROM sessions WHERE token_hash=? AND owner_id=? AND security_epoch=? AND revoked_at IS NULL AND created_at>? AND last_seen_at>?"+(lock?" FOR UPDATE":""),s.hash(),s.owner(),s.epoch(),stamp(now.minusSeconds(43200)),stamp(now.minusSeconds(7200)));
        if(rows.isEmpty())throw new ApiException(401,"SESSION_REVOKED");Instant reauth=time(rows.getFirst(),"reauthenticated_at");if(recent&&(reauth==null||reauth.isBefore(now.minusSeconds(300))||reauth.isAfter(now)))throw new ApiException(403,"REAUTHENTICATION_REQUIRED");return owners.getFirst();
    }
    // Owner lock precedes clock capture: a request waiting on pause cannot use a pre-pause time.
    Map<String,Object> lockOwner(UUID owner){var rows=db.queryForList("SELECT * FROM owners WHERE id=? FOR UPDATE",owner);if(rows.isEmpty())throw new ApiException(401,"SESSION_REVOKED");return rows.getFirst();}
    static void unpaused(Map<String,Object> owner){if(Boolean.TRUE.equals(owner.get("execution_paused")))throw new ApiException(409,"EXECUTION_PAUSED");}
    static void binding(AuthService.Session s,Map<String,Object> row){if(!Json.equal(s.hash(),(String)row.get("session_hash"))||s.epoch()!=value(row,"security_epoch"))throw new ApiException(403,"MUTATION_SESSION_CHANGED");}
    <T>T read(AuthService.Session s,Supplier<T> action){return readTx.execute(tx->{session(s,false,false,databaseNow());return action.get();});}
    Map<String,Object> record(UUID owner,UUID id,boolean lock){var rows=db.queryForList("SELECT * FROM schedules WHERE owner_id=? AND id=?"+(lock?" FOR UPDATE":""),owner,id);if(rows.isEmpty())throw ApiException.missing();project(owner,(UUID)rows.getFirst().get("project_id"));return rows.getFirst();}
    JsonNode content(Map<String,Object> row){return Json.parse(vault.open((UUID)row.get("owner_id"),(UUID)row.get("id"),"schedule-definition:"+row.get("version"),(String)row.get("definition_ref")).getBytes(StandardCharsets.UTF_8));}
    String definitionDigest(UUID owner,JsonNode definition){return keys.digest(owner,"definition",Json.canonical(definition));}
    Map<String,Object> authorization(Map<String,Object> schedule){var rows=db.queryForList("SELECT * FROM schedule_authorizations WHERE owner_id=? AND schedule_id=? AND schedule_version=?",schedule.get("owner_id"),schedule.get("id"),schedule.get("version"));return rows.isEmpty()?null:rows.getFirst();}
    static Map<String,Object> slot(ReminderRecurrence.Slot slot){return Map.of("planned_fire_at_utc",slot.instant().toString(),"local_datetime",LOCAL.format(slot.resolved()),"offset_seconds",slot.offset().getTotalSeconds(),"dst_adjustment",slot.adjustment());}
    ReminderRecurrence.Slot storedSlot(JsonNode definition,Instant planned){if(planned==null)return null;var rule=definition(definition);var result=ReminderRecurrence.next(rule,planned.minusNanos(1),utc(definition.get("authorization_expires_at").asText()));if(result==null||!result.instant().equals(planned))throw new IllegalStateException("Invalid schedule time binding");return result;}
    Map<String,Object> view(Map<String,Object> row,Map<String,Object> owner,Instant now){
        JsonNode definition=content(row);var authority=authorization(row);String state="ENABLED";
        if(!Boolean.TRUE.equals(row.get("enabled")))state="DISABLED";
        else if(Boolean.TRUE.equals(owner.get("execution_paused"))||value(row,"security_epoch")!=value(owner,"security_epoch"))state="RECONFIRM_REQUIRED";
        else if(authority==null||authority.get("revoked_at")!=null)state="RECONFIRM_REQUIRED";
        else if(!time(authority,"expires_at").isAfter(now))state="EXPIRED";
        else if(value(authority,"delivered_count")>=value(authority,"max_deliveries")||row.get("next_fire_at")==null&&db.queryForObject("SELECT count(*) FROM schedule_occurrences WHERE owner_id=? AND schedule_id=? AND schedule_version=? AND status='PLANNED'",Long.class,row.get("owner_id"),row.get("id"),row.get("version"))==0)state="EXHAUSTED";
        Map<String,Object> out=new LinkedHashMap<>();out.put("id",row.get("id"));out.put("version",row.get("version"));out.put("status",state);out.put("definition",definition);out.put("destination","OWN_INBOX");
        ReminderRecurrence.Slot next=state.equals("ENABLED")?storedSlot(definition,time(row,"next_fire_at")):null;out.put("next_occurrence",next==null?null:slot(next));out.put("delivered_count",authority==null?0:authority.get("delivered_count"));out.put("created_at",iso(row.get("created_at")));out.put("updated_at",iso(row.get("updated_at")));return out;
    }
    Map<String,Object> get(AuthService.Session s,UUID id){return readTx.execute(tx->{Instant now=databaseNow();var owner=session(s,false,false,now);return view(record(s.owner(),id,false),owner,now);});}
    String payloadHash(AuthService.Session s,Map<String,Object> preview){return keys.digest(s.owner(),"preparation",Json.canonical(Map.of("revision","schedules-inbox-v1","owner_id",s.owner(),"session_hash",s.hash(),"security_epoch",s.epoch(),"preparation",preview)));}
    @SuppressWarnings("unchecked") Map<String,Object> preparation(Map<String,Object> row){Map<String,Object> out=Json.MAPPER.convertValue(Json.parse(vault.open((UUID)row.get("owner_id"),(UUID)row.get("id"),"schedule-preparation",(String)row.get("snapshot_ref")).getBytes(StandardCharsets.UTF_8)),Map.class);out.put("payload_hash",row.get("payload_hash"));return out;}
    Map<String,Object> prepare(AuthService.Session s,JsonNode body){
        exact(body,"operation","target_id","expected_version","definition");String operation=Json.string(body,"operation",7);boolean create=operation.equals("CREATE");if(!create&&!operation.equals("REPLACE"))throw ApiException.bad();
        UUID target=create?null:Json.uuid(body,"target_id");Long expected=create?null:number(body,"expected_version",1,MAX);if(create&&(!body.get("target_id").isNull()||!body.get("expected_version").isNull()))throw ApiException.bad();
        JsonNode definition=body.get("definition");var rule=definition(definition);AuthService.requireOwner(s);
        return writeTx.execute(tx->{lockOwner(s.owner());Instant now=databaseNow();var owner=session(s,true,false,now);unpaused(owner);project(s.owner(),projectId(definition));
            if(!create){var current=record(s.owner(),target,true);expected(current,expected);advance(expected);}
            Instant until=utc(definition.get("authorization_expires_at").asText());if(!until.isAfter(now)||until.isAfter(now.plusSeconds(2592000)))throw ApiException.bad();
            var first=ReminderRecurrence.next(rule,now,until);if(first==null)throw ApiException.bad();int count=0;var next=first;while(next!=null&&count<definition.get("max_deliveries").asInt()){count++;next=ReminderRecurrence.next(rule,next.instant(),until);}
            UUID id=UUID.randomUUID(),resolved=create?UUID.randomUUID():target;Instant expires=now.plusSeconds(300);Map<String,Object> preview=new LinkedHashMap<>();preview.put("id",id);preview.put("version",1);preview.put("status","PREPARED");preview.put("operation",operation);preview.put("target_id",resolved);preview.put("expected_version",expected);preview.put("definition",definition);preview.put("destination","OWN_INBOX");preview.put("next_occurrence",slot(first));preview.put("planned_delivery_count",count);preview.put("max_lateness_seconds",3600);preview.put("issued_at",now.toString());preview.put("expires_at",expires.toString());String hash=payloadHash(s,preview);
            db.update("INSERT INTO schedule_preparations(id,owner_id,session_hash,security_epoch,operation,target_id,expected_version,snapshot_ref,payload_hash,first_fire_at,issued_at,expires_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",id,s.owner(),s.hash(),s.epoch(),operation,resolved,expected,vault.seal(s.owner(),id,"schedule-preparation",Json.canonical(preview)),hash,stamp(first.instant()),stamp(now),stamp(expires));audit(s.owner(),"SCHEDULE_PREPARED",id,now);preview.put("payload_hash",hash);return preview;
        });
    }
    static void expected(Map<String,Object> current,long expected){if(value(current,"version")!=expected)throw new ScheduleConflict(value(current,"version"));}
    static long advance(long version){if(version>=MAX)throw new ApiException(409,"VERSION_EXHAUSTED");return version+1;}
    Map<String,Object> receipt(Map<String,Object> row){Map<String,Object> result=new LinkedHashMap<>();result.put("status",row==null?"NOT_OBSERVED":"COMMITTED");result.put("schedule_id",row==null?null:row.get("schedule_id"));result.put("version",row==null?null:row.get("result_version"));result.put("operation",row==null?null:row.get("operation"));return result;}
    Map<String,Object> prior(AuthService.Session s,UUID key,String digest){var rows=db.queryForList("SELECT * FROM schedule_mutations WHERE owner_id=? AND idempotency_key=?",s.owner(),key);if(rows.isEmpty())return null;var row=rows.getFirst();binding(s,row);if(!Json.equal(digest,(String)row.get("request_digest")))throw new ApiException(409,"IDEMPOTENCY_CONFLICT");return receipt(row);}
    String mutationDigest(UUID owner,String operation,UUID target,JsonNode body){return keys.digest(owner,"mutation",Json.canonical(Map.of("operation",operation,"target_id",target,"body",body)));}
    void mutation(AuthService.Session s,UUID key,String digest,UUID id,long version,String operation,Instant now){db.update("INSERT INTO schedule_mutations(owner_id,idempotency_key,session_hash,security_epoch,request_digest,schedule_id,result_version,operation,committed_at) VALUES (?,?,?,?,?,?,?,?,?)",s.owner(),key,s.hash(),s.epoch(),digest,id,version,operation,stamp(now));}
    Map<String,Object> reconcile(AuthService.Session s,UUID key){return read(s,()->{var rows=db.queryForList("SELECT * FROM schedule_mutations WHERE owner_id=? AND idempotency_key=?",s.owner(),key);return receipt(rows.isEmpty()?null:rows.getFirst());});}
    Map<String,Object> confirm(AuthService.Session s,UUID key,UUID id,JsonNode body){
        exact(body,"expected_preparation_version","payload_hash","confirm");number(body,"expected_preparation_version",1,1);String hash=Json.string(body,"payload_hash",64);if(!hash.matches("[0-9a-f]{64}")||!body.get("confirm").isBoolean()||!body.get("confirm").asBoolean())throw ApiException.bad();AuthService.requireOwner(s);
        return writeTx.execute(tx->{lockOwner(s.owner());Instant now=databaseNow();var owner=session(s,true,false,now);
            var rows=db.queryForList("SELECT * FROM schedule_preparations WHERE owner_id=? AND id=? FOR UPDATE",s.owner(),id);if(rows.isEmpty())throw ApiException.missing();var row=rows.getFirst();String operation=(String)row.get("operation");String digest=mutationDigest(s.owner(),operation,id,body);var prior=prior(s,key,digest);if(prior!=null)return prior;
            session(s,true,true,now);unpaused(owner);binding(s,row);if(row.get("consumed_at")!=null)throw new ApiException(409,"APPROVAL_CONSUMED");
            if(!time(row,"expires_at").isAfter(now)||!time(row,"first_fire_at").isAfter(now))throw new ApiException(409,"APPROVAL_EXPIRED");
            var preview=preparation(row);preview.remove("payload_hash");if(!Json.equal(hash,(String)row.get("payload_hash"))||!Json.equal(hash,payloadHash(s,preview)))throw new ApiException(409,"PRECONDITION_CHANGED");
            JsonNode snapshot=Json.MAPPER.valueToTree(preview);
            if(!Json.uuid(snapshot,"id").equals(id)||!Json.uuid(snapshot,"target_id").equals(row.get("target_id"))||!snapshot.get("operation").asText().equals(operation)||!Objects.equals(snapshot.get("expected_version").isNull()?null:snapshot.get("expected_version").longValue(),row.get("expected_version"))||!utc(snapshot.get("issued_at").asText()).equals(time(row,"issued_at"))||!utc(snapshot.get("expires_at").asText()).equals(time(row,"expires_at"))||!utc(snapshot.get("next_occurrence").get("planned_fire_at_utc").asText()).equals(time(row,"first_fire_at")))throw new ApiException(409,"PRECONDITION_CHANGED");
            JsonNode definition=Json.MAPPER.valueToTree(preview.get("definition"));definition(definition);project(s.owner(),projectId(definition));UUID target=(UUID)row.get("target_id");boolean create=operation.equals("CREATE");long version=1;
            if(!create){var current=record(s.owner(),target,true);expected(current,value(row,"expected_version"));version=advance(value(current,"version"));revoke(s.owner(),target,"REPLACED",now);}
            long active=db.queryForObject("SELECT count(*) FROM schedules s JOIN schedule_authorizations a ON a.owner_id=s.owner_id AND a.schedule_id=s.id AND a.schedule_version=s.version WHERE s.owner_id=? AND s.enabled AND s.security_epoch=? AND a.revoked_at IS NULL AND a.expires_at>? AND a.delivered_count<a.max_deliveries AND (s.next_fire_at IS NOT NULL OR EXISTS(SELECT 1 FROM schedule_occurrences o WHERE o.owner_id=s.owner_id AND o.schedule_id=s.id AND o.schedule_version=s.version AND o.status='PLANNED'))"+(create?"":" AND s.id<>?"),Long.class,create?new Object[]{s.owner(),s.epoch(),stamp(now)}:new Object[]{s.owner(),s.epoch(),stamp(now),target});
            if(active>=100)throw new ApiException(409,"SCHEDULE_LIMIT_REACHED");String canonical=Json.canonical(definition),definitionHash=definitionDigest(s.owner(),definition);String sealed=vault.seal(s.owner(),target,"schedule-definition:"+version,canonical);
            if(create)db.update("INSERT INTO schedules(id,owner_id,project_id,version,definition_ref,definition_digest,security_epoch,next_fire_at,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)",target,s.owner(),projectId(definition),version,sealed,definitionHash,s.epoch(),row.get("first_fire_at"),stamp(now),stamp(now));
            else db.update("UPDATE schedules SET project_id=?,version=?,enabled=true,definition_ref=?,definition_digest=?,security_epoch=?,next_fire_at=?,updated_at=? WHERE owner_id=? AND id=?",projectId(definition),version,sealed,definitionHash,s.epoch(),row.get("first_fire_at"),stamp(now),s.owner(),target);
            db.update("INSERT INTO schedule_authorizations(id,owner_id,schedule_id,schedule_version,preparation_id,recipient_owner_id,definition_digest,security_epoch,issued_at,expires_at,max_deliveries) VALUES (?,?,?,?,?,?,?,?,?,?,?)",UUID.randomUUID(),s.owner(),target,version,id,s.owner(),definitionHash,s.epoch(),row.get("issued_at"),stamp(utc(definition.get("authorization_expires_at").asText())),definition.get("max_deliveries").asInt());
            db.update("UPDATE schedule_preparations SET consumed_at=? WHERE owner_id=? AND id=?",stamp(now),s.owner(),id);mutation(s,key,digest,target,version,operation,now);audit(s.owner(),"SCHEDULE_"+operation+"_CONFIRMED",target,now);return prior(s,key,digest);
        });
    }
    void revoke(UUID owner,UUID id,String reason,Instant now){
        db.update("UPDATE schedule_authorizations SET revoked_at=? WHERE owner_id=? AND schedule_id=? AND revoked_at IS NULL",stamp(now),owner,id);
        db.update("UPDATE schedule_occurrences SET status='CANCELLED',reason_code=?,settled_at=?,lateness_seconds=GREATEST(0,FLOOR(EXTRACT(EPOCH FROM (?::timestamptz-planned_fire_at)))) WHERE owner_id=? AND schedule_id=? AND status='PLANNED'",reason,stamp(now),stamp(now),owner,id);
        db.update("UPDATE reminder_jobs SET status='CANCELLED',lease_generation=LEAST(lease_generation+1,9007199254740991),lease_owner=NULL,lease_until=NULL,finished_at=? WHERE owner_id=? AND schedule_id=? AND status IN ('QUEUED','RUNNING')",stamp(now),owner,id);
    }
    Map<String,Object> disable(AuthService.Session s,UUID key,UUID id,JsonNode body){
        exact(body,"expected_version");long expected=number(body,"expected_version",1,MAX);AuthService.requireOwner(s);String digest=mutationDigest(s.owner(),"DISABLE",id,body);
        return writeTx.execute(tx->{lockOwner(s.owner());Instant now=databaseNow();session(s,true,false,now);var prior=prior(s,key,digest);if(prior!=null)return prior;var current=record(s.owner(),id,true);expected(current,expected);long version=advance(expected);JsonNode definition=content(current);revoke(s.owner(),id,"DISABLED",now);
            db.update("UPDATE schedules SET enabled=false,version=?,definition_ref=?,next_fire_at=NULL,updated_at=? WHERE owner_id=? AND id=?",version,vault.seal(s.owner(),id,"schedule-definition:"+version,Json.canonical(definition)),stamp(now),s.owner(),id);mutation(s,key,digest,id,version,"DISABLE",now);audit(s.owner(),"SCHEDULE_DISABLED",id,now);return prior(s,key,digest);
        });
    }
    void audit(UUID owner,String event,UUID id,Instant now){db.update("INSERT INTO audit_events(id,owner_id,event_type,resource_id,happened_at) VALUES (?,?,?,?,?)",UUID.randomUUID(),owner,event,id,stamp(now));}
    record Boundary(Instant time,UUID id){}
    Boundary boundary(UUID owner,String context,String cursor){if(cursor==null)return null;if(!cursor.matches("[A-Za-z0-9_.-]{1,1024}"))throw ApiException.bad();String[] parts=keys.boundary(owner,context,cursor).split("\\|",-1);if(parts.length!=2)throw ApiException.bad();try{return new Boundary(Instant.parse(parts[0]),uuid(parts[1]));}catch(DateTimeException e){throw ApiException.bad();}}
    String cursor(UUID owner,String context,Map<String,Object> row,String time){return keys.cursor(owner,context,iso(row.get(time))+"|"+row.get("id"));}
    static void limit(int limit){if(limit<1||limit>50)throw ApiException.bad();}
    Map<String,Object> list(AuthService.Session s,UUID project,String cursor,int limit){limit(limit);return readTx.execute(tx->{Instant now=databaseNow();var owner=session(s,false,false,now);project(s.owner(),project);String context="schedules|"+project;var before=boundary(s.owner(),context,cursor);
        List<Object> params=new ArrayList<>();params.add(s.owner());String where="";if(project!=null){where+=" AND project_id=?";params.add(project);}if(before!=null){where+=" AND (created_at,id)<(?,?)";params.add(stamp(before.time()));params.add(before.id());}params.add(limit+1);
        var rows=db.queryForList("SELECT * FROM schedules WHERE owner_id=?"+where+" ORDER BY created_at DESC,id DESC LIMIT ?",params.toArray());Map<String,Object> out=new LinkedHashMap<>();out.put("items",rows.stream().limit(limit).map(row->view(row,owner,now)).toList());out.put("next_cursor",rows.size()>limit?cursor(s.owner(),context,rows.get(limit-1),"created_at"):null);return out;
    });}
    Map<String,Object> occurrences(AuthService.Session s,UUID id,String cursor,int limit){limit(limit);return read(s,()->{record(s.owner(),id,false);String context="occurrences|"+id;var before=boundary(s.owner(),context,cursor);List<Object> params=new ArrayList<>(List.of(s.owner(),id));String where="";if(before!=null){where=" AND (o.planned_fire_at,o.id)<(?,?)";params.add(stamp(before.time()));params.add(before.id());}params.add(limit+1);
        var rows=db.queryForList("SELECT o.*,n.id AS notification_id FROM schedule_occurrences o LEFT JOIN notifications n ON n.owner_id=o.owner_id AND n.occurrence_id=o.id WHERE o.owner_id=? AND o.schedule_id=?"+where+" ORDER BY o.planned_fire_at DESC,o.id DESC LIMIT ?",params.toArray());Map<String,Object> out=new LinkedHashMap<>();out.put("items",rows.stream().limit(limit).map(this::occurrenceView).toList());out.put("next_cursor",rows.size()>limit?cursor(s.owner(),context,rows.get(limit-1),"planned_fire_at"):null);return out;
    });}
    Map<String,Object> occurrenceView(Map<String,Object> row){Map<String,Object> out=new LinkedHashMap<>();for(String key:List.of("id","schedule_id","schedule_version","status","reason_code","notification_id","lateness_seconds"))out.put(key,row.get(key));out.put("planned_fire_at_utc",iso(row.get("planned_fire_at")));out.put("settled_at",iso(row.get("settled_at")));return out;}
    static final class ScheduleConflict extends RuntimeException{final long currentVersion;ScheduleConflict(long version){super("PRECONDITION_CHANGED");currentVersion=version;}}
}

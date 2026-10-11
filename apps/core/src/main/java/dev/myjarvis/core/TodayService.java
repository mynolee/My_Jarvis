package dev.myjarvis.core;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Local read model only. Never issues authority, decrypts task descriptions or runs providers. */
@Service
final class TodayService {
    private static final String OPEN=" owner_id=? AND status IN ('TODO','IN_PROGRESS')";
    private static final String PRIORITY="CASE priority WHEN 'HIGH' THEN 0 WHEN 'NORMAL' THEN 1 ELSE 2 END";
    private static final String SUMMARY="SELECT id,project_id,title,priority,status,due_at,version FROM tasks WHERE";
    private final JdbcTemplate db;
    private final Vault vault;
    private final TransactionTemplate readTx;

    TodayService(JdbcTemplate db,Vault vault,PlatformTransactionManager manager){
        this.db=db;this.vault=vault;readTx=new TransactionTemplate(manager);
        readTx.setReadOnly(true);readTx.setTimeout(15);
        readTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        // Do not silently inherit a caller's weaker isolation or writable transaction.
        readTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    Map<String,Object> get(AuthService.Session session){
        AuthService.requireOwner(session);
        return readTx.execute(transaction->{
            // One database clock value, owner and session validation in the same MVCC snapshot.
            var owners=db.queryForList("WITH checked AS MATERIALIZED (SELECT clock_timestamp() AS at) SELECT o.id,o.profile_ref,checked.at AS checked_at FROM owners o JOIN sessions s ON s.owner_id=o.id CROSS JOIN checked WHERE o.id=? AND o.security_epoch=? AND s.token_hash=? AND s.security_epoch=o.security_epoch AND s.revoked_at IS NULL AND s.created_at>checked.at-interval '12 hours' AND s.last_seen_at>checked.at-interval '2 hours'",session.owner(),session.epoch(),session.hash());
            if(owners.isEmpty())throw new ApiException(401,"SESSION_REVOKED");
            var owner=owners.getFirst();Instant checked=AuthService.instant(owner.get("checked_at"));
            String timezone=timezone(owner,session.owner());var window=dayWindow(checked,timezone);
            var top=db.queryForList(SUMMARY+OPEN+" ORDER BY CASE WHEN due_at<? THEN 0 ELSE 1 END,"+PRIORITY+",due_at ASC NULLS LAST,id ASC LIMIT 3",session.owner(),Timestamp.from(checked));
            var deadlines=db.queryForList(SUMMARY+OPEN+" AND due_at<? ORDER BY due_at ASC,"+PRIORITY+",id ASC LIMIT 20",session.owner(),Timestamp.from(window.end()));
            long total=Objects.requireNonNull(db.queryForObject("SELECT count(*) FROM tasks WHERE"+OPEN+" AND due_at<?",Long.class,session.owner(),Timestamp.from(window.end())));
            Map<String,Object> result=new LinkedHashMap<>();
            result.put("checked_at",outputInstant(checked));result.put("timezone",timezone);result.put("local_date",window.date().toString());
            result.put("day_start_at",outputInstant(window.start()));result.put("next_day_start_at",outputInstant(window.end()));result.put("deadline_window_end_at",outputInstant(window.end()));
            result.put("tasks",Map.of("status","AVAILABLE","top_tasks",top.stream().map(TodayService::summary).toList(),"deadlines",deadlines.stream().map(TodayService::summary).toList(),"deadline_total",total,"deadlines_truncated",total>deadlines.size()));
            for(String section:List.of("calendar","briefing","device"))result.put(section,Map.of("status","UNAVAILABLE","reason","NOT_IMPLEMENTED"));
            return result;
        });
    }

    private String timezone(Map<String,Object> owner,UUID id){
        if(owner.get("profile_ref")==null)return "UTC";
        tools.jackson.databind.JsonNode profile;
        try{
            profile=Json.parse(vault.open(id,id,"owner-profile",(String)owner.get("profile_ref")).getBytes(StandardCharsets.UTF_8));
            Json.fields(profile,"display_name","locale","timezone");
            if(profile.size()!=3)throw new IllegalStateException("Invalid profile record");
            // Apply the existing profile field rules without saving or mutating anything.
            if(!profile.path("locale").isTextual()||!OwnerProfileService.LOCALES.contains(profile.path("locale").asText())||!profile.path("timezone").isTextual())throw new IllegalStateException("Invalid profile record");
            var display=profile.get("display_name");
            if(!display.isNull()){
                String name=Json.string(profile,"display_name",80);
                if(name.codePoints().allMatch(OwnerProfileService::blankCodePoint)||name.codePoints().anyMatch(Character::isISOControl)||name.getBytes(StandardCharsets.UTF_8).length>320)throw new IllegalStateException("Invalid profile record");
            }
        }catch(ApiException error){throw new IllegalStateException("Invalid profile record");}
        String timezone=profile.path("timezone").asText();
        if(!timezone.equals("UTC")&&(!ZoneId.getAvailableZoneIds().contains(timezone)||!timezone.contains("/")||timezone.startsWith("SystemV/")))throw new IllegalStateException("Invalid profile timezone");
        return timezone;
    }
    record DayWindow(LocalDate date,Instant start,Instant end) {}
    static DayWindow dayWindow(Instant checked,String timezone){
        ZoneId zone=ZoneId.of(timezone);LocalDate date=checked.atZone(zone).toLocalDate();
        if(date.getYear()<1||date.getYear()>9999)throw new IllegalStateException("Unrepresentable local date");
        Instant start=date.atStartOfDay(zone).toInstant(),end=date.plusDays(1).atStartOfDay(zone).toInstant();
        outputInstant(checked);outputInstant(start);outputInstant(end);
        if(checked.isBefore(start)||!checked.isBefore(end))throw new IllegalStateException("Invalid local day");
        return new DayWindow(date,start,end);
    }
    private static String outputInstant(Instant value){
        int year=value.atOffset(ZoneOffset.UTC).getYear();
        if(year<1||year>9999||value.getNano()%1000!=0)throw new IllegalStateException("Unrepresentable timestamp");
        return value.toString();
    }
    private static Map<String,Object> summary(Map<String,Object> row){
        if(!(row.get("id") instanceof UUID)||(row.get("project_id")!=null&&!(row.get("project_id") instanceof UUID)))throw new IllegalStateException("Invalid task identity");
        if(!(row.get("title") instanceof String title)||title.isEmpty()||title.codePointCount(0,title.length())>200||title.indexOf(0)>=0||title.codePoints().allMatch(TaskService::blankCodePoint))throw new IllegalStateException("Invalid task title");
        if(!Set.of("HIGH","NORMAL","LOW").contains(row.get("priority"))||!Set.of("TODO","IN_PROGRESS").contains(row.get("status")))throw new IllegalStateException("Invalid task state");
        if(!(row.get("version") instanceof Long version)||version<1||version>9007199254740991L)throw new IllegalStateException("Invalid task version");
        Map<String,Object> result=new LinkedHashMap<>();
        for(String field:List.of("id","project_id","title","priority","status","version"))result.put(field,row.get(field));
        Instant due=AuthService.instant(row.get("due_at"));result.put("due_at",due==null?null:outputInstant(due));return result;
    }
}

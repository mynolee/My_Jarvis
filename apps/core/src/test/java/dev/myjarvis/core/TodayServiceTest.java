package dev.myjarvis.core;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL snapshot, authorization and read-only invariants; synthetic content only. */
class TodayServiceTest extends ReminderTestSupport {
    TodayService today(){return context.getBean(TodayService.class);}
    Instant now(){return AuthService.instant(db.queryForObject("SELECT clock_timestamp()",Object.class));}
    UUID task(UUID id,String priority,String status,Instant due){
        db.update("INSERT INTO tasks(id,owner_id,title,description_ref,priority,status,due_at) VALUES (?,?,?,'not-read-by-today',?,?,?)",id,owner,"Synthetic "+id,priority,status,due==null?null:Timestamp.from(due));return id;
    }
    UUID task(String priority,String status,Instant due){return task(UUID.randomUUID(),priority,status,due);}
    tools.jackson.databind.JsonNode result(){return body(today().get(session));}
    List<String> ids(tools.jackson.databind.JsonNode rows){List<String> ids=new ArrayList<>();rows.forEach(row->ids.add(row.path("id").asText()));return ids;}
    void zone(String timezone){Map<String,Object> profile=new LinkedHashMap<>();profile.put("display_name",null);profile.put("locale","ko-KR");profile.put("timezone",timezone);db.update("UPDATE owners SET profile_ref=? WHERE id=?",vault.seal(owner,owner,"owner-profile",Json.MAPPER.writeValueAsString(profile)),owner);}

    @Test void ranksEntireOwnerSetRatherThanFirstPage(){
        UUID urgent=task("HIGH","TODO",now().minusSeconds(3600));
        db.update("UPDATE tasks SET created_at=now()-interval '2 days' WHERE id=?",urgent);
        for(int i=0;i<125;i++)task("NORMAL","TODO",null);
        var response=result();assertEquals(urgent.toString(),response.path("tasks").path("top_tasks").get(0).path("id").asText());
        assertEquals(3,response.path("tasks").path("top_tasks").size());assertEquals(1,response.path("tasks").path("deadline_total").asLong());
    }
    @Test void orderingExcludesClosedAndUsesPriorityThenDueThenIdWithNullsLast(){
        Instant before=now().minusSeconds(7200);UUID low=task("LOW","TODO",before);
        UUID first=UUID.fromString("00000000-0000-4000-8000-000000000001"),second=UUID.fromString("00000000-0000-4000-8000-000000000002");
        task(second,"HIGH","TODO",before.plusSeconds(1));task(first,"HIGH","IN_PROGRESS",before.plusSeconds(1));
        task("HIGH","DONE",before.minusSeconds(1));task("HIGH","CANCELLED",before.minusSeconds(1));
        UUID highNull=task("HIGH","TODO",null);task("LOW","TODO",null);
        var response=result();assertEquals(List.of(first.toString(),second.toString(),low.toString()),ids(response.path("tasks").path("top_tasks")));
        assertEquals(List.of(low.toString(),first.toString(),second.toString()),ids(response.path("tasks").path("deadlines")));
        db.update("UPDATE tasks SET status='DONE' WHERE due_at IS NOT NULL");
        Instant future=now().plusSeconds(172800);UUID normal=task("NORMAL","TODO",future);UUID highDue=task("HIGH","TODO",future);
        assertEquals(List.of(highDue.toString(),highNull.toString(),normal.toString()),ids(result().path("tasks").path("top_tasks")));
    }
    @Test void deadlinesHaveExclusiveLocalMidnightNoLowerBoundExactCountAndTruncation(){
        zone("Asia/Seoul");Instant end=TodayService.dayWindow(now(),"Asia/Seoul").end();
        UUID ancient=task("LOW","TODO",Instant.parse("2000-01-01T00:00:00Z"));
        for(int i=0;i<23;i++)task("NORMAL","TODO",end.minusSeconds(100+i));
        task("HIGH","TODO",end);task("HIGH","TODO",end.plusSeconds(1));task("HIGH","TODO",null);
        task("HIGH","DONE",end.minusSeconds(1));task("HIGH","CANCELLED",end.minusSeconds(1));
        var response=result();var tasks=response.path("tasks");assertEquals(24,tasks.path("deadline_total").asLong());assertTrue(tasks.path("deadlines_truncated").asBoolean());assertEquals(20,tasks.path("deadlines").size());assertEquals(ancient.toString(),tasks.path("deadlines").get(0).path("id").asText());
        assertEquals(end.toString(),response.path("deadline_window_end_at").asText());assertEquals(response.path("next_day_start_at"),response.path("deadline_window_end_at"));
        db.update("DELETE FROM tasks WHERE id<>?",ancient);assertFalse(result().path("tasks").path("deadlines_truncated").asBoolean());
    }
    @Test void checkedTimeComesFromDatabaseAndOwnerTimezoneDeterminesDate(){
        zone("Pacific/Kiritimati");Instant lower=now();var response=result();Instant upper=now();Instant checked=Instant.parse(response.path("checked_at").asText());
        assertFalse(checked.isBefore(lower));assertFalse(checked.isAfter(upper));assertEquals(0,checked.getNano()%1000);
        var window=TodayService.dayWindow(checked,"Pacific/Kiritimati");assertEquals(window.date().toString(),response.path("local_date").asText());assertEquals(window.start().toString(),response.path("day_start_at").asText());
        assertEquals("Pacific/Kiritimati",response.path("timezone").asText());
    }
    @Test void timezoneRulesCoverDstShortLongDaysAndExactMidnightUsingPostgresInstants(){
        for(var sample:List.of(
            List.of("2026-03-08T07:30:00Z","America/New_York","2026-03-08T05:00:00Z","2026-03-09T04:00:00Z"),
            List.of("2026-11-01T07:30:00Z","America/New_York","2026-11-01T04:00:00Z","2026-11-02T05:00:00Z"),
            List.of("2026-10-09T15:00:00Z","Asia/Seoul","2026-10-09T15:00:00Z","2026-10-10T15:00:00Z"),
            List.of("2026-10-09T14:59:59.999999Z","Asia/Seoul","2026-10-08T15:00:00Z","2026-10-09T15:00:00Z"),
            List.of("2018-11-04T12:00:00Z","America/Sao_Paulo","2018-11-04T03:00:00Z","2018-11-05T02:00:00Z"))){
            Instant checked=AuthService.instant(db.queryForObject("SELECT ?::timestamptz",Object.class,sample.get(0)));var window=TodayService.dayWindow(checked,sample.get(1));
            assertEquals(Instant.parse(sample.get(2)),window.start());assertEquals(Instant.parse(sample.get(3)),window.end());
        }
    }
    @Test void sessionAndOwnerAreRevalidatedWithoutTouchingIdleExpiry(){
        assertEquals("AUTHENTICATION_REQUIRED",assertThrows(ApiException.class,()->today().get(null)).code);
        var forged=new AuthService.Session(session.hash(),UUID.randomUUID(),session.reauth(),session.epoch());code("SESSION_REVOKED",()->today().get(forged));
        var mismatched=new AuthService.Session("different-session",owner,session.reauth(),session.epoch());code("SESSION_REVOKED",()->today().get(mismatched));
        db.update("UPDATE sessions SET last_seen_at=now()-interval '3 hours'");code("SESSION_REVOKED",()->today().get(session));
        db.update("UPDATE sessions SET last_seen_at=now(),created_at=now()-interval '13 hours'");code("SESSION_REVOKED",()->today().get(session));
        db.update("UPDATE sessions SET created_at=now(),revoked_at=now()");code("SESSION_REVOKED",()->today().get(session));
        db.update("UPDATE sessions SET revoked_at=NULL");db.update("UPDATE owners SET security_epoch=security_epoch+1");code("SESSION_REVOKED",()->today().get(session));
    }
    @Test void otherOwnerTasksCannotLeak(){
        task("NORMAL","TODO",null);UUID other=UUID.randomUUID();
        db.execute("ALTER TABLE owners DROP CONSTRAINT owners_singleton_key");
        try{
            db.update("INSERT INTO owners(id,login_name,password_hash) VALUES (?,'other-today-owner','unused')",other);
            db.update("INSERT INTO tasks(id,owner_id,title,description_ref,priority,status,due_at) VALUES (?,?,'foreign-secret','unused','HIGH','TODO',now()-interval '1 year')",UUID.randomUUID(),other);
            var response=result();assertEquals(1,response.path("tasks").path("top_tasks").size());assertEquals(0,response.path("tasks").path("deadline_total").asLong());assertFalse(response.toString().contains("foreign-secret"));
        }finally{
            db.update("DELETE FROM tasks WHERE owner_id=?",other);db.update("DELETE FROM owners WHERE id=?",other);
            db.execute("ALTER TABLE owners ADD CONSTRAINT owners_singleton_key UNIQUE(singleton)");
        }
    }
    Map<String,String> databaseContents(){
        Map<String,String> snapshot=new TreeMap<>();
        for(String table:db.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename",String.class)){
            if(!table.matches("[a-z_][a-z0-9_]*"))throw new AssertionError("Unexpected table identifier");
            snapshot.put(table,db.queryForObject("SELECT COALESCE(jsonb_agg(value ORDER BY value::text),'[]'::jsonb)::text FROM (SELECT to_jsonb(t) AS value FROM "+table+" t) rows",String.class));
        }return snapshot;
    }
    @Test void allTablesIncludingSessionsAuditAndNoncesRemainByteEquivalent(){
        zone("Asia/Seoul");task("HIGH","IN_PROGRESS",now().minusSeconds(1));db.update("UPDATE owners SET execution_paused=true");
        var before=databaseContents();var response=result();result();assertEquals(before,databaseContents());
        assertEquals("AVAILABLE",response.path("tasks").path("status").asText());
        for(String section:List.of("calendar","briefing","device")){assertEquals("UNAVAILABLE",response.path(section).path("status").asText());assertEquals("NOT_IMPLEMENTED",response.path(section).path("reason").asText());assertEquals(2,response.path(section).size());}
        var summary=response.path("tasks").path("top_tasks").get(0);assertEquals(7,summary.size());assertFalse(summary.has("description"));assertFalse(summary.has("description_ref"));assertEquals(summary,response.path("tasks").path("deadlines").get(0));
    }
    @Test void concurrentCommitCannotSplitTopRowsDeadlineRowsAndTotal(){
        task("LOW","TODO",Instant.parse("2000-01-01T00:00:00Z"));
        JdbcTemplate intercepted=new JdbcTemplate(Objects.requireNonNull(db.getDataSource())){
            boolean inserted;
            @Override public List<Map<String,Object>> queryForList(String sql,Object... args){
                if(!inserted&&sql.startsWith("SELECT id,project_id,title")){
                    inserted=true;
                    assertEquals("on",super.queryForObject("SHOW transaction_read_only",String.class));
                    assertEquals("repeatable read",super.queryForObject("SHOW transaction_isolation",String.class));
                    try(var executor=Executors.newSingleThreadExecutor()){
                        executor.submit(()->task("HIGH","TODO",Instant.parse("1999-01-01T00:00:00Z"))).get(5,TimeUnit.SECONDS);
                    }catch(Exception error){throw new AssertionError(error);}
                }
                return super.queryForList(sql,args);
            }
        };
        var service=new TodayService(intercepted,vault,context.getBean(PlatformTransactionManager.class));
        var response=body(service.get(session)).path("tasks");assertEquals(1,response.path("top_tasks").size());assertEquals(1,response.path("deadlines").size());assertEquals(1,response.path("deadline_total").asLong());assertEquals(2,result().path("tasks").path("deadline_total").asLong());
    }
    @Test void databaseAndCorruptProfileErrorsNeverBecomeAvailableEmptyData(){
        db.execute("ALTER TABLE tasks RENAME TO today_test_hidden_tasks");
        try{assertThrows(org.springframework.dao.DataAccessException.class,()->today().get(session));}finally{db.execute("ALTER TABLE today_test_hidden_tasks RENAME TO tasks");}
        db.update("UPDATE owners SET profile_ref='corrupt' WHERE id=?",owner);assertThrows(RuntimeException.class,()->today().get(session));
    }
    @Test void invalidStoredTaskTitlesAndTimestampsFailClosed(){
        UUID id=task("HIGH","TODO",null);
        for(String title:List.of(" ","\u00a0\u2003\ufeff","\t\r\n")){
            db.update("UPDATE tasks SET title=? WHERE id=?",title,id);
            assertThrows(IllegalStateException.class,()->today().get(session));
        }
        db.update("UPDATE tasks SET title='Synthetic valid title' WHERE id=?",id);
        for(String timestamp:List.of("infinity","-infinity","10000-01-01 00:00:00+00","0001-01-01 00:00:00+00 BC")){
            db.update("UPDATE tasks SET due_at=?::timestamptz WHERE id=?",timestamp,id);
            assertThrows(RuntimeException.class,()->today().get(session));
        }
        db.update("UPDATE tasks SET due_at=NULL WHERE id=?",id);assertEquals(1,result().path("tasks").path("top_tasks").size());
    }
    @Test void invalidStoredProfileFieldsFailClosed(){
        for(var profile:List.of(
            Map.of("display_name","Valid","locale","unknown","timezone","UTC"),
            Map.of("display_name"," ","locale","ko-KR","timezone","UTC"),
            Map.of("display_name","control\nname","locale","ko-KR","timezone","UTC"),
            Map.of("display_name","x".repeat(81),"locale","ko-KR","timezone","UTC"),
            Map.of("display_name",3,"locale","ko-KR","timezone","UTC"),
            Map.of("display_name","Valid","locale","ko-KR","timezone","Unknown/Nowhere"))){
            db.update("UPDATE owners SET profile_ref=? WHERE id=?",vault.seal(owner,owner,"owner-profile",Json.MAPPER.writeValueAsString(profile)),owner);
            assertThrows(IllegalStateException.class,()->today().get(session));
        }
    }
    @Test void unrepresentableNextDayAndLocalDateFailClosed(){
        assertThrows(IllegalStateException.class,()->TodayService.dayWindow(Instant.parse("9999-12-31T12:00:00Z"),"UTC"));
        assertThrows(IllegalStateException.class,()->TodayService.dayWindow(Instant.parse("0001-01-01T00:00:00Z"),"America/New_York"));
    }

}

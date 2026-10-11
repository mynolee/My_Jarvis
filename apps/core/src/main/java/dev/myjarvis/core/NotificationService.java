package dev.myjarvis.core;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** Same-owner encrypted inbox. Reading never acknowledges or marks an item opened. */
@Service
final class NotificationService {
    private final ScheduleService s;
    NotificationService(ScheduleService schedules){s=schedules;}
    Map<String,Object> record(UUID owner,UUID id,boolean lock){var rows=s.db.queryForList("SELECT * FROM notifications WHERE owner_id=? AND id=?"+(lock?" FOR UPDATE":""),owner,id);if(rows.isEmpty())throw ApiException.missing();s.project(owner,(UUID)rows.getFirst().get("project_id"));return rows.getFirst();}
    Map<String,Object> view(Map<String,Object> row){
        JsonNode content=Json.parse(s.vault.open((UUID)row.get("owner_id"),(UUID)row.get("id"),"notification-content",(String)row.get("content_ref")).getBytes(StandardCharsets.UTF_8));ScheduleService.exact(content,"title","text");
        Map<String,Object> result=new LinkedHashMap<>();for(String field:List.of("id","occurrence_id","schedule_id","schedule_version","project_id","lateness_seconds"))result.put(field,row.get(field));result.put("title",content.get("title").asText());result.put("text",content.get("text").asText());result.put("planned_fire_at_utc",ScheduleService.iso(row.get("planned_fire_at")));result.put("created_at",ScheduleService.iso(row.get("created_at")));result.put("opened_at",ScheduleService.iso(row.get("opened_at")));result.put("status",row.get("opened_at")==null?"CREATED":"OPENED");return result;
    }
    Map<String,Object> get(AuthService.Session session,UUID id){return s.read(session,()->view(record(session.owner(),id,false)));}
    Map<String,Object> open(AuthService.Session session,UUID id,JsonNode body){ScheduleService.exact(body);AuthService.requireOwner(session);return s.writeTx.execute(tx->{s.lockOwner(session.owner());Instant now=s.databaseNow();s.session(session,true,false,now);var row=record(session.owner(),id,true);if(row.get("opened_at")==null){s.db.update("UPDATE notifications SET opened_at=? WHERE owner_id=? AND id=?",ScheduleService.stamp(now),session.owner(),id);s.audit(session.owner(),"NOTIFICATION_OPENED",id,now);row=record(session.owner(),id,false);}return view(row);});}
    Map<String,Object> list(AuthService.Session session,UUID project,String cursor,int limit){ScheduleService.limit(limit);return s.read(session,()->{s.project(session.owner(),project);String context="notifications|"+project;var before=s.boundary(session.owner(),context,cursor);List<Object> params=new ArrayList<>();params.add(session.owner());String where="";if(project!=null){where+=" AND project_id=?";params.add(project);}if(before!=null){where+=" AND (created_at,id)<(?,?)";params.add(ScheduleService.stamp(before.time()));params.add(before.id());}params.add(limit+1);var rows=s.db.queryForList("SELECT * FROM notifications WHERE owner_id=?"+where+" ORDER BY created_at DESC,id DESC LIMIT ?",params.toArray());Map<String,Object> result=new LinkedHashMap<>();result.put("items",rows.stream().limit(limit).map(this::view).toList());result.put("next_cursor",rows.size()>limit?s.cursor(session.owner(),context,rows.get(limit-1),"created_at"):null);return result;});}
}

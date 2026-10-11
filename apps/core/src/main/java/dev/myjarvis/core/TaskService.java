package dev.myjarvis.core;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;

@Service
final class TaskService {
    static final Set<String> STATUSES=Set.of("TODO","IN_PROGRESS","DONE","CANCELLED");
    static final String[] CREATE_FIELDS={"title","description","project_id","due_at","priority","estimated_minutes"};
    static final String[] PATCH_FIELDS={"title","description","project_id","due_at","priority","estimated_minutes","status","expected_version"};
    private final TaskIntentService intents;
    private final JdbcTemplate db;
    private final Vault vault;
    TaskService(TaskIntentService intents){this.intents=intents;this.db=intents.db;this.vault=intents.vault;}

    Map<String,Object> create(AuthService.Session session,UUID key,JsonNode body){return mutate(session,key,null,body);}
    Map<String,Object> update(AuthService.Session session,UUID key,UUID id,JsonNode body){return mutate(session,key,id,body);}
    private Map<String,Object> mutate(AuthService.Session session,UUID key,UUID target,JsonNode body){
        boolean create=target==null;
        var allowed=new ArrayList<>(Arrays.asList(create?CREATE_FIELDS:PATCH_FIELDS));allowed.add("user_intent_id");
        Json.fields(body,allowed.toArray(String[]::new));UUID intentId=Json.uuid(body,"user_intent_id");
        var args=body.deepCopy();((tools.jackson.databind.node.ObjectNode)args).remove("user_intent_id");validateArguments(args,create);
        String requestHash=Json.hash(Json.canonical(Map.of("method",create?"POST":"PATCH","target",create?"/api/v1/tasks":"/api/v1/tasks/"+target,"body",body)));
        return intents.tx.execute(transaction->{
            var owner=intents.lockSession(session);
            var prior=db.queryForList("SELECT * FROM task_mutations WHERE owner_id=? AND idempotency_key=?",session.owner(),key);
            if(!prior.isEmpty()){
                var row=prior.getFirst();TaskIntentService.validateBinding(session,row);
                if(!Json.equal(requestHash,(String)row.get("request_hash")))throw new ApiException(409,"IDEMPOTENCY_CONFLICT");
                return intents.readResponse(session.owner(),key,"task-mutation",(String)row.get("response_ref"));
            }
            TaskIntentService.requireUnpaused(owner);
            var intent=intents.intent(session,intentId);
            if(key.equals(intent.get("issuance_key")))throw ApiException.bad();
            UUID id=create?(UUID)intent.get("target_id"):target;
            intents.verify(intent,create?"task.create":"task.update",id,args);
            validateProject(db,session.owner(),args);
            if(create){
                db.update("INSERT INTO tasks(id,owner_id,project_id,title,description_ref,due_at,priority,status,estimated_minutes) VALUES (?,?,?,?,?,?,?,'TODO',?)",
                    id,session.owner(),nullableUuid(args,"project_id"),args.get("title").asText(),
                    vault.seal(session.owner(),id,"task-description",args.get("description").asText()),
                    nullableTimestamp(args,"due_at"),args.get("priority").asText(),nullableInteger(args,"estimated_minutes"));
            }else{
                var current=owned(db,session.owner(),id);checkVersion(vault,current,Json.integer(args,"expected_version"));
                // Explicit allowlisted assignments only. A DONE task may be deliberately reopened.
                List<String> sets=new ArrayList<>();List<Object> values=new ArrayList<>();
                for(String field:PATCH_FIELDS){
                    if(field.equals("expected_version")||!args.has(field))continue;
                    sets.add((field.equals("description")?"description_ref":field)+"=?");
                    values.add(switch(field){
                        case "description" -> vault.seal(session.owner(),id,"task-description",args.get(field).asText());
                        case "project_id" -> nullableUuid(args,field);
                        case "due_at" -> nullableTimestamp(args,field);
                        case "estimated_minutes" -> nullableInteger(args,field);
                        default -> args.get(field).asText();
                    });
                }
                values.add(session.owner());values.add(id);values.add(Json.integer(args,"expected_version"));
                int changed=db.update("UPDATE tasks SET "+String.join(",",sets)+",version=version+1,updated_at=now() WHERE owner_id=? AND id=? AND version=?",values.toArray());
                if(changed!=1)throw new TaskConflict(view(vault,owned(db,session.owner(),id)));
            }
            var result=view(vault,owned(db,session.owner(),id));
            int consumed=db.update("UPDATE task_user_intents SET consumed_at=now() WHERE owner_id=? AND id=? AND consumed_at IS NULL AND expires_at>clock_timestamp()",session.owner(),intentId);
            if(consumed!=1)throw new ApiException(409,"INTENT_EXPIRED");
            db.update("INSERT INTO task_mutations(owner_id,idempotency_key,session_hash,security_epoch,request_hash,intent_id,task_id,response_ref) VALUES (?,?,?,?,?,?,?,?)",
                session.owner(),key,session.hash(),session.epoch(),requestHash,intentId,id,vault.seal(session.owner(),key,"task-mutation",Json.MAPPER.writeValueAsString(result)));
            intents.audit(session.owner(),create?"TASK_CREATED":"TASK_UPDATED",id);
            intents.audit(session.owner(),"TASK_INTENT_CONSUMED",intentId);
            return result;
        });
    }

    Map<String,Object> get(AuthService.Session session,UUID id){return intents.tx.execute(transaction->{intents.lockSession(session);return view(vault,owned(db,session.owner(),id));});}
    Map<String,Object> reconcile(AuthService.Session session,UUID key){return intents.tx.execute(transaction->{
        intents.lockSession(session);
        var rows=db.queryForList("SELECT * FROM task_mutations WHERE owner_id=? AND idempotency_key=?",session.owner(),key);
        Map<String,Object> response=new LinkedHashMap<>();
        if(rows.isEmpty()){response.put("status","NOT_OBSERVED");response.put("task",null);}
        else{var row=rows.getFirst();TaskIntentService.validateBinding(session,row);response.put("status","COMMITTED");response.put("task",intents.readResponse(session.owner(),key,"task-mutation",(String)row.get("response_ref")));}
        return response;
    });}
    Map<String,Object> list(AuthService.Session session,Map<String,String[]> query){
        for(var entry:query.entrySet())if(!Set.of("status","from","to","cursor","limit").contains(entry.getKey())||entry.getValue().length!=1)throw ApiException.bad();
        String status=parameter(query,"status"),from=parameter(query,"from"),to=parameter(query,"to"),cursor=parameter(query,"cursor"),limitText=parameter(query,"limit");
        if(status!=null&&!STATUSES.contains(status))throw ApiException.bad();
        Instant lower=from==null?null:timestamp(from),upper=to==null?null:timestamp(to);
        if(lower!=null&&upper!=null&&!lower.isBefore(upper))throw ApiException.bad();
        int limit=20;
        if(limitText!=null){try{if(!limitText.matches("[1-9][0-9]{0,2}"))throw ApiException.bad();limit=Integer.parseInt(limitText);if(limit>100)throw ApiException.bad();}catch(NumberFormatException e){throw ApiException.bad();}}
        UUID cursorId=null;if(cursor!=null){if(cursor.length()>512)throw ApiException.bad();cursorId=parseUuid(cursor);}
        final int pageSize=limit;final UUID after=cursorId;
        return intents.tx.execute(transaction->{
            intents.lockSession(session);StringBuilder sql=new StringBuilder("SELECT * FROM tasks WHERE owner_id=?");List<Object> values=new ArrayList<>();values.add(session.owner());
            if(status!=null){sql.append(" AND status=?");values.add(status);}
            if(lower!=null){sql.append(" AND due_at>=?");values.add(java.sql.Timestamp.from(lower));}
            if(upper!=null){sql.append(" AND due_at<?");values.add(java.sql.Timestamp.from(upper));}
            if(after!=null){var anchor=owned(db,session.owner(),after);sql.append(" AND (created_at,id)<(?,?)");values.add(anchor.get("created_at"));values.add(after);}
            sql.append(" ORDER BY created_at DESC,id DESC LIMIT ?");values.add(pageSize+1);
            var rows=db.queryForList(sql.toString(),values.toArray());boolean more=rows.size()>pageSize;
            var page=rows.subList(0,Math.min(pageSize,rows.size()));
            Map<String,Object> response=new LinkedHashMap<>();response.put("items",page.stream().map(row->view(vault,row)).toList());response.put("next_cursor",more?page.getLast().get("id").toString():null);return response;
        });
    }
    private static String parameter(Map<String,String[]> query,String key){return query.containsKey(key)?query.get(key)[0]:null;}
    static UUID parseUuid(String text){try{UUID id=UUID.fromString(text);if(!id.toString().equals(text))throw ApiException.bad();return id;}catch(IllegalArgumentException e){throw ApiException.bad();}}
    static void validateArguments(JsonNode args,boolean create){
        if(args==null)throw ApiException.bad();Json.validate(args);Json.fields(args,create?CREATE_FIELDS:PATCH_FIELDS);
        if(create){for(String field:CREATE_FIELDS)if(!args.has(field))throw ApiException.bad();}
        else if(!args.has("expected_version")||Json.integer(args,"expected_version")<1||args.size()<2)throw ApiException.bad();
        if(args.has("title")){String title=Json.string(args,"title",200);if(title.indexOf(0)>=0||title.codePoints().allMatch(TaskService::blankCodePoint))throw ApiException.bad();}
        if(args.has("description")){var value=args.get("description");if(!value.isTextual()||value.asText().codePointCount(0,value.asText().length())>8000)throw ApiException.bad();}
        if(args.has("project_id")&&!args.get("project_id").isNull())Json.uuid(args,"project_id");
        if(args.has("due_at")&&!args.get("due_at").isNull())timestamp(Json.string(args,"due_at",262144));
        if(args.has("priority")&&!Set.of("LOW","NORMAL","HIGH").contains(Json.string(args,"priority",16)))throw ApiException.bad();
        if(args.has("status")&&!STATUSES.contains(Json.string(args,"status",16)))throw ApiException.bad();
        if(args.has("estimated_minutes")&&!args.get("estimated_minutes").isNull()){long n=Json.integer(args,"estimated_minutes");if(n<1||n>525600)throw ApiException.bad();}
    }
    // Explicit shared Unicode White_Space + FEFF set; never normalize a bound title.
    static boolean blankCodePoint(int cp){return cp>=0x09&&cp<=0x0D||cp==0x20||cp==0x85||cp==0xA0||cp==0x1680||cp>=0x2000&&cp<=0x200A||cp==0x2028||cp==0x2029||cp==0x202F||cp==0x205F||cp==0x3000||cp==0xFEFF;}
    static Instant timestamp(String text){
        // Explicit microsecond precision avoids JDBC rounding of dates and query bounds.
        // Lowercase T/Z is accepted; original input remains unchanged for intent binding.
        var match=java.util.regex.Pattern.compile("([0-9]{4}-[0-9]{2}-[0-9]{2})[Tt]([0-9]{2}:[0-9]{2}:[0-9]{2})(?:\\.([0-9]{1,6}))?([Zz]|([+-])([0-9]{2}):([0-9]{2}))").matcher(text);
        if(!match.matches())throw ApiException.bad();
        try{
            var local=LocalDateTime.parse(match.group(1)+"T"+match.group(2));if(local.getYear()<1)throw ApiException.bad();
            String fraction=match.group(3);if(fraction!=null)local=local.withNano(Integer.parseInt((fraction+"000000000").substring(0,9)));
            int offset=0;
            if(match.group(5)!=null){int hours=Integer.parseInt(match.group(6)),minutes=Integer.parseInt(match.group(7));if(hours>23||minutes>59)throw ApiException.bad();offset=(hours*60+minutes)*60*(match.group(5).equals("-")?-1:1);}
            Instant instant=local.toInstant(ZoneOffset.UTC).minusSeconds(offset);
            int utcYear=instant.atOffset(ZoneOffset.UTC).getYear();if(utcYear<1||utcYear>9999)throw ApiException.bad();return instant;
        }catch(java.time.DateTimeException|NumberFormatException e){throw ApiException.bad();}
    }
    static UUID nullableUuid(JsonNode args,String field){return args.hasNonNull(field)?Json.uuid(args,field):null;}
    static Integer nullableInteger(JsonNode args,String field){return args.hasNonNull(field)?(int)Json.integer(args,field):null;}
    static java.sql.Timestamp nullableTimestamp(JsonNode args,String field){return args.hasNonNull(field)?java.sql.Timestamp.from(timestamp(args.get(field).asText())):null;}
    static void validateProject(JdbcTemplate db,UUID owner,JsonNode args){
        UUID project=nullableUuid(args,"project_id");
        if(project!=null&&!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM projects WHERE owner_id=? AND id=?)",Boolean.class,owner,project)))throw ApiException.missing();
    }
    static Map<String,Object> owned(JdbcTemplate db,UUID owner,UUID id){var rows=db.queryForList("SELECT * FROM tasks WHERE owner_id=? AND id=?",owner,id);if(rows.isEmpty())throw ApiException.missing();return rows.getFirst();}
    static void checkVersion(Vault vault,Map<String,Object> row,long expected){if(((Number)row.get("version")).longValue()!=expected)throw new TaskConflict(view(vault,row));if(expected>=9007199254740991L)throw new ApiException(409,"VERSION_EXHAUSTED");}
    static Map<String,Object> view(Vault vault,Map<String,Object> row){
        Map<String,Object> result=new LinkedHashMap<>();for(String field:List.of("id","project_id","title","priority","status","estimated_minutes","version"))result.put(field,row.get(field));
        result.put("description",vault.open((UUID)row.get("owner_id"),(UUID)row.get("id"),"task-description",(String)row.get("description_ref")));
        for(String field:List.of("due_at","created_at","updated_at")){Instant value=AuthService.instant(row.get(field));result.put(field,value==null?null:value.toString());}return result;
    }
    static final class TaskConflict extends RuntimeException {
        final Map<String,Object> currentTask;
        TaskConflict(Map<String,Object> currentTask){super("PRECONDITION_CHANGED");this.currentTask=currentTask;}
    }
}

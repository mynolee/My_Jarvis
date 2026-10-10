package dev.myjarvis.core;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.time.zone.ZoneRulesProvider;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** Owner-editable display settings only; no profile injection into inference or scheduling. */
@Service
final class OwnerProfileService {
    static final long MAX=9007199254740991L;
    static final List<String> FIELDS=List.of("display_name","locale","timezone");
    static final Set<String> LOCALES=Set.of("ko-KR","en-US");
    private final JdbcTemplate db;private final Vault vault;private final OwnerProfileKeyCodec keys;private final InferenceContextFreshness contexts;
    final TransactionTemplate writeTx,readTx;
    private final Set<String> zones;private final String tzdbVersion;
    OwnerProfileService(JdbcTemplate db,Vault vault,OwnerProfileKeyCodec keys,PlatformTransactionManager manager,InferenceContextFreshness contexts){
        this.db=db;this.vault=vault;this.keys=keys;this.contexts=contexts;
        writeTx=new TransactionTemplate(manager);writeTx.setTimeout(15);writeTx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        readTx=new TransactionTemplate(manager);readTx.setReadOnly(true);readTx.setTimeout(15);readTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        TreeSet<String> supported=new TreeSet<>();supported.add("UTC");
        for(String zone:ZoneId.getAvailableZoneIds())if(zone.matches("[A-Za-z0-9._+-]+(?:/[A-Za-z0-9._+-]+)+")&&!zone.startsWith("SystemV/")&&zone.length()<=80){ZoneId.of(zone).getRules();supported.add(zone);}
        zones=Collections.unmodifiableSortedSet(supported);var versions=ZoneRulesProvider.getVersions("Etc/UTC");if(versions.isEmpty())throw new IllegalStateException("Timezone data unavailable");tzdbVersion=versions.lastKey();if(tzdbVersion.length()>80||!tzdbVersion.matches("[A-Za-z0-9._+-]+"))throw new IllegalStateException("Unsupported timezone version");
    }
    static UUID uuid(String value){try{UUID result=UUID.fromString(value);if(!result.toString().equals(value))throw ApiException.bad();return result;}catch(IllegalArgumentException e){throw ApiException.bad();}}
    static boolean blankCodePoint(int cp){return cp>=0x09&&cp<=0x0D||cp==0x20||cp==0x85||cp==0xA0||cp==0x1680||cp>=0x2000&&cp<=0x200A||cp==0x2028||cp==0x2029||cp==0x202F||cp==0x205F||cp==0x3000||cp==0xFEFF;}
    void validate(JsonNode body){
        if(body==null)throw ApiException.bad();Json.validate(body);Json.fields(body,"expected_version","display_name","locale","timezone");if(body.size()<2||body.size()>4||!body.has("expected_version"))throw ApiException.bad();
        if(Json.canonical(body).getBytes(StandardCharsets.UTF_8).length>4096)throw new ApiException(413,"BODY_TOO_LARGE");long version=Json.integer(body,"expected_version");if(version<1||version>MAX)throw ApiException.bad();
        if(body.has("display_name")&&!body.get("display_name").isNull()){String name=Json.string(body,"display_name",80);if(name.codePoints().allMatch(OwnerProfileService::blankCodePoint)||name.codePoints().anyMatch(Character::isISOControl)||name.getBytes(StandardCharsets.UTF_8).length>320)throw ApiException.bad();}
        if(body.has("locale")&&!LOCALES.contains(Json.string(body,"locale",5)))throw ApiException.bad();
        if(body.has("timezone")){String zone=Json.string(body,"timezone",80);if(!zones.contains(zone))throw new ApiException(400,"INVALID_TIMEZONE");}
    }
    private Map<String,Object> session(AuthService.Session session,boolean lock){
        AuthService.requireOwner(session);var owners=db.queryForList("SELECT * FROM owners WHERE id=?"+(lock?" FOR UPDATE":""),session.owner());
        if(owners.isEmpty()||session.epoch()!=((Number)owners.getFirst().get("security_epoch")).longValue())throw new ApiException(401,"SESSION_REVOKED");
        var rows=db.queryForList("SELECT token_hash FROM sessions WHERE token_hash=? AND owner_id=? AND security_epoch=? AND revoked_at IS NULL AND created_at>clock_timestamp()-interval '12 hours' AND last_seen_at>clock_timestamp()-interval '2 hours'"+(lock?" FOR UPDATE":""),session.hash(),session.owner(),session.epoch());
        if(rows.isEmpty())throw new ApiException(401,"SESSION_REVOKED");long version=((Number)owners.getFirst().get("version")).longValue();if(version<1||version>MAX)throw new ApiException(409,"VERSION_EXHAUSTED");return owners.getFirst();
    }
    private Instant now(){return AuthService.instant(db.queryForObject("SELECT clock_timestamp()",Object.class));}
    private <T>T read(AuthService.Session session,Supplier<T> supplier){return readTx.execute(tx->{session(session,false);return supplier.get();});}
    private Map<String,Object> profile(Map<String,Object> owner){
        Map<String,Object> result=new LinkedHashMap<>();Object encrypted=owner.get("profile_ref");
        if(encrypted==null){result.put("display_name",null);result.put("locale","ko-KR");result.put("timezone","UTC");}
        else {UUID id=(UUID)owner.get("id");JsonNode payload=Json.parse(vault.open(id,id,"owner-profile",(String)encrypted).getBytes(StandardCharsets.UTF_8));Json.fields(payload,"display_name","locale","timezone");if(payload.size()!=3)throw new IllegalStateException("Invalid profile record");for(String field:FIELDS)result.put(field,payload.get(field).isNull()?null:payload.get(field).asText());}
        return result;
    }
    private Map<String,Object> view(Map<String,Object> owner){
        Map<String,Object> result=new LinkedHashMap<>(profile(owner));for(String field:List.of("id","login_name","execution_paused","version"))result.put(field,owner.get(field));
        result.put("mode","MOCK");result.put("profile_updated_at",owner.get("profile_updated_at")==null?null:AuthService.instant(owner.get("profile_updated_at")).toString());result.put("server_time",now().toString());
        result.put("profile_effects",Map.of("settings_time_preview",true,"ui_translation",false,"chat_personalization",false,"inference_timezone",result.get("timezone"),"scheduling",false));return result;
    }
    Map<String,Object> get(AuthService.Session session){return readTx.execute(tx->view(session(session,false)));}
    Map<String,Object> options(AuthService.Session session){return read(session,()->Map.of("locales",List.of("ko-KR","en-US"),"timezones",List.copyOf(zones),"tzdb_version",tzdbVersion));}
    private Map<String,Object> receipt(Map<String,Object> row){
        Map<String,Object> result=new LinkedHashMap<>();result.put("status",row==null?"NOT_OBSERVED":"COMMITTED");result.put("owner_id",row==null?null:row.get("owner_id"));result.put("version",row==null?null:row.get("result_version"));
        if(row==null)result.put("updated_fields",null);else{List<String> submitted=new ArrayList<>();for(String field:FIELDS)if(Boolean.TRUE.equals(row.get("submitted_"+field)))submitted.add(field);result.put("updated_fields",submitted);}return result;
    }
    Map<String,Object> reconcile(AuthService.Session session,UUID key){return read(session,()->{var rows=db.queryForList("SELECT * FROM owner_profile_mutations WHERE owner_id=? AND idempotency_key=?",session.owner(),key);return receipt(rows.isEmpty()?null:rows.getFirst());});}
    Map<String,Object> patch(AuthService.Session session,UUID key,JsonNode body){
        validate(body);AuthService.requireOwner(session);String digest=keys.request(session.owner(),Json.canonical(body));long expected=Json.integer(body,"expected_version");
        return writeTx.execute(tx->{var owner=session(session,true);var prior=db.queryForList("SELECT * FROM owner_profile_mutations WHERE owner_id=? AND idempotency_key=?",session.owner(),key);
            if(!prior.isEmpty()){var row=prior.getFirst();if(!Json.equal(session.hash(),(String)row.get("session_hash"))||session.epoch()!=((Number)row.get("security_epoch")).longValue())throw new ApiException(403,"MUTATION_SESSION_CHANGED");if(!Json.equal(digest,(String)row.get("request_digest")))throw new ApiException(409,"IDEMPOTENCY_CONFLICT");return Map.of("profile",view(owner),"mutation",receipt(row));}
            long version=((Number)owner.get("version")).longValue();if(version!=expected)throw new ProfileConflict(version);if(version>=MAX)throw new ApiException(409,"VERSION_EXHAUSTED");
            Map<String,Object> content=profile(owner);for(String field:FIELDS)if(body.has(field))content.put(field,body.get(field).isNull()?null:body.get(field).asText());
            String encrypted=vault.seal(session.owner(),session.owner(),"owner-profile",Json.MAPPER.writeValueAsString(content));Instant updated=now();
            db.update("UPDATE owners SET profile_ref=?,profile_updated_at=?,version=version+1 WHERE id=?",encrypted,Timestamp.from(updated),session.owner());
            db.update("INSERT INTO owner_profile_mutations(owner_id,idempotency_key,session_hash,security_epoch,request_digest,result_version,submitted_display_name,submitted_locale,submitted_timezone) VALUES (?,?,?,?,?,?,?,?,?)",session.owner(),key,session.hash(),session.epoch(),digest,version+1,body.has("display_name"),body.has("locale"),body.has("timezone"));
            contexts.invalidateOwner(session.owner(),false);
            db.update("INSERT INTO audit_events(id,owner_id,event_type,resource_id) VALUES (?,?,?,?)",UUID.randomUUID(),session.owner(),"OWNER_PROFILE_UPDATED",session.owner());
            var current=db.queryForMap("SELECT * FROM owners WHERE id=?",session.owner());var mutation=db.queryForMap("SELECT * FROM owner_profile_mutations WHERE owner_id=? AND idempotency_key=?",session.owner(),key);return Map.of("profile",view(current),"mutation",receipt(mutation));
        });
    }
    static final class ProfileConflict extends RuntimeException{final long currentVersion;ProfileConflict(long version){super("PRECONDITION_CHANGED");currentVersion=version;}}
}

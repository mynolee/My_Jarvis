package dev.myjarvis.core;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;

@Service
final class AuthService {
    record Session(String hash,UUID owner,Instant reauth,long epoch) {}
    record Issued(String token,String csrf) {}
    private final JdbcTemplate db;private final TransactionTemplate tx;private final InferenceContextFreshness contexts;
    private final Argon2PasswordEncoder encoder=new Argon2PasswordEncoder(16,32,1,19456,2);
    private final Semaphore hashing=new Semaphore(4,true);
    AuthService(JdbcTemplate db,TransactionTemplate tx,InferenceContextFreshness contexts){this.db=db;this.tx=tx;this.contexts=contexts;}
    Session find(String token) {
        if(token==null||token.length()!=43)return null;
        var rows=db.queryForList("SELECT s.* FROM sessions s LEFT JOIN owners o ON o.id=s.owner_id WHERE s.token_hash=? AND s.revoked_at IS NULL AND s.created_at>now()-interval '12 hours' AND s.last_seen_at>now()-interval '2 hours' AND (s.owner_id IS NULL OR s.security_epoch=o.security_epoch)",Json.hash(token));
        if(rows.isEmpty())return null;var r=rows.getFirst();
        db.update("UPDATE sessions SET last_seen_at=now() WHERE token_hash=?",Json.hash(token));
        return new Session(Json.hash(token),(UUID)r.get("owner_id"),instant(r.get("reauthenticated_at")),r.get("security_epoch")==null?0:((Number)r.get("security_epoch")).longValue());
    }
    boolean csrf(Session s,String csrf){if(s==null||csrf==null||csrf.length()!=43)return false;return Json.equal(db.queryForObject("SELECT csrf_hash FROM sessions WHERE token_hash=?",String.class,s.hash),Json.hash(csrf));}
    Issued issue(UUID owner,boolean reauth,String previous) {
        return tx.execute(status->{
            if(owner!=null)db.queryForObject("SELECT id FROM owners WHERE id=? FOR UPDATE",UUID.class,owner);
            String token=Vault.token(),csrf=Vault.token();Long epoch=owner==null?null:db.queryForObject("SELECT security_epoch FROM owners WHERE id=?",Long.class,owner);
            if(previous!=null){db.update("UPDATE sessions SET revoked_at=now() WHERE token_hash=?",previous);if(owner!=null)contexts.invalidateSession(owner,previous);}
            db.update("INSERT INTO sessions(token_hash,owner_id,csrf_hash,reauthenticated_at,security_epoch) VALUES (?,?,?,CASE WHEN ? THEN now() ELSE NULL END,?)",Json.hash(token),owner,Json.hash(csrf),reauth,epoch);
            return new Issued(token,csrf);
        });
    }
    String refreshCsrf(Session s){String token=Vault.token();db.update("UPDATE sessions SET csrf_hash=? WHERE token_hash=?",Json.hash(token),s.hash);return token;}
    UUID bootstrap(String supplied,String login,String password) {
        validateLogin(login);validatePassword(password);
        if(supplied.length()!=43)throw new ApiException(401,"BOOTSTRAP_DENIED");
        UUID owner=tx.execute(status->{
            db.queryForObject("SELECT pg_advisory_xact_lock(870017)",Object.class);
            var rows=db.queryForList("SELECT * FROM bootstrap_tokens WHERE singleton=true FOR UPDATE");
            if(rows.isEmpty()||db.queryForObject("SELECT count(*) FROM owners",Long.class)!=0)return null;
            var r=rows.getFirst();
            if(r.get("consumed_at")!=null||((Number)r.get("attempts")).intValue()>=5||!instant(r.get("expires_at")).isAfter(Instant.now()))return null;
            db.update("UPDATE bootstrap_tokens SET attempts=attempts+1 WHERE singleton=true");
            if(!Json.equal((String)r.get("token_hash"),Json.hash(supplied)))return null;
            String encoded=hash(password);
            UUID id=UUID.randomUUID();db.update("INSERT INTO owners(id,login_name,password_hash) VALUES (?,?,?)",id,login,encoded);
            db.update("UPDATE bootstrap_tokens SET consumed_at=now() WHERE singleton=true");
            db.update("INSERT INTO audit_events(id,owner_id,event_type,resource_id) VALUES (?,?,?,?)",UUID.randomUUID(),id,"OWNER_CREATED",id);return id;
        });
        if(owner==null)throw new ApiException(401,"BOOTSTRAP_DENIED");return owner;
    }
    UUID login(String login,String password,String ip) {
        validateLogin(login);validatePassword(password);
        String accountKey="account:"+Json.hash(login),ipKey="ip:"+Json.hash(ip);
        reserveAttempt(accountKey);reserveAttempt(ipKey);
        var rows=db.queryForList("SELECT id,password_hash FROM owners WHERE login_name=?",login);
        boolean valid=!rows.isEmpty()&&matches(password,(String)rows.getFirst().get("password_hash"));
        if(!valid){throw new ApiException(401,"LOGIN_FAILED");}
        db.update("DELETE FROM login_limits WHERE key_hash=?",Json.hash(accountKey));return (UUID)rows.getFirst().get("id");
    }
    void reauthenticate(Session s,String password,String ip) {
        requireOwner(s);validatePassword(password);String key="reauth:"+s.owner;reserveAttempt(key);reserveAttempt("ip:"+Json.hash(ip));
        String encoded=db.queryForObject("SELECT password_hash FROM owners WHERE id=?",String.class,s.owner);
        if(!matches(password,encoded)){throw new ApiException(401,"LOGIN_FAILED");}
        db.update("DELETE FROM login_limits WHERE key_hash=?",Json.hash(key));
    }
    void logout(Session s){tx.executeWithoutResult(status->{if(s.owner!=null)db.queryForObject("SELECT id FROM owners WHERE id=? FOR UPDATE",UUID.class,s.owner);db.update("UPDATE sessions SET revoked_at=now() WHERE token_hash=?",s.hash);if(s.owner!=null)contexts.invalidateSession(s.owner,s.hash);});}
    static void requireOwner(Session s){if(s==null||s.owner==null)throw new ApiException(401,"AUTHENTICATION_REQUIRED");}
    static void requireRecent(Session s){requireOwner(s);if(s.reauth==null||s.reauth.isBefore(Instant.now().minusSeconds(300)))throw new ApiException(403,"REAUTHENTICATION_REQUIRED");}
    /** Atomic per-key reservation happens before hashing; concurrent bursts cannot bypass five attempts. */
    private void reserveAttempt(String key){
        boolean accepted=Boolean.TRUE.equals(tx.execute(status->{
            String digest=Json.hash(key);
            db.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,digest);
            var rows=db.queryForList("SELECT * FROM login_limits WHERE key_hash=? FOR UPDATE",digest);
            if(rows.isEmpty()){db.update("INSERT INTO login_limits(key_hash,failures,window_start) VALUES (?,1,now())",digest);return true;}
            var row=rows.getFirst();Instant blocked=instant(row.get("blocked_until"));
            if(blocked!=null&&blocked.isAfter(Instant.now()))return false;
            if(instant(row.get("window_start")).isBefore(Instant.now().minusSeconds(900))){db.update("UPDATE login_limits SET failures=1,window_start=now(),blocked_until=NULL WHERE key_hash=?",digest);return true;}
            int attempts=((Number)row.get("failures")).intValue();if(attempts>=5)return false;
            db.update("UPDATE login_limits SET failures=failures+1,blocked_until=CASE WHEN failures>=4 THEN now()+interval '15 minutes' ELSE NULL END WHERE key_hash=?",digest);return true;
        }));
        if(!accepted)throw new ApiException(429,"RATE_LIMITED");
    }
    private String hash(String p){if(!hashing.tryAcquire())throw new ApiException(429,"RATE_LIMITED");try{return encoder.encode(p);}finally{hashing.release();}}
    private boolean matches(String p,String h){if(!hashing.tryAcquire())throw new ApiException(429,"RATE_LIMITED");try{return encoder.matches(p,h);}finally{hashing.release();}}
    static void validatePassword(String p){int count=p.codePointCount(0,p.length());if(count<15||count>128||p.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>1024)throw ApiException.bad();}
    static void validateLogin(String s){if(!s.matches("[A-Za-z0-9_.-]{3,64}"))throw ApiException.bad();}
    static Instant instant(Object value){if(value==null)return null;if(value instanceof java.sql.Timestamp t)return t.toInstant();if(value instanceof OffsetDateTime d)return d.toInstant();throw new IllegalArgumentException("Unsupported timestamp");}
}

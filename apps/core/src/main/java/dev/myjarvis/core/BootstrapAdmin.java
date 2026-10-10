package dev.myjarvis.core;

import java.sql.*;
import java.io.Console;

/** Local-only administrative entrypoint. Never displays the token on redirected stdout. */
public final class BootstrapAdmin {
    public static void main(String[] args)throws Exception { launch(args, System.getenv()); }

    static void launch(String[] args, java.util.Map<String, String> environment)throws Exception {
        RestoreLaunchGuard.check(environment);
        Console tty=System.console();if(tty==null)throw new IllegalStateException("A local interactive TTY is required");
        String url=System.getenv("JARVIS_DB_URL"),user=System.getenv("JARVIS_DB_USER"),password=System.getenv("JARVIS_DB_PASSWORD");
        try(Connection c=DriverManager.getConnection(url,user,password)) {
            c.setAutoCommit(false);
            try(var lock=c.prepareStatement("SELECT pg_advisory_xact_lock(870017)")){lock.execute();}
            try(var q=c.prepareStatement("SELECT count(*) FROM owners");var r=q.executeQuery()){r.next();if(r.getLong(1)!=0)throw new IllegalStateException("Bootstrap is permanently closed");}
            String token=Vault.token();
            try(var p=c.prepareStatement("INSERT INTO bootstrap_tokens(singleton,token_hash,expires_at,attempts) VALUES(true,?,now()+interval '15 minutes',0) ON CONFLICT(singleton) DO UPDATE SET token_hash=excluded.token_hash,expires_at=excluded.expires_at,attempts=0,consumed_at=NULL")){p.setString(1,Json.hash(token));p.executeUpdate();}
            c.commit();tty.printf("One-time bootstrap token (expires in 15 minutes): %s%n",token);
        }
    }
}

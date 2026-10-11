package dev.myjarvis.core;

import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Domain-separated, owner-bound blind keys and opaque cursor authentication. */
@Component
final class ReminderKeyCodec {
    private final byte[] key;
    ReminderKeyCodec(@Value("${jarvis.key-file}") String file) throws Exception {
        Path path=Path.of(file);
        if(Files.isSymbolicLink(path)||!Files.isRegularFile(path)||Files.getPosixFilePermissions(path).stream().anyMatch(p->p.name().startsWith("GROUP_")||p.name().startsWith("OTHERS_")))throw new IllegalStateException("Invalid private key file");
        byte[] raw=Files.readAllBytes(path);
        try {if(raw.length!=32)throw new IllegalStateException("Expected private key");key=mac(raw,"jarvis:schedules-inbox:v1");}finally{Arrays.fill(raw,(byte)0);}
    }
    String digest(UUID owner,String purpose,String text){return HexFormat.of().formatHex(mac(key,owner+"|"+purpose+"|"+text));}
    String cursor(UUID owner,String context,String boundary){
        String data=Base64.getUrlEncoder().withoutPadding().encodeToString((context+"|"+boundary).getBytes(StandardCharsets.UTF_8));
        return data+"."+digest(owner,"cursor",data);
    }
    String boundary(UUID owner,String context,String cursor){
        try{
            if(cursor.length()>1024)throw ApiException.bad();String[] parts=cursor.split("\\.",-1);
            if(parts.length!=2)throw ApiException.bad();if(!Json.equal(digest(owner,"cursor",parts[0]),parts[1]))throw ApiException.missing();
            String decoded=new String(Base64.getUrlDecoder().decode(parts[0]),StandardCharsets.UTF_8);
            if(!decoded.startsWith(context+"|"))throw ApiException.missing();return decoded.substring(context.length()+1);
        }catch(IllegalArgumentException e){throw ApiException.bad();}
    }
    private static byte[] mac(byte[] key,String value){try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));}catch(Exception e){throw new IllegalStateException("Reminder key unavailable");}}
    @PreDestroy void close(){Arrays.fill(key,(byte)0);}
}

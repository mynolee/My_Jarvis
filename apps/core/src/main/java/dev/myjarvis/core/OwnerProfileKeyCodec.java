package dev.myjarvis.core;

import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Keyed request commitments; never persist guessable hashes of private profile choices. */
@Component
final class OwnerProfileKeyCodec {
    private final byte[] key;
    OwnerProfileKeyCodec(@Value("${jarvis.key-file}") String file) throws Exception {
        Path path=Path.of(file);
        if(Files.isSymbolicLink(path)||!Files.isRegularFile(path)||Files.getPosixFilePermissions(path).stream().anyMatch(p->p.name().startsWith("GROUP_")||p.name().startsWith("OTHERS_")))throw new IllegalStateException("Invalid private key file");
        byte[] raw=Files.readAllBytes(path);
        try{if(raw.length!=32)throw new IllegalStateException("Expected private key");key=mac(raw,"jarvis:owner-profile:v1");}finally{Arrays.fill(raw,(byte)0);}
    }
    String request(UUID owner,String canonical){return HexFormat.of().formatHex(mac(key,owner+"|owner.profile.patch|"+canonical));}
    private static byte[] mac(byte[] key,String value){try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));}catch(Exception e){throw new IllegalStateException("Profile key unavailable");}}
    @PreDestroy void close(){Arrays.fill(key,(byte)0);}
}

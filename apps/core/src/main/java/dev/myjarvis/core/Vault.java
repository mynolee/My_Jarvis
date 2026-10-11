package dev.myjarvis.core;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;

/** Application data encryption. Key supplied only through a private mounted file. */
@Component
final class Vault {
    private final byte[] key; private final String keyId; private final JdbcTemplate db; private final TransactionTemplate nonceTx; private final HikariDataSource noncePool;
    static final SecureRandom RNG=new SecureRandom();
    Vault(JdbcTemplate primaryDatabase,@Value("${jarvis.key-file}") String file,@Value("${jarvis.mode}")String mode,
          @Value("${spring.datasource.url}")String databaseUrl,@Value("${spring.datasource.username}")String databaseUser,
          @Value("${spring.datasource.password}")String databasePassword) throws Exception {
        if(!"mock".equals(mode))throw new IllegalStateException("Only mock mode is implemented");
        if(file.isBlank())throw new IllegalStateException("JARVIS_KEY_FILE is required; use a private 32-byte file");
        Path path=Path.of(file);
        if(Files.isSymbolicLink(path)||!Files.isRegularFile(path))throw new IllegalStateException("Invalid key file");
        var permissions=Files.getPosixFilePermissions(path);
        if(permissions.stream().anyMatch(p->p.name().startsWith("GROUP_")||p.name().startsWith("OTHERS_")))throw new IllegalStateException("Key file must be private");
        this.key=Files.readAllBytes(path);if(key.length!=32)throw new IllegalStateException("Expected 32-byte key");
        this.keyId=Json.hash(Base64.getEncoder().encodeToString(key)).substring(0,16);
        // Never borrow the outer business pool: owner-lock waiters may exhaust it.
        HikariConfig config=new HikariConfig();config.setPoolName("jarvis-nonce-reservation");
        config.setJdbcUrl(databaseUrl);config.setUsername(databaseUser);config.setPassword(databasePassword);
        config.setMaximumPoolSize(2);config.setMinimumIdle(0);config.setConnectionTimeout(5000);
        config.setInitializationFailTimeout(-1);
        this.noncePool=new HikariDataSource(config);this.db=new JdbcTemplate(noncePool);
        this.nonceTx=new TransactionTemplate(new DataSourceTransactionManager(noncePool));
        this.nonceTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.nonceTx.setTimeout(5);
    }
    String seal(UUID owner,UUID record,String purpose,String plaintext) {
        try {
            byte[] nonce=new byte[12];RNG.nextBytes(nonce);String ns=Base64.getEncoder().encodeToString(nonce);
            // Serialize nonce count + reservation across instances sharing this key.
            nonceTx.executeWithoutResult(status->{
                db.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,keyId);
                if(db.queryForObject("SELECT count(*) FROM crypto_nonces WHERE key_id=?",Long.class,keyId)>=16777216L)throw new IllegalStateException("Key rotation required");
                db.update("INSERT INTO crypto_nonces(key_id,nonce) VALUES (?,?)",keyId,ns);
            });
            Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
            c.updateAAD(aad(owner,record,purpose));
            return keyId+"."+ns+"."+Base64.getEncoder().encodeToString(c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
        }catch(Exception e){throw new IllegalStateException("Encryption failed");}
    }
    String open(UUID owner,UUID record,String purpose,String ciphertext) {
        try {
            String[] p=ciphertext.split("\\.",3);if(p.length!=3||!keyId.equals(p[0]))throw new IllegalStateException();
            Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,Base64.getDecoder().decode(p[1])));c.updateAAD(aad(owner,record,purpose));
            return new String(c.doFinal(Base64.getDecoder().decode(p[2])),StandardCharsets.UTF_8);
        }catch(Exception e){throw new IllegalStateException("Decryption failed");}
    }
    @PreDestroy void closeNoncePool(){noncePool.close();Arrays.fill(key,(byte)0);}
    private byte[] aad(UUID owner,UUID record,String purpose){return ("jarvis:v1:"+owner+":"+record+":"+purpose).getBytes(StandardCharsets.UTF_8);}
    static String token(){byte[] b=new byte[32];RNG.nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
}

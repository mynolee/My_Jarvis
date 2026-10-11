package dev.myjarvis.core;

import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import javax.net.ssl.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic certificates are generated at test time; no private key fixture is checked in. */
class LocalInferenceTransportTest {
    @TempDir Path directory;
    @Test void realMtlsSignedBodyAndFullResponseDeadline()throws Exception {
        String password=UUID.randomUUID().toString();Path store=directory.resolve("ephemeral.p12");
        var command=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","keytool").toString(),"-genkeypair","-alias","fixture","-keyalg","RSA","-keysize","2048","-dname","CN=localhost","-ext","SAN=dns:localhost,ip:127.0.0.1","-validity","1","-storetype","PKCS12","-keystore",store.toString(),"-storepass",password,"-keypass",password,"-noprompt").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        assertTrue(command.waitFor(20,TimeUnit.SECONDS));assertEquals(0,command.exitValue());
        var keys=KeyStore.getInstance("PKCS12");try(var input=Files.newInputStream(store)){keys.load(input,password.toCharArray());}
        var certificate=keys.getCertificate("fixture");PrivateKey tls=(PrivateKey)keys.getKey("fixture",password.toCharArray());
        Path cert=pem("certificate.pem","CERTIFICATE",certificate.getEncoded());Path tlsKey=pem("tls.pem","PRIVATE KEY",tls.getEncoded());
        var signing=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();Path signingFile=pem("signing.pem","PRIVATE KEY",signing.getPrivate().getEncoded());
        var km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(keys,password.toCharArray());var tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(keys);
        var ssl=SSLContext.getInstance("TLSv1.3");ssl.init(km.getKeyManagers(),tm.getTrustManagers(),new SecureRandom());
        var server=HttpsServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);server.setHttpsConfigurator(new HttpsConfigurator(ssl){@Override public void configure(HttpsParameters p){var config=getSSLContext().getDefaultSSLParameters();config.setNeedClientAuth(true);p.setSSLParameters(config);}});
        var executor=Executors.newVirtualThreadPerTaskExecutor();server.setExecutor(executor);var stalled=new AtomicBoolean();var verified=new AtomicBoolean();
        server.createContext("/health/ready",exchange->{
            exchange.sendResponseHeaders(200,0);
            if(stalled.get()){try{Thread.sleep(6000);}catch(InterruptedException e){Thread.currentThread().interrupt();}finally{exchange.close();}return;}
            exchange.getResponseBody().write(Json.canonical(Map.of("ready",true,"status","READY","model_id","local-mlx-v1","model_manifest_hash","a".repeat(64),"download_complete",true,"integrity_verified",true,"runtime_ready",true,"inference_verified",false,"integration_verified",false)).getBytes(StandardCharsets.UTF_8));exchange.close();
        });
        server.createContext("/internal/v1/turn",exchange->{try{
            String[] parts=exchange.getRequestHeaders().getFirst("X-Jarvis-Capability").split("\\.");byte[] raw=Base64.getUrlDecoder().decode(parts[0]);var signature=Signature.getInstance("Ed25519");signature.initVerify(signing.getPublic());signature.update(raw);assertTrue(signature.verify(Base64.getUrlDecoder().decode(parts[1])));
            var body=Json.parse(exchange.getRequestBody().readAllBytes());assertEquals(Json.hash(Json.canonical(body)),Json.parse(raw).get("body_hash").asText());verified.set(true);
            exchange.sendResponseHeaders(200,0);exchange.getResponseBody().write("{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8));
        }catch(Exception e){throw new java.io.IOException(e);}finally{exchange.close();}});
        server.start();
        try{
            var env=new MockEnvironment().withProperty("jarvis.local.enabled","true").withProperty("jarvis.local.url","https://localhost:"+server.getAddress().getPort()).withProperty("jarvis.local.manifest-hash","a".repeat(64)).withProperty("jarvis.local.client-cert-file",cert.toString()).withProperty("jarvis.local.ca-file",cert.toString()).withProperty("jarvis.local.client-key-file",tlsKey.toString()).withProperty("jarvis.local.signing-key-file",signingFile.toString());
            var client=new LocalInferenceClient(env);assertTrue(client.configured());assertTrue(client.ready());assertEquals("ok",client.infer(UUID.randomUUID(),LocalInferenceContractTest.request()).get("status").asText());assertTrue(verified.get());
            stalled.set(true);assertTimeoutPreemptively(Duration.ofSeconds(5),()->assertFalse(client.ready()));
        }finally{server.stop(0);executor.shutdownNow();}
    }
    Path pem(String filename,String type,byte[] bytes)throws Exception {Path path=directory.resolve(filename);Files.writeString(path,"-----BEGIN "+type+"-----\n"+Base64.getMimeEncoder(64,new byte[]{'\n'}).encodeToString(bytes)+"\n-----END "+type+"-----\n");Files.setPosixFilePermissions(path,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));return path;}
}

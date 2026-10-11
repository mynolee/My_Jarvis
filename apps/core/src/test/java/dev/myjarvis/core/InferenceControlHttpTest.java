package dev.myjarvis.core;

import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.net.ssl.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

/** Real loopback mTLS and deadlines with ephemeral synthetic keys, authored for CI only. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InferenceControlHttpTest {
    @TempDir static Path directory;
    HttpsServer server;ExecutorService executor;LocalInferenceClient client;KeyPair signing;
    final AtomicReference<String> mode=new AtomicReference<>("ok");
    final AtomicReference<Throwable> serverFailure=new AtomicReference<>();
    final List<String> routes=new CopyOnWriteArrayList<>();final AtomicInteger redirects=new AtomicInteger();
    @BeforeAll void start()throws Exception {
        String password=UUID.randomUUID().toString();Path store=directory.resolve("ephemeral.p12");
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","keytool").toString(),"-genkeypair","-alias","fixture","-keyalg","RSA","-keysize","2048","-dname","CN=localhost","-ext","SAN=dns:localhost,ip:127.0.0.1","-validity","1","-storetype","PKCS12","-keystore",store.toString(),"-storepass",password,"-keypass",password,"-noprompt").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        assertTrue(process.waitFor(20,TimeUnit.SECONDS));assertEquals(0,process.exitValue());
        var keys=KeyStore.getInstance("PKCS12");try(var input=Files.newInputStream(store)){keys.load(input,password.toCharArray());}
        Path cert=pem("certificate.pem","CERTIFICATE",keys.getCertificate("fixture").getEncoded());Path tls=pem("tls.pem","PRIVATE KEY",keys.getKey("fixture",password.toCharArray()).getEncoded());
        signing=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();Path key=pem("signing.pem","PRIVATE KEY",signing.getPrivate().getEncoded());
        var km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(keys,password.toCharArray());var tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(keys);
        var ssl=SSLContext.getInstance("TLSv1.3");ssl.init(km.getKeyManagers(),tm.getTrustManagers(),new SecureRandom());
        server=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setHttpsConfigurator(new HttpsConfigurator(ssl){@Override public void configure(HttpsParameters p){var config=getSSLContext().getDefaultSSLParameters();config.setNeedClientAuth(true);p.setSSLParameters(config);}});
        executor=Executors.newVirtualThreadPerTaskExecutor();server.setExecutor(executor);
        server.createContext("/internal/v1/inference-control",exchange->{
            try{
                routes.add(exchange.getRequestURI().getPath());assertEquals("POST",exchange.getRequestMethod());assertNull(exchange.getRequestURI().getQuery());
                var request=Json.parse(exchange.getRequestBody().readAllBytes());InferenceControlResult.request(request);
                String[] parts=exchange.getRequestHeaders().getFirst("X-Jarvis-Capability").split("\\.");byte[] payload=Base64.getUrlDecoder().decode(parts[0]);var signature=Signature.getInstance("Ed25519");signature.initVerify(signing.getPublic());signature.update(payload);assertTrue(signature.verify(Base64.getUrlDecoder().decode(parts[1])));
                var claims=Json.parse(payload);assertEquals("jarvis-inference-control",claims.get("audience").asText());assertEquals(0,claims.get("max_model_calls").asInt());assertEquals(Json.hash(Json.canonical(request)),claims.get("body_hash").asText());
                for(String field:InferenceControlResult.REQUEST_FIELDS)assertEquals(Json.canonical(request.get(field)),Json.canonical(claims.get(field)));
                assertEquals(request.get("operation").asText().equals("OBSERVE")?"/internal/v1/inference-control/observe":"/internal/v1/inference-control",exchange.getRequestURI().getPath());
                String current=mode.get();
                if(current.equals("redirect")){exchange.getResponseHeaders().add("Location","/must-not-follow");exchange.sendResponseHeaders(302,-1);return;}
                if(current.equals("expired")){exchange.sendResponseHeaders(401,-1);return;}
                if(current.equals("headers"))exchange.getResponseHeaders().add("X-Synthetic-Limit","x".repeat(8193));
                exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,0);
                if(current.equals("stall")){Thread.sleep(6000);return;}
                var body=InferenceControlResultTest.response(request,"TERMINAL","COMPLETED");
                if(current.equals("wrong-identity"))body.put("request_id",UUID.randomUUID().toString());
                byte[] bytes=switch(current){case "body"->new byte[16385];case "duplicate"->"{\"revision\":1,\"revision\":1}".getBytes(StandardCharsets.UTF_8);case "invalid-utf8"->new byte[]{(byte)0xc3,0x28};default->Json.canonical(body).getBytes(StandardCharsets.UTF_8);};
                exchange.getResponseBody().write(bytes);
            }catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            catch(java.io.IOException disconnected){/* Bound/deadline refusals intentionally close the response early. */}
            catch(Throwable failure){serverFailure.compareAndSet(null,failure);}
            finally{exchange.close();}
        });
        server.createContext("/health/ready",exchange->{try{
            var body=new LinkedHashMap<String,Object>(Map.of("ready",true,"status","READY","model_id","local-mlx-v1","model_manifest_hash","a".repeat(64),"download_complete",true,"integrity_verified",true,"runtime_ready",true,"inference_verified",false,"integration_verified",false));
            if(!mode.get().equals("legacy-ready"))body.put("control_revision",mode.get().equals("wrong-revision")?2:1);
            exchange.sendResponseHeaders(200,0);exchange.getResponseBody().write(Json.canonical(body).getBytes(StandardCharsets.UTF_8));
        }finally{exchange.close();}});
        server.createContext("/must-not-follow",exchange->{redirects.incrementAndGet();exchange.sendResponseHeaders(200,-1);exchange.close();});server.start();
        client=new LocalInferenceClient(new MockEnvironment().withProperty("jarvis.local.enabled","true").withProperty("jarvis.local.url","https://localhost:"+server.getAddress().getPort()).withProperty("jarvis.local.manifest-hash","a".repeat(64)).withProperty("jarvis.local.client-cert-file",cert.toString()).withProperty("jarvis.local.ca-file",cert.toString()).withProperty("jarvis.local.client-key-file",tls.toString()).withProperty("jarvis.local.signing-key-file",key.toString()));assertTrue(client.configured());
    }
    @AfterAll void stop(){if(client!=null)client.close();if(server!=null)server.stop(0);if(executor!=null)executor.shutdownNow();}
    @BeforeEach void reset(){mode.set("ok");routes.clear();redirects.set(0);serverFailure.set(null);}
    @AfterEach void serverAssertions(){assertNull(serverFailure.get());}
    Path pem(String filename,String type,byte[] bytes)throws Exception {Path file=directory.resolve(filename);Files.writeString(file,"-----BEGIN "+type+"-----\n"+Base64.getMimeEncoder(64,new byte[]{'\n'}).encodeToString(bytes)+"\n-----END "+type+"-----\n");Files.setPosixFilePermissions(file,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));return file;}
    @Test void separateSignedControlCapabilityUsesExactPostRoutes()throws Exception {
        var request=InferenceControlResultTest.request();assertTrue(client.control(request).terminal());request.put("operation","FENCE_OR_STOP");assertTrue(client.control(request).terminal());
        assertEquals(List.of("/internal/v1/inference-control/observe","/internal/v1/inference-control"),routes);
    }
    @Test void currentReadinessRequiresPairedRevisionOne(){
        assertTrue(client.ready());mode.set("legacy-ready");assertFalse(client.ready());mode.set("wrong-revision");assertFalse(client.ready());
    }
    @Test void controlRemainsAvailableWithoutGenerationReadiness()throws Exception {
        mode.set("legacy-ready");assertFalse(client.ready());assertTrue(client.control(InferenceControlResultTest.request()).terminal());assertEquals(1,routes.size());
    }
    @Test void responseByteHeaderDuplicateUtf8AndIdentityViolationsAreNotFacts(){
        for(String error:List.of("body","headers","duplicate","invalid-utf8","wrong-identity","expired")){mode.set(error);assertThrows(Exception.class,()->client.control(InferenceControlResultTest.request()),error);}
        assertEquals(6,routes.size());
    }
    @Test void redirectsNeverReachAnotherEndpointOrRetryGeneration(){
        mode.set("redirect");assertThrows(Exception.class,()->client.control(InferenceControlResultTest.request()));assertEquals(0,redirects.get());assertEquals(1,routes.size());
    }
    @Test void fullResponseHasThreeSecondDeadline(){
        mode.set("stall");assertTimeoutPreemptively(Duration.ofSeconds(5),()->assertThrows(Exception.class,()->client.control(InferenceControlResultTest.request())));assertEquals(1,routes.size());
    }
    @Test void streamingLimitRejectsCrossChunkOverflowWithoutWaitingForEnd(){
        var subscriber=new LocalInferenceClient.BoundedBody(16384);var cancelled=new AtomicBoolean();subscriber.onSubscribe(new Flow.Subscription(){public void request(long n){}public void cancel(){cancelled.set(true);}});
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[16000])));assertFalse(subscriber.result.isDone());subscriber.onNext(List.of(ByteBuffer.wrap(new byte[385])));assertTrue(cancelled.get());assertTrue(subscriber.result.isCompletedExceptionally());assertEquals(16000,subscriber.bytes.size());
    }
}

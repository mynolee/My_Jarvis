package dev.myjarvis.core;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import javax.net.ssl.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.*;
import java.util.*;

/** One-shot, bounded, mutually authenticated local transport. Never follows redirects or retries. */
@Component
class LocalInferenceClient {
    private final HttpClient client;
    private final URI endpoint;
    private final PrivateKey signingKey;
    private final String keyId;
    final String manifestHash;
    final boolean enabled;
    LocalInferenceClient(Environment env) {
        enabled=Boolean.parseBoolean(env.getProperty("jarvis.local.enabled","false"));
        HttpClient c=null; URI uri=null; PrivateKey key=null;
        keyId=env.getProperty("jarvis.local.signing-key-id","core-local-v1");
        manifestHash=env.getProperty("jarvis.local.manifest-hash","");
        if(enabled) try {
            if(!manifestHash.matches("[a-f0-9]{64}")||!keyId.matches("[A-Za-z0-9_-]{1,80}"))throw new IllegalArgumentException();
            uri=URI.create(env.getRequiredProperty("jarvis.local.url"));
            if(!"https".equals(uri.getScheme())||!Set.of("localhost","127.0.0.1","[::1]").contains(uri.getHost())||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||!(uri.getPath().isEmpty()||uri.getPath().equals("/")))throw new IllegalArgumentException();
            var factory=CertificateFactory.getInstance("X.509");
            var certificates=factory.generateCertificates(new java.io.ByteArrayInputStream(read(env.getRequiredProperty("jarvis.local.client-cert-file"),false)));
            var trust=KeyStore.getInstance("PKCS12");trust.load(null,null);int i=0;
            for(var cert:factory.generateCertificates(new java.io.ByteArrayInputStream(read(env.getRequiredProperty("jarvis.local.ca-file"),false))))trust.setCertificateEntry("ca"+i++,cert);
            if(i==0||certificates.isEmpty())throw new IllegalArgumentException();
            PrivateKey tlsKey=privateKey(read(env.getRequiredProperty("jarvis.local.client-key-file"),true));
            var identity=KeyStore.getInstance("PKCS12");identity.load(null,null);
            identity.setKeyEntry("client",tlsKey,new char[0],certificates.toArray(java.security.cert.Certificate[]::new));
            var km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(identity,new char[0]);
            var tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(trust);
            var ssl=SSLContext.getInstance("TLSv1.3");ssl.init(km.getKeyManagers(),tm.getTrustManagers(),new SecureRandom());
            var params=new SSLParameters();params.setEndpointIdentificationAlgorithm("HTTPS");params.setProtocols(new String[]{"TLSv1.3"});
            c=HttpClient.newBuilder().sslContext(ssl).sslParameters(params).connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();
            key=privateKey(read(env.getRequiredProperty("jarvis.local.signing-key-file"),true));
            if(!Set.of("EdDSA","Ed25519").contains(key.getAlgorithm()))throw new IllegalArgumentException();
        }catch(Exception invalid){c=null;uri=null;key=null;/* Deliberately do not log private paths or key material. */}
        client=c;endpoint=uri;signingKey=key;
    }
    @jakarta.annotation.PreDestroy void close(){if(client!=null)client.shutdownNow();}
    static byte[] read(String file,boolean secret)throws Exception {
        Path path=Path.of(file);if(Files.isSymbolicLink(path)||!Files.isRegularFile(path)||Files.size(path)>65536)throw new IllegalArgumentException();
        if(secret&&Files.getPosixFilePermissions(path).stream().anyMatch(p->p.name().startsWith("GROUP_")||p.name().startsWith("OTHERS_")))throw new IllegalArgumentException();
        return Files.readAllBytes(path);
    }
    static PrivateKey privateKey(byte[] pem)throws Exception {
        String text=new String(pem,StandardCharsets.US_ASCII);
        byte[] der=Base64.getMimeDecoder().decode(text.replace("-----BEGIN PRIVATE KEY-----","").replace("-----END PRIVATE KEY-----",""));
        for(String algorithm:List.of("Ed25519","RSA","EC"))try{return KeyFactory.getInstance(algorithm).generatePrivate(new PKCS8EncodedKeySpec(der));}catch(GeneralSecurityException ignored){}
        throw new GeneralSecurityException("Unsupported key format");
    }
    boolean configured(){return client!=null&&signingKey!=null;}
    boolean ready(){
        if(!configured())return false;
        try {
            JsonNode response=exchange(HttpRequest.newBuilder(endpoint.resolve("/health/ready")).timeout(Duration.ofSeconds(3)).GET().build());
            Json.fields(response,"ready","status","model_id","model_manifest_hash","download_complete","integrity_verified","runtime_ready","inference_verified","integration_verified","control_revision");
            if(response.size()!=10||Json.integer(response,"control_revision")!=1)return false;
            for(String field:List.of("ready","download_complete","integrity_verified","runtime_ready"))if(!response.path(field).isBoolean()||!response.path(field).asBoolean())return false;
            for(String field:List.of("inference_verified","integration_verified"))if(!response.path(field).isBoolean())return false;
            return "READY".equals(response.path("status").asText())&&"local-mlx-v1".equals(response.path("model_id").asText())&&Json.equal(manifestHash,response.path("model_manifest_hash").asText());
        }catch(Exception e){return false;}
    }
    boolean feedbackReady(){
        if(!configured())return false;
        try {
            JsonNode response=exchange(HttpRequest.newBuilder(endpoint.resolve("/health/study-feedback")).timeout(Duration.ofSeconds(3)).GET().build(),16384,8192);
            InferenceControlResult.exact(response,"revision","control_revision","model_id","model_manifest_hash","ready");
            return Json.integer(response,"revision")==1&&Json.integer(response,"control_revision")==1
                &&"local-mlx-v1".equals(Json.string(response,"model_id",40))&&response.path("ready").isBoolean()&&response.path("ready").asBoolean()
                &&Json.equal(manifestHash,Json.string(response,"model_manifest_hash",64));
        }catch(Exception unavailable){return false;}
    }
    static String capability(PrivateKey key,String keyId,UUID owner,JsonNode body,Instant now)throws Exception {
        Map<String,Object> claims=new LinkedHashMap<>();
        claims.put("issuer","jarvis-core");claims.put("audience","jarvis-orchestrator");claims.put("key_id",keyId);
        for(String field:List.of("request_id","run_id","model_id"))claims.put(field,body.get(field).asText());
        claims.put("owner_id",owner.toString());claims.put("body_hash",Json.hash(Json.canonical(body)));
        claims.put("reservation_id",body.get("budget").get("reservation_id").asText());claims.put("max_model_calls",1);
        claims.put("max_output_tokens",body.get("budget").get("max_output_tokens").intValue());claims.put("issued_at",now.toString());claims.put("expires_at",now.plusSeconds(60).toString());claims.put("nonce",UUID.randomUUID().toString());
        byte[] payload=Json.canonical(claims).getBytes(StandardCharsets.UTF_8);var signature=Signature.getInstance("Ed25519");signature.initSign(key);signature.update(payload);
        var b64=Base64.getUrlEncoder().withoutPadding();return b64.encodeToString(payload)+"."+b64.encodeToString(signature.sign());
    }
    static String controlCapability(PrivateKey key,String keyId,JsonNode body,Instant now)throws Exception {
        InferenceControlResult.request(body);
        if(keyId==null||!keyId.matches("[A-Za-z0-9_-]{1,80}"))throw ApiException.bad();
        // Python's closed instant grammar has at most six fractional digits. Derive expiry
        // from this same truncated instant; no future tolerance or rounded-up timestamp.
        Instant issued=now.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        Map<String,Object> claims=new LinkedHashMap<>();
        claims.put("issuer","jarvis-core");claims.put("audience","jarvis-inference-control");claims.put("key_id",keyId);
        for(String field:InferenceControlResult.REQUEST_FIELDS)claims.put(field,body.get(field));
        claims.put("body_hash",Json.hash(Json.canonical(body)));claims.put("max_model_calls",0);
        claims.put("issued_at",issued.toString());claims.put("expires_at",issued.plusSeconds(60).toString());claims.put("nonce",UUID.randomUUID().toString());
        byte[] payload=Json.canonical(claims).getBytes(StandardCharsets.UTF_8);var signature=Signature.getInstance("Ed25519");signature.initSign(key);signature.update(payload);
        var b64=Base64.getUrlEncoder().withoutPadding();return b64.encodeToString(payload)+"."+b64.encodeToString(signature.sign());
    }
    InferenceControlResult control(JsonNode body)throws Exception {
        if(!configured())throw new IllegalStateException("LOCAL_UNAVAILABLE");
        InferenceControlResult.request(body);
        String route=body.get("operation").asText().equals("OBSERVE")?"/internal/v1/inference-control/observe":"/internal/v1/inference-control";
        String header=controlCapability(signingKey,keyId,body,Instant.now());
        var request=HttpRequest.newBuilder(endpoint.resolve(route)).timeout(Duration.ofSeconds(3)).header("Content-Type","application/json").header("X-Jarvis-Capability",header).POST(HttpRequest.BodyPublishers.ofString(Json.canonical(body),StandardCharsets.UTF_8)).build();
        return InferenceControlResult.verify(exchange(request,InferenceControlResult.MAX_BYTES,8192),body);
    }
    JsonNode infer(UUID owner,JsonNode body)throws Exception {
        if(!configured())throw new IllegalStateException("LOCAL_UNAVAILABLE");
        String header=capability(signingKey,keyId,owner,body,Instant.now());
        return exchange(HttpRequest.newBuilder(endpoint.resolve("/internal/v1/turn")).timeout(Duration.ofSeconds(65)).header("Content-Type","application/json").header("X-Jarvis-Capability",header).POST(HttpRequest.BodyPublishers.ofString(Json.canonical(body),StandardCharsets.UTF_8)).build());
    }
    private JsonNode exchange(HttpRequest request)throws Exception {
        return exchange(request,262144,Integer.MAX_VALUE);
    }
    private JsonNode exchange(HttpRequest request,int bodyLimit,int headerLimit)throws Exception {
        var future=client.sendAsync(request,info->{
            long bytes=0;
            for(var entry:info.headers().map().entrySet())for(String value:entry.getValue())bytes+=entry.getKey().getBytes(StandardCharsets.UTF_8).length+value.getBytes(StandardCharsets.UTF_8).length+4;
            if(bytes>headerLimit)throw new IllegalStateException("LOCAL_HEADER_LIMIT");
            return new BoundedBody(bodyLimit);
        });
        try {
            var response=future.get(request.timeout().orElse(Duration.ofSeconds(65)).toMillis(),java.util.concurrent.TimeUnit.MILLISECONDS);
            if(response.statusCode()!=200)throw new IllegalStateException("LOCAL_UNCERTAIN");
            return Json.parse(response.body());
        }finally{if(!future.isDone())future.cancel(true);}
    }
    static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        final int limit;
        BoundedBody(){this(262144);}
        BoundedBody(int limit){this.limit=limit;}
        final java.util.concurrent.CompletableFuture<byte[]> result=new java.util.concurrent.CompletableFuture<>();
        final java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
        java.util.concurrent.Flow.Subscription subscription;
        public java.util.concurrent.CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(java.util.concurrent.Flow.Subscription value){subscription=value;value.request(1);}
        public void onNext(List<java.nio.ByteBuffer> chunks){
            for(var chunk:chunks){if(chunk.remaining()>limit-bytes.size()){subscription.cancel();result.completeExceptionally(new IllegalStateException("LOCAL_RESPONSE_LIMIT"));return;}byte[] part=new byte[chunk.remaining()];chunk.get(part);bytes.writeBytes(part);}
            subscription.request(1);
        }
        public void onError(Throwable error){result.completeExceptionally(error);}
        public void onComplete(){result.complete(bytes.toByteArray());}
    }
}

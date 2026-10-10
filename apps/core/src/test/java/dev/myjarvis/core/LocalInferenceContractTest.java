package dev.myjarvis.core;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LocalInferenceContractTest {
    static tools.jackson.databind.JsonNode request(){return Json.MAPPER.valueToTree(Map.of("request_id",UUID.randomUUID().toString(),"run_id",UUID.randomUUID().toString(),"model_id","local-mlx-v1","model_manifest_hash","a".repeat(64),"budget",Map.of("reservation_id",UUID.randomUUID().toString(),"max_output_tokens",512)));}
    @Test void signatureBindsExactCanonicalBodyAndOwner()throws Exception {
        var pair=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();var request=request();UUID owner=UUID.randomUUID();Instant now=Instant.parse("2026-10-10T00:00:00Z");
        String header=LocalInferenceClient.capability(pair.getPrivate(),"test-key",owner,request,now);String[] parts=header.split("\\.");byte[] raw=Base64.getUrlDecoder().decode(parts[0]);var claims=Json.parse(raw);
        assertEquals(Json.canonical(claims),new String(raw,StandardCharsets.UTF_8));assertEquals(Json.hash(Json.canonical(request)),claims.get("body_hash").asText());assertEquals(owner.toString(),claims.get("owner_id").asText());assertEquals(now.plusSeconds(60).toString(),claims.get("expires_at").asText());
        var signature=Signature.getInstance("Ed25519");signature.initVerify(pair.getPublic());signature.update(raw);assertTrue(signature.verify(Base64.getUrlDecoder().decode(parts[1])));
        raw[raw.length-2]^=1;signature.initVerify(pair.getPublic());signature.update(raw);assertFalse(signature.verify(Base64.getUrlDecoder().decode(parts[1])));
    }
    @Test void localConfigurationFailsClosedWithoutSecrets(){var client=new LocalInferenceClient(new MockEnvironment().withProperty("jarvis.local.enabled","true"));assertFalse(client.configured());assertFalse(client.ready());}
    @Test void bodySubscriberRejectsOverLimitWithoutWaitingForComplete(){var body=new LocalInferenceClient.BoundedBody();var cancelled=new java.util.concurrent.atomic.AtomicBoolean();body.onSubscribe(new java.util.concurrent.Flow.Subscription(){public void request(long n){}public void cancel(){cancelled.set(true);}});body.onNext(List.of(java.nio.ByteBuffer.wrap(new byte[262145])));assertTrue(cancelled.get());assertTrue(body.result.isCompletedExceptionally());}
    @Test void responseCannotMintToolsAuthorityOrUsage(){var request=request();var response=response(request);assertEquals("Synthetic answer",LocalInferenceService.validate(response,request));response.put("approved",true);assertThrows(ApiException.class,()->LocalInferenceService.validate(response,request));response.remove("approved");response.withObject("usage").put("output_tokens",513);assertThrows(ApiException.class,()->LocalInferenceService.validate(response,request));}
    @Test void responseMustMatchReservationAndManifest(){var request=request();var response=response(request);response.withObject("usage").put("reservation_id",UUID.randomUUID().toString());assertThrows(ApiException.class,()->LocalInferenceService.validate(response,request));var bad=response(request);bad.withObject("usage").put("model_manifest_hash","b".repeat(64));assertThrows(ApiException.class,()->LocalInferenceService.validate(bad,request));}
    @Test void validClarificationIsDataNotUnknownOrAuthority(){var request=request();var answer=response(request);var clarification=Json.MAPPER.valueToTree(Map.of("kind","NEED_INPUT","question","Which detail?","missing_fields",List.of("detail"),"usage",answer.get("usage")));assertEquals("Which detail?",LocalInferenceService.validate(clarification,request));}
    static tools.jackson.databind.node.ObjectNode response(tools.jackson.databind.JsonNode request){return (tools.jackson.databind.node.ObjectNode)Json.MAPPER.valueToTree(Map.of("kind","ANSWER","reply_text","Synthetic answer","cited_source_ids",List.of(),"usage",Map.of("inference_request_id",request.get("request_id").asText(),"reservation_id",request.get("budget").get("reservation_id").asText(),"model_manifest_hash",request.get("model_manifest_hash").asText(),"input_tokens",4,"output_tokens",3,"compute_ms",1,"peak_memory_bytes",1024)));}
}

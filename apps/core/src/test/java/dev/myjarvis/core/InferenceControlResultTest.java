package dev.myjarvis.core;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

class InferenceControlResultTest {
    static ObjectNode request(){
        Map<String,Object> values=new LinkedHashMap<>();values.put("revision",1);values.put("operation","OBSERVE");
        for(String field:InferenceControlResult.ID_FIELDS)values.put(field,UUID.randomUUID().toString());
        values.put("purpose","CONVERSATION");values.put("security_epoch",2L);values.put("origin_binding","a".repeat(64));values.put("dispatch_commitment","b".repeat(64));return Json.MAPPER.valueToTree(values);
    }
    static ObjectNode response(JsonNode request,String state,String kind){
        ObjectNode body=(ObjectNode)request.deepCopy();body.remove("operation");body.put("state",state);body.put("ledger_instance_id",UUID.randomUUID().toString());body.putNull("terminal");
        if(kind!=null){var terminal=body.putObject("terminal");terminal.put("kind",kind);terminal.put("ordinal",1L);if(kind.equals("UNSTARTED_FENCED"))terminal.putNull("generation_id");else terminal.put("generation_id",UUID.randomUUID().toString());}
        return body;
    }
    @Test void parsedSmallIntegersMatchJdbcLongIdentity(){
        var request=request();var wire=response(request,"TERMINAL","COMPLETED");
        var parsed=Json.parse(Json.canonical(wire).getBytes(StandardCharsets.UTF_8));
        assertTrue(InferenceControlResult.verify(parsed,request).terminal());
    }
    @Test void onlyCompleteClosedTerminalTuplesBecomeFacts(){
        var request=request();
        for(String kind:List.of("COMPLETED","WORKER_STOPPED","UNSTARTED_FENCED"))assertEquals(kind,InferenceControlResult.verify(response(request,"TERMINAL",kind),request).kind());
        for(String state:List.of("PENDING","NOT_OBSERVED"))assertFalse(InferenceControlResult.verify(response(request,state,null),request).terminal());
        var missing=response(request,"NOT_OBSERVED",null);missing.putNull("ledger_instance_id");assertFalse(InferenceControlResult.verify(missing,request).terminal());
        var pending=response(request,"PENDING",null);pending.putNull("ledger_instance_id");assertThrows(ApiException.class,()->InferenceControlResult.verify(pending,request));
        var completed=response(request,"TERMINAL","COMPLETED");((ObjectNode)completed.get("terminal")).putNull("generation_id");assertThrows(ApiException.class,()->InferenceControlResult.verify(completed,request));
        var fenced=response(request,"TERMINAL","UNSTARTED_FENCED");((ObjectNode)fenced.get("terminal")).put("generation_id",UUID.randomUUID().toString());assertThrows(ApiException.class,()->InferenceControlResult.verify(fenced,request));
    }
    @Test void everyProtectedIdentityFieldAndPurposeMustMatch(){
        var request=request();
        for(String field:InferenceControlResult.ID_FIELDS){var swapped=response(request,"TERMINAL","COMPLETED");swapped.put(field,UUID.randomUUID().toString());assertThrows(ApiException.class,()->InferenceControlResult.verify(swapped,request),field);}
        for(String field:List.of("origin_binding","dispatch_commitment")){var swapped=response(request,"TERMINAL","COMPLETED");swapped.put(field,"c".repeat(64));assertThrows(ApiException.class,()->InferenceControlResult.verify(swapped,request),field);}
        var epoch=response(request,"TERMINAL","COMPLETED");epoch.put("security_epoch",3);assertThrows(ApiException.class,()->InferenceControlResult.verify(epoch,request));
        var purpose=response(request,"TERMINAL","COMPLETED");purpose.put("purpose","STUDY_LESSON");assertThrows(ApiException.class,()->InferenceControlResult.verify(purpose,request));
    }
    @Test void missingUnknownUnsafeAndProducerTimeFieldsAreRejected(){
        var request=request();
        for(String field:InferenceControlResult.RESPONSE_FIELDS){var missing=response(request,"TERMINAL","COMPLETED");missing.remove(field);assertThrows(ApiException.class,()->InferenceControlResult.verify(missing,request),field);}
        var extra=response(request,"TERMINAL","COMPLETED");extra.put("completed_at","2026-10-11T00:00:00Z");assertThrows(ApiException.class,()->InferenceControlResult.verify(extra,request));
        for(long ordinal:List.of(0L,-1L,9007199254740992L)){var unsafe=response(request,"TERMINAL","COMPLETED");((ObjectNode)unsafe.get("terminal")).put("ordinal",ordinal);assertThrows(ApiException.class,()->InferenceControlResult.verify(unsafe,request));}
        var noncanonical=response(request,"TERMINAL","COMPLETED");noncanonical.put("owner_id",request.get("owner_id").asText().toUpperCase(Locale.ROOT));assertThrows(ApiException.class,()->InferenceControlResult.verify(noncanonical,request));
        var feedback=request();feedback.put("purpose","STUDY_FEEDBACK");assertThrows(ApiException.class,()->InferenceControlResult.request(feedback));
    }
    @Test void missingAndPendingAreNeverUnstartedEvidence(){
        var request=request();var missing=InferenceControlResult.verify(response(request,"NOT_OBSERVED",null),request);
        assertNull(missing.kind());assertNull(missing.generation());assertNull(missing.ordinal());
        var illegal=response(request,"PENDING","UNSTARTED_FENCED");assertThrows(ApiException.class,()->InferenceControlResult.verify(illegal,request));
    }
    @Test void controlSignerUsesDistinctAudienceZeroCallsAndMicrosecondLifetime()throws Exception {
        var keys=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();var request=request();Instant now=Instant.parse("2026-10-11T10:11:12.123456789Z");
        String[] encoded=LocalInferenceClient.controlCapability(keys.getPrivate(),"synthetic",request,now).split("\\.");byte[] payload=Base64.getUrlDecoder().decode(encoded[0]);
        var signature=Signature.getInstance("Ed25519");signature.initVerify(keys.getPublic());signature.update(payload);assertTrue(signature.verify(Base64.getUrlDecoder().decode(encoded[1])));
        JsonNode claims=Json.parse(payload);assertEquals("jarvis-inference-control",claims.get("audience").asText());assertEquals(0,claims.get("max_model_calls").asInt());
        assertEquals("2026-10-11T10:11:12.123456Z",claims.get("issued_at").asText());assertEquals("2026-10-11T10:12:12.123456Z",claims.get("expires_at").asText());
        assertEquals(Json.hash(Json.canonical(request)),claims.get("body_hash").asText());assertFalse(claims.has("max_output_tokens"));assertFalse(claims.has("model_id"));
        var inference=Json.parse(Base64.getUrlDecoder().decode(LocalInferenceClient.capability(keys.getPrivate(),"synthetic",UUID.randomUUID(),LocalInferenceContractTest.request(),now).split("\\.")[0]));assertEquals("jarvis-orchestrator",inference.get("audience").asText());assertEquals(1,inference.get("max_model_calls").asInt());
    }
    @Test void strictUtf8DuplicateUnicodeAndNumbersRemainClosed(){
        for(byte[] bytes:List.of(new byte[]{(byte)0xc3,0x28},"{\"revision\":1,\"revision\":1}".getBytes(StandardCharsets.UTF_8),"{\"value\":1.0}".getBytes(StandardCharsets.UTF_8),"{\"value\":\"\\ud800\"}".getBytes(StandardCharsets.UTF_8)))assertThrows(ApiException.class,()->Json.parse(bytes));
        var body=request();body.put("source","x".repeat(16384));assertThrows(ApiException.class,()->InferenceControlResult.request(body));
    }
}

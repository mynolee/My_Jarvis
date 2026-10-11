package dev.myjarvis.core;

import java.nio.charset.StandardCharsets;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** Closed facts from the authenticated connection. The in-process request binding covers
 * only the source-free control envelope and cannot be retargeted during persistence. */
record InferenceControlResult(String state, UUID ledger, String kind, UUID generation, Long ordinal, String requestBinding) {
    static final long MAX_SAFE_INTEGER=9007199254740991L;
    static final int MAX_BYTES=16384;
    static final List<String> ID_FIELDS=List.of("owner_id","request_id","reservation_id","run_id","context_id");
    static final String[] REQUEST_FIELDS={"revision","operation","owner_id","request_id","reservation_id","run_id","context_id","purpose","security_epoch","origin_binding","dispatch_commitment"};
    static final String[] RESPONSE_FIELDS={"revision","owner_id","request_id","reservation_id","run_id","context_id","purpose","security_epoch","origin_binding","dispatch_commitment","state","ledger_instance_id","terminal"};

    static void exact(JsonNode value,String... fields){
        if(value==null)throw ApiException.bad();Json.validate(value);Json.fields(value,fields);
        if(value.size()!=fields.length)throw ApiException.bad();
    }
    static void bounded(JsonNode value){
        if(value==null||Json.canonical(value).getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)throw ApiException.bad();
    }
    static void identity(JsonNode value){
        if(Json.integer(value,"revision")!=1)throw ApiException.bad();
        for(String field:ID_FIELDS)Json.uuid(value,field);
        if(!Set.of("CONVERSATION","STUDY_LESSON","STUDY_FEEDBACK").contains(Json.string(value,"purpose",20)))throw ApiException.bad();
        long epoch=Json.integer(value,"security_epoch");if(epoch<0||epoch>MAX_SAFE_INTEGER)throw ApiException.bad();
        for(String field:List.of("origin_binding","dispatch_commitment"))if(!Json.string(value,field,64).matches("[a-f0-9]{64}"))throw ApiException.bad();
    }
    static void request(JsonNode request){
        bounded(request);exact(request,REQUEST_FIELDS);identity(request);
        if(!Set.of("FENCE_OR_STOP","OBSERVE").contains(Json.string(request,"operation",20)))throw ApiException.bad();
    }
    static InferenceControlResult verify(JsonNode response,JsonNode expected){
        request(expected);bounded(response);exact(response,RESPONSE_FIELDS);identity(response);
        for(String field:REQUEST_FIELDS)if(!field.equals("operation")&&!Json.canonical(response.get(field)).equals(Json.canonical(expected.get(field))))throw new ApiException(409,"CONTROL_IDENTITY_CONFLICT");
        String state=Json.string(response,"state",20);
        if(!Set.of("NOT_OBSERVED","PENDING","TERMINAL").contains(state))throw ApiException.bad();
        UUID ledger=response.get("ledger_instance_id").isNull()?null:Json.uuid(response,"ledger_instance_id");
        JsonNode terminal=response.get("terminal");
        if(!state.equals("TERMINAL")){
            if(!terminal.isNull()||(state.equals("PENDING")&&ledger==null))throw ApiException.bad();
            return new InferenceControlResult(state,ledger,null,null,null,Json.hash(Json.canonical(expected)));
        }
        if(ledger==null)throw ApiException.bad();exact(terminal,"kind","generation_id","ordinal");
        String kind=Json.string(terminal,"kind",30);
        if(!Set.of("UNSTARTED_FENCED","COMPLETED","WORKER_STOPPED").contains(kind))throw ApiException.bad();
        UUID generation=terminal.get("generation_id").isNull()?null:Json.uuid(terminal,"generation_id");
        long ordinal=Json.integer(terminal,"ordinal");
        if(ordinal<1||ordinal>MAX_SAFE_INTEGER||(kind.equals("UNSTARTED_FENCED")!=(generation==null)))throw ApiException.bad();
        return new InferenceControlResult(state,ledger,kind,generation,ordinal,Json.hash(Json.canonical(expected)));
    }
    boolean terminal(){return "TERMINAL".equals(state);}
}

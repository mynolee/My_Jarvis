package dev.myjarvis.core;

import java.nio.charset.StandardCharsets;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** A closed model assessment. No generated identity, action, or execution claim is accepted. */
final class StudyFeedbackResult {
    static final String PURPOSE="STUDY_FEEDBACK";
    private static final Set<String> ASSESSMENTS=Set.of("CORRECT","PARTIALLY_CORRECT","NEEDS_WORK","INSUFFICIENT_INFORMATION");
    private StudyFeedbackResult() {}
    private static String text(JsonNode value,String key,int maximum) {
        String result=Json.string(value,key,maximum);
        if(result.indexOf(0)>=0||result.codePoints().allMatch(OwnerProfileService::blankCodePoint))throw ApiException.bad(); return result;
    }
    private static List<String> list(JsonNode values) {
        if(values==null||!values.isArray()||values.isEmpty()||values.size()>3)throw ApiException.bad();
        var result=new ArrayList<String>();
        for(var value:values)result.add(text(Json.MAPPER.valueToTree(Map.of("value",value)),"value",500));
        return List.copyOf(result);
    }
    static void validateWire(JsonNode response,JsonNode request) {
        if(!PURPOSE.equals(StudyLessonResult.purpose(request))||request.has("user_input")
                ||!"local-mlx-v1".equals(Json.string(request,"model_id",40))||!"local-v1".equals(Json.string(request,"prompt_revision",40))
                ||!request.path("feedback_source").isObject()||!request.path("context").isObject()||!request.path("control").isObject())throw ApiException.bad();
        for(String field:List.of("memories","history","evidence","tool_results","allowed_tool_ids"))if(!request.path(field).isArray()||!request.path(field).isEmpty())throw ApiException.bad();
        var budget=request.get("budget");
        InferenceControlResult.exact(budget,"reservation_id","max_compute_ms","max_input_tokens","max_output_tokens","max_model_calls");
        if(Json.integer(budget,"max_compute_ms")!=60000||Json.integer(budget,"max_input_tokens")!=3584||Json.integer(budget,"max_output_tokens")!=512||Json.integer(budget,"max_model_calls")!=1)throw ApiException.bad();
        InferenceControlResult.exact(response,"kind","assessment","explanation","example","assumptions","review_checklist","usage");
        if(!"FEEDBACK".equals(Json.string(response,"kind",30))||Json.canonical(response).getBytes(StandardCharsets.UTF_8).length>65536)throw ApiException.bad();
        feedback(response);
        var usage=response.get("usage");
        InferenceControlResult.exact(usage,"inference_request_id","model_manifest_hash","input_tokens","output_tokens","compute_ms","peak_memory_bytes","reservation_id");
        if(!Json.uuid(usage,"inference_request_id").equals(Json.uuid(request,"request_id"))
                ||!Json.uuid(usage,"reservation_id").equals(Json.uuid(request.get("budget"),"reservation_id"))
                ||!Json.equal(Json.string(usage,"model_manifest_hash",64),Json.string(request,"model_manifest_hash",64)))throw ApiException.bad();
        for(String field:List.of("input_tokens","output_tokens","compute_ms","peak_memory_bytes"))if(Json.integer(usage,field)<0)throw ApiException.bad();
        if(Json.integer(usage,"input_tokens")>3584||Json.integer(usage,"output_tokens")>512||Json.integer(usage,"compute_ms")>60000)throw ApiException.bad();
    }
    private static Map<String,Object> feedback(JsonNode response) {
        String assessment=Json.string(response,"assessment",40); if(!ASSESSMENTS.contains(assessment))throw ApiException.bad();
        return Map.of("assessment",assessment,"explanation",text(response,"explanation",2000),"example",text(response,"example",2000),
                "assumptions",list(response.get("assumptions")),"review_checklist",list(response.get("review_checklist")),"code_execution","NOT_RUN");
    }
    static String encodeAccepted(Map<String,Object> target,JsonNode response) {
        validateTarget(Json.MAPPER.valueToTree(target));
        String encoded=Json.canonical(Map.of("kind",PURPOSE,"revision",1,"target",target,"feedback",feedback(response)));
        if(encoded.getBytes(StandardCharsets.UTF_8).length>65536)throw new ApiException(413,"FEEDBACK_CAPACITY_LIMIT"); return encoded;
    }
    private static void validateTarget(JsonNode target) {
        InferenceControlResult.exact(target,"feedback_id","study_session_id","lesson_run_id","question_id","input_id","attempt_id");
        for(String field:List.of("feedback_id","study_session_id","lesson_run_id","question_id","input_id","attempt_id"))Json.uuid(target,field);
    }
    static Map<String,Object> projectStored(Map<String,Object> expected,String encoded) {
        try {
            if(encoded==null||encoded.getBytes(StandardCharsets.UTF_8).length>65536)throw ApiException.bad();
            JsonNode envelope=Json.parse(encoded.getBytes(StandardCharsets.UTF_8));
            InferenceControlResult.exact(envelope,"kind","revision","target","feedback");
            if(!PURPOSE.equals(Json.string(envelope,"kind",30))||Json.integer(envelope,"revision")!=1)throw ApiException.bad();
            validateTarget(envelope.get("target"));
            if(!Json.canonical(expected).equals(Json.canonical(envelope.get("target"))))throw ApiException.bad();
            JsonNode source=envelope.get("feedback");
            InferenceControlResult.exact(source,"assessment","explanation","example","assumptions","review_checklist","code_execution");
            if(!"NOT_RUN".equals(Json.string(source,"code_execution",20)))throw ApiException.bad();
            var result=new LinkedHashMap<String,Object>(feedback(source));
            if(!encodeAccepted(expected,source).equals(encoded))throw ApiException.bad();
            result.put("revision",1); return result;
        }catch(RuntimeException invalid){throw new ApiException(409,"FEEDBACK_SOURCE_UNAVAILABLE");}
    }
}

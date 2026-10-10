package dev.myjarvis.core;

import tools.jackson.databind.JsonNode;
import java.time.*;

/** Versioned, fail-closed registry. Model output is input data and never execution authority. */
final class ToolPolicy {
    private ToolPolicy(){}
    static void validateProposal(JsonNode proposal){
        Json.fields(proposal,"tool_id","tool_version","arguments");
        if(!"calendar.event.create".equals(Json.string(proposal,"tool_id",100))||!"1".equals(Json.string(proposal,"tool_version",10)))throw new ApiException(403,"TOOL_NOT_ALLOWED");
        JsonNode args=proposal.get("arguments");if(args==null)throw ApiException.bad();
        Json.fields(args,"calendar_id","title","start_at","end_at","timezone","attendees");
        Json.uuid(args,"calendar_id");Json.string(args,"title",200);
        try {Instant start=Instant.parse(Json.string(args,"start_at",40)),end=Instant.parse(Json.string(args,"end_at",40));if(!end.isAfter(start))throw ApiException.bad();ZoneId.of(Json.string(args,"timezone",80));}catch(Exception e){throw ApiException.bad();}
        if(!args.has("attendees")||!args.get("attendees").isArray()||!args.get("attendees").isEmpty())throw new ApiException(403,"TOOL_NOT_ALLOWED");
    }
    static void validateSnapshot(JsonNode payload){
        Json.fields(payload,"owner_id","action_id","tool_id","tool_version","arguments","policy_revision","security_epoch","issued_at","expires_at","max_executions","max_cost_usd_micro");
        if(!WorkflowService.POLICY.equals(Json.string(payload,"policy_revision",100))||Json.integer(payload,"max_executions")!=1||Json.integer(payload,"max_cost_usd_micro")!=0)throw new ApiException(409,"PRECONDITION_CHANGED");
        var proposal=Json.MAPPER.createObjectNode();proposal.set("tool_id",payload.get("tool_id"));proposal.set("tool_version",payload.get("tool_version"));proposal.set("arguments",payload.get("arguments"));validateProposal(proposal);
    }
}

package dev.myjarvis.core;

import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Strict bounded JSON and RFC8785-compatible integer-only canonical domain. */
final class Json {
    static final JsonMapper MAPPER = JsonMapper.builder(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16).maxStringLength(262144).maxNumberLength(17).build()).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    static JsonNode parse(byte[] bytes) {
        if(bytes.length>262144) throw new ApiException(413,"BODY_TOO_LARGE");
        try {
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            JsonNode node=MAPPER.readTree(text);
            if(node==null) throw ApiException.bad();
            validate(node); return node;
        } catch(ApiException e) { throw e; } catch(Exception e) { throw ApiException.bad(); }
    }
    static void validate(JsonNode n) {
        if(n.isFloatingPointNumber()) throw ApiException.bad();
        if(n.isIntegralNumber() && (!n.canConvertToLong() || n.longValue() < -9007199254740991L || n.longValue() > 9007199254740991L)) throw ApiException.bad();
        if(n.isArray() && n.size()>1000) throw ApiException.bad();
        if(n.isTextual()) {
            String s=n.asText();
            for(int i=0;i<s.length();i++) if(Character.isSurrogate(s.charAt(i))) {
                if(!Character.isHighSurrogate(s.charAt(i))||i+1>=s.length()||!Character.isLowSurrogate(s.charAt(++i))) throw ApiException.bad();
            }
        }
        if(n.isObject()) for(String key:n.propertyNames()) validate(MAPPER.valueToTree(key));
        if((n.isObject()||n.isArray())) for(JsonNode child:n) validate(child);
    }
    static void fields(JsonNode n, String... allowed) {
        if(!n.isObject()) throw ApiException.bad();
        Set<String> keys=Set.of(allowed);
        for(String k:n.propertyNames()) if(!keys.contains(k)) throw ApiException.bad();
    }
    static String string(JsonNode n,String k,int max) {
        JsonNode v=n.get(k); if(v==null||!v.isTextual()) throw ApiException.bad();
        String s=v.asText(); if(s.isEmpty()||s.codePointCount(0,s.length())>max) throw ApiException.bad(); return s;
    }
    static UUID uuid(JsonNode n,String k) { try { String s=string(n,k,36); UUID u=UUID.fromString(s); if(!u.toString().equals(s))throw ApiException.bad(); return u; }catch(Exception e){throw ApiException.bad();} }
    static long integer(JsonNode n,String k) { JsonNode v=n.get(k); if(v==null||!v.isIntegralNumber()||!v.canConvertToLong())throw ApiException.bad();return v.longValue(); }
    static String canonical(JsonNode n) {
        validate(n);
        if(n.isObject()) {
            TreeMap<String,JsonNode> fields=new TreeMap<>(); n.properties().forEach(e->fields.put(e.getKey(),e.getValue()));
            StringJoiner out=new StringJoiner(",","{","}");fields.forEach((k,v)->out.add(MAPPER.writeValueAsString(k)+":"+canonical(v)));return out.toString();
        }
        if(n.isArray()){StringJoiner out=new StringJoiner(",","[","]");n.forEach(v->out.add(canonical(v)));return out.toString();}
        return MAPPER.writeValueAsString(n);
    }
    static String canonical(Object o) { return canonical(MAPPER.valueToTree(o)); }
    static String hash(String s) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    static boolean equal(String a,String b){return a!=null&&b!=null&&MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8));}
}

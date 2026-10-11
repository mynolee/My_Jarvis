package dev.myjarvis.core;

import java.util.*;
import org.springframework.stereotype.Service;

/** Data-only selection, deliberately not wired into model inference. */
@Service
final class MemoryContextSelector {
    private final MemoryService m;
    MemoryContextSelector(MemoryService memories){m=memories;}
    List<Map<String,Object>> select(AuthService.Session session,UUID project){return m.read(session,()->{
        m.project(session.owner(),project);
        var rows=m.db.queryForList("SELECT m.*,s.scope,s.project_id FROM memories m JOIN memory_slots s ON s.owner_id=m.owner_id AND s.id=m.slot_id WHERE m.owner_id=? AND m.deleted_at IS NULL AND s.current_memory_id=m.id AND ((s.scope='PROJECT' AND s.project_id=?) OR (s.scope='GLOBAL' AND NOT EXISTS (SELECT 1 FROM memory_slots p WHERE p.owner_id=s.owner_id AND p.scope='PROJECT' AND p.project_id=? AND p.key_digest=s.key_digest AND (p.tombstone OR p.current_memory_id IS NOT NULL)))) ORDER BY CASE WHEN s.scope='PROJECT' THEN 0 ELSE 1 END,m.updated_at DESC,m.id ASC LIMIT 20",session.owner(),project,project);
        return rows.stream().map(row->{Map<String,Object> content=m.payload(row);Map<String,Object> result=new LinkedHashMap<>();for(String field:List.of("id","version","scope","kind"))result.put(field,row.get(field));result.put("key",content.get("key"));result.put("value",content.get("value"));return result;}).toList();
    });}
}

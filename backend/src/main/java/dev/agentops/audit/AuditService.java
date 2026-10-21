package dev.agentops.audit;

import dev.agentops.shared.PageResult;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public class AuditService {
    private final JdbcTemplate db;
    private final JsonMapper json;
    public AuditService(JdbcTemplate db, JsonMapper json) { this.db = db; this.json = json; }
    public void record(UUID actor, String action, String subjectType, UUID subjectId, String requestId, Map<String,?> metadata) {
        db.update("INSERT INTO audit_event(id,actor_id,action,subject_type,subject_id,request_id,metadata) VALUES(?,?,?,?,?,?,?::jsonb)",
            UUID.randomUUID(), actor, action, subjectType, subjectId, requestId, json.writeValueAsString(metadata));
    }
    public PageResult<Map<String,Object>> list(UUID actor, UUID subject, int page, int size) {
        int offset = PageResult.offset(page, size);
        String where = " WHERE (?::uuid IS NULL OR actor_id=?::uuid) AND (?::uuid IS NULL OR subject_id=?::uuid)";
        var items = db.queryForList("SELECT id,actor_id,action,subject_type,subject_id,request_id,metadata,created_at FROM audit_event" + where + " ORDER BY created_at DESC,id LIMIT ? OFFSET ?", actor, actor, subject, subject, size, offset);
        Long total = db.queryForObject("SELECT count(*) FROM audit_event" + where, Long.class, actor, actor, subject, subject);
        // JSONB is parsed into JSON, never exposed as driver-specific PGobject.
        for (var item : items) item.put("metadata", json.readTree(item.get("metadata").toString()));
        return new PageResult<>(items, total, page, size);
    }
}

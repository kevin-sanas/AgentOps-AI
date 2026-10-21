package dev.agentops.audit;

import dev.agentops.shared.PageResult;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuditController {
    private final AuditService audit;
    public AuditController(AuditService audit) { this.audit = audit; }
    @GetMapping("/api/v1/audit-events")
    @PreAuthorize("hasRole('AUDITOR')")
    PageResult<Map<String,Object>> list(@RequestParam(required=false) UUID actorId, @RequestParam(required=false) UUID subjectId,
        @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
        return audit.list(actorId, subjectId, page, size);
    }
}

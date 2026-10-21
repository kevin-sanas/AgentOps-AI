package dev.agentops.monitoring;

import dev.agentops.access.Caller;
import dev.agentops.shared.ApiError;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** All monitoring writes lock the target before checks/incidents to keep a common lock order. */
@Component
public class TargetGuard {
    private final JdbcTemplate db;
    public TargetGuard(JdbcTemplate db) { this.db=db; }
    public void lock(UUID target) {
        if(db.queryForList("SELECT id FROM service_target WHERE id=? FOR UPDATE",target).isEmpty()) throw ApiError.notFound();
    }
    public void noActiveIncident(UUID target) {
        if(db.queryForObject("SELECT EXISTS(SELECT 1 FROM incident WHERE target_id=? AND status IN ('OPEN','ACKNOWLEDGED','INVESTIGATING','MONITORING'))",Boolean.class,target))
            throw new ApiError(409,"ACTIVE_INCIDENT_CONFIG_FROZEN");
    }
    public void requireOperate(Caller caller, UUID target) {
        // Lock user/ACL so concurrent deactivation, role changes and ACL revocation serialize with this write.
        // Acquire the user lock in a separate statement: a wait must end before taking the role-query snapshot.
        if(db.queryForList("SELECT id FROM app_user WHERE id=? FOR SHARE",caller.userId()).isEmpty())throw ApiError.notFound();
        var grants=db.queryForList("SELECT a.target_id FROM app_user u JOIN target_access a ON a.user_id=u.id WHERE u.id=? AND u.active AND a.target_id=? AND a.can_read AND a.can_operate AND EXISTS(SELECT 1 FROM user_role ur JOIN app_role r ON r.id=ur.role_id WHERE ur.user_id=u.id AND r.code='ON_CALL') FOR SHARE OF u,a",caller.userId(),target);
        if(grants.isEmpty()) throw ApiError.notFound();
    }
}

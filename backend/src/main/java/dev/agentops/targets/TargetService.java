package dev.agentops.targets;

import dev.agentops.access.Caller;
import dev.agentops.audit.AuditService;
import dev.agentops.shared.ApiError;
import dev.agentops.shared.Idempotency;
import dev.agentops.shared.PageResult;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TargetService {
    private final JdbcTemplate db;
    private final TargetUrlPolicy policy;
    private final Idempotency idempotency;
    private final AuditService audit;
    public TargetService(JdbcTemplate db, TargetUrlPolicy policy, Idempotency idempotency, AuditService audit) {
        this.db = db; this.policy = policy; this.idempotency = idempotency; this.audit = audit;
    }
    public record Target(UUID id, String code, String name, String environment, String baseUrl, String ownerTeam, boolean active, String networkPolicy, Instant createdAt,int configVersion,boolean allowHttp) {}
    private Target map(ResultSet rs, int n) throws SQLException {
        return new Target(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"), rs.getString("environment"), rs.getString("base_url"), rs.getString("owner_team"), rs.getBoolean("active"), rs.getString("network_policy"), rs.getTimestamp("created_at").toInstant(),rs.getInt("config_version"),rs.getBoolean("allow_http"));
    }
    private String scope() { return " (? OR EXISTS(SELECT 1 FROM target_access a WHERE a.target_id=t.id AND a.user_id=? AND a.can_read)) "; }
    public PageResult<Target> list(Caller caller, int page, int size) {
        int offset = PageResult.offset(page, size);
        var items = db.query("SELECT t.* FROM service_target t WHERE" + scope() + "ORDER BY t.created_at DESC,t.id LIMIT ? OFFSET ?", this::map, caller.isAdmin(), caller.userId(), size, offset);
        Long total = db.queryForObject("SELECT count(*) FROM service_target t WHERE" + scope(), Long.class, caller.isAdmin(), caller.userId());
        return new PageResult<>(items, total, page, size);
    }
    public Target get(Caller caller, UUID id) {
        var targets = db.query("SELECT t.* FROM service_target t WHERE t.id=? AND" + scope(), this::map, id, caller.isAdmin(), caller.userId());
        if (targets.isEmpty()) throw ApiError.notFound();
        return targets.getFirst();
    }
    @Transactional
    public Target create(Caller caller, TargetController.Create input, String key, String requestId) {
        // Preserve G1 fingerprints for the unchanged five-field request during the 24-hour replay window.
        Object fingerprint=input.allowHttp()==null&&input.allowedPrivateCidrs()==null&&input.networkReason()==null?
            new LegacyCreate(input.code(),input.name(),input.environment(),input.baseUrl(),input.ownerTeam()):input;
        UUID id = idempotency.create(caller.userId(), "CREATE_TARGET", key, fingerprint, () -> {
            boolean http=Boolean.TRUE.equals(input.allowHttp());
            var cidrs=policy.validateCidrs(input.allowedPrivateCidrs()==null?List.of():input.allowedPrivateCidrs());
            if((http||!cidrs.isEmpty())&&(input.networkReason()==null||input.networkReason().isBlank()))throw ApiError.badRequest("NETWORK_APPROVAL_REASON_REQUIRED");
            String safeUrl = policy.resolve(input.baseUrl(),http,cidrs).uri().toASCIIString();
            UUID newId = UUID.randomUUID();
            String network=cidrs.isEmpty()?"PUBLIC_ONLY":"APPROVED_PRIVATE_CIDR";
            db.update("INSERT INTO service_target(id,code,name,environment,base_url,owner_team,created_by,allow_http,network_policy) VALUES(?,?,?,?,?,?,?,?,?)",
                newId, input.code(), input.name(), input.environment(), safeUrl, input.ownerTeam(), caller.userId(),http,network);
            if(http||!cidrs.isEmpty())db.update("INSERT INTO target_network_policy(id,target_id,version,allowed_private_cidrs,reason,approved_by) VALUES(?,?,1,?::cidr[],?,?)",UUID.randomUUID(),newId,cidrs.toArray(String[]::new),input.networkReason(),caller.userId());
            audit.record(caller.userId(), "TARGET_CREATED", "SERVICE_TARGET", newId, requestId, Map.of("networkPolicy",network,"allowHttp",http,"privateCidrCount",cidrs.size()));
            return newId;
        });
        return get(caller, id);
    }
    private record LegacyCreate(String code,String name,String environment,String baseUrl,String ownerTeam){}
    public record Access(UUID userId,String username,String displayName,boolean canRead,boolean canOperate){}
    public PageResult<Access> access(Caller caller,UUID target,UUID user,int page,int size){
        get(caller,target);int offset=PageResult.offset(page,size);
        var rows=db.query("SELECT a.*,u.username,u.display_name FROM target_access a JOIN app_user u ON u.id=a.user_id WHERE a.target_id=? AND (?::uuid IS NULL OR a.user_id=?) ORDER BY u.username,u.id LIMIT ? OFFSET ?",(r,n)->new Access(r.getObject("user_id",UUID.class),r.getString("username"),r.getString("display_name"),r.getBoolean("can_read"),r.getBoolean("can_operate")),target,user,user,size,offset);
        return new PageResult<>(rows,db.queryForObject("SELECT count(*) FROM target_access WHERE target_id=? AND (?::uuid IS NULL OR user_id=?)",Long.class,target,user,user),page,size);
    }
    @Transactional
    public Map<String,Object> grant(Caller caller, UUID target, UUID user, TargetController.Grant grant, String requestId) {
        if (grant.canOperate() && !grant.canRead()) throw ApiError.badRequest("INVALID_TARGET_ACCESS");
        get(caller, target);
        if (db.queryForObject("SELECT count(*) FROM app_user WHERE id=?", Integer.class, user) == 0) throw ApiError.notFound();
        db.update("INSERT INTO target_access(target_id,user_id,can_read,can_operate) VALUES(?,?,?,?) ON CONFLICT(target_id,user_id) DO UPDATE SET can_read=EXCLUDED.can_read,can_operate=EXCLUDED.can_operate", target, user, grant.canRead(), grant.canOperate());
        audit.record(caller.userId(), "TARGET_ACCESS_CHANGED", "SERVICE_TARGET", target, requestId,
            Map.of("userId", user.toString(), "canRead", grant.canRead(), "canOperate", grant.canOperate(), "reason", grant.reason()));
        return Map.of("targetId", target, "userId", user, "canRead", grant.canRead(), "canOperate", grant.canOperate());
    }
}

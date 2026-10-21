package dev.agentops.targets;

import dev.agentops.access.Caller;
import dev.agentops.audit.AuditService;
import dev.agentops.monitoring.TargetGuard;
import dev.agentops.shared.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/targets/{targetId}/network-policy")
public class NetworkPolicyController {
    public record Update(@Min(1) int version,@NotBlank @Size(max=2048) String baseUrl,@NotNull Boolean allowHttp,
        @NotNull @Size(max=16) List<@NotBlank @Size(max=64) String> allowedPrivateCidrs,@NotBlank @Size(max=500) String reason){}
    private final JdbcTemplate db;private final TargetGuard guard;private final TargetService targets;private final TargetUrlPolicy policy;private final AuditService audit;
    public NetworkPolicyController(JdbcTemplate db,TargetGuard guard,TargetService targets,TargetUrlPolicy policy,AuditService audit){this.db=db;this.guard=guard;this.targets=targets;this.policy=policy;this.audit=audit;}
    @GetMapping @PreAuthorize("hasRole('OPS_ADMIN')")
    public Map<String,Object> get(@AuthenticationPrincipal Caller caller,@PathVariable UUID targetId){
        var target=targets.get(caller,targetId);
        List<String> cidrs=db.query("SELECT unnest(allowed_private_cidrs)::text FROM target_network_policy WHERE target_id=? AND active",(r,n)->r.getString(1),targetId);
        return Map.of("version",target.configVersion(),"baseUrl",target.baseUrl(),"allowHttp",target.allowHttp(),"allowedPrivateCidrs",cidrs);
    }
    @PutMapping @PreAuthorize("hasRole('OPS_ADMIN')") @Transactional
    public TargetService.Target update(@AuthenticationPrincipal Caller caller,@PathVariable UUID targetId,@Valid @RequestBody Update body,HttpServletRequest request){
        guard.lock(targetId);var target=targets.get(caller,targetId);if(target.configVersion()!=body.version())throw new ApiError(409,"TARGET_VERSION_CONFLICT");
        guard.noActiveIncident(targetId);List<String> cidrs=policy.validateCidrs(body.allowedPrivateCidrs());
        String url=policy.resolve(body.baseUrl(),body.allowHttp(),cidrs).uri().toASCIIString();
        db.update("UPDATE target_network_policy SET active=false WHERE target_id=? AND active",targetId);
        db.update("INSERT INTO target_network_policy(id,target_id,version,allowed_private_cidrs,reason,approved_by) VALUES(?,?,?,?::cidr[],?,?)",UUID.randomUUID(),targetId,body.version()+1,cidrs.toArray(String[]::new),body.reason(),caller.userId());
        db.update("UPDATE service_target SET base_url=?,allow_http=?,config_version=config_version+1,network_policy=? WHERE id=?",url,body.allowHttp(),cidrs.isEmpty()?"PUBLIC_ONLY":"APPROVED_PRIVATE_CIDR",targetId);
        db.update("UPDATE check_definition SET failure_count=0,success_count=0,lease_token=NULL,lease_until=NULL,execution_id=NULL,scheduled_at=NULL WHERE target_id=?",targetId);
        audit.record(caller.userId(),"TARGET_NETWORK_POLICY_CHANGED","SERVICE_TARGET",targetId,RequestIds.of(request),Map.of("version",body.version()+1,"allowHttp",body.allowHttp(),"privateCidrCount",cidrs.size(),"reason",body.reason()));
        return targets.get(caller,targetId);
    }
}

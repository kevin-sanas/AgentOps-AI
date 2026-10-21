package dev.agentops.targets;

import dev.agentops.access.Caller;
import dev.agentops.shared.PageResult;
import dev.agentops.shared.RequestIds;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/targets")
public class TargetController {
    private final TargetService targets;
    public TargetController(TargetService targets) { this.targets = targets; }
    public record Create(@NotBlank @Pattern(regexp="[a-z][a-z0-9-]{0,79}") String code, @NotBlank @Size(max=160) String name,
        @NotBlank @Size(max=40) String environment, @NotBlank @Size(max=2048) String baseUrl, @Size(max=120) String ownerTeam,
        Boolean allowHttp,@Size(max=16) List<@NotBlank @Size(max=64) String> allowedPrivateCidrs,@Size(max=500) String networkReason) {}
    public record Grant(@NotNull Boolean canRead, @NotNull Boolean canOperate, @NotBlank @Size(max=500) String reason) {}
    @GetMapping
    @PreAuthorize("hasAnyRole('OPS_ADMIN','OBSERVER','ON_CALL','AUDITOR')")
    PageResult<TargetService.Target> list(@AuthenticationPrincipal Caller caller, @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) { return targets.list(caller, page, size); }
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('OPS_ADMIN','OBSERVER','ON_CALL','AUDITOR')")
    TargetService.Target get(@AuthenticationPrincipal Caller caller, @PathVariable UUID id) { return targets.get(caller, id); }
    @PostMapping
    @PreAuthorize("hasRole('OPS_ADMIN')")
    ResponseEntity<TargetService.Target> create(@AuthenticationPrincipal Caller caller, @Valid @RequestBody Create input,
        @RequestHeader("Idempotency-Key") String key, HttpServletRequest request) {
        var target = targets.create(caller, input, key, RequestIds.of(request));
        return ResponseEntity.created(URI.create("/api/v1/targets/" + target.id())).body(target);
    }
    @PutMapping("/{id}/access/{userId}")
    @PreAuthorize("hasRole('OPS_ADMIN')")
    Map<String,Object> grant(@AuthenticationPrincipal Caller caller, @PathVariable UUID id, @PathVariable UUID userId,
        @Valid @RequestBody Grant grant, HttpServletRequest request) { return targets.grant(caller, id, userId, grant, RequestIds.of(request)); }
    @GetMapping("/{id}/access")
    @PreAuthorize("hasRole('OPS_ADMIN')")
    PageResult<TargetService.Access> access(@AuthenticationPrincipal Caller caller,@PathVariable UUID id,@RequestParam(required=false) UUID userId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return targets.access(caller,id,userId,page,size);}
}

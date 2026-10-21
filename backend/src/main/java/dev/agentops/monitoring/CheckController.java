package dev.agentops.monitoring;

import dev.agentops.access.Caller;
import dev.agentops.shared.PageResult;
import dev.agentops.shared.RequestIds;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/targets/{targetId}/checks")
public class CheckController {
    public record Config(@NotNull @Pattern(regexp="GET|HEAD") String method,
        @Min(60) @Max(86400) int intervalSeconds, @Min(100) @Max(10000) int timeoutMs,
        @NotEmpty @Size(max=20) List<@NotNull @Min(100) @Max(599) Integer> expectedStatuses,
        @Min(1) @Max(20) int failThreshold,@Min(1) @Max(20) int recoverThreshold) {}
    public record Update(@Min(1) int configVersion,@NotNull @Valid Config config,@NotBlank @Size(max=500) String reason) {}
    public record State(@Min(1) int configVersion,@NotNull @Pattern(regexp="ACTIVE|PAUSED|DISABLED") String state,@NotBlank @Size(max=500) String reason) {}
    private final CheckService checks;
    public CheckController(CheckService checks){this.checks=checks;}
    @GetMapping @PreAuthorize("hasAnyRole('OPS_ADMIN','OBSERVER','ON_CALL','AUDITOR')")
    PageResult<CheckService.Definition> list(@AuthenticationPrincipal Caller c,@PathVariable UUID targetId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return checks.list(c,targetId,page,size);}
    @PostMapping @PreAuthorize("hasRole('OPS_ADMIN')")
    ResponseEntity<CheckService.Definition> create(@AuthenticationPrincipal Caller c,@PathVariable UUID targetId,@RequestBody @Valid Config body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest request){
        var result=checks.create(c,targetId,body,key,RequestIds.of(request));
        return ResponseEntity.created(URI.create("/api/v1/targets/"+targetId+"/checks/"+result.id())).body(result);
    }
    @PutMapping("/{id}") @PreAuthorize("hasRole('OPS_ADMIN')")
    CheckService.Definition update(@AuthenticationPrincipal Caller c,@PathVariable UUID targetId,@PathVariable UUID id,@Valid @RequestBody Update body,HttpServletRequest request){return checks.update(c,targetId,id,body,RequestIds.of(request));}
    @PutMapping("/{id}/state") @PreAuthorize("hasRole('OPS_ADMIN')")
    CheckService.Definition state(@AuthenticationPrincipal Caller c,@PathVariable UUID targetId,@PathVariable UUID id,@Valid @RequestBody State body,HttpServletRequest request){return checks.state(c,targetId,id,body,RequestIds.of(request));}
    @GetMapping("/history") @PreAuthorize("hasAnyRole('OPS_ADMIN','OBSERVER','ON_CALL','AUDITOR')")
    PageResult<CheckService.Run> history(@AuthenticationPrincipal Caller c,@PathVariable UUID targetId,@RequestParam(required=false) UUID checkId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return checks.history(c,targetId,checkId,page,size);}
}

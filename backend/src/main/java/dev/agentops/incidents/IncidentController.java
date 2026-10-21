package dev.agentops.incidents;

import dev.agentops.access.Caller;
import dev.agentops.shared.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/incidents")
public class IncidentController {
    public record Transition(@Min(0) long version,@NotNull @Pattern(regexp="ACK|INVESTIGATE|RESOLVE|CLOSE") String action,@NotBlank @Size(max=2000) String reason){}
    private final IncidentService incidents;
    public IncidentController(IncidentService incidents){this.incidents=incidents;}
    @GetMapping @PreAuthorize("hasAnyRole('OPS_ADMIN','OBSERVER','ON_CALL','AUDITOR')")
    PageResult<IncidentService.Incident> list(@AuthenticationPrincipal Caller c,@RequestParam(required=false) UUID targetId,@RequestParam(required=false) String status,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return incidents.list(c,targetId,status,page,size);}
    @GetMapping("/pending-alerts") @PreAuthorize("hasAnyRole('OPS_ADMIN','OBSERVER','ON_CALL','AUDITOR')")
    PageResult<IncidentService.PendingAlert> pendingAlerts(@AuthenticationPrincipal Caller c,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return incidents.pendingAlerts(c,page,size);}
    @GetMapping("/{id}") @PreAuthorize("hasAnyRole('OPS_ADMIN','OBSERVER','ON_CALL','AUDITOR')")
    IncidentService.Detail detail(@AuthenticationPrincipal Caller c,@PathVariable UUID id){return incidents.detail(c,id);}
    @GetMapping("/{id}/events") @PreAuthorize("hasAnyRole('OPS_ADMIN','OBSERVER','ON_CALL','AUDITOR')")
    PageResult<IncidentService.Event> events(@AuthenticationPrincipal Caller c,@PathVariable UUID id,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return incidents.events(c,id,page,size);}
    @PostMapping("/{id}/transitions") @PreAuthorize("hasRole('ON_CALL')")
    IncidentService.Detail transition(@AuthenticationPrincipal Caller c,@PathVariable UUID id,@Valid @RequestBody Transition body,HttpServletRequest request){return incidents.transition(c,id,body,RequestIds.of(request));}
}

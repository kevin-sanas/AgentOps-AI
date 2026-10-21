package dev.agentops.agent;
import dev.agentops.access.Caller;
import dev.agentops.shared.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1")
public class AgentController {
    private final AgentService service;
    public AgentController(AgentService service){this.service=service;}
    @org.springframework.beans.factory.annotation.Autowired ApprovalService approvals;
    public record Start(){}
    public record Retry(@NotBlank @Size(max=500) String reason){}
    public record Decision(@NotNull UUID approvalRequestId,@Min(1) long version,@NotNull @Pattern(regexp="APPROVED|REJECTED") String decision,@NotBlank @Size(max=2000) String reason){}
    public record Manual(@NotNull @Pattern(regexp="EXECUTED|NOT_EXECUTED|FAILED") String outcome,@NotBlank @Size(max=2000) String note){}
    @PostMapping("/agent-runs/{id}/approval") @PreAuthorize("hasRole('ON_CALL')")
    ApprovalService.Approval decide(@AuthenticationPrincipal Caller c,@PathVariable UUID id,@Valid @RequestBody Decision input,HttpServletRequest request){approvals.expireDue();return approvals.decide(c,id,new ApprovalService.Decision(input.approvalRequestId(),input.version(),input.decision(),input.reason()),RequestIds.of(request));}
    @PostMapping("/agent-runs/{id}/manual-action-report") @PreAuthorize("hasRole('ON_CALL')")
    ApprovalService.Manual report(@AuthenticationPrincipal Caller c,@PathVariable UUID id,@Valid @RequestBody Manual input,@RequestHeader("Idempotency-Key") String key,HttpServletRequest request){return approvals.report(c,id,input.outcome(),input.note(),key,RequestIds.of(request));}
    @GetMapping("/admin/agent-deliveries") @PreAuthorize("hasRole('OPS_ADMIN')")
    PageResult<AgentService.Delivery> deliveries(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return service.deliveries(page,size);}
    @PostMapping("/incidents/{id}/agent-runs") @PreAuthorize("hasRole('ON_CALL')")
    ResponseEntity<AgentService.Requested> start(@AuthenticationPrincipal Caller c,@PathVariable UUID id,@Valid @RequestBody Start input,@RequestHeader("Idempotency-Key") String key,HttpServletRequest request){return ResponseEntity.accepted().body(service.start(c,id,key,RequestIds.of(request)));}
    @GetMapping("/incidents/{id}/agent-runs")
    PageResult<AgentService.Run> list(@AuthenticationPrincipal Caller c,@PathVariable UUID id,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return service.list(c,id,page,size);}
    @GetMapping("/agent-runs/{id}") AgentService.Run get(@AuthenticationPrincipal Caller c,@PathVariable UUID id){return service.get(c,id);}
    @PostMapping("/agent-runs/{id}/retry") @PreAuthorize("hasRole('OPS_ADMIN')")
    ResponseEntity<AgentService.Requested> retry(@AuthenticationPrincipal Caller c,@PathVariable UUID id,@Valid @RequestBody Retry input,HttpServletRequest request){return ResponseEntity.accepted().body(service.retry(c,id,input.reason(),RequestIds.of(request)));}
}

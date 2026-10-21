package dev.agentops.integrations;

import dev.agentops.access.Caller;
import dev.agentops.shared.RequestIds;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/integrations/uptime-kuma")
@PreAuthorize("hasRole('OPS_ADMIN')")
public class KumaController {
    private final KumaService service;public KumaController(KumaService service){this.service=service;}
    public record Create(@NotNull UUID targetId,@NotBlank @Size(max=2048) String baseUrl,@NotBlank @Size(max=120) String secretRef,@NotBlank @Size(max=500) String reason){}
    public record State(@Min(1) int version,@NotNull Boolean enabled,@NotBlank @Size(max=500) String reason){}
    public record Reason(@NotBlank @Size(max=500) String reason){}
    @PostMapping ResponseEntity<?> create(@AuthenticationPrincipal Caller caller,@Valid @RequestBody Create input,HttpServletRequest request){var c=service.create(caller,input,RequestIds.of(request));return ResponseEntity.created(URI.create("/api/v1/integrations/uptime-kuma/"+c.id())).body(c);}
    @GetMapping Object list(@AuthenticationPrincipal Caller caller,@RequestParam UUID targetId){return service.list(caller,targetId);}
    @PutMapping("/{id}") Object state(@AuthenticationPrincipal Caller caller,@PathVariable UUID id,@Valid @RequestBody State input,HttpServletRequest request){return service.state(caller,id,input,RequestIds.of(request));}
    @PostMapping("/{id}/test") ResponseEntity<?> smoke(@AuthenticationPrincipal Caller caller,@PathVariable UUID id,@Valid @RequestBody Reason input,HttpServletRequest request){return ResponseEntity.accepted().body(service.smoke(caller,id,input.reason(),RequestIds.of(request)));}
    @GetMapping("/{id}/deliveries") Object deliveries(@AuthenticationPrincipal Caller caller,@PathVariable UUID id,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return service.deliveries(caller,id,page,size);}
}

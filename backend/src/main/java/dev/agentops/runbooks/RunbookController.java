package dev.agentops.runbooks;

import dev.agentops.access.Caller;
import dev.agentops.shared.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1")
@PreAuthorize("hasAnyRole('OPS_ADMIN','OBSERVER','ON_CALL','AUDITOR')")
public class RunbookController {
    private final RunbookService service;
    public RunbookController(RunbookService service){this.service=service;}
    public record Create(@NotBlank @Size(max=160) String title,@NotBlank @Size(max=24000) String markdown){}
    public record Draft(@Min(1) long revision,@NotBlank @Size(max=160) String title,@NotBlank @Size(max=24000) String markdown,@NotBlank @Size(max=500) String reason){}
    public record Publish(@Min(1) long revision,@NotBlank @Size(max=500) String reason){}
    public record State(@Min(1) long revision,@NotNull Boolean enabled,@NotBlank @Size(max=500) String reason){}
    @GetMapping("/targets/{target}/runbooks")
    PageResult<RunbookService.Book> list(@AuthenticationPrincipal Caller c,@PathVariable UUID target,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return service.list(c,target,page,size);}
    @PostMapping("/targets/{target}/runbooks") @PreAuthorize("hasRole('OPS_ADMIN')")
    ResponseEntity<RunbookService.Book> create(@AuthenticationPrincipal Caller c,@PathVariable UUID target,@Valid @RequestBody Create input,@RequestHeader("Idempotency-Key") String key,HttpServletRequest request){var book=service.create(c,target,input,key,RequestIds.of(request));return ResponseEntity.created(URI.create("/api/v1/runbooks/"+book.id())).body(book);}
    @GetMapping("/runbooks/{id}") RunbookService.Book get(@AuthenticationPrincipal Caller c,@PathVariable UUID id){return service.get(c,id);}
    @GetMapping("/runbooks/{id}/versions/{version}") RunbookService.Version version(@AuthenticationPrincipal Caller c,@PathVariable UUID id,@PathVariable int version){return service.version(c,id,version);}
    @PutMapping("/runbooks/{id}/draft") @PreAuthorize("hasRole('OPS_ADMIN')")
    RunbookService.Book draft(@AuthenticationPrincipal Caller c,@PathVariable UUID id,@Valid @RequestBody Draft input,HttpServletRequest request){return service.draft(c,id,input,RequestIds.of(request));}
    @PostMapping("/runbooks/{id}/publish") @PreAuthorize("hasRole('OPS_ADMIN')")
    RunbookService.Book publish(@AuthenticationPrincipal Caller c,@PathVariable UUID id,@Valid @RequestBody Publish input,HttpServletRequest request){return service.publish(c,id,input,RequestIds.of(request));}
    @PutMapping("/runbooks/{id}/state") @PreAuthorize("hasRole('OPS_ADMIN')")
    RunbookService.Book state(@AuthenticationPrincipal Caller c,@PathVariable UUID id,@Valid @RequestBody State input,HttpServletRequest request){return service.state(c,id,input,RequestIds.of(request));}
}

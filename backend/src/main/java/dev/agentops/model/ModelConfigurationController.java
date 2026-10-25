package dev.agentops.model;

import dev.agentops.access.Caller;
import dev.agentops.shared.RequestIds;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/model-config")
@PreAuthorize("hasRole('OPS_ADMIN')")
public class ModelConfigurationController {
    private final ModelConfigurationService service;
    public ModelConfigurationController(ModelConfigurationService service) { this.service = service; }

    public record Save(@NotBlank @Size(min=8,max=512) String apiKey, @Min(0) long version) {
        @Override public String toString() { return "SaveModelConfiguration[REDACTED]"; }
    }
    public record Version(@Min(1) long version) {}
    public record Test(@Size(min=8,max=512) String apiKey) {
        @Override public String toString() { return "TestModelConfiguration[REDACTED]"; }
    }

    @GetMapping
    ModelConfigurationStore.Status status() { return service.status(); }

    @PutMapping
    ModelConfigurationStore.Status save(@AuthenticationPrincipal Caller caller, @Valid @RequestBody Save input,
                                        HttpServletRequest request) {
        return service.save(caller, input.apiKey(), input.version(), RequestIds.of(request));
    }

    @DeleteMapping
    ResponseEntity<Void> delete(@AuthenticationPrincipal Caller caller, @Valid @RequestBody Version input,
                                HttpServletRequest request) {
        service.delete(caller, input.version(), RequestIds.of(request));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/test")
    ModelProviderClient.Reply test(@AuthenticationPrincipal Caller caller,
                                   @Valid @RequestBody(required=false) Test input, HttpServletRequest request) {
        return service.test(caller, input == null ? null : input.apiKey(), RequestIds.of(request));
    }

    @GetMapping("/balance")
    ModelProviderClient.Reply balance(@AuthenticationPrincipal Caller caller, HttpServletRequest request) {
        return service.balance(caller, RequestIds.of(request));
    }
}

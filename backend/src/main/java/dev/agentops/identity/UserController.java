package dev.agentops.identity;

import dev.agentops.access.Caller;
import dev.agentops.shared.RequestIds;
import dev.agentops.shared.PageResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import jakarta.validation.constraints.Pattern;

@RestController
@PreAuthorize("hasRole('OPS_ADMIN')")
public class UserController {
    private final UserAdministration users;
    public UserController(UserAdministration users) { this.users = users; }
    public record Roles(@NotNull @Size(max=4) List<@NotBlank String> roles, @NotBlank @Size(max=500) String reason) {}
    public record Create(@NotBlank @Pattern(regexp="[a-zA-Z0-9_.-]{3,80}") String username,
        @NotBlank @Size(max=120) String displayName, @NotBlank @Size(min=12,max=72) String password,
        @NotNull @Size(max=4) List<@NotBlank String> roles) {
        @Override public String toString() { return "CreateUser[REDACTED]"; }
    }
    public record Active(@NotNull Boolean active, @NotBlank @Size(max=500) String reason) {}
    @GetMapping("/api/v1/admin/users")
    PageResult<UserAdministration.UserView> list(@RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) { return users.list(page, size); }
    @PostMapping("/api/v1/admin/users")
    ResponseEntity<UserAdministration.UserView> create(@AuthenticationPrincipal Caller caller, @Valid @RequestBody Create input,
        @RequestHeader("Idempotency-Key") String key, HttpServletRequest request) {
        var user = users.create(caller, input, key, RequestIds.of(request));
        return ResponseEntity.created(URI.create("/api/v1/admin/users/" + user.id())).body(user);
    }
    @PutMapping("/api/v1/admin/users/{userId}/active")
    UserAdministration.UserView active(@AuthenticationPrincipal Caller caller, @PathVariable UUID userId, @Valid @RequestBody Active input, HttpServletRequest request) {
        return users.setActive(caller, userId, input.active(), input.reason(), RequestIds.of(request));
    }
    @PutMapping("/api/v1/admin/users/{userId}/roles")
    Map<String,Object> roles(@PathVariable UUID userId, @Valid @RequestBody Roles roles,
        @AuthenticationPrincipal Caller caller, HttpServletRequest request) {
        return users.replaceRoles(caller, userId, roles.roles(), roles.reason(), RequestIds.of(request));
    }
}

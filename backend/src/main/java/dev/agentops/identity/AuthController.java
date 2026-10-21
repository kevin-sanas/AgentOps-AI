package dev.agentops.identity;

import dev.agentops.access.Caller;
import dev.agentops.shared.RequestIds;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {
    private final SessionService sessions;
    public AuthController(SessionService sessions) { this.sessions = sessions; }
    public record Login(@NotBlank @Size(max=80) String username, @NotBlank @Size(max=72) String password) {
        @Override public String toString() { return "Login[REDACTED]"; }
    }
    @PostMapping("/api/v1/auth/login")
    SessionService.LoginResult login(@Valid @RequestBody Login login, HttpServletRequest request) {
        return sessions.login(login.username(), login.password(), RequestIds.of(request));
    }
    @GetMapping("/api/v1/me")
    Map<String,Object> me(@AuthenticationPrincipal Caller caller) {
        return Map.of("id", caller.userId(), "username", caller.username(), "displayName", caller.displayName(), "roles", caller.roles());
    }
    @PostMapping("/api/v1/auth/logout")
    ResponseEntity<Void> logout(@AuthenticationPrincipal Caller caller, HttpServletRequest request) {
        sessions.logout(caller, RequestIds.of(request)); return ResponseEntity.noContent().build();
    }
    @GetMapping("/internal/v1/health")
    Map<String,String> internalHealth() { return Map.of("status", "UP"); }
}

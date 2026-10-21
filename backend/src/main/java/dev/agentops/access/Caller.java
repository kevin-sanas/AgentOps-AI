package dev.agentops.access;

import java.util.Set;
import java.util.UUID;

public record Caller(UUID userId, UUID sessionId, String username, String displayName, Set<String> roles) {
    public boolean isAdmin() { return roles.contains("OPS_ADMIN"); }
}

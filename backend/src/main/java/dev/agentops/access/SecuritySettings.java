package dev.agentops.access;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record SecuritySettings(String pepper, String internalToken, Duration sessionDuration) {
    public SecuritySettings(@Value("${agentops.security.session-pepper}") String pepper,
                            @Value("${agentops.security.internal-token}") String internalToken,
                            @Value("${agentops.security.session-duration}") Duration sessionDuration) {
        if (pepper == null || pepper.length() < 32 || internalToken == null || internalToken.length() < 32 || pepper.equals(internalToken)) {
            throw new IllegalStateException("Distinct session and internal secrets of at least 32 characters are required.");
        }
        if (sessionDuration.isNegative() || sessionDuration.isZero() || sessionDuration.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalStateException("Session duration must be positive and at most 24 hours.");
        }
        this.pepper = pepper; this.internalToken = internalToken; this.sessionDuration = sessionDuration;
    }
    @Override public String toString() { return "SecuritySettings[REDACTED]"; }
}

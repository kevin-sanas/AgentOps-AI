package dev.agentops.identity;

import dev.agentops.access.Caller;
import dev.agentops.access.SecuritySettings;
import dev.agentops.audit.AuditService;
import dev.agentops.shared.ApiError;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SessionService {
    private final JdbcTemplate db;
    private final PasswordEncoder passwords;
    private final SecuritySettings settings;
    private final AuditService audit;
    private final SecureRandom random = new SecureRandom();
    private final String dummyHash;
    public SessionService(JdbcTemplate db, PasswordEncoder passwords, SecuritySettings settings, AuditService audit) {
        this.db = db; this.passwords = passwords; this.settings = settings; this.audit = audit;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }
    public record LoginResult(String token, Instant expiresAt) {
        @Override public String toString() { return "LoginResult[REDACTED]"; }
    }
    private record User(UUID id, String username, String displayName, String hash, boolean active) {}
    @Transactional
    public LoginResult login(String username, String password, String requestId) {
        List<User> users = db.query("SELECT id,username,display_name,password_hash,active FROM app_user WHERE username=? FOR SHARE",
            (rs, n) -> new User(rs.getObject("id", UUID.class), rs.getString("username"), rs.getString("display_name"), rs.getString("password_hash"), rs.getBoolean("active")), username);
        User user = users.isEmpty() ? null : users.getFirst();
        boolean matches = passwords.matches(password, user == null ? dummyHash : user.hash());
        if (user == null || !matches || !user.active()) throw new ApiError(401, "INVALID_CREDENTIALS");
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiresAt = Instant.now().plus(settings.sessionDuration());
        UUID sessionId = UUID.randomUUID();
        db.update("INSERT INTO user_session(id,user_id,token_hash,expires_at) VALUES(?,?,?,?)", sessionId, user.id(), hash(token), OffsetDateTime.ofInstant(expiresAt, java.time.ZoneOffset.UTC));
        audit.record(user.id(), "LOGIN_SUCCEEDED", "USER_SESSION", sessionId, requestId, Map.of());
        return new LoginResult(token, expiresAt);
    }
    public Caller authenticate(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return null;
        var callers = db.query("SELECT u.id,u.username,u.display_name,s.id AS session_id FROM user_session s JOIN app_user u ON u.id=s.user_id WHERE s.token_hash=? AND s.revoked_at IS NULL AND s.expires_at>now() AND u.active",
            (rs, n) -> new Caller(rs.getObject("id", UUID.class), rs.getObject("session_id", UUID.class), rs.getString("username"), rs.getString("display_name"), java.util.Set.of()), hash(token));
        if (callers.isEmpty()) return null;
        Caller c = callers.getFirst();
        var roles = db.queryForList("SELECT r.code FROM app_role r JOIN user_role ur ON ur.role_id=r.id WHERE ur.user_id=?", String.class, c.userId());
        return new Caller(c.userId(), c.sessionId(), c.username(), c.displayName(), new HashSet<>(roles));
    }
    @Transactional
    public void logout(Caller caller, String requestId) {
        db.update("UPDATE user_session SET revoked_at=now() WHERE id=? AND revoked_at IS NULL", caller.sessionId());
        audit.record(caller.userId(), "LOGOUT", "USER_SESSION", caller.sessionId(), requestId, Map.of());
    }
    public String hash(String token) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(settings.pepper().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(token.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) { throw new IllegalStateException("Session hashing unavailable"); }
    }
}

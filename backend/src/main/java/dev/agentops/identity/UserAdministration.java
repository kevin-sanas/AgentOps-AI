package dev.agentops.identity;

import dev.agentops.access.Caller;
import dev.agentops.audit.AuditService;
import dev.agentops.shared.ApiError;
import dev.agentops.shared.Idempotency;
import dev.agentops.shared.PageResult;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserAdministration {
    private static final Set<String> ROLES = Set.of("OBSERVER", "ON_CALL", "OPS_ADMIN", "AUDITOR");
    private final JdbcTemplate db;
    private final AuditService audit;
    private final PasswordEncoder passwords;
    private final Idempotency idempotency;
    public UserAdministration(JdbcTemplate db, AuditService audit, PasswordEncoder passwords, Idempotency idempotency) {
        this.db = db; this.audit = audit; this.passwords = passwords; this.idempotency = idempotency;
    }
    public record UserView(UUID id, String username, String displayName, boolean active, List<String> roles) {}
    private UserView get(UUID id) {
        var users = db.query("SELECT id,username,display_name,active FROM app_user WHERE id=?",
            (rs,n) -> new UserView(rs.getObject("id", UUID.class), rs.getString("username"), rs.getString("display_name"), rs.getBoolean("active"), List.of()), id);
        if (users.isEmpty()) throw ApiError.notFound();
        var user = users.getFirst();
        var roles = db.queryForList("SELECT r.code FROM app_role r JOIN user_role ur ON ur.role_id=r.id WHERE ur.user_id=? ORDER BY r.code", String.class, id);
        return new UserView(id, user.username(), user.displayName(), user.active(), roles);
    }
    public PageResult<UserView> list(int page, int size) {
        int offset = PageResult.offset(page, size);
        var ids = db.queryForList("SELECT id FROM app_user ORDER BY created_at DESC,id LIMIT ? OFFSET ?", UUID.class, size, offset);
        return new PageResult<>(ids.stream().map(this::get).toList(), db.queryForObject("SELECT count(*) FROM app_user", Long.class), page, size);
    }
    @Transactional
    public UserView create(Caller caller, UserController.Create input, String key, String requestId) {
        if (!ROLES.containsAll(input.roles()) || input.roles().stream().distinct().count() != input.roles().size()) throw ApiError.badRequest("INVALID_ROLES");
        if (input.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) throw ApiError.badRequest("INVALID_PASSWORD_LENGTH");
        UUID id = idempotency.create(caller.userId(), "CREATE_USER", key, input, () -> {
            UUID newId = UUID.randomUUID();
            db.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES(?,?,?,?)", newId, input.username(), passwords.encode(input.password()), input.displayName());
            for (String role : input.roles()) db.update("INSERT INTO user_role(user_id,role_id) SELECT ?,id FROM app_role WHERE code=?", newId, role);
            audit.record(caller.userId(), "USER_CREATED", "APP_USER", newId, requestId, Map.of("roles", input.roles()));
            return newId;
        });
        return get(id);
    }
    @Transactional
    public UserView setActive(Caller caller, UUID userId, boolean active, String reason, String requestId) {
        lockAdministration();
        if (db.queryForList("SELECT id FROM app_user WHERE id=? FOR UPDATE", UUID.class, userId).isEmpty()) throw ApiError.notFound();
        if (!active) ensureAnotherAdmin(userId);
        db.update("UPDATE app_user SET active=? WHERE id=?", active, userId);
        if (!active) db.update("UPDATE user_session SET revoked_at=now() WHERE user_id=? AND revoked_at IS NULL", userId);
        audit.record(caller.userId(), "USER_ACTIVE_CHANGED", "APP_USER", userId, requestId, Map.of("active", active, "reason", reason));
        return get(userId);
    }
    @Transactional
    public Map<String,Object> replaceRoles(Caller caller, UUID userId, List<String> roles, String reason, String requestId) {
        if (!ROLES.containsAll(roles) || roles.stream().distinct().count() != roles.size()) throw ApiError.badRequest("INVALID_ROLES");
        lockAdministration();
        if (db.queryForList("SELECT id FROM app_user WHERE id=? FOR UPDATE", UUID.class, userId).isEmpty()) throw ApiError.notFound();
        if (!roles.contains("OPS_ADMIN")) ensureAnotherAdmin(userId);
        db.update("DELETE FROM user_role WHERE user_id=?", userId);
        for (String role : roles) db.update("INSERT INTO user_role(user_id,role_id) SELECT ?,id FROM app_role WHERE code=?", userId, role);
        audit.record(caller.userId(), "USER_ROLES_CHANGED", "APP_USER", userId, requestId, Map.of("roles", roles, "reason", reason));
        return Map.of("userId", userId, "roles", roles);
    }
    private void lockAdministration() { db.execute("SELECT pg_advisory_xact_lock(20261020)"); }
    private void ensureAnotherAdmin(UUID userId) {
        String admins = " FROM app_user u JOIN user_role ur ON ur.user_id=u.id JOIN app_role r ON r.id=ur.role_id WHERE u.active AND r.code='OPS_ADMIN'";
        if (db.queryForObject("SELECT count(*)" + admins + " AND u.id=?", Integer.class, userId) > 0
            && db.queryForObject("SELECT count(*)" + admins, Integer.class) <= 1) throw new ApiError(409, "LAST_ADMIN_REQUIRED");
    }
}

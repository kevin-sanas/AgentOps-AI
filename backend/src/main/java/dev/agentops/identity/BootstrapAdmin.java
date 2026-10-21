package dev.agentops.identity;

import dev.agentops.audit.AuditService;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class BootstrapAdmin implements ApplicationRunner {
    private final JdbcTemplate db;
    private final PasswordEncoder passwords;
    private final AuditService audit;
    private final String username;
    private final String password;
    public BootstrapAdmin(JdbcTemplate db, PasswordEncoder passwords, AuditService audit,
        @Value("${agentops.bootstrap.username}") String username, @Value("${agentops.bootstrap.password}") String password) {
        this.db = db; this.passwords = passwords; this.audit = audit; this.username = username; this.password = password;
    }
    @Override @Transactional
    public void run(ApplicationArguments args) {
        db.execute("SELECT pg_advisory_xact_lock(20261020)");
        if (db.queryForObject("SELECT count(*) FROM app_user", Long.class) > 0) return;
        if (username.isBlank() && password.isBlank()) return;
        if (!username.matches("[a-zA-Z0-9_.-]{3,80}") || password.length() < 12 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalStateException("Bootstrap requires a valid username and a password of at least 12 characters and at most 72 UTF-8 bytes.");
        }
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES(?,?,?,?)", id, username, passwords.encode(password), username);
        db.update("INSERT INTO user_role(user_id,role_id) SELECT ?,id FROM app_role WHERE code='OPS_ADMIN'", id);
        audit.record(id, "BOOTSTRAP_ADMIN_CREATED", "APP_USER", id, UUID.randomUUID().toString(), Map.of());
    }
}

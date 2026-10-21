package dev.agentops;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties="agentops.monitoring.enabled=false")
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class BootstrapTest {
    static final EmbeddedPostgres POSTGRES = PostgresFixture.start();
    static final String PASSWORD = UUID.randomUUID().toString();
    @Autowired JdbcTemplate db;
    @Autowired PasswordEncoder passwords;
    @Autowired ApplicationContext context;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:" + POSTGRES.getPort() + "/postgres");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("agentops.security.session-pepper", () -> UUID.randomUUID() + "-" + UUID.randomUUID());
        registry.add("agentops.security.internal-token", () -> UUID.randomUUID() + "-" + UUID.randomUUID());
        registry.add("agentops.bootstrap.username", () -> "initial-admin");
        registry.add("agentops.bootstrap.password", () -> PASSWORD);
    }
    @AfterAll static void closeDatabase() throws Exception { POSTGRES.close(); }

    @Test void explicitBootstrapCreatesOneAdminAndNeverResetsExistingAccounts() throws Exception {
        assertThat(db.queryForObject("SELECT count(*) FROM app_user", Integer.class)).isEqualTo(1);
        String hash = db.queryForObject("SELECT password_hash FROM app_user WHERE username='initial-admin'", String.class);
        assertThat(hash).isNotEqualTo(PASSWORD);
        assertThat(passwords.matches(PASSWORD, hash)).isTrue();
        assertThat(db.queryForList("SELECT code FROM app_role r JOIN user_role ur ON ur.role_id=r.id", String.class)).containsExactly("OPS_ADMIN");
        for (ApplicationRunner runner : context.getBeansOfType(ApplicationRunner.class).values()) runner.run(new DefaultApplicationArguments());
        assertThat(db.queryForObject("SELECT count(*) FROM app_user", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT password_hash FROM app_user", String.class)).isEqualTo(hash);
        assertThat(db.queryForObject("SELECT count(*) FROM audit_event WHERE action='BOOTSTRAP_ADMIN_CREATED'", Integer.class)).isEqualTo(1);
    }
}

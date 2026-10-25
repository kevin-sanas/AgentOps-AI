package dev.agentops;

import dev.agentops.access.SecuritySettings;
import dev.agentops.model.ModelConfigurationStore;
import dev.agentops.model.ModelSecretCipher;
import dev.agentops.model.ModelUsageStore;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
    "agentops.monitoring.enabled=false",
    "agentops.agent.enabled=false"
})
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class ModelConfigurationStoreTest {
    private static final UUID ADMIN = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final String PEPPER = "model-configuration-test-pepper-that-is-long-enough";
    private static final String INTERNAL = "model-configuration-test-internal-token-long-enough";
    private static final EmbeddedPostgres POSTGRES = PostgresFixture.start();

    @Autowired JdbcTemplate db;
    @Autowired ModelSecretCipher cipher;
    @Autowired ModelConfigurationStore configurations;
    @Autowired ModelUsageStore usage;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:" + POSTGRES.getPort() + "/postgres");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("agentops.security.session-pepper", () -> PEPPER);
        registry.add("agentops.security.internal-token", () -> INTERNAL);
        registry.add("spring.security.user.password", () -> UUID.randomUUID().toString());
    }

    @AfterAll
    static void closeDatabase() throws Exception {
        POSTGRES.close();
    }

    @BeforeEach
    void reset() {
        db.execute("TRUNCATE model_provider_request, model_provider_config, app_user CASCADE");
        db.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES(?,?,?,?)",
            ADMIN, "admin", "unused", "Administrator");
    }

    @Test
    void encryptsWithRandomNonceAndRejectsWrongPepperOrTampering() {
        var first = cipher.encrypt("sk-test-secret-value");
        var second = cipher.encrypt("sk-test-secret-value");

        assertThat(first.nonce()).hasSize(12).isNotEqualTo(second.nonce());
        assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
        assertThat(cipher.decrypt(first)).isEqualTo("sk-test-secret-value");
        assertThat(first.toString()).doesNotContain("secret", "sk-test");

        var wrong = new ModelSecretCipher(new SecuritySettings(
            "a-different-model-configuration-pepper-value", INTERNAL, Duration.ofMinutes(30)));
        assertThatThrownBy(() -> wrong.decrypt(first))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("MODEL_CREDENTIAL_DECRYPTION_FAILED");

        byte[] tampered = first.ciphertext().clone();
        tampered[0] ^= 1;
        assertThatThrownBy(() -> cipher.decrypt(new ModelSecretCipher.EncryptedSecret(tampered, first.nonce())))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("MODEL_CREDENTIAL_DECRYPTION_FAILED");
    }

    @Test
    void persistsOnlyEncryptedCredentialAndUsesOptimisticVersioning() {
        assertThat(configurations.status().configured()).isFalse();

        var created = configurations.save(ADMIN, "sk-first-credential", 0);
        assertThat(created.configured()).isTrue();
        assertThat(created.provider()).isEqualTo("deepseek");
        assertThat(created.model()).isEqualTo("deepseek-flash");
        assertThat(created.keySuffix()).isEqualTo("tial");
        assertThat(created.version()).isEqualTo(1);
        assertThat(configurations.credential().orElseThrow().apiKey()).isEqualTo("sk-first-credential");
        assertThat(configurations.credential().orElseThrow().toString()).doesNotContain("sk-first", "credential");
        assertThat(db.queryForObject(
            "SELECT encode(api_key_ciphertext,'hex') LIKE '%' || encode(convert_to(?,'UTF8'),'hex') || '%' FROM model_provider_config",
            Boolean.class, "sk-first-credential")).isFalse();

        assertThatThrownBy(() -> configurations.save(ADMIN, "sk-stale", 0))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("MODEL_CONFIGURATION_VERSION_CONFLICT");

        var updated = configurations.save(ADMIN, "sk-second-credential", 1);
        assertThat(updated.version()).isEqualTo(2);
        assertThat(configurations.credential().orElseThrow().apiKey()).isEqualTo("sk-second-credential");

        assertThatThrownBy(() -> configurations.delete(1))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("MODEL_CONFIGURATION_VERSION_CONFLICT");
        configurations.delete(2);
        assertThat(configurations.status().configured()).isFalse();
    }

    @Test
    void requestLedgerPreservesUnknownUsageInsteadOfWritingZero() {
        UUID request = usage.begin(new ModelUsageStore.Start(
            "CONNECTION_TEST", "deepseek", "deepseek-flash", null, null, ADMIN, "request-1"));
        usage.complete(request, new ModelUsageStore.Completion(
            "FAILED", "MODEL_TIMEOUT", null, 1250, null));

        var row = usage.list("CONNECTION_TEST", Instant.now().minus(Duration.ofHours(1)), Instant.now().plusSeconds(1), 0, 20)
            .items().getFirst();
        assertThat(row.id()).isEqualTo(request);
        assertThat(row.outcome()).isEqualTo("FAILED");
        assertThat(row.promptTokens()).isNull();
        assertThat(row.completionTokens()).isNull();
        assertThat(row.totalTokens()).isNull();
        assertThat(row.toString()).doesNotContain("Bearer", "sk-");

        var summary = usage.summary(Instant.now().minus(Duration.ofHours(1)), Instant.now().plusSeconds(1));
        assertThat(summary.requests()).isEqualTo(1);
        assertThat(summary.failed()).isEqualTo(1);
        assertThat(summary.requestsWithUsage()).isZero();
        assertThat(summary.totalTokens()).isZero();
    }
}

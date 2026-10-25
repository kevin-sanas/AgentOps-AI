package dev.agentops.model;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ModelConfigurationStore {
    public static final String PROVIDER = "deepseek";
    public static final String MODEL = "deepseek-flash";
    private final JdbcTemplate db;
    private final ModelSecretCipher cipher;

    public ModelConfigurationStore(JdbcTemplate db, ModelSecretCipher cipher) {
        this.db = db;
        this.cipher = cipher;
    }

    public Status status() {
        var rows = db.query("SELECT provider,model,key_suffix,version,updated_at,updated_by,last_test_at,last_test_outcome,last_test_error_code FROM model_provider_config WHERE id=1", this::status);
        return rows.isEmpty() ? Status.unconfigured() : rows.getFirst();
    }

    public Optional<Credential> credential() {
        return db.query("SELECT provider,model,api_key_ciphertext,api_key_nonce,version FROM model_provider_config WHERE id=1", (row, n) ->
            new Credential(row.getString("provider"), row.getString("model"), cipher.decrypt(new ModelSecretCipher.EncryptedSecret(
                row.getBytes("api_key_ciphertext"), row.getBytes("api_key_nonce"))), row.getLong("version"))).stream().findFirst();
    }

    @Transactional
    public Status save(UUID actor, String apiKey, long expectedVersion) {
        if (actor == null) throw new IllegalArgumentException("MODEL_CONFIGURATION_ACTOR_REQUIRED");
        if (expectedVersion < 0) throw new IllegalArgumentException("MODEL_CONFIGURATION_VERSION_INVALID");
        var encrypted = cipher.encrypt(apiKey);
        String suffix = apiKey.length() <= 4 ? apiKey : apiKey.substring(apiKey.length() - 4);
        if (suffix.length() != 4) throw new IllegalArgumentException("MODEL_CREDENTIAL_INVALID");
        int changed = db.update("""
            INSERT INTO model_provider_config(id,provider,model,api_key_ciphertext,api_key_nonce,key_suffix,version,updated_by)
            VALUES(1,?,?,?,?,?,1,?)
            ON CONFLICT (id) DO UPDATE SET
              provider=EXCLUDED.provider,model=EXCLUDED.model,
              api_key_ciphertext=EXCLUDED.api_key_ciphertext,api_key_nonce=EXCLUDED.api_key_nonce,
              key_suffix=EXCLUDED.key_suffix,version=model_provider_config.version+1,
              updated_at=now(),updated_by=EXCLUDED.updated_by,
              last_test_at=NULL,last_test_outcome=NULL,last_test_error_code=NULL
            WHERE model_provider_config.version=?
            """, PROVIDER, MODEL, encrypted.ciphertext(), encrypted.nonce(), suffix, actor, expectedVersion);
        if (changed != 1) throw versionConflict();
        Status saved = status();
        long requiredVersion = expectedVersion == 0 ? 1 : Math.addExact(expectedVersion, 1);
        if (saved.version() != requiredVersion) throw versionConflict();
        return saved;
    }

    @Transactional
    public void delete(long expectedVersion) {
        if (expectedVersion < 1 || db.update("DELETE FROM model_provider_config WHERE id=1 AND version=?", expectedVersion) != 1) {
            throw versionConflict();
        }
    }

    public Status recordTest(long expectedVersion, String outcome, String errorCode) {
        if (!("SUCCEEDED".equals(outcome) || "FAILED".equals(outcome))) {
            throw new IllegalArgumentException("MODEL_TEST_OUTCOME_INVALID");
        }
        int changed = db.update("UPDATE model_provider_config SET last_test_at=now(),last_test_outcome=?,last_test_error_code=? WHERE id=1 AND version=?",
            outcome, errorCode, expectedVersion);
        if (changed != 1) throw versionConflict();
        return status();
    }

    private Status status(ResultSet row, int number) throws SQLException {
        return new Status(true, row.getString("provider"), row.getString("model"), row.getString("key_suffix"), row.getLong("version"),
            row.getTimestamp("updated_at").toInstant(), row.getObject("updated_by", UUID.class),
            instant(row, "last_test_at"), row.getString("last_test_outcome"), row.getString("last_test_error_code"));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        var value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static IllegalStateException versionConflict() {
        return new IllegalStateException("MODEL_CONFIGURATION_VERSION_CONFLICT");
    }

    public record Status(boolean configured, String provider, String model, String keySuffix, long version,
                         Instant updatedAt, UUID updatedBy, Instant lastTestAt, String lastTestOutcome,
                         String lastTestErrorCode) {
        static Status unconfigured() {
            return new Status(false, PROVIDER, MODEL, null, 0, null, null, null, null, null);
        }
    }

    public record Credential(String provider, String model, String apiKey, long version) {
        @Override public String toString() {
            return "Credential[provider=" + provider + ",model=" + model + ",apiKey=REDACTED,version=" + version + "]";
        }
    }
}

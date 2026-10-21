package dev.agentops.shared;

import java.nio.charset.StandardCharsets;
import dev.agentops.access.SecuritySettings;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Must be called inside the same transaction as the business write and audit. */
@Component
public class Idempotency {
    private final JdbcTemplate db;
    private final JsonMapper json;
    private final SecuritySettings settings;
    public Idempotency(JdbcTemplate db, JsonMapper json, SecuritySettings settings) { this.db = db; this.json = json; this.settings = settings; }
    public UUID create(UUID user, String operation, String key, Object input, Supplier<UUID> create) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}")) throw ApiError.badRequest("INVALID_IDEMPOTENCY_KEY");
        String hash = fingerprint(json.writeValueAsString(input));
        db.update("DELETE FROM api_idempotency WHERE user_id=? AND operation=? AND idempotency_key=? AND expires_at<=now()", user, operation, key);
        int claimed = db.update("INSERT INTO api_idempotency(id,user_id,operation,idempotency_key,request_hash,state,expires_at) VALUES(?,?,?,?,?,'IN_PROGRESS',now()+interval '24 hours') ON CONFLICT(user_id,operation,idempotency_key) DO NOTHING", UUID.randomUUID(), user, operation, key, hash);
        if (claimed == 0) {
            var row = db.queryForMap("SELECT request_hash,state,response_resource_id FROM api_idempotency WHERE user_id=? AND operation=? AND idempotency_key=?", user, operation, key);
            if (!hash.equals(row.get("request_hash"))) throw new ApiError(409, "IDEMPOTENCY_CONFLICT");
            if (!"COMPLETED".equals(row.get("state"))) throw new ApiError(409, "REQUEST_IN_PROGRESS");
            return (UUID) row.get("response_resource_id");
        }
        UUID id = create.get();
        db.update("UPDATE api_idempotency SET state='COMPLETED',response_status=201,response_resource_id=? WHERE user_id=? AND operation=? AND idempotency_key=?", id, user, operation, key);
        return id;
    }
    private String fingerprint(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(settings.pepper().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            // Domain separation prevents treating a request fingerprint as a session hash.
            return HexFormat.of().formatHex(mac.doFinal(("agentops:idempotency:v1\0" + value).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) { throw new IllegalStateException("Request fingerprint unavailable"); }
    }
}

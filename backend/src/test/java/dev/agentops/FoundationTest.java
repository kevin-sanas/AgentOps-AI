package dev.agentops;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties="agentops.monitoring.enabled=false")
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Import(SessionRaceTestConfiguration.class)
class FoundationTest {
    static final UUID ADMIN = UUID.fromString("10000000-0000-0000-0000-000000000001");
    static final UUID READER = UUID.fromString("10000000-0000-0000-0000-000000000002");
    static final UUID OTHER = UUID.fromString("10000000-0000-0000-0000-000000000003");
    static final String INTERNAL = UUID.randomUUID() + "-" + UUID.randomUUID();
    static final String PEPPER = UUID.randomUUID() + "-" + UUID.randomUUID();
    static final EmbeddedPostgres POSTGRES = PostgresFixture.start();
    final HttpClient http = HttpClient.newHttpClient();
    final JsonMapper json = new JsonMapper();
    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired Flyway flyway;
    @Autowired SessionRaceTestConfiguration.GatePasswordEncoder gatePasswords;
    String password;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:" + POSTGRES.getPort() + "/postgres");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("agentops.security.session-pepper", () -> PEPPER);
        registry.add("agentops.security.internal-token", () -> INTERNAL);
        registry.add("spring.security.user.password", () -> UUID.randomUUID().toString());
    }

    @AfterAll static void closeDatabase() throws Exception { POSTGRES.close(); }

    @BeforeEach
    void seed() {
        db.execute("TRUNCATE app_user, user_role, user_session, audit_event, service_target, target_access, api_idempotency CASCADE");
        password = UUID.randomUUID().toString();
        String hash = new BCryptPasswordEncoder(4).encode(password);
        for (var user : Map.of(ADMIN, "admin", READER, "reader", OTHER, "other").entrySet()) {
            db.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES(?,?,?,?)", user.getKey(), user.getValue(), hash, user.getValue());
        }
        role(ADMIN, "OPS_ADMIN"); role(READER, "OBSERVER"); role(OTHER, "ON_CALL");
    }

    void role(UUID user, String role) {
        db.update("INSERT INTO user_role(user_id,role_id) SELECT ?,id FROM app_role WHERE code=?", user, role);
    }

    HttpResponse<String> request(String method, String path, String token, Object body, String key) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Content-Type", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (key != null) builder.header("Idempotency-Key", key);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> request(String method, String path, String token, Object body) throws Exception {
        return request(method, path, token, body, null);
    }

    String login(String username) throws Exception {
        var result = request("POST", "/api/v1/auth/login", null, Map.of("username", username, "password", password));
        assertThat(result.statusCode()).as("login response: %s", result.body()).isEqualTo(200);
        return json.readTree(result.body()).path("token").asText();
    }

    Map<String,Object> target(String code) {
        return Map.of("code", code, "name", "Orders fixture", "environment", "test", "baseUrl", "https://example.com/health", "ownerTeam", "demo");
    }

    UUID seedTarget(String code) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO service_target(id,code,name,environment,base_url,created_by) VALUES(?,?,?,'test','https://example.com/health',?)", id, code, code, ADMIN);
        return id;
    }

    @Test void anonymousAndInvalidLoginReturnSafeProblems() throws Exception {
        var anonymous = request("GET", "/api/v1/me", null, null);
        assertThat(anonymous.statusCode()).isEqualTo(401);
        JsonNode problem = json.readTree(anonymous.body());
        assertThat(problem.path("code").asText()).isEqualTo("UNAUTHENTICATED");
        assertThat(problem.path("traceId").asText()).isEqualTo(anonymous.headers().firstValue("X-Request-Id").orElseThrow());
        var wrong = request("POST", "/api/v1/auth/login", null, Map.of("username", "admin", "password", "wrong"));
        var missing = request("POST", "/api/v1/auth/login", null, Map.of("username", "absent", "password", "wrong"));
        assertThat(wrong.statusCode()).isEqualTo(401);
        assertThat(missing.statusCode()).isEqualTo(401);
        assertThat(json.readTree(wrong.body()).path("code").asText()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(wrong.body()).doesNotContain("password", "stackTrace", "jdbc:");
    }

    @Test void sessionStoresOnlyHashAndLogoutRevokesIt() throws Exception {
        String token = login("admin");
        assertThat(token).hasSizeGreaterThanOrEqualTo(43);
        String stored = db.queryForObject("SELECT token_hash FROM user_session", String.class);
        assertThat(stored).hasSize(64).isNotEqualTo(token);
        var me = request("GET", "/api/v1/me", token, null);
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(me.body()).contains("OPS_ADMIN").doesNotContain("ON_CALL", "passwordHash");
        assertThat(request("POST", "/api/v1/auth/logout", token, null).statusCode()).isEqualTo(204);
        assertThat(request("GET", "/api/v1/me", token, null).statusCode()).isEqualTo(401);
        assertThat(db.queryForObject("SELECT revoked_at IS NOT NULL FROM user_session", Boolean.class)).isTrue();
        String audit = db.queryForObject("SELECT string_agg(metadata::text,'') FROM audit_event", String.class);
        assertThat(audit).doesNotContain(token, password, PEPPER, INTERNAL);
    }

    @Test void expiryAndDisabledUsersInvalidateExistingSessions() throws Exception {
        String token = login("reader");
        db.update("UPDATE user_session SET expires_at=?", java.sql.Timestamp.from(Instant.now().minusSeconds(1)));
        assertThat(request("GET", "/api/v1/me", token, null).statusCode()).isEqualTo(401);
        token = login("reader");
        db.update("UPDATE app_user SET active=false WHERE id=?", READER);
        assertThat(request("GET", "/api/v1/me", token, null).statusCode()).isEqualTo(401);
        assertThat(request("POST", "/api/v1/auth/login", null, Map.of("username", "reader", "password", password)).statusCode()).isEqualTo(401);
    }

    @Test void internalAndUserTokensAreNotInterchangeable() throws Exception {
        String token = login("admin");
        assertThat(request("GET", "/internal/v1/health", null, null).statusCode()).isEqualTo(401);
        assertThat(request("GET", "/internal/v1/health", token, null).statusCode()).isEqualTo(401);
        assertThat(request("GET", "/internal/v1/health", INTERNAL, null).statusCode()).isEqualTo(200);
        assertThat(request("GET", "/api/v1/me", INTERNAL, null).statusCode()).isEqualTo(401);
        assertThat(request("GET", "/actuator/health", null, null).statusCode()).isEqualTo(200);
    }

    @Test void resourceAclFiltersListAndHidesOtherTargets() throws Exception {
        UUID visible = seedTarget("visible");
        UUID hidden = seedTarget("hidden");
        db.update("INSERT INTO target_access(target_id,user_id,can_read,can_operate) VALUES(?,?,true,false)", visible, READER);
        String token = login("reader");
        var list = request("GET", "/api/v1/targets?page=0&size=10", token, null);
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(json.readTree(list.body()).path("total").asInt()).isEqualTo(1);
        assertThat(list.body()).contains(visible.toString()).doesNotContain(hidden.toString());
        assertThat(request("GET", "/api/v1/targets/" + hidden, token, null).statusCode()).isEqualTo(404);
        assertThat(request("POST", "/api/v1/targets", token, target("denied"), "create-target-denied").statusCode()).isEqualTo(403);
        assertThat(request("GET", "/api/v1/targets?size=101", token, null).statusCode()).isEqualTo(400);
    }

    @Test void targetCreationIsAtomicIdempotentAndAudited() throws Exception {
        String token = login("admin");
        var first = request("POST", "/api/v1/targets", token, target("orders"), "create-orders");
        assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        var replay = request("POST", "/api/v1/targets", token, target("orders"), "create-orders");
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(json.readTree(replay.body()).path("id")).isEqualTo(json.readTree(first.body()).path("id"));
        assertThat(request("POST", "/api/v1/targets", token, target("different"), "create-orders").statusCode()).isEqualTo(409);
        assertThat(request("POST", "/api/v1/targets", token, target("orders"), "duplicate-code").statusCode()).isEqualTo(409);
        assertThat(db.queryForObject("SELECT count(*) FROM service_target", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM api_idempotency", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM audit_event WHERE action='TARGET_CREATED'", Integer.class)).isEqualTo(1);
        assertThat(request("POST", "/api/v1/targets", token, target("no-key")).statusCode()).isEqualTo(400);
    }

    @Test void aclAndRoleChangesTakeEffectForExistingTokens() throws Exception {
        String admin = login("admin"); String reader = login("reader");
        UUID id = seedTarget("acl");
        assertThat(request("PUT", "/api/v1/targets/" + id + "/access/" + READER, admin, Map.of("canRead", false, "canOperate", true, "reason", "invalid")).statusCode()).isEqualTo(400);
        assertThat(request("PUT", "/api/v1/targets/" + id + "/access/" + READER, admin, Map.of("canRead", true, "canOperate", false, "reason", "demo access")).statusCode()).isEqualTo(200);
        assertThat(request("GET", "/api/v1/targets/" + id, reader, null).statusCode()).isEqualTo(200);
        assertThat(request("PUT", "/api/v1/admin/users/" + READER + "/roles", admin, Map.of("roles", List.of("AUDITOR"), "reason", "audit rotation")).statusCode()).isEqualTo(200);
        assertThat(request("GET", "/api/v1/me", reader, null).body()).contains("AUDITOR").doesNotContain("OBSERVER");
        assertThat(request("GET", "/api/v1/audit-events", reader, null).statusCode()).isEqualTo(200);
        assertThat(request("GET", "/api/v1/audit-events", admin, null).statusCode()).isEqualTo(403);
        assertThat(request("PUT", "/api/v1/targets/" + id + "/access/" + READER, admin, Map.of("canRead", false, "canOperate", false, "reason", "access ended")).statusCode()).isEqualTo(200);
        assertThat(request("GET", "/api/v1/targets/" + id, reader, null).statusCode()).isEqualTo(404);
    }

    @Test void unsafeTargetUrlsCannotBeRegistered() throws Exception {
        String admin = login("admin");
        for (String url : List.of("http://example.com", "https://127.0.0.1", "https://169.254.169.254", "https://[::1]", "https://user:secret@example.com", "https://example.com:8443", "https://example.com/?token=secret", "https://localhost", "https://2130706433", "https://example.com/#secret")) {
            var input = Map.of("code", "bad", "name", "bad", "environment", "test", "baseUrl", url);
            var result = request("POST", "/api/v1/targets", admin, input, UUID.randomUUID().toString());
            assertThat(result.statusCode()).as("must reject %s: %s", url, result.body()).isEqualTo(400);
            assertThat(json.readTree(result.body()).path("code").asText()).isEqualTo("TARGET_URL_BLOCKED");
            assertThat(result.body()).doesNotContain(url, "secret");
        }
        assertThat(db.queryForObject("SELECT count(*) FROM service_target", Integer.class)).isZero();
    }

    @Test void invalidAndUnknownFieldsReturnValidationProblems() throws Exception {
        assertThat(request("POST", "/api/v1/auth/login", null, Map.of("username", "admin", "password", password, "role", "OPS_ADMIN")).statusCode()).isEqualTo(400);
        var result = request("POST", "/api/v1/targets", login("admin"), Map.of("code", "", "name", "bad", "environment", "test", "baseUrl", "https://example.com"), "bad-fields");
        assertThat(result.statusCode()).isEqualTo(400);
        assertThat(result.body()).doesNotContain("password_hash", "stackTrace");
    }

    @Test void administratorsCanCreateAndDisableUsersWithoutExposingCredentials() throws Exception {
        String admin = login("admin");
        var input = Map.of("username", "new-user", "displayName", "New user", "password", password, "roles", List.of("OBSERVER"));
        var created = request("POST", "/api/v1/admin/users", admin, input, "new-user-request");
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        assertThat(created.body()).doesNotContain(password, "passwordHash", "password_hash");
        UUID id = UUID.fromString(json.readTree(created.body()).path("id").asText());
        assertThat(json.readTree(request("POST", "/api/v1/admin/users", admin, input, "new-user-request").body()).path("id").asText()).isEqualTo(id.toString());
        String token = login("new-user");
        var disabled = request("PUT", "/api/v1/admin/users/" + id + "/active", admin, Map.of("active", false, "reason", "demo account ended"));
        assertThat(disabled.statusCode()).isEqualTo(200);
        assertThat(request("GET", "/api/v1/me", token, null).statusCode()).isEqualTo(401);
        assertThat(db.queryForObject("SELECT count(*) FROM user_session WHERE user_id=? AND revoked_at IS NULL", Integer.class, id)).isZero();
        var users = request("GET", "/api/v1/admin/users", admin, null);
        assertThat(users.statusCode()).isEqualTo(200);
        assertThat(users.body()).contains("new-user").doesNotContain(password, "passwordHash", "password_hash");
        assertThat(request("POST", "/api/v1/admin/users", login("reader"), input, "observer-cannot-create").statusCode()).isEqualTo(403);
    }

    @Test void unknownRolesDoNotRemoveExistingRolesOrWriteAnAudit() throws Exception {
        String admin = login("admin");
        var result = request("PUT", "/api/v1/admin/users/" + READER + "/roles", admin, Map.of("roles", List.of("SUPERUSER"), "reason", "invalid"));
        assertThat(result.statusCode()).isEqualTo(400);
        assertThat(db.queryForObject("SELECT r.code FROM app_role r JOIN user_role ur ON ur.role_id=r.id WHERE ur.user_id=?", String.class, READER)).isEqualTo("OBSERVER");
        assertThat(db.queryForObject("SELECT count(*) FROM audit_event WHERE action='USER_ROLES_CHANGED'", Integer.class)).isZero();
    }

    @Test void concurrentIdempotentTargetCreatesCommitOnlyOneResource() throws Exception {
        String token = login("admin");
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> request("POST", "/api/v1/targets", token, target("concurrent"), "same-request"));
            var two = pool.submit(() -> request("POST", "/api/v1/targets", token, target("concurrent"), "same-request"));
            var first = one.get(); var second = two.get();
            assertThat(first.statusCode()).isEqualTo(201);
            assertThat(second.statusCode()).isEqualTo(201);
            assertThat(json.readTree(first.body()).path("id")).isEqualTo(json.readTree(second.body()).path("id"));
        }
        assertThat(db.queryForObject("SELECT count(*) FROM service_target", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM audit_event WHERE action='TARGET_CREATED'", Integer.class)).isEqualTo(1);
    }

    @Test void secondFlywayMigrationRunIsANoop() {
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(db.queryForList("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class)).containsExactly("1","2","3","4","5");
    }

    @Test void userIdempotencyDoesNotExposeAnUnkeyedPasswordVerifier() throws Exception {
        String admin = login("admin");
        var input = Map.of("username", "new-user", "displayName", "New user", "password", password, "roles", List.of("OBSERVER"));
        assertThat(request("POST", "/api/v1/admin/users", admin, input, "protected-fingerprint").statusCode()).isEqualTo(201);
        String canonical = "{\"username\":\"new-user\",\"displayName\":\"New user\",\"password\":\"" + password + "\",\"roles\":[\"OBSERVER\"]}";
        String unkeyed = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        String stored = db.queryForObject("SELECT request_hash FROM api_idempotency WHERE idempotency_key='protected-fingerprint'", String.class);
        assertThat(stored).isNotEqualTo(unkeyed);
        assertThat(request("POST", "/api/v1/admin/users", admin, input, "protected-fingerprint").statusCode()).isEqualTo(201);
        var changed = Map.of("username", "new-user", "displayName", "New user", "password", UUID.randomUUID().toString(), "roles", List.of("OBSERVER"));
        assertThat(request("POST", "/api/v1/admin/users", admin, changed, "protected-fingerprint").statusCode()).isEqualTo(409);
    }

    @Test void unknownRoutesAndUnsupportedMethodsHaveCorrectProblemStatuses() throws Exception {
        String token = login("admin");
        assertThat(request("GET", "/api/v1/not-implemented", token, null).statusCode()).isEqualTo(404);
        assertThat(request("PATCH", "/api/v1/targets", token, Map.of()).statusCode()).isEqualTo(405);
    }

    @Test void theLastActiveAdministratorCannotBeDisabledOrDemoted() throws Exception {
        String token = login("admin");
        assertThat(request("PUT", "/api/v1/admin/users/" + ADMIN + "/active", token, Map.of("active", false, "reason", "remove last admin")).statusCode()).isEqualTo(409);
        assertThat(request("PUT", "/api/v1/admin/users/" + ADMIN + "/roles", token, Map.of("roles", List.of("OBSERVER"), "reason", "remove last admin role")).statusCode()).isEqualTo(409);
        assertThat(request("GET", "/api/v1/admin/users", token, null).statusCode()).isEqualTo(200);
        assertThat(db.queryForObject("SELECT count(*) FROM audit_event WHERE action IN ('USER_ACTIVE_CHANGED','USER_ROLES_CHANGED')", Integer.class)).isZero();
    }

    @Test void concurrentAdministratorDeactivationLeavesOneActiveAdministrator() throws Exception {
        role(OTHER, "OPS_ADMIN");
        String first = login("admin"); String second = login("other");
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> request("PUT", "/api/v1/admin/users/" + OTHER + "/active", first, Map.of("active", false, "reason", "one")));
            var two = pool.submit(() -> request("PUT", "/api/v1/admin/users/" + ADMIN + "/active", second, Map.of("active", false, "reason", "two")));
            var codes = List.of(one.get().statusCode(), two.get().statusCode());
            assertThat(codes).contains(200);
            assertThat(codes.stream().filter(code -> code == 200).count()).isEqualTo(1);
        }
        assertThat(db.queryForObject("SELECT count(*) FROM app_user u JOIN user_role ur ON ur.user_id=u.id JOIN app_role r ON r.id=ur.role_id WHERE u.active AND r.code='OPS_ADMIN'", Integer.class)).isEqualTo(1);
    }

    @Test void loginOverlappingDeactivationCannotLeaveARevivableSession() throws Exception {
        String admin = login("admin");
        var gate = gatePasswords.arm();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var loggingIn = pool.submit(() -> login("reader"));
            try {
                assertThat(gate.entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                var disabling = pool.submit(() -> request("PUT", "/api/v1/admin/users/" + READER + "/active", admin, Map.of("active", false, "reason", "disable during password verification")));
                try { disabling.get(500, java.util.concurrent.TimeUnit.MILLISECONDS); }
                catch (java.util.concurrent.TimeoutException expected) { /* Row locking may intentionally block deactivation. */ }
                gate.release.countDown();
                String token = loggingIn.get(5, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(disabling.get(5, java.util.concurrent.TimeUnit.SECONDS).statusCode()).isEqualTo(200);
                assertThat(db.queryForObject("SELECT count(*) FROM user_session WHERE user_id=? AND revoked_at IS NULL", Integer.class, READER)).isZero();
                assertThat(request("PUT", "/api/v1/admin/users/" + READER + "/active", admin, Map.of("active", true, "reason", "reactivate after review")).statusCode()).isEqualTo(200);
                assertThat(request("GET", "/api/v1/me", token, null).statusCode()).isEqualTo(401);
            } finally { gate.release.countDown(); }
        }
    }

    @Test void auditFailureRollsBackTargetAndIdempotencyClaim() throws Exception {
        String admin = login("admin");
        db.execute("CREATE FUNCTION reject_target_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action='TARGET_CREATED' THEN RAISE EXCEPTION 'fixture audit failure'; END IF; RETURN NEW; END $$");
        db.execute("CREATE TRIGGER fixture_reject_target_audit BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION reject_target_audit()");
        try {
            assertThat(request("POST", "/api/v1/targets", admin, target("rollback"), "rollback-request").statusCode()).isEqualTo(500);
            assertThat(db.queryForObject("SELECT count(*) FROM service_target", Integer.class)).isZero();
            assertThat(db.queryForObject("SELECT count(*) FROM api_idempotency", Integer.class)).isZero();
        } finally {
            db.execute("DROP TRIGGER fixture_reject_target_audit ON audit_event");
            db.execute("DROP FUNCTION reject_target_audit()");
        }
    }
}

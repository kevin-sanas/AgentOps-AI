package dev.agentops;

import com.sun.net.httpserver.HttpServer;
import dev.agentops.model.ModelConfigurationStore;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "agentops.monitoring.enabled=false", "agentops.agent.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ModelConfigurationApiTest {
    private static final EmbeddedPostgres POSTGRES = PostgresFixture.start();
    private static final HttpServer AGENT = startAgent();
    private static final UUID ADMIN = UUID.randomUUID();
    private static final UUID OPERATOR = UUID.randomUUID();
    private static final UUID OBSERVER = UUID.randomUUID();
    private static final String INTERNAL = "model-api-internal-token-" + UUID.randomUUID();
    private static final String PEPPER = "model-api-session-pepper-" + UUID.randomUUID();

    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired ModelConfigurationStore configurations;
    final JsonMapper json = new JsonMapper();
    final HttpClient http = HttpClient.newHttpClient();
    String password;
    String admin;
    String operator;
    String observer;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:" + POSTGRES.getPort() + "/postgres");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("agentops.security.session-pepper", () -> PEPPER);
        registry.add("agentops.security.internal-token", () -> INTERNAL);
        registry.add("agentops.agent.origin", () -> "http://127.0.0.1:" + AGENT.getAddress().getPort());
    }

    @AfterAll
    static void close() throws Exception {
        AGENT.stop(0);
        POSTGRES.close();
    }

    @BeforeEach
    void seed() throws Exception {
        db.execute("TRUNCATE app_user CASCADE");
        password = UUID.randomUUID().toString();
        for (var entry : Map.of(ADMIN, "admin", OPERATOR, "operator", OBSERVER, "observer").entrySet()) {
            db.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES(?,?,?,?)", entry.getKey(), entry.getValue(),
                new BCryptPasswordEncoder(4).encode(password), entry.getValue());
        }
        role(ADMIN, "OPS_ADMIN");
        role(OPERATOR, "ON_CALL");
        role(OBSERVER, "OBSERVER");
        admin = login("admin");
        operator = login("operator");
        observer = login("observer");
    }

    @Test
    void onlyAdminCanReadRedactedStatusAndMutateWithOptimisticVersions() throws Exception {
        assertThat(request("GET", "/admin/model-config", null, null).statusCode()).isEqualTo(401);
        assertThat(request("GET", "/admin/model-config", operator, null).statusCode()).isEqualTo(403);
        assertThat(request("GET", "/admin/model-config", observer, null).statusCode()).isEqualTo(403);
        var empty = ok(request("GET", "/admin/model-config", admin, null), 200);
        assertThat(empty.path("configured").asBoolean()).isFalse();

        String firstKey = "fixture-first-model-key";
        var createdResponse = request("PUT", "/admin/model-config", admin, Map.of("apiKey", firstKey, "version", 0));
        var created = ok(createdResponse, 200);
        assertThat(created.path("configured").asBoolean()).isTrue();
        assertThat(created.path("keySuffix").asText()).isEqualTo("-key");
        assertThat(created.path("version").asLong()).isEqualTo(1);
        assertThat(createdResponse.body()).doesNotContain(firstKey, "ciphertext", "nonce");
        assertThat(createdResponse.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(configurations.credential().orElseThrow().apiKey()).isEqualTo(firstKey);

        assertThat(request("PUT", "/admin/model-config", admin, Map.of("apiKey", "fixture-stale-key", "version", 0)).statusCode()).isEqualTo(409);
        var updated = ok(request("PUT", "/admin/model-config", admin, Map.of("apiKey", "fixture-second-model-key", "version", 1)), 200);
        assertThat(updated.path("version").asLong()).isEqualTo(2);
        assertThat(request("DELETE", "/admin/model-config", admin, Map.of("version", 1)).statusCode()).isEqualTo(409);
        assertThat(request("DELETE", "/admin/model-config", admin, Map.of("version", 2)).statusCode()).isEqualTo(204);
        assertThat(configurations.credential()).isEmpty();
    }

    @Test
    void candidateTestNeverOverwritesSavedKeyAndBalanceUsesOnlySavedCredential() throws Exception {
        String saved = "fixture-saved-model-key";
        ok(request("PUT", "/admin/model-config", admin, Map.of("apiKey", saved, "version", 0)), 200);

        var rejected = request("POST", "/admin/model-config/test", admin, Map.of("apiKey", "candidate-fail"));
        assertThat(rejected.statusCode()).isEqualTo(400);
        assertThat(json.readTree(rejected.body()).path("code").asText()).isEqualTo("MODEL_AUTHENTICATION_FAILED");
        assertThat(configurations.credential().orElseThrow().apiKey()).isEqualTo(saved);

        var tested = ok(request("POST", "/admin/model-config/test", admin, Map.of()), 200);
        assertThat(tested.path("outcome").asText()).isEqualTo("SUCCEEDED");
        assertThat(tested.toString()).doesNotContain(saved);
        var balance = ok(request("GET", "/admin/model-config/balance", admin, null), 200);
        assertThat(balance.path("isAvailable").asBoolean()).isTrue();
        assertThat(balance.path("balances").get(0).path("currency").asText()).isEqualTo("CNY");
        assertThat(balance.path("balances").get(0).path("totalBalance").asText()).isEqualTo("18.25");
        assertThat(balance.toString()).doesNotContain(saved);

        String audit = db.queryForObject("SELECT coalesce(string_agg(metadata::text,''),'') FROM audit_event", String.class);
        assertThat(audit).doesNotContain(saved, "candidate-fail");
        assertThat(db.queryForObject("SELECT count(*) FROM model_provider_request", Integer.class)).isEqualTo(3);
    }

    @Test
    void failedAuditRollsBackCredentialWrite() throws Exception {
        db.execute("ALTER TABLE audit_event ADD CONSTRAINT reject_model_config_audit CHECK(action<>'MODEL_CONFIGURATION_SAVED') NOT VALID");
        try {
            assertThat(request("PUT", "/admin/model-config", admin, Map.of("apiKey", "fixture-rollback-model-key", "version", 0)).statusCode()).isEqualTo(409);
            assertThat(configurations.status().configured()).isFalse();
        } finally {
            db.execute("ALTER TABLE audit_event DROP CONSTRAINT reject_model_config_audit");
        }
    }

    void role(UUID user, String role) {
        db.update("INSERT INTO user_role SELECT ?,id FROM app_role WHERE code=?", user, role);
    }

    String login(String username) throws Exception {
        return ok(request("POST", "/auth/login", null, Map.of("username", username, "password", password)), 200).path("token").asText();
    }

    HttpResponse<String> request(String method, String path, String token, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
            .header("Content-Type", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        var publisher = body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body));
        return http.send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
    }

    JsonNode ok(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        return response.body().isBlank() ? json.createObjectNode() : json.readTree(response.body());
    }

    static HttpServer startAgent() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String key = exchange.getRequestHeaders().getFirst("X-Dawnwatch-Model-Credential");
                String path = exchange.getRequestURI().getPath();
                String requestId = exchange.getRequestHeaders().getFirst("X-Request-Id");
                String response;
                if (!INTERNAL.equals(exchange.getRequestHeaders().getFirst("Authorization").replace("Bearer ", ""))) {
                    response = "{\"code\":\"UNAUTHENTICATED\"}";
                    send(exchange, 401, response);
                    return;
                }
                if (path.endsWith("/test") && "candidate-fail".equals(key)) {
                    response = "{\"requestId\":\"" + requestId + "\",\"purpose\":\"CONNECTION_TEST\",\"provider\":\"deepseek\",\"model\":\"deepseek-flash\",\"outcome\":\"FAILED\",\"errorCode\":\"MODEL_AUTHENTICATION_FAILED\",\"httpStatus\":401,\"elapsedMs\":2,\"usage\":null}";
                } else if (path.endsWith("/test")) {
                    response = "{\"requestId\":\"" + requestId + "\",\"purpose\":\"CONNECTION_TEST\",\"provider\":\"deepseek\",\"model\":\"deepseek-flash\",\"outcome\":\"SUCCEEDED\",\"errorCode\":null,\"httpStatus\":200,\"elapsedMs\":2,\"usage\":null}";
                } else {
                    response = "{\"requestId\":\"" + requestId + "\",\"purpose\":\"BALANCE_QUERY\",\"provider\":\"deepseek\",\"model\":\"deepseek-flash\",\"outcome\":\"SUCCEEDED\",\"errorCode\":null,\"httpStatus\":200,\"elapsedMs\":3,\"usage\":null,\"isAvailable\":true,\"balances\":[{\"currency\":\"CNY\",\"totalBalance\":\"18.25\",\"grantedBalance\":\"0.00\",\"toppedUpBalance\":\"18.25\"}]}";
                }
                send(exchange, 200, response);
            });
            server.start();
            return server;
        } catch (Exception error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    static void send(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}

package dev.agentops;

import dev.agentops.model.ModelUsageStore;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
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
class ModelUsageApiTest {
    private static final EmbeddedPostgres POSTGRES = PostgresFixture.start();
    private static final UUID ADMIN = UUID.randomUUID();
    private static final UUID OBSERVER = UUID.randomUUID();
    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired ModelUsageStore usage;
    final HttpClient http = HttpClient.newHttpClient();
    final JsonMapper json = new JsonMapper();
    String password;
    String admin;
    String observer;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:" + POSTGRES.getPort() + "/postgres");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("agentops.security.session-pepper", () -> "usage-api-pepper-" + ADMIN);
        registry.add("agentops.security.internal-token", () -> "usage-api-internal-" + OBSERVER);
    }

    @AfterAll static void close() throws Exception { POSTGRES.close(); }

    @BeforeEach
    void seed() throws Exception {
        db.execute("TRUNCATE app_user CASCADE");
        password = UUID.randomUUID().toString();
        for (var entry : Map.of(ADMIN, "admin", OBSERVER, "observer").entrySet()) {
            db.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES(?,?,?,?)", entry.getKey(), entry.getValue(),
                new BCryptPasswordEncoder(4).encode(password), entry.getValue());
        }
        db.update("INSERT INTO user_role SELECT ?,id FROM app_role WHERE code='OPS_ADMIN'", ADMIN);
        db.update("INSERT INTO user_role SELECT ?,id FROM app_role WHERE code='OBSERVER'", OBSERVER);
        admin = login("admin");
        observer = login("observer");
        UUID success = usage.begin(new ModelUsageStore.Start("CONNECTION_TEST", "deepseek", "deepseek-flash", null, null, ADMIN, "usage-success"));
        usage.complete(success, new ModelUsageStore.Completion("SUCCEEDED", null, 200, 12, null));
        UUID failed = usage.begin(new ModelUsageStore.Start("DIAGNOSIS", "deepseek", "deepseek-flash", null, null, ADMIN, "usage-failed"));
        usage.complete(failed, new ModelUsageStore.Completion("FAILED", "MODEL_TIMEOUT", null, 1000, null));
    }

    @Test
    void usageSummaryAndRequestsAreAdminOnlyBoundedAndKeepUnknownTokensNull() throws Exception {
        String from = Instant.now().minusSeconds(3600).toString();
        String to = Instant.now().plusSeconds(1).toString();
        assertThat(get("/admin/model-usage/summary?from=" + from + "&to=" + to, null).statusCode()).isEqualTo(401);
        assertThat(get("/admin/model-usage/summary?from=" + from + "&to=" + to, observer).statusCode()).isEqualTo(403);
        JsonNode summary = ok(get("/admin/model-usage/summary?from=" + from + "&to=" + to, admin));
        assertThat(summary.path("requests").asInt()).isEqualTo(2);
        assertThat(summary.path("failed").asInt()).isEqualTo(1);
        assertThat(summary.path("requestsWithUsage").asInt()).isZero();
        JsonNode requests = ok(get("/admin/model-usage/requests?from=" + from + "&to=" + to + "&page=0&size=20", admin));
        assertThat(requests.path("total").asInt()).isEqualTo(2);
        assertThat(requests.path("items").get(0).path("totalTokens").isNull()).isTrue();
        assertThat(get("/admin/model-usage/requests?from=" + Instant.now().minusSeconds(40L * 86400).toString() + "&to=" + to, admin).statusCode()).isEqualTo(400);
        assertThat(get("/admin/model-usage/requests?from=" + from + "&to=" + to + "&size=101", admin).statusCode()).isEqualTo(400);
    }

    String login(String username) throws Exception {
        var response = request("POST", "/auth/login", null, json.writeValueAsString(Map.of("username", username, "password", password)));
        return json.readTree(response.body()).path("token").asText();
    }

    HttpResponse<String> get(String path, String token) throws Exception { return request("GET", path, token, null); }

    HttpResponse<String> request(String method, String path, String token, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path)).header("Content-Type", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    JsonNode ok(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }
}

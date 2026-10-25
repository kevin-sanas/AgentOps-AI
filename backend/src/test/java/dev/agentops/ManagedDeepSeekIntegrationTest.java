package dev.agentops;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.agentops.access.Caller;
import dev.agentops.agent.AgentClient;
import dev.agentops.agent.AgentDispatcher;
import dev.agentops.agent.AgentOutbox;
import dev.agentops.agent.AgentService;
import dev.agentops.model.ModelConfigurationStore;
import dev.agentops.model.ModelUsageStore;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {"agentops.monitoring.enabled=false", "agentops.agent.enabled=false"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ManagedDeepSeekIntegrationTest {
    private static final EmbeddedPostgres POSTGRES = PostgresFixture.start();
    private static final UUID ADMIN = UUID.randomUUID();
    private static final UUID OPERATOR = UUID.randomUUID();
    private static final UUID TARGET = UUID.randomUUID();
    private static final UUID INCIDENT = UUID.randomUUID();
    private static final String INTERNAL = "managed-deepseek-internal-token-" + UUID.randomUUID();
    private static final String PEPPER = "managed-deepseek-session-pepper-" + UUID.randomUUID();
    private static final String KEY = "fixture-managed-deepseek-key";
    private static final FakeAgent AGENT = FakeAgent.start();

    @Autowired JdbcTemplate db;
    @Autowired AgentService runs;
    @Autowired AgentOutbox outbox;
    @Autowired AgentClient client;
    @Autowired ModelConfigurationStore configurations;
    @Autowired ModelUsageStore usage;
    final Caller operator = new Caller(OPERATOR, UUID.randomUUID(), "operator", "Operator", Set.of("ON_CALL"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:" + POSTGRES.getPort() + "/postgres");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("agentops.security.session-pepper", () -> PEPPER);
        registry.add("agentops.security.internal-token", () -> INTERNAL);
        registry.add("agentops.agent.origin", AGENT::origin);
    }

    @AfterAll static void close() throws Exception { AGENT.close(); POSTGRES.close(); }

    @BeforeEach
    void seed() {
        AGENT.reset();
        db.execute("TRUNCATE app_user CASCADE");
        db.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES(?,?,'unused','Admin'),(?,?,'unused','Operator')",
            ADMIN, "admin", OPERATOR, "operator");
        db.update("INSERT INTO user_role SELECT ?,id FROM app_role WHERE code='OPS_ADMIN'", ADMIN);
        db.update("INSERT INTO user_role SELECT ?,id FROM app_role WHERE code='ON_CALL'", OPERATOR);
        db.update("INSERT INTO service_target(id,code,name,environment,base_url,created_by) VALUES(?,'managed','Managed','test','https://example.com/health',?)", TARGET, ADMIN);
        db.update("INSERT INTO target_access(target_id,user_id,can_read,can_operate) VALUES(?,?,true,true)", TARGET, OPERATOR);
        db.update("INSERT INTO incident(id,target_id,last_failure_at,summary) VALUES(?,?,now(),'Managed DeepSeek fixture')", INCIDENT, TARGET);
    }

    @Test
    void configuredRunUsesOneScopedCredentialAndPersistsExactUsageAssociations() {
        configurations.save(ADMIN, KEY, 0);
        UUID runId = runs.start(operator, INCIDENT, "managed-success", "request-success").runId();
        AgentOutbox.Task task = outbox.claim().orElseThrow();

        assertThat(task.input().modelName()).isEqualTo("deepseek-flash");
        assertThat(task.toString()).doesNotContain(KEY);
        dispatcher().deliver(task);

        assertThat(AGENT.invokeCalls.get()).isEqualTo(1);
        assertThat(AGENT.invokeCredentials).containsExactly(KEY);
        assertThat(AGENT.readCredentials).containsOnly("<absent>");
        assertThat(runs.get(operator, runId).status()).isEqualTo("COMPLETED");

        ModelUsageStore.Request request = usage.byDiagnosisRun(runId).orElseThrow();
        assertThat(request.outcome()).isEqualTo("SUCCEEDED");
        assertThat(request.agentRunId()).isEqualTo(runId);
        assertThat(request.incidentId()).isEqualTo(INCIDENT);
        assertThat(request.actorId()).isEqualTo(OPERATOR);
        assertThat(request.promptTokens()).isEqualTo(120);
        assertThat(request.completionTokens()).isEqualTo(30);
        assertThat(request.totalTokens()).isEqualTo(150);
        assertThat(request.cacheHitTokens()).isEqualTo(20);
        assertThat(request.cacheMissTokens()).isEqualTo(100);

        AgentClient.Reply replay = client.reconcile(task, KEY);
        assertThat(replay.status()).isEqualTo("COMPLETED");
        assertThat(AGENT.invokeCalls.get()).isEqualTo(1);
        assertThat(AGENT.readCredentials).containsOnly("<absent>");

        assertThat(db.queryForObject("SELECT input::text FROM agent_run WHERE id=?", String.class, runId)).doesNotContain(KEY);
        assertThat(db.queryForObject("SELECT payload::text FROM outbox_event WHERE run_id=?", String.class, runId)).doesNotContain(KEY);
        assertThat(db.queryForObject("SELECT coalesce(string_agg(metadata::text,''),'') FROM audit_event", String.class)).doesNotContain(KEY);
    }

    @Test
    void unconfiguredRunStaysMockAndSafeProviderFailureIsVisibleWithoutTokens() {
        UUID mock = runs.start(operator, INCIDENT, "managed-mock", "request-mock").runId();
        assertThat(outbox.claim().orElseThrow().input().modelName()).isEqualTo("mock");
        db.execute("TRUNCATE outbox_event,agent_step,agent_run,api_idempotency CASCADE");

        configurations.save(ADMIN, KEY, 0);
        AGENT.mode.set("TLS_FAILURE");
        UUID failed = runs.start(operator, INCIDENT, "managed-tls", "request-tls").runId();
        dispatcher().deliver(outbox.claim().orElseThrow());

        AgentService.Run run = runs.get(operator, failed);
        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(run.errorCode()).isEqualTo("MODEL_TLS_VERIFY_FAILED");
        ModelUsageStore.Request request = usage.byDiagnosisRun(failed).orElseThrow();
        assertThat(request.outcome()).isEqualTo("FAILED");
        assertThat(request.errorCode()).isEqualTo("MODEL_TLS_VERIFY_FAILED");
        assertThat(request.totalTokens()).isNull();
        assertThat(AGENT.invokeCalls.get()).isEqualTo(1);
    }

    private AgentDispatcher dispatcher() {
        return new AgentDispatcher(outbox, client, configurations);
    }

    private static final class FakeAgent implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger invokeCalls = new AtomicInteger();
        final AtomicReference<String> mode = new AtomicReference<>("SUCCESS");
        final List<String> invokeCredentials = new CopyOnWriteArrayList<>();
        final List<String> readCredentials = new CopyOnWriteArrayList<>();
        final Map<String, String> checkpoints = new ConcurrentHashMap<>();
        final JsonMapper json = new JsonMapper();

        private FakeAgent(HttpServer server) { this.server = server; }
        static FakeAgent start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                FakeAgent fixture = new FakeAgent(server);
                server.createContext("/", fixture::handle);
                server.start();
                return fixture;
            } catch (Exception error) { throw new ExceptionInInitializerError(error); }
        }
        String origin() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        void reset() { invokeCalls.set(0); mode.set("SUCCESS"); invokeCredentials.clear(); readCredentials.clear(); checkpoints.clear(); }

        void handle(HttpExchange exchange) {
            try {
                assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer " + INTERNAL);
                String credential = exchange.getRequestHeaders().getFirst("X-Dawnwatch-Model-Credential");
                String path = exchange.getRequestURI().getPath();
                String runId = path.split("/")[4];
                if ("GET".equals(exchange.getRequestMethod())) {
                    readCredentials.add(credential == null ? "<absent>" : credential);
                    String checkpoint = checkpoints.get(runId);
                    if (checkpoint == null) { send(exchange, 404, "{\"code\":\"RESOURCE_NOT_FOUND\"}"); return; }
                    send(exchange, 200, checkpoint); return;
                }
                invokeCalls.incrementAndGet(); invokeCredentials.add(credential);
                JsonNode input = json.readTree(exchange.getRequestBody().readAllBytes());
                String requestId = exchange.getRequestHeaders().getFirst("X-Request-Id");
                String provider = provider(requestId, "TLS_FAILURE".equals(mode.get()));
                if ("TLS_FAILURE".equals(mode.get())) {
                    String body = "{\"code\":\"DEPENDENCY_UNAVAILABLE\",\"providerRequest\":" + provider + "}";
                    checkpoints.put(runId, "{\"runId\":\"" + runId + "\",\"status\":\"RUNNING\",\"graphVersion\":\"readonly-review-v2\",\"providerRequest\":" + provider + "}");
                    send(exchange, 503, body); return;
                }
                String result = "{\"runId\":\"" + runId + "\",\"status\":\"COMPLETED\",\"graphVersion\":\"readonly-review-v2\",\"modelName\":\"deepseek-flash\",\"summary\":\"合成诊断完成\",\"hypotheses\":[],\"evidenceIds\":[],\"nextSteps\":[],\"citations\":[],\"uncertainties\":[\"合成测试证据\"],\"steps\":[\"validate\",\"context\",\"retrieval\",\"analyze\",\"guard\",\"finalize\"]}";
                String body = "{\"runId\":\"" + runId + "\",\"status\":\"COMPLETED\",\"graphVersion\":\"" + input.path("graphVersion").asText() + "\",\"result\":" + result + ",\"providerRequest\":" + provider + "}";
                checkpoints.put(runId, body);
                send(exchange, 200, body);
            } catch (Throwable error) {
                try { send(exchange, 500, "{\"code\":\"FIXTURE_FAILURE\"}"); } catch (Exception ignored) {}
            }
        }

        String provider(String requestId, boolean failed) {
            if (failed) return "{\"requestId\":\"" + requestId + "\",\"purpose\":\"DIAGNOSIS\",\"provider\":\"deepseek\",\"model\":\"deepseek-flash\",\"outcome\":\"FAILED\",\"errorCode\":\"MODEL_TLS_VERIFY_FAILED\",\"httpStatus\":null,\"elapsedMs\":7,\"usage\":null}";
            return "{\"requestId\":\"" + requestId + "\",\"purpose\":\"DIAGNOSIS\",\"provider\":\"deepseek\",\"model\":\"deepseek-flash\",\"outcome\":\"SUCCEEDED\",\"errorCode\":null,\"httpStatus\":200,\"elapsedMs\":9,\"usage\":{\"promptTokens\":120,\"completionTokens\":30,\"totalTokens\":150,\"cacheHitTokens\":20,\"cacheMissTokens\":100}}";
        }
        static void send(HttpExchange exchange, int status, String body) throws Exception {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
        @Override public void close() { server.stop(0); }
    }
}

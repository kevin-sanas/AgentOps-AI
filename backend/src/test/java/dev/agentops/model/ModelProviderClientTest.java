package dev.agentops.model;

import com.sun.net.httpserver.HttpServer;
import dev.agentops.access.SecuritySettings;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelProviderClientTest {
    private static final String INTERNAL = "provider-client-internal-token-000000000000";

    @Test
    void oversizedAgentResponseHasStableNonRetryableCode() throws Exception {
        try (ServerFixture fixture = server(exchange -> {
            byte[] body = ("{" + "\"padding\":\"" + "x".repeat(70_000) + "\"}")
                .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        })) {
            assertThatThrownBy(() -> fixture.client().test("fixture-model-key", "request-large"))
                .isInstanceOfSatisfying(ModelProviderClient.Failure.class, failure -> {
                    org.assertj.core.api.Assertions.assertThat(failure.code).isEqualTo("MODEL_AGENT_RESPONSE_INVALID");
                    org.assertj.core.api.Assertions.assertThat(failure.retryable).isFalse();
                });
        }
    }

    @Test
    void invalidBalanceAndOverflowingElapsedTimeAreRejected() throws Exception {
        try (ServerFixture invalidBalance = jsonServer(requestId -> """
            {"requestId":"%s","purpose":"BALANCE_QUERY","provider":"deepseek","model":"deepseek-flash",
             "outcome":"SUCCEEDED","errorCode":null,"httpStatus":200,"elapsedMs":3,"usage":null,
             "isAvailable":true,"balances":[{"currency":"yuan","totalBalance":"-1",
             "grantedBalance":"0","toppedUpBalance":"0"}]}
            """.formatted(requestId))) {
            assertInvalid(() -> invalidBalance.client().balance("fixture-model-key", "request-balance"));
        }
        try (ServerFixture overflow = jsonServer(requestId -> """
            {"requestId":"%s","purpose":"CONNECTION_TEST","provider":"deepseek","model":"deepseek-flash",
             "outcome":"SUCCEEDED","errorCode":null,"httpStatus":200,"elapsedMs":2147483648,"usage":null}
            """.formatted(requestId))) {
            assertInvalid(() -> overflow.client().test("fixture-model-key", "request-overflow"));
        }
    }

    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ModelProviderClient.Failure.class,
            failure -> org.assertj.core.api.Assertions.assertThat(failure.code)
                .isEqualTo("MODEL_AGENT_RESPONSE_INVALID"));
    }

    private static ServerFixture jsonServer(java.util.function.Function<String, String> response) throws Exception {
        return server(exchange -> {
            String requestId = exchange.getRequestHeaders().getFirst("X-Request-Id");
            byte[] body = response.apply(requestId).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
    }

    private static ServerFixture server(com.sun.net.httpserver.HttpHandler handler) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            if (!("Bearer " + INTERNAL).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                exchange.sendResponseHeaders(401, -1);
                exchange.close();
                return;
            }
            handler.handle(exchange);
        });
        server.start();
        return new ServerFixture(server);
    }

    private record ServerFixture(HttpServer server) implements AutoCloseable {
        ModelProviderClient client() {
            SecuritySettings settings = new SecuritySettings(
                "provider-client-session-pepper-" + UUID.randomUUID(), INTERNAL, Duration.ofMinutes(30));
            return new ModelProviderClient(settings, new JsonMapper(),
                "http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(2));
        }

        @Override public void close() { server.stop(0); }
    }
}

package dev.agentops.model;

import dev.agentops.access.SecuritySettings;
import dev.agentops.agent.AgentClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ModelProviderClient {
    private static final Set<String> PURPOSES = Set.of("CONNECTION_TEST", "BALANCE_QUERY");
    private static final Set<String> OUTCOMES = Set.of("SUCCEEDED", "FAILED");
    private final HttpClient http;
    private final URI origin;
    private final String internalToken;
    private final JsonMapper json;
    private final Duration timeout;

    @org.springframework.beans.factory.annotation.Autowired
    public ModelProviderClient(SecuritySettings settings, JsonMapper json,
                               @Value("${agentops.agent.origin:http://127.0.0.1:8090}") String origin) {
        this(settings, json, origin, Duration.ofSeconds(15));
    }

    public ModelProviderClient(SecuritySettings settings, JsonMapper json, String origin, Duration timeout) {
        this.origin = AgentClient.validatedOrigin(origin);
        this.internalToken = settings.internalToken();
        this.json = json;
        if (timeout == null || timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofSeconds(15)) > 0) {
            throw new IllegalArgumentException("Model provider timeout must be between 1ns and 15s");
        }
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .proxy(new java.net.ProxySelector() {
                public java.util.List<java.net.Proxy> select(URI uri) { return java.util.List.of(java.net.Proxy.NO_PROXY); }
                public void connectFailed(URI uri, java.net.SocketAddress address, java.io.IOException error) {}
            }).build();
    }

    public Reply test(String apiKey, String requestId) {
        return exchange("POST", "/internal/v1/providers/deepseek/test", "CONNECTION_TEST", apiKey, requestId);
    }

    public Reply balance(String apiKey, String requestId) {
        return exchange("GET", "/internal/v1/providers/deepseek/balance", "BALANCE_QUERY", apiKey, requestId);
    }

    private Reply exchange(String method, String path, String purpose, String apiKey, String requestId) {
        if (apiKey == null || apiKey.isBlank() || apiKey.length() > 512 || apiKey.indexOf('\r') >= 0 || apiKey.indexOf('\n') >= 0) {
            throw new Failure("MODEL_CREDENTIAL_INVALID", false);
        }
        var builder = HttpRequest.newBuilder(origin.resolve(path)).timeout(timeout)
            .header("Authorization", "Bearer " + internalToken)
            .header("X-Request-Id", requestId)
            .header("X-Dawnwatch-Model-Credential", apiKey);
        HttpRequest request = "POST".equals(method)
            ? builder.POST(HttpRequest.BodyPublishers.noBody()).build()
            : builder.GET().build();
        try {
            var response = http.send(request, info -> new LimitedBody());
            if (response.statusCode() != 200) throw new Failure("MODEL_AGENT_HTTP_" + response.statusCode(), response.statusCode() >= 500);
            Reply reply = json.readValue(response.body(), Reply.class);
            validate(reply, purpose, requestId);
            return reply;
        } catch (Failure failure) {
            throw failure;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new Failure("MODEL_AGENT_INTERRUPTED", true);
        } catch (java.net.http.HttpTimeoutException error) {
            throw new Failure("MODEL_AGENT_TIMEOUT", true);
        } catch (Exception error) {
            if (hasCause(error, TooLarge.class)) throw new Failure("MODEL_AGENT_RESPONSE_INVALID", false);
            throw new Failure("MODEL_AGENT_UNAVAILABLE", true);
        }
    }

    private static void validate(Reply reply, String purpose, String requestId) {
        if (reply == null || !requestId.equals(reply.requestId()) || !purpose.equals(reply.purpose())
            || !"deepseek".equals(reply.provider()) || !"deepseek-flash".equals(reply.model())
            || !PURPOSES.contains(reply.purpose()) || !OUTCOMES.contains(reply.outcome()) || reply.elapsedMs() < 0
            || reply.elapsedMs() > Integer.MAX_VALUE
            || reply.usage() != null || ("SUCCEEDED".equals(reply.outcome()) && reply.errorCode() != null)
            || ("FAILED".equals(reply.outcome()) && (reply.errorCode() == null || reply.errorCode().isBlank()))) {
            throw new Failure("MODEL_AGENT_RESPONSE_INVALID", false);
        }
        if ("BALANCE_QUERY".equals(purpose) && "SUCCEEDED".equals(reply.outcome())
            && (reply.isAvailable() == null || reply.balances() == null || reply.balances().isEmpty() || reply.balances().size() > 20)) {
            throw new Failure("MODEL_AGENT_RESPONSE_INVALID", false);
        }
        if ("BALANCE_QUERY".equals(purpose) && "SUCCEEDED".equals(reply.outcome())) validateBalances(reply.balances());
    }

    private static void validateBalances(List<Balance> balances) {
        var currencies = new HashSet<String>();
        for (Balance balance : balances) {
            if (balance == null || balance.currency() == null || !balance.currency().matches("[A-Z]{3}")
                || !currencies.add(balance.currency()) || !validAmount(balance.totalBalance())
                || !validAmount(balance.grantedBalance()) || !validAmount(balance.toppedUpBalance())) {
                throw new Failure("MODEL_AGENT_RESPONSE_INVALID", false);
            }
        }
    }

    private static boolean validAmount(String value) {
        if (value == null || value.length() > 64) return false;
        try { return new BigDecimal(value).signum() >= 0; }
        catch (NumberFormatException error) { return false; }
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (type.isInstance(current)) return true;
        }
        return false;
    }

    public record Balance(String currency, String totalBalance, String grantedBalance, String toppedUpBalance) {}
    public record Reply(String requestId, String purpose, String provider, String model, String outcome,
                        String errorCode, Integer httpStatus, long elapsedMs, Object usage,
                        Boolean isAvailable, List<Balance> balances) {}

    public static final class Failure extends RuntimeException {
        public final String code;
        public final boolean retryable;
        public Failure(String code, boolean retryable) { super(code); this.code = code; this.retryable = retryable; }
    }

    private static final class TooLarge extends RuntimeException {}
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private int size;
        public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        public void onSubscribe(Flow.Subscription value) { subscription = value; delegate.onSubscribe(value); }
        public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                size += buffer.remaining();
                if (size > 65536) { subscription.cancel(); delegate.onError(new TooLarge()); return; }
            }
            delegate.onNext(buffers);
        }
        public void onError(Throwable error) { delegate.onError(error); }
        public void onComplete() { delegate.onComplete(); }
    }
}

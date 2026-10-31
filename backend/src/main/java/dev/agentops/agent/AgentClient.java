package dev.agentops.agent;

import dev.agentops.access.SecuritySettings;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class AgentClient {
    private static final Set<String> PROVIDER_OUTCOMES = Set.of("SUCCEEDED", "FAILED", "UNKNOWN");
    private final HttpClient http;
    private final URI origin;
    private final String token;
    private final JsonMapper json;
    private final Duration timeout;

    @org.springframework.beans.factory.annotation.Autowired
    public AgentClient(SecuritySettings settings, JsonMapper json,
                       @Value("${agentops.agent.origin:http://127.0.0.1:8090}") String origin) {
        this(settings, json, origin, Duration.ofSeconds(60));
    }

    AgentClient(SecuritySettings settings, JsonMapper json, String origin, Duration timeout) {
        this.timeout = timeout;
        this.token = settings.internalToken();
        this.json = json;
        this.origin = validatedOrigin(origin);
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .proxy(new java.net.ProxySelector() {
                public java.util.List<java.net.Proxy> select(URI uri) { return java.util.List.of(java.net.Proxy.NO_PROXY); }
                public void connectFailed(URI uri, java.net.SocketAddress address, java.io.IOException error) {}
            }).build();
    }

    public static URI validatedOrigin(String value) {
        URI origin = URI.create(value);
        String host = origin.getHost();
        boolean loopback = host != null && Set.of("127.0.0.1", "localhost", "::1", "[::1]")
            .contains(host.toLowerCase(Locale.ROOT));
        if (!Set.of("http", "https").contains(origin.getScheme()) || host == null || origin.getRawUserInfo() != null
            || origin.getRawQuery() != null || origin.getRawFragment() != null
            || !(origin.getPath().isEmpty() || origin.getPath().equals("/"))
            || ("http".equals(origin.getScheme()) && !loopback)) {
            throw new IllegalStateException("Invalid agent service origin");
        }
        return origin;
    }

    public record Usage(int promptTokens, int completionTokens, int totalTokens,
                        Integer cacheHitTokens, Integer cacheMissTokens) {}
    public record ProviderRequest(String requestId, String purpose, String provider, String model,
                                  String outcome, String errorCode, Integer httpStatus, int elapsedMs,
                                  Usage usage) {}
    public record Reply(String status, JsonNode result, ProviderRequest providerRequest) {}

    public static final class Failure extends RuntimeException {
        public final String code;
        public final boolean retryable;
        public final ProviderRequest providerRequest;
        public Failure(String code, boolean retryable) { this(code, retryable, null); }
        public Failure(String code, boolean retryable, ProviderRequest providerRequest) {
            super(code); this.code = code; this.retryable = retryable; this.providerRequest = providerRequest;
        }
    }

    public Reply reconcile(AgentOutbox.Task task) { return reconcile(task, (Supplier<String>) null); }
    public Reply reconcile(AgentOutbox.Task task, String credential) {
        return reconcile(task, credential == null ? null : () -> credential);
    }

    public Reply reconcile(AgentOutbox.Task task, Supplier<String> credential) {
        long deadline = System.nanoTime() + timeout.toNanos();
        boolean managed = "INVOKE".equals(task.phase()) && "deepseek-flash".equals(task.input().modelName());
        // Managed runs always read their durable checkpoint first. Duplicate delivery cannot issue a second paid request.
        if (managed || task.attempt() > 1) {
            Reply existing = exchange(task, false, deadline, null);
            if (existing != null && terminal(task, existing.status())) return existing;
        }
        return exchange(task, true, deadline, managed ? credential : null);
    }

    private boolean terminal(AgentOutbox.Task task, String status) {
        return switch (task.phase()) {
            case "RESUME" -> status.equals("APPROVED_FOR_MANUAL") || status.equals("REJECTED");
            case "CANCEL" -> status.equals("CANCELLED");
            default -> status.equals("COMPLETED") || status.equals("WAITING_HUMAN");
        };
    }

    private Reply exchange(AgentOutbox.Task task, boolean invoke, long deadline, Supplier<String> credentialSupplier) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new Failure("AGENT_UNAVAILABLE", true);
        String action = switch (task.phase()) { case "RESUME" -> "resume"; case "CANCEL" -> "cancel"; default -> "invoke"; };
        URI uri = origin.resolve("/internal/v1/runs/" + task.runId() + (invoke ? "/" + action : ""));
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofNanos(remaining))
            .header("Authorization", "Bearer " + token).header("X-Request-Id", task.requestId())
            .header("Content-Type", "application/json");
        String credential = null;
        if (invoke && "INVOKE".equals(task.phase()) && "deepseek-flash".equals(task.input().modelName())) {
            credential = credentialSupplier == null ? null : credentialSupplier.get();
            if (credential == null || credential.isBlank() || credential.length() > 512
                || credential.indexOf('\r') >= 0 || credential.indexOf('\n') >= 0) {
                throw new Failure("MODEL_CONFIGURATION_REQUIRED", false);
            }
            request.header("X-Dawnwatch-Model-Credential", credential);
        }
        if (invoke) {
            request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(
                task.phase().equals("INVOKE") ? task.input() : task.payload()), StandardCharsets.UTF_8));
        } else request.GET();
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            pending = http.sendAsync(request.build(), info -> new LimitedBody());
            credential = null;
            var response = pending.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (!invoke && response.statusCode() == 404) return null;
            JsonNode body = json.readTree(response.body());
            ProviderRequest provider = providerRequest(task, body.get("providerRequest"));
            if (response.statusCode() == 409 && "MODEL_OUTCOME_UNKNOWN".equals(body.path("code").asText())) {
                throw new Failure("AGENT_MODEL_OUTCOME_UNKNOWN", false, provider);
            }
            if (response.statusCode() != 200 && response.statusCode() != 202) {
                if (provider != null) throw providerFailure(provider);
                throw new Failure("AGENT_HTTP_" + response.statusCode(), response.statusCode() == 429 || response.statusCode() >= 500);
            }
            if (!task.runId().toString().equals(body.path("runId").asText())
                || !task.input().graphVersion().equals(body.path("graphVersion").asText())) {
                throw new Failure("AGENT_SCHEMA_INVALID", false);
            }
            String status = body.path("status").asText();
            if (!Set.of("RUNNING", "COMPLETED", "WAITING_HUMAN", "APPROVED_FOR_MANUAL", "REJECTED", "CANCELLED", "FAILED").contains(status)) {
                throw new Failure("AGENT_SCHEMA_INVALID", false);
            }
            if (provider != null && !"SUCCEEDED".equals(provider.outcome())) throw providerFailure(provider);
            if ("RESUME".equals(task.phase()) && terminal(task, status)
                && !("APPROVED".equals(task.payload().path("decision").asText()) ? "APPROVED_FOR_MANUAL" : "REJECTED").equals(status)) {
                throw new Failure("AGENT_SCHEMA_INVALID", false);
            }
            if (invoke && !status.equals("RUNNING") && !terminal(task, status)) throw new Failure("AGENT_SCHEMA_INVALID", false);
            return new Reply(status, body.path("result"), provider);
        } catch (Failure failure) {
            throw failure;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw new Failure("AGENT_INTERRUPTED", true);
        } catch (TimeoutException error) {
            throw new Failure("AGENT_UNAVAILABLE", true);
        } catch (ExecutionException error) {
            Throwable cause = error; while (cause.getCause() != null) cause = cause.getCause();
            throw new Failure(cause instanceof TooLarge ? "AGENT_SCHEMA_INVALID" : "AGENT_UNAVAILABLE", !(cause instanceof TooLarge));
        } catch (RuntimeException error) {
            throw new Failure("AGENT_SCHEMA_INVALID", false);
        } finally {
            credential = null;
            if (pending != null && !pending.isDone()) pending.cancel(true);
        }
    }

    private ProviderRequest providerRequest(AgentOutbox.Task task, JsonNode node) {
        if (node == null || node.isNull()) return null;
        try {
            String requestId = node.path("requestId").asText();
            String purpose = node.path("purpose").asText();
            String provider = node.path("provider").asText();
            String model = node.path("model").asText();
            String outcome = node.path("outcome").asText();
            String errorCode = node.path("errorCode").isNull() ? null : node.path("errorCode").asText();
            Integer httpStatus = node.path("httpStatus").isNull() ? null : node.path("httpStatus").intValue();
            long elapsed = node.path("elapsedMs").longValue();
            Usage usage = usage(node.get("usage"));
            boolean valid = task.requestId().equals(requestId) && "DIAGNOSIS".equals(purpose)
                && "deepseek".equals(provider) && "deepseek-flash".equals(model)
                && PROVIDER_OUTCOMES.contains(outcome) && elapsed >= 0 && elapsed <= Integer.MAX_VALUE
                && (httpStatus == null || (httpStatus >= 100 && httpStatus <= 599))
                && ("SUCCEEDED".equals(outcome) ? errorCode == null : errorCode != null && errorCode.matches("[A-Z0-9_]{1,80}"));
            if (!valid) throw new Failure("AGENT_SCHEMA_INVALID", false);
            return new ProviderRequest(requestId, purpose, provider, model, outcome, errorCode, httpStatus,
                (int) elapsed, usage);
        } catch (Failure failure) { throw failure; }
        catch (RuntimeException error) { throw new Failure("AGENT_SCHEMA_INVALID", false); }
    }

    private static Usage usage(JsonNode node) {
        if (node == null || node.isNull()) return null;
        int prompt = exactNonNegative(node, "promptTokens");
        int completion = exactNonNegative(node, "completionTokens");
        int total = exactNonNegative(node, "totalTokens");
        Integer hit = nullableNonNegative(node, "cacheHitTokens");
        Integer miss = nullableNonNegative(node, "cacheMissTokens");
        if (total != Math.addExact(prompt, completion) || (hit != null && hit > prompt) || (miss != null && miss > prompt)) {
            throw new Failure("AGENT_SCHEMA_INVALID", false);
        }
        return new Usage(prompt, completion, total, hit, miss);
    }

    private static int exactNonNegative(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) {
            throw new Failure("AGENT_SCHEMA_INVALID", false);
        }
        return value.intValue();
    }

    private static Integer nullableNonNegative(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) {
            throw new Failure("AGENT_SCHEMA_INVALID", false);
        }
        return value.intValue();
    }

    private static Failure providerFailure(ProviderRequest provider) {
        if ("UNKNOWN".equals(provider.outcome()) || "MODEL_OUTCOME_UNKNOWN".equals(provider.errorCode())) {
            return new Failure("AGENT_MODEL_OUTCOME_UNKNOWN", false, provider);
        }
        return new Failure(provider.errorCode(), false, provider);
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

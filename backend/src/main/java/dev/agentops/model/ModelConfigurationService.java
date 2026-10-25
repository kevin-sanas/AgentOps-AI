package dev.agentops.model;

import dev.agentops.access.Caller;
import dev.agentops.audit.AuditService;
import dev.agentops.shared.ApiError;
import dev.agentops.shared.PageResult;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ModelConfigurationService {
    private final ModelConfigurationStore configurations;
    private final ModelUsageStore usage;
    private final ModelProviderClient provider;
    private final AuditService audit;

    public ModelConfigurationService(ModelConfigurationStore configurations, ModelUsageStore usage,
                                     ModelProviderClient provider, AuditService audit) {
        this.configurations = configurations;
        this.usage = usage;
        this.provider = provider;
        this.audit = audit;
    }

    public ModelConfigurationStore.Status status() { return configurations.status(); }

    @Transactional
    public ModelConfigurationStore.Status save(Caller caller, String apiKey, long version, String requestId) {
        try {
            var saved = configurations.save(caller.userId(), apiKey, version);
            audit.record(caller.userId(), "MODEL_CONFIGURATION_SAVED", "MODEL_PROVIDER", null, requestId,
                Map.of("provider", saved.provider(), "model", saved.model(), "version", saved.version(), "keySuffix", saved.keySuffix()));
            return saved;
        } catch (IllegalStateException error) {
            throw configurationError(error);
        }
    }

    @Transactional
    public void delete(Caller caller, long version, String requestId) {
        try {
            configurations.delete(version);
            audit.record(caller.userId(), "MODEL_CONFIGURATION_DELETED", "MODEL_PROVIDER", null, requestId,
                Map.of("provider", ModelConfigurationStore.PROVIDER, "model", ModelConfigurationStore.MODEL, "version", version));
        } catch (IllegalStateException error) {
            throw configurationError(error);
        }
    }

    public ModelProviderClient.Reply test(Caller caller, String candidate, String requestId) {
        boolean candidateProvided = candidate != null;
        var saved = candidateProvided ? null : credential();
        String apiKey = candidateProvided ? candidate : saved.apiKey();
        UUIDHolder request = begin("CONNECTION_TEST", caller, requestId);
        try {
            var reply = provider.test(apiKey, requestId);
            complete(request, reply);
            if (!candidateProvided) configurations.recordTest(saved.version(), reply.outcome(), reply.errorCode());
            audit.record(caller.userId(), "MODEL_CONNECTION_TESTED", "MODEL_PROVIDER", null, requestId,
                metadata(reply, candidateProvided));
            if (!"SUCCEEDED".equals(reply.outcome())) throw providerError(reply.errorCode());
            return reply;
        } catch (ModelProviderClient.Failure failure) {
            completeFailure(request, failure.code);
            throw providerError(failure.code);
        } finally {
            apiKey = null;
        }
    }

    public ModelProviderClient.Reply balance(Caller caller, String requestId) {
        var saved = credential();
        String apiKey = saved.apiKey();
        UUIDHolder request = begin("BALANCE_QUERY", caller, requestId);
        try {
            var reply = provider.balance(apiKey, requestId);
            complete(request, reply);
            audit.record(caller.userId(), "MODEL_BALANCE_QUERIED", "MODEL_PROVIDER", null, requestId,
                Map.of("provider", reply.provider(), "model", reply.model(), "outcome", reply.outcome()));
            if (!"SUCCEEDED".equals(reply.outcome())) throw providerError(reply.errorCode());
            return reply;
        } catch (ModelProviderClient.Failure failure) {
            completeFailure(request, failure.code);
            throw providerError(failure.code);
        } finally {
            apiKey = null;
        }
    }

    public ModelUsageStore.Summary summary(Instant from, Instant to) {
        var range = range(from, to);
        return usage.summary(range.from(), range.to());
    }

    public PageResult<ModelUsageStore.Request> requests(String purpose, Instant from, Instant to, int page, int size) {
        if (purpose != null && !java.util.Set.of("DIAGNOSIS", "CONNECTION_TEST", "BALANCE_QUERY").contains(purpose)) {
            throw ApiError.badRequest("MODEL_USAGE_PURPOSE_INVALID");
        }
        var range = range(from, to);
        try { return usage.list(purpose, range.from(), range.to(), page, size); }
        catch (IllegalArgumentException | ArithmeticException error) { throw ApiError.badRequest("MODEL_USAGE_RANGE_INVALID"); }
    }

    private ModelConfigurationStore.Credential credential() {
        try { return configurations.credential().orElseThrow(() -> new ApiError(409, "MODEL_CONFIGURATION_REQUIRED")); }
        catch (IllegalStateException error) { throw configurationError(error); }
    }

    private UUIDHolder begin(String purpose, Caller caller, String requestId) {
        return new UUIDHolder(usage.begin(new ModelUsageStore.Start(purpose, ModelConfigurationStore.PROVIDER,
            ModelConfigurationStore.MODEL, null, null, caller.userId(), requestId)));
    }

    private void complete(UUIDHolder request, ModelProviderClient.Reply reply) {
        usage.complete(request.value(), new ModelUsageStore.Completion(reply.outcome(), reply.errorCode(),
            reply.httpStatus(), Math.toIntExact(reply.elapsedMs()), null));
        request.completed = true;
    }

    private void completeFailure(UUIDHolder request, String code) {
        if (!request.completed) usage.complete(request.value(), new ModelUsageStore.Completion("FAILED", code, null, 0, null));
        request.completed = true;
    }

    private static Map<String,Object> metadata(ModelProviderClient.Reply reply, boolean candidate) {
        var values = new java.util.LinkedHashMap<String,Object>();
        values.put("provider", reply.provider()); values.put("model", reply.model());
        values.put("outcome", reply.outcome()); values.put("candidate", candidate);
        if (reply.errorCode() != null) values.put("errorCode", reply.errorCode());
        return values;
    }

    private static Range range(Instant from, Instant to) {
        Instant end = to == null ? Instant.now() : to;
        Instant start = from == null ? end.minus(Duration.ofHours(24)) : from;
        if (!start.isBefore(end) || Duration.between(start, end).compareTo(Duration.ofDays(31)) > 0) {
            throw ApiError.badRequest("MODEL_USAGE_RANGE_INVALID");
        }
        return new Range(start, end);
    }

    private static ApiError configurationError(IllegalStateException error) {
        if ("MODEL_CONFIGURATION_VERSION_CONFLICT".equals(error.getMessage())) return new ApiError(409, error.getMessage());
        if ("MODEL_CREDENTIAL_DECRYPTION_FAILED".equals(error.getMessage())) return new ApiError(500, error.getMessage());
        return new ApiError(500, "MODEL_CONFIGURATION_UNAVAILABLE");
    }

    private static ApiError providerError(String code) {
        String normalized = code == null ? "MODEL_RESPONSE_INVALID" : code;
        return switch (normalized) {
            case "MODEL_AUTHENTICATION_FAILED", "MODEL_REQUEST_REJECTED", "MODEL_CREDENTIAL_INVALID" -> new ApiError(400, normalized);
            case "MODEL_RATE_LIMITED" -> new ApiError(429, normalized);
            case "MODEL_TIMEOUT", "MODEL_AGENT_TIMEOUT" -> new ApiError(504, "MODEL_TIMEOUT");
            case "MODEL_TLS_VERIFY_FAILED" -> new ApiError(502, normalized);
            case "MODEL_RESPONSE_INVALID", "MODEL_RESPONSE_TOO_LARGE", "MODEL_REQUIRED_MODEL_UNAVAILABLE",
                 "MODEL_AGENT_RESPONSE_INVALID" -> new ApiError(502, normalized);
            default -> new ApiError(503, "MODEL_PROVIDER_UNAVAILABLE");
        };
    }

    private record Range(Instant from, Instant to) {}
    private static final class UUIDHolder {
        private final java.util.UUID value;
        private boolean completed;
        UUIDHolder(java.util.UUID value) { this.value = value; }
        java.util.UUID value() { return value; }
    }
}

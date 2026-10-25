package dev.agentops.model;

import dev.agentops.shared.PageResult;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ModelUsageStore {
    private static final Duration MAX_RANGE = Duration.ofDays(31);
    private final JdbcTemplate db;

    public ModelUsageStore(JdbcTemplate db) { this.db = db; }

    public UUID begin(Start start) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO model_provider_request(id,request_id,purpose,provider,model,agent_run_id,incident_id,actor_id) VALUES(?,?,?,?,?,?,?,?)",
            id, start.requestId(), start.purpose(), start.provider(), start.model(), start.agentRunId(), start.incidentId(), start.actorId());
        return id;
    }

    public Request complete(UUID id, Completion completion) {
        return finish(id, completion, false);
    }

    @Transactional
    public Request recordDiagnosis(Start start, Completion completion) {
        if (!"DIAGNOSIS".equals(start.purpose()) || start.agentRunId() == null || start.incidentId() == null
            || start.actorId() == null) throw new IllegalArgumentException("MODEL_DIAGNOSIS_SOURCE_REQUIRED");
        UUID id = UUID.randomUUID();
        db.update("""
            INSERT INTO model_provider_request(id,request_id,purpose,provider,model,agent_run_id,incident_id,actor_id)
            VALUES(?,?,?,?,?,?,?,?)
            ON CONFLICT (agent_run_id) WHERE purpose='DIAGNOSIS' AND agent_run_id IS NOT NULL DO NOTHING
            """, id, start.requestId(), start.purpose(), start.provider(), start.model(), start.agentRunId(),
            start.incidentId(), start.actorId());
        Request current = byDiagnosisRun(start.agentRunId()).orElseThrow(() -> new IllegalStateException("MODEL_REQUEST_NOT_FOUND"));
        if (!Set.of("STARTED", "UNKNOWN").contains(current.outcome())) {
            if (same(current, completion)) return current;
            throw new IllegalStateException("MODEL_REQUEST_RECONCILIATION_CONFLICT");
        }
        return finish(current.id(), completion, true);
    }

    public Optional<Request> byDiagnosisRun(UUID runId) {
        return db.query("SELECT * FROM model_provider_request WHERE purpose='DIAGNOSIS' AND agent_run_id=?", this::map, runId)
            .stream().findFirst();
    }

    private Request finish(UUID id, Completion completion, boolean allowUnknown) {
        Usage tokens = completion.usage();
        int changed = db.update("""
            UPDATE model_provider_request SET outcome=?,error_code=?,http_status=?,elapsed_ms=?,
              prompt_tokens=?,completion_tokens=?,total_tokens=?,cache_hit_tokens=?,cache_miss_tokens=?,completed_at=now()
            WHERE id=? AND (outcome='STARTED' OR (? AND outcome='UNKNOWN'))
            """, completion.outcome(), completion.errorCode(), completion.httpStatus(), completion.elapsedMs(),
            tokens == null ? null : tokens.promptTokens(), tokens == null ? null : tokens.completionTokens(),
            tokens == null ? null : tokens.totalTokens(), tokens == null ? null : tokens.cacheHitTokens(),
            tokens == null ? null : tokens.cacheMissTokens(), id, allowUnknown);
        if (changed != 1) throw new IllegalStateException("MODEL_REQUEST_NOT_STARTABLE");
        return get(id);
    }

    private static boolean same(Request current, Completion completion) {
        Usage usage = completion.usage();
        return java.util.Objects.equals(current.outcome(), completion.outcome())
            && java.util.Objects.equals(current.errorCode(), completion.errorCode())
            && java.util.Objects.equals(current.httpStatus(), completion.httpStatus())
            && java.util.Objects.equals(current.elapsedMs(), completion.elapsedMs())
            && java.util.Objects.equals(current.promptTokens(), usage == null ? null : usage.promptTokens())
            && java.util.Objects.equals(current.completionTokens(), usage == null ? null : usage.completionTokens())
            && java.util.Objects.equals(current.totalTokens(), usage == null ? null : usage.totalTokens())
            && java.util.Objects.equals(current.cacheHitTokens(), usage == null ? null : usage.cacheHitTokens())
            && java.util.Objects.equals(current.cacheMissTokens(), usage == null ? null : usage.cacheMissTokens());
    }

    public Request get(UUID id) {
        var rows = db.query("SELECT * FROM model_provider_request WHERE id=?", this::map, id);
        if (rows.isEmpty()) throw new IllegalStateException("MODEL_REQUEST_NOT_FOUND");
        return rows.getFirst();
    }

    public Summary summary(Instant from, Instant to) {
        return summary(null, from, to);
    }

    public Summary summary(String purpose, Instant from, Instant to) {
        validateRange(from, to);
        return db.queryForObject("""
            SELECT count(*) requests,
              count(*) FILTER (WHERE outcome='SUCCEEDED') succeeded,
              count(*) FILTER (WHERE outcome='FAILED') failed,
              count(*) FILTER (WHERE outcome='UNKNOWN') unknown,
              count(total_tokens) requests_with_usage,
              coalesce(sum(prompt_tokens),0) prompt_tokens,
              coalesce(sum(completion_tokens),0) completion_tokens,
              coalesce(sum(total_tokens),0) total_tokens
            FROM model_provider_request
            WHERE started_at>=? AND started_at<? AND (?::varchar IS NULL OR purpose=?)
            """, (row, n) -> new Summary(row.getLong("requests"), row.getLong("succeeded"), row.getLong("failed"),
            row.getLong("unknown"), row.getLong("requests_with_usage"), row.getLong("prompt_tokens"),
            row.getLong("completion_tokens"), row.getLong("total_tokens")), Timestamp.from(from), Timestamp.from(to), purpose, purpose);
    }

    public PageResult<Request> list(String purpose, Instant from, Instant to, int page, int size) {
        validateRange(from, to);
        int offset = PageResult.offset(page, size);
        String where = " WHERE started_at>=? AND started_at<? AND (?::varchar IS NULL OR purpose=?)";
        var items = db.query("SELECT * FROM model_provider_request" + where + " ORDER BY started_at DESC,id LIMIT ? OFFSET ?",
            this::map, Timestamp.from(from), Timestamp.from(to), purpose, purpose, size, offset);
        Long total = db.queryForObject("SELECT count(*) FROM model_provider_request" + where, Long.class,
            Timestamp.from(from), Timestamp.from(to), purpose, purpose);
        return new PageResult<>(items, total == null ? 0 : total, page, size);
    }

    public List<TrendBucket> trend(String purpose, Instant from, Instant to) {
        validateRange(from, to);
        long rangeSeconds = Duration.between(from, to).getSeconds();
        int bucketSeconds = rangeSeconds <= Duration.ofHours(48).getSeconds() ? 3600
            : rangeSeconds <= Duration.ofDays(14).getSeconds() ? 21600 : 86400;
        int bucketCount = Math.toIntExact((rangeSeconds + bucketSeconds - 1) / bucketSeconds);
        var grouped = new HashMap<Integer, Aggregate>();
        db.query("""
            SELECT floor(extract(epoch FROM (started_at - ?::timestamptz)) / ?)::integer bucket_index,
              count(*) requests, count(total_tokens) requests_with_usage,
              coalesce(sum(prompt_tokens),0) prompt_tokens,
              coalesce(sum(completion_tokens),0) completion_tokens,
              coalesce(sum(total_tokens),0) total_tokens
            FROM model_provider_request
            WHERE started_at>=? AND started_at<? AND (?::varchar IS NULL OR purpose=?)
            GROUP BY bucket_index ORDER BY bucket_index
            """, row -> {
                var aggregate = new Aggregate(row.getLong("requests"), row.getLong("requests_with_usage"),
                    row.getLong("prompt_tokens"), row.getLong("completion_tokens"), row.getLong("total_tokens"));
                grouped.put(row.getInt("bucket_index"), aggregate);
            }, Timestamp.from(from), bucketSeconds, Timestamp.from(from), Timestamp.from(to), purpose, purpose);
        var result = new ArrayList<TrendBucket>(bucketCount);
        for (int index = 0; index < bucketCount; index++) {
            Instant start = from.plusSeconds((long) index * bucketSeconds);
            Instant end = start.plusSeconds(bucketSeconds);
            if (end.isAfter(to)) end = to;
            Aggregate value = grouped.getOrDefault(index, Aggregate.EMPTY);
            boolean unknown = value.requests() > 0 && value.requestsWithUsage() == 0;
            result.add(new TrendBucket(start, end, value.requests(), value.requestsWithUsage(),
                unknown ? null : value.promptTokens(), unknown ? null : value.completionTokens(),
                unknown ? null : value.totalTokens()));
        }
        return List.copyOf(result);
    }

    private static void validateRange(Instant from, Instant to) {
        if (from == null || to == null || !from.isBefore(to) || Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
            throw new IllegalArgumentException("MODEL_USAGE_RANGE_INVALID");
        }
    }

    private Request map(ResultSet row, int number) throws SQLException {
        return new Request(row.getObject("id", UUID.class), row.getString("request_id"), row.getString("purpose"),
            row.getString("provider"), row.getString("model"), row.getObject("agent_run_id", UUID.class),
            row.getObject("incident_id", UUID.class), row.getObject("actor_id", UUID.class), row.getString("outcome"),
            row.getString("error_code"), (Integer) row.getObject("http_status"), (Integer) row.getObject("elapsed_ms"),
            (Integer) row.getObject("prompt_tokens"), (Integer) row.getObject("completion_tokens"),
            (Integer) row.getObject("total_tokens"), (Integer) row.getObject("cache_hit_tokens"),
            (Integer) row.getObject("cache_miss_tokens"), row.getTimestamp("started_at").toInstant(), instant(row, "completed_at"));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        var value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    public record Start(String purpose, String provider, String model, UUID agentRunId, UUID incidentId,
                        UUID actorId, String requestId) {}
    public record Usage(int promptTokens, int completionTokens, int totalTokens, Integer cacheHitTokens,
                        Integer cacheMissTokens) {}
    public record Completion(String outcome, String errorCode, Integer httpStatus, int elapsedMs, Usage usage) {}
    public record Summary(long requests, long succeeded, long failed, long unknown, long requestsWithUsage,
                          long promptTokens, long completionTokens, long totalTokens) {}
    public record TrendBucket(Instant startedAt, Instant endedAt, long requests, long requestsWithUsage,
                              Long promptTokens, Long completionTokens, Long totalTokens) {}
    public record Request(UUID id, String requestId, String purpose, String provider, String model,
                          UUID agentRunId, UUID incidentId, UUID actorId, String outcome, String errorCode,
                          Integer httpStatus, Integer elapsedMs, Integer promptTokens, Integer completionTokens,
                          Integer totalTokens, Integer cacheHitTokens, Integer cacheMissTokens,
                          Instant startedAt, Instant completedAt) {}
    private record Aggregate(long requests, long requestsWithUsage, long promptTokens,
                             long completionTokens, long totalTokens) {
        private static final Aggregate EMPTY = new Aggregate(0, 0, 0, 0, 0);
    }
}

package dev.agentops.model;

import dev.agentops.shared.PageResult;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

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
        Usage tokens = completion.usage();
        int changed = db.update("""
            UPDATE model_provider_request SET outcome=?,error_code=?,http_status=?,elapsed_ms=?,
              prompt_tokens=?,completion_tokens=?,total_tokens=?,cache_hit_tokens=?,cache_miss_tokens=?,completed_at=now()
            WHERE id=? AND outcome='STARTED'
            """, completion.outcome(), completion.errorCode(), completion.httpStatus(), completion.elapsedMs(),
            tokens == null ? null : tokens.promptTokens(), tokens == null ? null : tokens.completionTokens(),
            tokens == null ? null : tokens.totalTokens(), tokens == null ? null : tokens.cacheHitTokens(),
            tokens == null ? null : tokens.cacheMissTokens(), id);
        if (changed != 1) throw new IllegalStateException("MODEL_REQUEST_NOT_STARTABLE");
        return get(id);
    }

    public Request get(UUID id) {
        var rows = db.query("SELECT * FROM model_provider_request WHERE id=?", this::map, id);
        if (rows.isEmpty()) throw new IllegalStateException("MODEL_REQUEST_NOT_FOUND");
        return rows.getFirst();
    }

    public Summary summary(Instant from, Instant to) {
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
            FROM model_provider_request WHERE started_at>=? AND started_at<?
            """, (row, n) -> new Summary(row.getLong("requests"), row.getLong("succeeded"), row.getLong("failed"),
            row.getLong("unknown"), row.getLong("requests_with_usage"), row.getLong("prompt_tokens"),
            row.getLong("completion_tokens"), row.getLong("total_tokens")), Timestamp.from(from), Timestamp.from(to));
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
    public record Request(UUID id, String requestId, String purpose, String provider, String model,
                          UUID agentRunId, UUID incidentId, UUID actorId, String outcome, String errorCode,
                          Integer httpStatus, Integer elapsedMs, Integer promptTokens, Integer completionTokens,
                          Integer totalTokens, Integer cacheHitTokens, Integer cacheMissTokens,
                          Instant startedAt, Instant completedAt) {}
}

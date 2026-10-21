package dev.agentops.monitoring;

import dev.agentops.access.Caller;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class OverviewController {
    private final JdbcTemplate db;
    public OverviewController(JdbcTemplate db){this.db=db;}
    public record TargetHealth(UUID id,String code,String name,String environment,String health,int activeChecks,Instant lastCheckedAt){}
    public record Overview(long totalTargets,long up,long down,long unknown,long activeIncidents,long activeChecks,Instant generatedAt,List<TargetHealth> targets){}
    private static final String BASE="""
        WITH visible AS (
          SELECT t.* FROM service_target t WHERE ? OR EXISTS(SELECT 1 FROM target_access a WHERE a.target_id=t.id AND a.user_id=? AND a.can_read)
        ), health AS (
          SELECT v.id,v.code,v.name,v.environment,count(d.id) active_checks,max(r.completed_at) last_checked_at,
            CASE WHEN NOT v.active THEN 'UNKNOWN'
              WHEN bool_or(r.outcome='DOWN' AND r.completed_at>=now()-make_interval(secs=>d.interval_seconds*2)) THEN 'DOWN'
              WHEN count(d.id)>0 AND bool_and(COALESCE(r.outcome='UP' AND r.completed_at>=now()-make_interval(secs=>d.interval_seconds*2),false)) THEN 'UP'
              ELSE 'UNKNOWN' END health
          FROM visible v LEFT JOIN check_definition d ON d.target_id=v.id AND d.state='ACTIVE' AND v.active
          LEFT JOIN LATERAL (SELECT outcome,completed_at FROM check_run WHERE check_definition_id=d.id AND config_version=d.config_version AND target_config_version=v.config_version ORDER BY completed_at DESC,id DESC LIMIT 1) r ON true
          GROUP BY v.id,v.code,v.name,v.environment,v.active
        )
        """;
    @GetMapping("/api/v1/overview") @PreAuthorize("hasAnyRole('OPS_ADMIN','ON_CALL','OBSERVER','AUDITOR')")
    public Overview get(@AuthenticationPrincipal Caller caller){
        var totals=db.queryForMap(BASE+"SELECT count(*) total,count(*) FILTER(WHERE health='UP') up,count(*) FILTER(WHERE health='DOWN') down,count(*) FILTER(WHERE health='UNKNOWN') unknown,COALESCE(sum(active_checks),0) checks,(SELECT count(*) FROM incident WHERE target_id IN(SELECT id FROM visible) AND status IN ('OPEN','ACKNOWLEDGED','INVESTIGATING','MONITORING')) incidents FROM health",caller.isAdmin(),caller.userId());
        var rows=db.query(BASE+"SELECT * FROM health ORDER BY CASE health WHEN 'DOWN' THEN 0 WHEN 'UNKNOWN' THEN 1 ELSE 2 END,name,id LIMIT 20",(r,n)->{
            Timestamp checked=r.getTimestamp("last_checked_at");return new TargetHealth(r.getObject("id",UUID.class),r.getString("code"),r.getString("name"),r.getString("environment"),r.getString("health"),r.getInt("active_checks"),checked==null?null:checked.toInstant());
        },caller.isAdmin(),caller.userId());
        return new Overview(number(totals,"total"),number(totals,"up"),number(totals,"down"),number(totals,"unknown"),number(totals,"incidents"),number(totals,"checks"),Instant.now(),rows);
    }
    private long number(Map<String,Object> values,String key){return ((Number)values.get(key)).longValue();}
}

package dev.agentops.monitoring;

import java.time.Instant;
import java.util.*;
import java.sql.Timestamp;
import java.net.URI;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

@Service
public class MonitoringStore {
    private final JdbcTemplate db;private final TargetGuard guard;private final IncidentAggregator aggregator;
    public MonitoringStore(JdbcTemplate db,TargetGuard guard,IncidentAggregator aggregator){this.db=db;this.guard=guard;this.aggregator=aggregator;}
    public record Task(UUID targetId,UUID checkId,UUID executionId,UUID leaseToken,int slotId,Instant scheduledAt,int targetConfigVersion,String baseUrl,boolean allowHttp,List<String> privateCidrs,CheckService.Definition definition){}
    @Transactional public List<Task> claim(int limit){
        if(limit<1)return List.of();limit=Math.min(limit,4);List<Task> tasks=new ArrayList<>();
        var candidates=db.query("SELECT d.target_id FROM check_definition d JOIN service_target t ON t.id=d.target_id WHERE t.active AND d.state='ACTIVE' AND d.next_run_at<=now() AND (d.lease_until IS NULL OR d.lease_until<=now()) GROUP BY d.target_id ORDER BY min(d.next_run_at),d.target_id LIMIT 64",(r,n)->r.getObject(1,UUID.class));
        for(UUID target:candidates){
            if(tasks.size()>=limit)break;
            var targetRows=db.queryForList("SELECT * FROM service_target WHERE id=? AND active AND (last_probe_started_at IS NULL OR last_probe_started_at<=now()-interval '60 seconds') FOR UPDATE SKIP LOCKED",target);
            if(targetRows.isEmpty())continue;var t=targetRows.getFirst();
            var definitions=db.query("SELECT * FROM check_definition WHERE target_id=? AND state='ACTIVE' AND next_run_at<=now() AND (lease_until IS NULL OR lease_until<=now()) ORDER BY next_run_at,id LIMIT 1 FOR UPDATE SKIP LOCKED",CheckService::map,target);
            if(definitions.isEmpty())continue;var definition=definitions.getFirst();
            String baseUrl=(String)t.get("base_url"),host=URI.create(baseUrl).getHost();
            db.update("INSERT INTO probe_host_lease(host) VALUES(?) ON CONFLICT DO NOTHING",host);
            if(db.queryForList("SELECT host FROM probe_host_lease WHERE host=? AND (lease_token IS NULL OR lease_until<=now()) FOR UPDATE SKIP LOCKED",host).isEmpty())continue;
            var slots=db.queryForList("SELECT id FROM probe_slot WHERE lease_token IS NULL OR lease_until<=now() ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED");
            if(slots.isEmpty())break;int slot=((Number)slots.getFirst().get("id")).intValue();
            var budget=db.queryForMap("SELECT claimed,window_started<=now()-interval '1 second' AS reset FROM probe_budget WHERE id=true FOR UPDATE");
            boolean reset=(Boolean)budget.get("reset");if(!reset&&((Number)budget.get("claimed")).intValue()>=4)break;
            UUID token=UUID.randomUUID();var previous=db.queryForMap("SELECT execution_id,scheduled_at FROM check_definition WHERE id=?",definition.id());
            UUID execution=previous.get("execution_id")==null?UUID.randomUUID():(UUID)previous.get("execution_id");
            Instant scheduled=previous.get("scheduled_at")==null?db.queryForObject("SELECT now()",Timestamp.class).toInstant():((Timestamp)previous.get("scheduled_at")).toInstant();
            db.update("UPDATE probe_host_lease SET lease_token=?,lease_until=now()+interval '60 seconds' WHERE host=?",token,host);
            db.update("UPDATE probe_slot SET lease_token=?,lease_until=now()+interval '60 seconds' WHERE id=?",token,slot);
            db.update("UPDATE probe_budget SET claimed=CASE WHEN ? THEN 1 ELSE claimed+1 END,window_started=CASE WHEN ? THEN now() ELSE window_started END WHERE id=true",reset,reset);
            db.update("UPDATE service_target SET last_probe_started_at=now() WHERE id=?",target);
            db.update("UPDATE check_definition SET execution_id=?,scheduled_at=?,lease_token=?,lease_until=now()+interval '60 seconds',last_started_at=now() WHERE id=?",execution,Timestamp.from(scheduled),token,definition.id());
            var cidrs=db.query("SELECT unnest(allowed_private_cidrs)::text FROM target_network_policy WHERE target_id=? AND active",(r,n)->r.getString(1),target);
            tasks.add(new Task(target,definition.id(),execution,token,slot,scheduled,((Number)t.get("config_version")).intValue(),baseUrl,(Boolean)t.get("allow_http"),List.copyOf(cidrs),definition));
        }
        return List.copyOf(tasks);
    }
    @Transactional public boolean complete(Task task,Instant startedAt,Instant completedAt,ProbeResult result){
        guard.lock(task.targetId());
        var valid=db.queryForObject("SELECT EXISTS(SELECT 1 FROM check_definition d JOIN service_target t ON t.id=d.target_id WHERE d.id=? AND d.target_id=? AND d.lease_token=? AND d.execution_id=? AND d.lease_until>clock_timestamp() AND d.config_version=? AND t.config_version=? AND d.state='ACTIVE' AND t.active)",Boolean.class,task.checkId(),task.targetId(),task.leaseToken(),task.executionId(),task.definition().configVersion(),task.targetConfigVersion());
        if(!Boolean.TRUE.equals(valid)){LeaseSupport.release(db,task.leaseToken());return false;}
        if(completedAt.isBefore(startedAt))throw new IllegalArgumentException("Invalid observation time");
        db.update("INSERT INTO check_run(id,check_definition_id,config_version,target_config_version,started_at,completed_at,scheduled_at,outcome,status_code,latency_ms,error_code,resolved_ips,redirect_decision) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",task.executionId(),task.checkId(),task.definition().configVersion(),task.targetConfigVersion(),Timestamp.from(startedAt),Timestamp.from(completedAt),Timestamp.from(task.scheduledAt()),result.outcome(),result.statusCode(),result.latencyMs(),result.errorCode(),result.resolvedIps().toArray(String[]::new),result.redirectDecision());
        db.update("UPDATE check_definition SET failure_count=CASE WHEN ?='DOWN' THEN LEAST(failure_count+1,20) ELSE 0 END,success_count=CASE WHEN ?='UP' THEN LEAST(success_count+1,20) ELSE 0 END,lease_token=NULL,lease_until=NULL,execution_id=NULL,scheduled_at=NULL,next_run_at=GREATEST(now(),last_started_at+make_interval(secs=>interval_seconds)) WHERE id=?",result.outcome(),result.outcome(),task.checkId());
        LeaseSupport.release(db,task.leaseToken());aggregator.observe(task,result,completedAt);return true;
    }
}

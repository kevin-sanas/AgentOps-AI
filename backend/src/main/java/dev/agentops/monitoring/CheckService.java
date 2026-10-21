package dev.agentops.monitoring;

import dev.agentops.access.Caller;
import dev.agentops.audit.AuditService;
import dev.agentops.shared.*;
import dev.agentops.targets.TargetService;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CheckService {
    private final JdbcTemplate db;private final TargetService targets;private final TargetGuard guard;private final Idempotency idem;private final AuditService audit;private final IncidentAggregator aggregator;
    public CheckService(JdbcTemplate db,TargetService targets,TargetGuard guard,Idempotency idem,AuditService audit,IncidentAggregator aggregator){this.db=db;this.targets=targets;this.guard=guard;this.idem=idem;this.audit=audit;this.aggregator=aggregator;}
    public record Definition(UUID id,UUID targetId,String method,int intervalSeconds,int timeoutMs,List<Integer> expectedStatuses,int failThreshold,int recoverThreshold,String state,int configVersion,Instant nextRunAt,int failureCount,int successCount){}
    public record Run(UUID id,UUID checkDefinitionId,int configVersion,int targetConfigVersion,Instant startedAt,Instant completedAt,String outcome,Integer statusCode,Integer latencyMs,String errorCode,List<String> resolvedIps,String redirectDecision){}
    static Definition map(ResultSet r,int n)throws SQLException{return new Definition(r.getObject("id",UUID.class),r.getObject("target_id",UUID.class),r.getString("method"),r.getInt("interval_seconds"),r.getInt("timeout_ms"),Arrays.asList((Integer[])r.getArray("expected_statuses").getArray()),r.getInt("fail_threshold"),r.getInt("recover_threshold"),r.getString("state"),r.getInt("config_version"),r.getTimestamp("next_run_at").toInstant(),r.getInt("failure_count"),r.getInt("success_count"));}
    public PageResult<Definition> list(Caller caller,UUID target,int page,int size){
        targets.get(caller,target);int offset=PageResult.offset(page,size);
        return new PageResult<>(db.query("SELECT * FROM check_definition WHERE target_id=? ORDER BY created_at,id LIMIT ? OFFSET ?",CheckService::map,target,size,offset),db.queryForObject("SELECT count(*) FROM check_definition WHERE target_id=?",Long.class,target),page,size);
    }
    public Definition get(UUID target,UUID id){var rows=db.query("SELECT * FROM check_definition WHERE target_id=? AND id=?",CheckService::map,target,id);if(rows.isEmpty())throw ApiError.notFound();return rows.getFirst();}
    @Transactional public Definition create(Caller caller,UUID target,CheckController.Config c,String key,String requestId){
        targets.get(caller,target);guard.lock(target);
        UUID id=idem.create(caller.userId(),"CREATE_CHECK:"+target,key,c,()->{
            guard.noActiveIncident(target);UUID created=UUID.randomUUID();
            db.update("INSERT INTO check_definition(id,target_id,method,interval_seconds,timeout_ms,expected_statuses,fail_threshold,recover_threshold) VALUES(?,?,?,?,?,?,?,?)",created,target,c.method(),c.intervalSeconds(),c.timeoutMs(),c.expectedStatuses().toArray(Integer[]::new),c.failThreshold(),c.recoverThreshold());
            audit.record(caller.userId(),"CHECK_CREATED","CHECK_DEFINITION",created,requestId,Map.of("targetId",target));return created;
        });return get(target,id);
    }
    @Transactional public Definition update(Caller caller,UUID target,UUID id,CheckController.Update body,String requestId){
        targets.get(caller,target);guard.lock(target);guard.noActiveIncident(target);Definition old=get(target,id);version(old,body.configVersion());
        var c=body.config();
        db.update("UPDATE check_definition SET method=?,interval_seconds=?,timeout_ms=?,expected_statuses=?,fail_threshold=?,recover_threshold=?,config_version=config_version+1,failure_count=0,success_count=0,lease_token=NULL,lease_until=NULL,execution_id=NULL,scheduled_at=NULL,next_run_at=GREATEST(now(),COALESCE(last_started_at+make_interval(secs=>?),now())) WHERE id=?",c.method(),c.intervalSeconds(),c.timeoutMs(),c.expectedStatuses().toArray(Integer[]::new),c.failThreshold(),c.recoverThreshold(),c.intervalSeconds(),id);
        audit.record(caller.userId(),"CHECK_CONFIG_CHANGED","CHECK_DEFINITION",id,requestId,Map.of("version",body.configVersion()+1,"reason",body.reason()));return get(target,id);
    }
    @Transactional public Definition state(Caller caller,UUID target,UUID id,CheckController.State body,String requestId){
        targets.get(caller,target);guard.lock(target);Definition old=get(target,id);version(old,body.configVersion());
        boolean valid=switch(old.state()){case "DISABLED"->body.state().equals("ACTIVE");case "ACTIVE"->Set.of("PAUSED","DISABLED").contains(body.state());case "PAUSED"->Set.of("ACTIVE","DISABLED").contains(body.state());default->false;};
        if(!valid)throw new ApiError(409,"INVALID_CHECK_TRANSITION");
        if(body.state().equals("ACTIVE")&&!db.queryForObject("SELECT EXISTS(SELECT 1 FROM incident_check_snapshot s JOIN incident i ON i.id=s.incident_id WHERE i.target_id=? AND s.check_definition_id=? AND i.status IN ('OPEN','ACKNOWLEDGED','INVESTIGATING','MONITORING'))",Boolean.class,target,id))guard.noActiveIncident(target);
        db.update("UPDATE check_definition SET state=?,config_version=config_version+1,failure_count=0,success_count=0,lease_token=NULL,lease_until=NULL,execution_id=NULL,scheduled_at=NULL,next_run_at=GREATEST(now(),COALESCE(last_started_at+make_interval(secs=>interval_seconds),now())) WHERE id=?",body.state(),id);
        if(!body.state().equals("ACTIVE"))aggregator.suspend(target,id,caller.userId(),body.reason(),requestId);
        audit.record(caller.userId(),"CHECK_STATE_CHANGED","CHECK_DEFINITION",id,requestId,Map.of("state",body.state(),"reason",body.reason()));return get(target,id);
    }
    static void version(Definition d,int version){if(d.configVersion()!=version)throw new ApiError(409,"CHECK_VERSION_CONFLICT");}
    public PageResult<Run> history(Caller caller,UUID target,UUID check,int page,int size){
        targets.get(caller,target);int offset=PageResult.offset(page,size);if(check!=null)get(target,check);
        String where=" FROM check_run r JOIN check_definition d ON d.id=r.check_definition_id WHERE d.target_id=? AND (?::uuid IS NULL OR d.id=?)";
        var rows=db.query("SELECT r.*"+where+" ORDER BY r.completed_at DESC,r.id LIMIT ? OFFSET ?",(r,n)->new Run(r.getObject("id",UUID.class),r.getObject("check_definition_id",UUID.class),r.getInt("config_version"),r.getInt("target_config_version"),r.getTimestamp("started_at").toInstant(),r.getTimestamp("completed_at").toInstant(),r.getString("outcome"),(Integer)r.getObject("status_code"),(Integer)r.getObject("latency_ms"),r.getString("error_code"),Arrays.asList((String[])r.getArray("resolved_ips").getArray()),r.getString("redirect_decision")),target,check,check,size,offset);
        return new PageResult<>(rows,db.queryForObject("SELECT count(*)"+where,Long.class,target,check,check),page,size);
    }
}

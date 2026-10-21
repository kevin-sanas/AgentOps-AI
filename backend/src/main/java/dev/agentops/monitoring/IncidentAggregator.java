package dev.agentops.monitoring;

import dev.agentops.audit.AuditService;
import dev.agentops.incidents.IncidentService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Invoked by MonitoringStore inside the result transaction, with the target locked. */
@Component
public class IncidentAggregator {
    private final JdbcTemplate db;private final IncidentService incidents;private final AuditService audit;
    public IncidentAggregator(JdbcTemplate db,IncidentService incidents,AuditService audit){this.db=db;this.incidents=incidents;this.audit=audit;}
    public void suspend(UUID target,UUID check,UUID actor,String reason,String requestId){
        incidents.active(target).ifPresent(i->{
            db.update("UPDATE incident SET recovered_at=NULL,status=CASE WHEN status='MONITORING' THEN 'INVESTIGATING' ELSE status END,version=version+1 WHERE id=?",i.id());
            db.update("UPDATE incident_check_snapshot SET monitoring_successes=0 WHERE incident_id=?",i.id());
            incidents.event(i.id(),actor,"CHECK_SUSPENDED",reason,Map.of("checkId",check));
            audit.record(actor,"INCIDENT_RECOVERY_INTERRUPTED","INCIDENT",i.id(),requestId,Map.of("checkId",check,"reason",reason));
        });
    }
    public void observe(MonitoringStore.Task task,ProbeResult result,Instant completed){
        var active=incidents.active(task.targetId());
        if(active.isEmpty()){
            int failures=db.queryForObject("SELECT failure_count FROM check_definition WHERE id=?",Integer.class,task.checkId());
            if(!result.outcome().equals("DOWN")||failures<task.definition().failThreshold())return;
            UUID id=UUID.randomUUID();
            db.update("INSERT INTO incident(id,target_id,opened_at,last_failure_at,summary) VALUES(?,?,?,?,?)",id,task.targetId(),Timestamp.from(completed),Timestamp.from(completed),"健康检查连续失败达到阈值");
            db.update("INSERT INTO incident_check_snapshot(incident_id,check_definition_id,config_version,recover_threshold) SELECT ?,id,config_version,recover_threshold FROM check_definition WHERE target_id=? AND state='ACTIVE'",id,task.targetId());
            incidents.event(id,null,"INCIDENT_OPENED",null,Map.of("checkRunId",task.executionId(),"checkId",task.checkId(),"failThreshold",task.definition().failThreshold()));
            audit.record(null,"INCIDENT_OPENED","INCIDENT",id,"check:"+task.executionId(),Map.of("targetId",task.targetId(),"checkRunId",task.executionId()));
            return;
        }
        var incident=active.get();UUID id=incident.id();
        if(!result.outcome().equals("UP")){
            boolean monitoring=incident.status().equals("MONITORING");
            db.update("UPDATE incident SET last_failure_at=CASE WHEN ?='DOWN' THEN ? ELSE last_failure_at END,recovered_at=NULL,status=?,version=version+1 WHERE id=?",result.outcome(),Timestamp.from(completed),monitoring?"INVESTIGATING":incident.status(),id);
            db.update("UPDATE incident_check_snapshot SET monitoring_successes=0 WHERE incident_id=?",id);
            incidents.event(id,null,monitoring?"RECOVERY_INTERRUPTED":result.outcome().equals("DOWN")?"CHECK_FAILED":"CHECK_UNKNOWN",null,Map.of("checkRunId",task.executionId()));
            if(monitoring)audit.record(null,"INCIDENT_RECOVERY_INTERRUPTED","INCIDENT",id,"check:"+task.executionId(),Map.of("checkRunId",task.executionId()));
            return;
        }
        boolean healthy=Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM incident_check_snapshot WHERE incident_id=?) AND NOT EXISTS(SELECT 1 FROM incident_check_snapshot s JOIN check_definition d ON d.id=s.check_definition_id WHERE s.incident_id=? AND (d.state<>'ACTIVE' OR d.success_count<s.recover_threshold))",Boolean.class,id,id));
        if(incident.status().equals("MONITORING")){
            db.update("UPDATE incident_check_snapshot SET monitoring_successes=LEAST(monitoring_successes+1,20) WHERE incident_id=? AND check_definition_id=?",id,task.checkId());
            boolean observed=Boolean.TRUE.equals(db.queryForObject("SELECT NOT EXISTS(SELECT 1 FROM incident_check_snapshot WHERE incident_id=? AND monitoring_successes<recover_threshold)",Boolean.class,id));
            if(healthy&&observed){
                db.update("UPDATE incident SET status='RESOLVED',resolved_at=?,version=version+1 WHERE id=?",Timestamp.from(completed),id);
                incidents.event(id,null,"INCIDENT_RESOLVED",null,Map.of("source","RECOVERY_WINDOW","checkRunId",task.executionId()));
                audit.record(null,"INCIDENT_AUTO_RESOLVED","INCIDENT",id,"check:"+task.executionId(),Map.of("checkRunId",task.executionId()));
            }
            return;
        }
        if(healthy){
            if(incident.recoveredAt()==null){
                db.update("UPDATE incident SET recovered_at=?,version=version+1 WHERE id=?",Timestamp.from(completed),id);
                incidents.event(id,null,"RECOVERY_DETECTED",null,Map.of("checkRunId",task.executionId()));
            }
            if(incident.status().equals("INVESTIGATING")){
                db.update("UPDATE incident SET status='MONITORING',version=version+1 WHERE id=?",id);
                db.update("UPDATE incident_check_snapshot SET monitoring_successes=0 WHERE incident_id=?",id);
                incidents.event(id,null,"INCIDENT_MONITORING",null,Map.of("checkRunId",task.executionId()));
                audit.record(null,"INCIDENT_MONITORING","INCIDENT",id,"check:"+task.executionId(),Map.of("checkRunId",task.executionId()));
            }
        }
    }
}

package dev.agentops.agent;

import dev.agentops.access.Caller;
import dev.agentops.audit.AuditService;
import dev.agentops.incidents.IncidentService;
import dev.agentops.monitoring.TargetGuard;
import dev.agentops.shared.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Java owns approval facts; Python receives only the committed, immutable decision. */
@Service
public class ApprovalService {
    private final JdbcTemplate db;private final TargetGuard guard;private final AuditService audit;private final IncidentService incidents;private final Idempotency keys;private final JsonMapper json;
    public ApprovalService(JdbcTemplate db,TargetGuard guard,AuditService audit,IncidentService incidents,Idempotency keys,JsonMapper json){this.db=db;this.guard=guard;this.audit=audit;this.incidents=incidents;this.keys=keys;this.json=json;}
    public record Approval(UUID id,long version,String status,String outputHash,Instant expiresAt,UUID decidedBy,String reason,Instant decidedAt){}
    public record Manual(String outcome,String note,UUID actorId,Instant reportedAt){}
    public record Decision(UUID approvalRequestId,long version,String decision,String reason){}
    public Approval get(UUID run){var rows=db.query("SELECT * FROM approval_request WHERE run_id=?",(r,n)->new Approval(r.getObject("id",UUID.class),r.getLong("version"),r.getString("status"),r.getString("output_hash"),r.getTimestamp("expires_at").toInstant(),r.getObject("decided_by",UUID.class),r.getString("reason"),r.getTimestamp("decided_at")==null?null:r.getTimestamp("decided_at").toInstant()),run);return rows.isEmpty()?null:rows.getFirst();}
    public Manual manual(UUID run){var rows=db.query("SELECT * FROM manual_action_report WHERE run_id=?",(r,n)->new Manual(r.getString("outcome"),r.getString("note"),r.getObject("actor_id",UUID.class),r.getTimestamp("reported_at").toInstant()),run);return rows.isEmpty()?null:rows.getFirst();}
    public static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.GeneralSecurityException e){throw new IllegalStateException(e);}}
    // Caller holds the target and delivery row locks in one transaction.
    public void waiting(AgentOutbox.Task task,JsonNode result){
        String body=json.writeValueAsString(result);String hash=hash(body);
        db.update("UPDATE agent_run SET status='WAITING_HUMAN',result=?::jsonb,result_hash=?,error_code=NULL WHERE id=?",body,hash,task.runId());
        db.update("INSERT INTO approval_request(id,run_id,output_hash) VALUES(?,?,?) ON CONFLICT(run_id) DO NOTHING",UUID.randomUUID(),task.runId(),hash);
        db.update("UPDATE agent_step SET status='COMPLETED',completed_at=now() WHERE run_id=? AND node<>'finalize'",task.runId());
        incidents.event(task.incidentId(),null,"AGENT_WAITING_HUMAN","诊断证据已冻结，等待人工审阅；批准后仍须线下处理。",Map.of("runId",task.runId(),"outputHash",hash));
        audit.record(null,"AGENT_REVIEW_REQUESTED","AGENT_RUN",task.runId(),task.requestId(),Map.of("outputHash",hash));
    }
    private Map<String,Object> lock(UUID id){var rows=db.queryForList("SELECT target_id FROM agent_run WHERE id=?",id);if(rows.isEmpty())throw ApiError.notFound();guard.lock((UUID)rows.getFirst().get("target_id"));return db.queryForMap("SELECT * FROM agent_run WHERE id=? FOR UPDATE",id);}
    @Transactional public Approval decide(Caller c,UUID run,Decision input,String requestId){
        var record=lock(run);guard.requireOperate(c,(UUID)record.get("target_id"));
        var a=get(run);if(a==null)throw new ApiError(409,"REVIEW_NOT_AVAILABLE");
        if(!a.id().equals(input.approvalRequestId()))throw new ApiError(409,"APPROVAL_VERSION_CONFLICT");
        if(!a.status().equals("PENDING")){
            if(a.status().equals(input.decision())&&a.version()==input.version()+1&&Objects.equals(a.reason(),input.reason())&&c.userId().equals(a.decidedBy()))return a;
            throw new ApiError(409,"APPROVAL_ALREADY_DECIDED");
        }
        if(a.version()!=input.version())throw new ApiError(409,"APPROVAL_VERSION_CONFLICT");
        if(Boolean.TRUE.equals(db.queryForObject("SELECT expires_at<=clock_timestamp() FROM approval_request WHERE run_id=?",Boolean.class,run)))throw new ApiError(409,"APPROVAL_EXPIRED");
        if(!"WAITING_HUMAN".equals(record.get("status")))throw new ApiError(409,"REVIEW_NOT_AVAILABLE");
        String digest=hash(json.writeValueAsString(List.of(a.id(),a.version(),input.decision(),input.reason(),a.outputHash(),c.userId())));
        db.update("UPDATE approval_request SET status=?,version=version+1,decided_by=?,reason=?,decision_hash=?,decided_at=now() WHERE run_id=?",input.decision(),c.userId(),input.reason(),digest,run);
        String payload=json.writeValueAsString(Map.of("approvalRequestId",a.id(),"decision",input.decision(),"decisionHash",digest));
        db.update("UPDATE outbox_event SET phase='RESUME',payload=?::jsonb,state='PENDING',attempts=0,available_at=now(),lease_token=NULL,lease_until=NULL,last_error=NULL WHERE run_id=?",payload,run);
        audit.record(c.userId(),"AGENT_REVIEW_DECIDED","AGENT_RUN",run,requestId,Map.of("decision",input.decision(),"reason",input.reason(),"outputHash",a.outputHash(),"approvalRequestId",a.id()));
        incidents.event((UUID)record.get("incident_id"),c.userId(),"AGENT_REVIEW_DECIDED","人工审阅决定已记录，等待断点同步。",Map.of("runId",run,"decision",input.decision()));return get(run);
    }
    @Transactional public void expireDue(){
        var due=db.queryForList("SELECT r.id FROM approval_request a JOIN agent_run r ON r.id=a.run_id WHERE a.status='PENDING' AND a.expires_at<=clock_timestamp() ORDER BY r.target_id,r.id LIMIT 50",UUID.class);
        for(UUID run:due){var record=lock(run);int changed=db.update("UPDATE approval_request SET status='EXPIRED',version=version+1,decided_at=now() WHERE run_id=? AND status='PENDING' AND expires_at<=clock_timestamp()",run);if(changed==0)continue;
            db.update("UPDATE agent_run SET status='CANCELLED',completed_at=now() WHERE id=?",run);
            db.update("UPDATE agent_step SET status='SKIPPED',completed_at=now() WHERE run_id=? AND status='PENDING'",run);
            db.update("UPDATE outbox_event SET phase='CANCEL',payload='{}',state='PENDING',attempts=0,available_at=now(),lease_token=NULL,lease_until=NULL,last_error=NULL WHERE run_id=?",run);
            audit.record(null,"AGENT_REVIEW_EXPIRED","AGENT_RUN",run,"approval-expiry",Map.of());
            incidents.event((UUID)record.get("incident_id"),null,"AGENT_REVIEW_EXPIRED","人工审阅已超过24小时，任务已取消。",Map.of("runId",run));
        }
    }
    @Transactional public Manual report(Caller c,UUID run,String outcome,String note,String key,String requestId){
        var record=lock(run);guard.requireOperate(c,(UUID)record.get("target_id"));
        keys.create(c.userId(),"AGENT_MANUAL:"+run,key,Map.of("outcome",outcome,"note",note),()->{
            if(!"APPROVED_FOR_MANUAL".equals(record.get("status")))throw new ApiError(409,"MANUAL_REPORT_NOT_AVAILABLE");
            db.update("INSERT INTO manual_action_report(run_id,outcome,note,actor_id) VALUES(?,?,?,?)",run,outcome,note,c.userId());
            db.update("UPDATE agent_run SET status='COMPLETED',completed_at=now() WHERE id=?",run);
            audit.record(c.userId(),"AGENT_MANUAL_REPORTED","AGENT_RUN",run,requestId,Map.of("outcome",outcome,"note",note));
            incidents.event((UUID)record.get("incident_id"),c.userId(),"AGENT_MANUAL_REPORTED","已回填线下处理结果："+outcome,Map.of("runId",run,"outcome",outcome));return run;
        });return manual(run);
    }
}

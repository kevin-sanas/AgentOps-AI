package dev.agentops.agent;

import dev.agentops.incidents.IncidentService;
import dev.agentops.monitoring.TargetGuard;
import dev.agentops.model.ModelConfigurationStore;
import dev.agentops.model.ModelUsageStore;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Every method is a short transaction; no HTTP is performed while database locks are held. */
@Service
public class AgentOutbox {
    private final JdbcTemplate db;private final JsonMapper json;private final TargetGuard guard;private final IncidentService incidents;private final ModelUsageStore usage;
    public AgentOutbox(JdbcTemplate db,JsonMapper json,TargetGuard guard,IncidentService incidents,ModelUsageStore usage){this.db=db;this.json=json;this.guard=guard;this.incidents=incidents;this.usage=usage;}
    @org.springframework.beans.factory.annotation.Autowired ApprovalService approvals;
    public record Task(UUID eventId,UUID runId,UUID targetId,UUID incidentId,UUID leaseToken,int attempt,String requestId,AgentContracts.Input input,String phase,JsonNode payload,UUID requestedBy){
        public Task(UUID event,UUID run,UUID target,UUID incident,UUID lease,int attempt,String request,AgentContracts.Input input){this(event,run,target,incident,lease,attempt,request,input,"INVOKE",null,null);}
    }
    @Transactional public Optional<Task> claim(){
        // A worker lost during its fifth lease must not leave a permanently leased run.
        var exhausted=db.queryForList("UPDATE outbox_event SET state='DEAD',lease_token=NULL,lease_until=NULL,last_error='AGENT_LEASE_EXHAUSTED' WHERE state='LEASED' AND lease_until<=clock_timestamp() AND attempts>=5 RETURNING run_id",UUID.class);
        for(UUID id:exhausted)dead(id,"AGENT_LEASE_EXHAUSTED");
        var candidates=db.queryForList("SELECT o.id,r.id run_id,r.target_id,r.incident_id,r.request_id,r.input,o.attempts,o.phase,o.payload,CASE WHEN o.phase='RESUME' THEN a.decided_by ELSE r.requested_by END requested_by FROM outbox_event o JOIN agent_run r ON r.id=o.run_id LEFT JOIN approval_request a ON a.run_id=r.id WHERE o.available_at<=clock_timestamp() AND o.attempts<5 AND (o.state='PENDING' OR (o.state='LEASED' AND o.lease_until<=clock_timestamp())) ORDER BY o.available_at,o.id FOR UPDATE OF o SKIP LOCKED LIMIT 1");
        if(candidates.isEmpty())return Optional.empty();var row=candidates.getFirst();UUID event=(UUID)row.get("id"),run=(UUID)row.get("run_id"),target=(UUID)row.get("target_id"),user=(UUID)row.get("requested_by");
        boolean authorized=Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM app_user u JOIN target_access a ON a.user_id=u.id WHERE u.id=? AND u.active AND a.target_id=? AND a.can_read AND a.can_operate AND EXISTS(SELECT 1 FROM user_role ur JOIN app_role r ON r.id=ur.role_id WHERE ur.user_id=u.id AND r.code='ON_CALL'))",Boolean.class,user,target));
        if(!authorized&&!"CANCEL".equals(row.get("phase"))){db.update("UPDATE outbox_event SET state='DEAD',lease_token=NULL,lease_until=NULL,last_error='AUTHORIZATION_REVOKED' WHERE id=?",event);dead(run,"AUTHORIZATION_REVOKED");return Optional.empty();}
        UUID token=UUID.randomUUID();int attempt=((Number)row.get("attempts")).intValue()+1;
        db.update("UPDATE outbox_event SET state='LEASED',attempts=?,lease_token=?,lease_until=clock_timestamp()+interval '90 seconds' WHERE id=?",attempt,token,event);
        if("INVOKE".equals(row.get("phase")))db.update("UPDATE agent_run SET status='RUNNING',started_at=coalesce(started_at,now()) WHERE id=?",run);
        return Optional.of(new Task(event,run,target,(UUID)row.get("incident_id"),token,attempt,row.get("request_id").toString(),json.readValue(row.get("input").toString(),AgentContracts.Input.class),row.get("phase").toString(),json.readTree(row.get("payload").toString()),user));
    }
    private boolean lockValid(Task t){
        guard.lock(t.targetId());
        // Re-evaluate real clock only after obtaining the row locks.
        db.queryForList("SELECT id FROM outbox_event WHERE id=? FOR UPDATE",t.eventId());
        return Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM outbox_event WHERE id=? AND state='LEASED' AND lease_token=? AND lease_until>clock_timestamp())",Boolean.class,t.eventId(),t.leaseToken()));
    }
    @Transactional public boolean complete(Task t,JsonNode candidate){return complete(t,candidate,null);}
    @Transactional public boolean complete(Task t,JsonNode candidate,AgentClient.ProviderRequest provider){
        if(!lockValid(t))return false;
        if("CANCEL".equals(t.phase())){done(t);return true;}
        var result=AgentContracts.validate(json,t.input(),candidate);
        recordProvider(t,provider);
        if("RESUME".equals(t.phase())){
            String frozen=db.queryForObject("SELECT result_hash FROM agent_run WHERE id=?",String.class,t.runId());
            if(!ApprovalService.hash(json.writeValueAsString(result)).equals(frozen))throw dev.agentops.shared.ApiError.badRequest("AGENT_SCHEMA_INVALID");
            var approval=approvals.get(t.runId());String status="APPROVED".equals(approval.status())?"APPROVED_FOR_MANUAL":"REJECTED";
            db.update("UPDATE agent_run SET status=?,error_code=NULL,completed_at=CASE WHEN ?='REJECTED' THEN now() ELSE NULL END WHERE id=?",status,status,t.runId());
            if("APPROVED_FOR_MANUAL".equals(status))db.update("UPDATE agent_step SET status='COMPLETED',completed_at=now() WHERE run_id=? AND node='finalize'",t.runId());
            else db.update("UPDATE agent_step SET status='SKIPPED',completed_at=now() WHERE run_id=? AND node='finalize'",t.runId());
            incidents.event(t.incidentId(),null,"AGENT_REVIEW_SYNCED","人工决定已同步；批准只允许线下处理。",Map.of("runId",t.runId(),"status",status));
        }else if(AgentContracts.REVIEW_GRAPH.equals(t.input().graphVersion())&&!result.nextSteps().isEmpty())approvals.waiting(t,json.valueToTree(result));
        else{
            db.update("UPDATE agent_run SET status='COMPLETED',result=?::jsonb,error_code=NULL,completed_at=now() WHERE id=? AND status='RUNNING'",json.writeValueAsString(result),t.runId());
            db.update("UPDATE agent_step SET status='COMPLETED',completed_at=now() WHERE run_id=?",t.runId());
            incidents.event(t.incidentId(),null,"AGENT_RUN_COMPLETED","只读诊断已完成，请人工审阅证据与不确定项。",Map.of("runId",t.runId()));
        }
        done(t);return true;
    }
    private void done(Task t){
        db.update("UPDATE outbox_event SET state='DONE',lease_token=NULL,lease_until=NULL,last_error=NULL WHERE id=?",t.eventId());
    }
    @Transactional public void pending(Task t){pending(t,null);}
    @Transactional public void pending(Task t,AgentClient.ProviderRequest provider){if(!lockValid(t))return;recordProvider(t,provider);db.update("UPDATE outbox_event SET state='PENDING',attempts=greatest(attempts-1,0),available_at=clock_timestamp()+interval '3 seconds',lease_token=NULL,lease_until=NULL WHERE id=?",t.eventId());}
    @Transactional public void fail(Task t,String code,boolean retryable){fail(t,code,retryable,null);}
    @Transactional public void fail(Task t,String code,boolean retryable,AgentClient.ProviderRequest provider){
        if(!lockValid(t))return;
        recordProvider(t,provider);
        boolean retry=retryable&&t.attempt()<5;int[] seconds={2,10,60,300,300};int delay=seconds[t.attempt()-1]+ThreadLocalRandom.current().nextInt(2);
        db.update("UPDATE outbox_event SET state=?,last_error=?,available_at=clock_timestamp()+make_interval(secs=>?),lease_token=NULL,lease_until=NULL WHERE id=?",retry?"PENDING":"DEAD",code,delay,t.eventId());
        db.update("UPDATE agent_run SET error_code=? WHERE id=?",code,t.runId());if(!retry)dead(t.runId(),code);
    }
    private void recordProvider(Task task,AgentClient.ProviderRequest provider){
        if(provider==null)return;
        AgentClient.Usage tokens=provider.usage();
        usage.recordDiagnosis(new ModelUsageStore.Start("DIAGNOSIS",ModelConfigurationStore.PROVIDER,
            ModelConfigurationStore.MODEL,task.runId(),task.incidentId(),task.requestedBy(),provider.requestId()),
            new ModelUsageStore.Completion(provider.outcome(),provider.errorCode(),provider.httpStatus(),provider.elapsedMs(),
                tokens==null?null:new ModelUsageStore.Usage(tokens.promptTokens(),tokens.completionTokens(),tokens.totalTokens(),tokens.cacheHitTokens(),tokens.cacheMissTokens())));
    }
    private void dead(UUID id,String code){db.update("UPDATE agent_run SET status=CASE WHEN result IS NULL THEN 'FAILED' ELSE status END,error_code=?,completed_at=CASE WHEN result IS NULL THEN now() ELSE completed_at END WHERE id=? AND status<>'COMPLETED'",code,id);db.update("UPDATE agent_step SET status='FAILED',completed_at=now() WHERE run_id=? AND status='PENDING' AND EXISTS(SELECT 1 FROM outbox_event WHERE run_id=? AND phase='INVOKE')",id,id);}
}

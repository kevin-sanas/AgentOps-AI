package dev.agentops.agent;

import dev.agentops.access.Caller;
import dev.agentops.audit.AuditService;
import dev.agentops.incidents.IncidentService;
import dev.agentops.monitoring.TargetGuard;
import dev.agentops.shared.*;
import dev.agentops.targets.TargetService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class AgentService {
    private final JdbcTemplate db;private final TargetService targets;private final TargetGuard guard;private final IncidentService incidents;private final Idempotency keys;private final AuditService audit;private final JsonMapper json;
    public AgentService(JdbcTemplate db,TargetService targets,TargetGuard guard,IncidentService incidents,Idempotency keys,AuditService audit,JsonMapper json){this.db=db;this.targets=targets;this.guard=guard;this.incidents=incidents;this.keys=keys;this.audit=audit;this.json=json;}
    @org.springframework.beans.factory.annotation.Autowired ApprovalService approvals;
    @org.springframework.beans.factory.annotation.Value("${agentops.agent.model-name:mock}") String modelName;
    public record Requested(UUID runId,String status){}
    public record Step(String node,String status,Instant completedAt){}
    public record Delivery(UUID runId,UUID incidentId,UUID targetId,String targetName,String status,String deliveryState,int attempts,String errorCode,Instant createdAt){}
    public PageResult<Delivery> deliveries(int page,int size){
        int offset=PageResult.offset(page,size);
        return new PageResult<>(db.query("SELECT r.id,r.incident_id,r.target_id,t.name,r.status,o.state,o.attempts,o.last_error,r.created_at FROM outbox_event o JOIN agent_run r ON r.id=o.run_id JOIN service_target t ON t.id=r.target_id WHERE o.state='DEAD' ORDER BY r.created_at DESC,r.id LIMIT ? OFFSET ?",(r,n)->new Delivery(r.getObject("id",UUID.class),r.getObject("incident_id",UUID.class),r.getObject("target_id",UUID.class),r.getString("name"),r.getString("status"),r.getString("state"),r.getInt("attempts"),r.getString("last_error"),r.getTimestamp("created_at").toInstant()),size,offset),db.queryForObject("SELECT count(*) FROM outbox_event WHERE state='DEAD'",Long.class),page,size);
    }
    public record Run(UUID id,UUID incidentId,UUID targetId,UUID requestedBy,String status,String graphVersion,String modelName,Instant createdAt,Instant startedAt,Instant completedAt,String errorCode,JsonNode result,String deliveryState,int attempts,List<Step> steps,List<AgentContracts.Check> checkEvidence,List<AgentContracts.Section> runbookEvidence,ApprovalService.Approval approval,ApprovalService.Manual manualReport){}
    @Transactional public Requested start(Caller c,UUID incident,String key,String requestId){
        var initial=incidents.find(incident);guard.lock(initial.targetId());guard.requireOperate(c,initial.targetId());
        UUID id=keys.create(c.userId(),"AGENT_RUN:"+incident,key,Map.of("incidentId",incident),()->{
            UUID fresh=UUID.randomUUID();var input=snapshot(fresh,incident);String body=json.writeValueAsString(input);
            db.update("INSERT INTO agent_run(id,incident_id,target_id,requested_by,request_id,graph_version,model_name,input) VALUES(?,?,?,?,?,?,?,?::jsonb)",fresh,incident,input.incident().targetId(),c.userId(),requestId,AgentContracts.REVIEW_GRAPH,modelName,body);
            for(int n=0;n<AgentContracts.NODES.size();n++)db.update("INSERT INTO agent_step(run_id,ordinal,node,status) VALUES(?,?,?,'PENDING')",fresh,n,AgentContracts.NODES.get(n));
            db.update("INSERT INTO outbox_event(id,run_id,type) VALUES(?,?,'AGENT_RUN_REQUESTED')",UUID.randomUUID(),fresh);
            incidents.event(incident,c.userId(),"AGENT_RUN_REQUESTED","已请求只读诊断，模型："+modelName,Map.of("runId",fresh));
            audit.record(c.userId(),"AGENT_RUN_REQUESTED","AGENT_RUN",fresh,requestId,Map.of("incidentId",incident,"graphVersion",AgentContracts.REVIEW_GRAPH,"modelName",modelName));return fresh;
        });return new Requested(id,db.queryForObject("SELECT status FROM agent_run WHERE id=?",String.class,id));
    }
    private AgentContracts.Input snapshot(UUID id,UUID incident){
        if(modelName==null||!modelName.matches("[A-Za-z0-9._:/-]{1,80}"))throw new IllegalStateException("Invalid agent model name");
        var i=incidents.find(incident);Instant capture=Instant.now();
        var checks=db.query("SELECT r.* FROM check_run r JOIN check_definition d ON d.id=r.check_definition_id WHERE d.target_id=? AND r.completed_at BETWEEN ?::timestamptz-interval '30 minutes' AND ?::timestamptz ORDER BY r.completed_at DESC,r.id LIMIT 20",(r,n)->new AgentContracts.Check(r.getObject("id",UUID.class),r.getString("outcome"),r.getObject("status_code",Integer.class),r.getObject("latency_ms",Integer.class),r.getTimestamp("completed_at").toInstant(),r.getString("error_code")),i.targetId(),capture.toString(),capture.toString());
        var candidates=db.query("SELECT s.* FROM runbook b JOIN runbook_section s ON s.runbook_id=b.id AND s.version=b.latest_version WHERE b.target_id=? AND b.enabled ORDER BY b.created_at DESC,b.id,s.ordinal LIMIT 5",(r,n)->new AgentContracts.Section(r.getObject("runbook_id",UUID.class),r.getInt("version"),r.getObject("id",UUID.class),r.getString("text"),r.getString("content_hash")),i.targetId());
        List<AgentContracts.Section> selected=new ArrayList<>();
        var fact=new AgentContracts.Incident(i.id(),i.targetId(),i.status(),i.openedAt());
        var limits=new AgentContracts.Constraints(List.of("get_check_history","get_published_runbook"),20);
        for(var s:candidates){selected.add(s);if(json.writeValueAsString(new AgentContracts.Input(id,AgentContracts.REVIEW_GRAPH,fact,checks,selected,limits,capture,modelName)).getBytes(StandardCharsets.UTF_8).length>65536)selected.removeLast();}
        return new AgentContracts.Input(id,AgentContracts.REVIEW_GRAPH,fact,checks,List.copyOf(selected),limits,capture,modelName);
    }
    public Run get(Caller c,UUID id){
        var rows=db.queryForList("SELECT * FROM agent_run WHERE id=?",id);if(rows.isEmpty())throw ApiError.notFound();var row=rows.getFirst();targets.get(c,(UUID)row.get("target_id"));
        if(!c.userId().equals(row.get("requested_by"))&&!c.roles().contains("ON_CALL")&&!c.roles().contains("AUDITOR"))throw ApiError.notFound();
        var input=json.readValue(row.get("input").toString(),AgentContracts.Input.class);
        return db.queryForObject("SELECT r.*,o.state delivery_state,o.attempts FROM agent_run r JOIN outbox_event o ON o.run_id=r.id WHERE r.id=?",(r,n)->new Run(id,r.getObject("incident_id",UUID.class),r.getObject("target_id",UUID.class),r.getObject("requested_by",UUID.class),r.getString("status"),r.getString("graph_version"),r.getString("model_name"),r.getTimestamp("created_at").toInstant(),instant(r,"started_at"),instant(r,"completed_at"),r.getString("error_code"),r.getString("result")==null?null:json.readTree(r.getString("result")),r.getString("delivery_state"),r.getInt("attempts"),db.query("SELECT * FROM agent_step WHERE run_id=? ORDER BY ordinal",(s,j)->new Step(s.getString("node"),s.getString("status"),instant(s,"completed_at")),id),input.checks(),input.runbooks(),approvals.get(id),approvals.manual(id)),id);
    }
    private static Instant instant(java.sql.ResultSet r,String field)throws java.sql.SQLException{var t=r.getTimestamp(field);return t==null?null:t.toInstant();}
    public PageResult<Run> list(Caller c,UUID incident,int page,int size){
        targets.get(c,incidents.find(incident).targetId());int offset=PageResult.offset(page,size);
        String scope=" WHERE incident_id=? AND (requested_by=? OR ? OR ?)";
        Object[] filter={incident,c.userId(),c.roles().contains("ON_CALL"),c.roles().contains("AUDITOR")};
        var ids=db.queryForList("SELECT id FROM agent_run"+scope+" ORDER BY created_at DESC,id LIMIT ? OFFSET ?",UUID.class,incident,c.userId(),c.roles().contains("ON_CALL"),c.roles().contains("AUDITOR"),size,offset);
        return new PageResult<>(ids.stream().map(id->get(c,id)).toList(),db.queryForObject("SELECT count(*) FROM agent_run"+scope,Long.class,filter),page,size);
    }
    @Transactional public Requested retry(Caller c,UUID id,String reason,String requestId){
        var rows=db.queryForList("SELECT target_id,incident_id FROM agent_run WHERE id=?",id);if(rows.isEmpty())throw ApiError.notFound();UUID target=(UUID)rows.getFirst().get("target_id");guard.lock(target);targets.get(c,target);
        if("AGENT_MODEL_OUTCOME_UNKNOWN".equals(db.queryForObject("SELECT error_code FROM agent_run WHERE id=?",String.class,id)))throw new ApiError(409,"AGENT_MODEL_OUTCOME_UNKNOWN");
        int changed=db.update("UPDATE outbox_event SET state='PENDING',attempts=0,available_at=now(),last_error=NULL WHERE run_id=? AND state='DEAD'",id);
        if(changed==0)throw new ApiError(409,"AGENT_NOT_RETRYABLE");
        String phase=db.queryForObject("SELECT phase FROM outbox_event WHERE run_id=?",String.class,id);
        if("INVOKE".equals(phase)){db.update("UPDATE agent_run SET status='QUEUED',error_code=NULL,completed_at=NULL WHERE id=?",id);db.update("UPDATE agent_step SET status='PENDING',completed_at=NULL WHERE run_id=?",id);}
        else db.update("UPDATE agent_run SET error_code=NULL WHERE id=?",id);
        audit.record(c.userId(),"AGENT_RUN_RETRIED","AGENT_RUN",id,requestId,Map.of("reason",reason,"phase",phase));return new Requested(id,db.queryForObject("SELECT status FROM agent_run WHERE id=?",String.class,id));
    }
}

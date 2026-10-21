package dev.agentops.incidents;

import dev.agentops.access.Caller;
import dev.agentops.audit.AuditService;
import dev.agentops.monitoring.TargetGuard;
import dev.agentops.shared.*;
import dev.agentops.targets.TargetService;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class IncidentService {
    private final JdbcTemplate db;private final TargetService targets;private final TargetGuard guard;private final AuditService audit;private final JsonMapper json;
    public IncidentService(JdbcTemplate db,TargetService targets,TargetGuard guard,AuditService audit,JsonMapper json){this.db=db;this.targets=targets;this.guard=guard;this.audit=audit;this.json=json;}
    public record Incident(UUID id,UUID targetId,String targetName,String status,String severity,Instant openedAt,Instant lastFailureAt,UUID acknowledgedBy,Instant recoveredAt,Instant resolvedAt,Instant closedAt,String summary,long version){}
    public record Event(UUID id,UUID actorId,String eventType,String body,JsonNode metadata,Instant createdAt){}
    public record Detail(Incident incident,List<Event> events,long eventsTotal,boolean canOperate){}
    public record PendingAlert(UUID targetId,String targetName,UUID checkId,int failureCount,int failThreshold,Integer statusCode,String errorCode,Instant lastCheckedAt,Instant nextRunAt){}
    private static final String PENDING="""
        FROM check_definition d JOIN service_target t ON t.id=d.target_id
        JOIN LATERAL (SELECT outcome,status_code,error_code,completed_at FROM check_run
          WHERE check_definition_id=d.id AND config_version=d.config_version AND target_config_version=t.config_version
          ORDER BY completed_at DESC,id DESC LIMIT 1) r ON true
        WHERE t.active AND d.state='ACTIVE' AND d.failure_count>0 AND d.failure_count<d.fail_threshold
          AND r.outcome='DOWN' AND r.completed_at>=now()-make_interval(secs=>d.interval_seconds*2)
          AND (? OR EXISTS(SELECT 1 FROM target_access a WHERE a.target_id=t.id AND a.user_id=? AND a.can_read))
          AND NOT EXISTS(SELECT 1 FROM incident i WHERE i.target_id=t.id AND i.status IN ('OPEN','ACKNOWLEDGED','INVESTIGATING','MONITORING'))
        """;
    public PageResult<PendingAlert> pendingAlerts(Caller c,int page,int size){
        int offset=PageResult.offset(page,size);
        var rows=db.query("SELECT t.id target_id,t.name target_name,d.id check_id,d.failure_count,d.fail_threshold,r.status_code,r.error_code,r.completed_at,d.next_run_at "+PENDING+" ORDER BY r.completed_at DESC,d.id LIMIT ? OFFSET ?",(r,n)->new PendingAlert(r.getObject("target_id",UUID.class),r.getString("target_name"),r.getObject("check_id",UUID.class),r.getInt("failure_count"),r.getInt("fail_threshold"),r.getObject("status_code",Integer.class),r.getString("error_code"),instant(r,"completed_at"),instant(r,"next_run_at")),c.isAdmin(),c.userId(),size,offset);
        return new PageResult<>(rows,db.queryForObject("SELECT count(*) "+PENDING,Long.class,c.isAdmin(),c.userId()),page,size);
    }
    private static Instant instant(ResultSet r,String field)throws SQLException{var t=r.getTimestamp(field);return t==null?null:t.toInstant();}
    static Incident map(ResultSet r,int n)throws SQLException{return new Incident(r.getObject("id",UUID.class),r.getObject("target_id",UUID.class),r.getString("target_name"),r.getString("status"),r.getString("severity"),instant(r,"opened_at"),instant(r,"last_failure_at"),r.getObject("acknowledged_by",UUID.class),instant(r,"recovered_at"),instant(r,"resolved_at"),instant(r,"closed_at"),r.getString("summary"),r.getLong("version"));}
    public Incident find(UUID id){var rows=db.query("SELECT i.*,t.name target_name FROM incident i JOIN service_target t ON t.id=i.target_id WHERE i.id=?",IncidentService::map,id);if(rows.isEmpty())throw ApiError.notFound();return rows.getFirst();}
    public Optional<Incident> active(UUID target){return db.query("SELECT i.*,t.name target_name FROM incident i JOIN service_target t ON t.id=i.target_id WHERE i.target_id=? AND i.status IN ('OPEN','ACKNOWLEDGED','INVESTIGATING','MONITORING')",IncidentService::map,target).stream().findFirst();}
    public PageResult<Incident> list(Caller c,UUID target,String status,int page,int size){
        int offset=PageResult.offset(page,size);
        if(status!=null&&!Set.of("OPEN","ACKNOWLEDGED","INVESTIGATING","MONITORING","RESOLVED","CLOSED").contains(status))throw ApiError.badRequest("INVALID_INCIDENT_STATUS");
        if(target!=null)targets.get(c,target);
        String where=" FROM incident i JOIN service_target t ON t.id=i.target_id WHERE (? OR EXISTS(SELECT 1 FROM target_access a WHERE a.target_id=t.id AND a.user_id=? AND a.can_read)) AND (?::uuid IS NULL OR t.id=?) AND (?::text IS NULL OR i.status=?)";
        Object[] filters={c.isAdmin(),c.userId(),target,target,status,status};
        var args=new ArrayList<Object>(Arrays.asList(filters));args.add(size);args.add(offset);
        return new PageResult<>(db.query("SELECT i.*,t.name target_name"+where+" ORDER BY i.opened_at DESC,i.id LIMIT ? OFFSET ?",IncidentService::map,args.toArray()),db.queryForObject("SELECT count(*)"+where,Long.class,filters),page,size);
    }
    public Detail detail(Caller c,UUID id){
        Incident i=find(id);targets.get(c,i.targetId());var events=events(c,id,0,100);
        boolean operate=c.roles().contains("ON_CALL")&&Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM target_access WHERE target_id=? AND user_id=? AND can_read AND can_operate)",Boolean.class,i.targetId(),c.userId()));
        return new Detail(i,events.items(),events.total(),operate);
    }
    public PageResult<Event> events(Caller c,UUID id,int page,int size){
        targets.get(c,find(id).targetId());int offset=PageResult.offset(page,size);
        var rows=db.query("SELECT * FROM incident_event WHERE incident_id=? ORDER BY event_sequence DESC LIMIT ? OFFSET ?",(r,n)->new Event(r.getObject("id",UUID.class),r.getObject("actor_id",UUID.class),r.getString("event_type"),r.getString("body"),json.readTree(r.getString("metadata")),instant(r,"created_at")),id,size,offset);
        return new PageResult<>(rows,db.queryForObject("SELECT count(*) FROM incident_event WHERE incident_id=?",Long.class,id),page,size);
    }
    /** Called inside the business transaction only. Body is untrusted text; clients render as text. */
    public void event(UUID incident,UUID actor,String type,String body,Map<String,?> metadata){
        db.update("INSERT INTO incident_event(id,incident_id,actor_id,event_type,body,metadata) VALUES(?,?,?,?,?,?::jsonb)",UUID.randomUUID(),incident,actor,type,body,json.writeValueAsString(metadata));
    }
    @Transactional public Detail transition(Caller c,UUID id,IncidentController.Transition input,String requestId){
        var initial=find(id);guard.lock(initial.targetId());guard.requireOperate(c,initial.targetId());var incident=find(id);
        if(incident.version()!=input.version())throw new ApiError(409,"INCIDENT_VERSION_CONFLICT");
        String next=switch(input.action()){
            case "ACK"->incident.status().equals("OPEN")?"ACKNOWLEDGED":null;
            case "INVESTIGATE"->incident.status().equals("ACKNOWLEDGED")?"INVESTIGATING":null;
            case "RESOLVE"->Set.of("INVESTIGATING","MONITORING").contains(incident.status())&&incident.recoveredAt()!=null?"RESOLVED":null;
            case "CLOSE"->incident.status().equals("RESOLVED")?"CLOSED":null;
            default->null;
        };
        if(next==null)throw new ApiError(409,"INVALID_INCIDENT_TRANSITION");
        db.update("UPDATE incident SET status=?,version=version+1,acknowledged_by=CASE WHEN ?='ACKNOWLEDGED' THEN ? ELSE acknowledged_by END,resolved_at=CASE WHEN ?='RESOLVED' THEN now() ELSE resolved_at END,closed_at=CASE WHEN ?='CLOSED' THEN now() ELSE closed_at END WHERE id=?",next,next,c.userId(),next,next,id);
        event(id,c.userId(),"INCIDENT_"+next,input.reason(),Map.of("from",incident.status(),"to",next,"version",input.version()+1));
        audit.record(c.userId(),"INCIDENT_TRANSITION","INCIDENT",id,requestId,Map.of("from",incident.status(),"to",next,"version",input.version()+1,"reason",input.reason()));
        return detail(c,id);
    }
}

package dev.agentops.workspace;

import dev.agentops.access.Caller;
import dev.agentops.shared.*;
import dev.agentops.targets.TargetService;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class WorkspaceQueries {
    private final JdbcTemplate db;
    private static final String ACL=" (? OR EXISTS(SELECT 1 FROM target_access a WHERE a.target_id=t.id AND a.user_id=? AND a.can_read)) ";
    private static final String INCIDENTS=" FROM incident i JOIN service_target t ON t.id=i.target_id WHERE "+ACL+
        " AND (t.name ILIKE ? ESCAPE '!' OR i.summary ILIKE ? ESCAPE '!' OR i.id::text ILIKE ? ESCAPE '!') AND (?::text IS NULL OR i.status=?) AND (?::timestamptz IS NULL OR i.opened_at>=?::timestamptz) AND (?::timestamptz IS NULL OR i.opened_at<=?::timestamptz)";
    private static final String BOOKS=" FROM runbook b JOIN service_target t ON t.id=b.target_id WHERE "+ACL+
        " AND (b.title ILIKE ? ESCAPE '!' OR t.name ILIKE ? ESCAPE '!') AND (?::uuid IS NULL OR b.target_id=?)";
    private static final String RUNS=" FROM agent_run r JOIN service_target t ON t.id=r.target_id LEFT JOIN approval_request ap ON ap.run_id=r.id JOIN outbox_event o ON o.run_id=r.id WHERE "+ACL+
        " AND (? OR r.requested_by=?) AND (t.name ILIKE ? ESCAPE '!' OR r.id::text ILIKE ? ESCAPE '!') AND (?::text IS NULL OR r.status=?) AND (?::timestamptz IS NULL OR r.created_at>=?::timestamptz) AND (?::timestamptz IS NULL OR r.created_at<=?::timestamptz)";
    public WorkspaceQueries(JdbcTemplate db){this.db=db;}
    public record IncidentRow(UUID id,UUID targetId,String targetName,String status,String severity,String summary,Instant openedAt,Instant lastFailureAt,Instant recoveredAt,Instant resolvedAt,Instant closedAt,long version){}
    public record BookRow(UUID id,UUID targetId,String targetName,String title,long revision,int latestVersion,boolean enabled,boolean hasUnpublishedChanges,Instant createdAt){}
    public record RunRow(UUID id,UUID incidentId,UUID targetId,String targetName,UUID requestedBy,String status,String modelName,String graphVersion,Instant createdAt,Instant completedAt,String approvalStatus,String deliveryState,String errorCode,int attempts){}
    public record SearchItem(String kind,UUID id,String title,String subtitle,String path){}
    public record SearchResult(List<SearchItem> items,int perCategoryLimit){}
    public record ActivityPoint(Instant time,long count){}
    public record Activity(Instant from,Instant to,String bucket,List<ActivityPoint> points,long total){}
    private static Instant instant(ResultSet r,String key)throws SQLException{Timestamp value=r.getTimestamp(key);return value==null?null:value.toInstant();}
    private static List<Object> args(Object...values){return new ArrayList<>(Arrays.asList(values));}
    private static Object[] paging(List<Object> values,WorkspaceQuery q){var result=new ArrayList<>(values);result.add(q.size());result.add(PageResult.offset(q.page(),q.size()));return result.toArray();}
    PageResult<TargetService.Target> targets(Caller c,WorkspaceQuery q){
        PageResult.offset(q.page(),q.size());String pattern=q.pattern();String where=" FROM service_target t WHERE "+ACL+" AND (t.name ILIKE ? ESCAPE '!' OR t.code ILIKE ? ESCAPE '!' OR coalesce(t.owner_team,'') ILIKE ? ESCAPE '!')";
        var values=args(c.isAdmin(),c.userId(),pattern,pattern,pattern);
        String order=q.order(Map.of("name_asc","t.name ASC,t.id","name_desc","t.name DESC,t.id","created_desc","t.created_at DESC,t.id","created_asc","t.created_at ASC,t.id"),"t.created_at DESC,t.id");
        var items=db.query("SELECT t.*"+where+" ORDER BY "+order+" LIMIT ? OFFSET ?",(r,n)->new TargetService.Target(r.getObject("id",UUID.class),r.getString("code"),r.getString("name"),r.getString("environment"),r.getString("base_url"),r.getString("owner_team"),r.getBoolean("active"),r.getString("network_policy"),instant(r,"created_at"),r.getInt("config_version"),r.getBoolean("allow_http")),paging(values,q));
        return new PageResult<>(items,db.queryForObject("SELECT count(*)"+where,Long.class,values.toArray()),q.page(),q.size());
    }
    private void incidentStatus(String status){if(status!=null&&!Set.of("OPEN","ACKNOWLEDGED","INVESTIGATING","MONITORING","RESOLVED","CLOSED").contains(status))throw ApiError.badRequest("INVALID_INCIDENT_STATUS");}
    private List<Object> incidentArgs(Caller c,WorkspaceQuery q){q.window();incidentStatus(q.status());String p=q.pattern();return args(c.isAdmin(),c.userId(),p,p,p,q.status(),q.status(),q.fromText(),q.fromText(),q.toText(),q.toText());}
    PageResult<IncidentRow> incidents(Caller c,WorkspaceQuery q){
        PageResult.offset(q.page(),q.size());var values=incidentArgs(c,q);
        String order=q.order(Map.of("created_desc","i.opened_at DESC,i.id","created_asc","i.opened_at ASC,i.id","severity","i.severity ASC,i.opened_at DESC,i.id","name_asc","t.name ASC,i.id"),"i.opened_at DESC,i.id");
        var items=db.query("SELECT i.*,t.name target_name"+INCIDENTS+" ORDER BY "+order+" LIMIT ? OFFSET ?",(r,n)->new IncidentRow(r.getObject("id",UUID.class),r.getObject("target_id",UUID.class),r.getString("target_name"),r.getString("status"),r.getString("severity"),r.getString("summary"),instant(r,"opened_at"),instant(r,"last_failure_at"),instant(r,"recovered_at"),instant(r,"resolved_at"),instant(r,"closed_at"),r.getLong("version")),paging(values,q));
        return new PageResult<>(items,db.queryForObject("SELECT count(*)"+INCIDENTS,Long.class,values.toArray()),q.page(),q.size());
    }
    PageResult<BookRow> books(Caller c,WorkspaceQuery q,UUID target){
        PageResult.offset(q.page(),q.size());String p=q.pattern();var values=args(c.isAdmin(),c.userId(),p,p,target,target);
        String order=q.order(Map.of("name_asc","b.title ASC,b.id","name_desc","b.title DESC,b.id","created_desc","b.created_at DESC,b.id"),"b.created_at DESC,b.id");
        var items=db.query("SELECT b.*,t.name target_name"+BOOKS+" ORDER BY "+order+" LIMIT ? OFFSET ?",(r,n)->new BookRow(r.getObject("id",UUID.class),r.getObject("target_id",UUID.class),r.getString("target_name"),r.getString("title"),r.getLong("revision"),r.getInt("latest_version"),r.getBoolean("enabled"),r.getObject("published_revision")==null||r.getLong("published_revision")<r.getLong("revision"),instant(r,"created_at")),paging(values,q));
        return new PageResult<>(items,db.queryForObject("SELECT count(*)"+BOOKS,Long.class,values.toArray()),q.page(),q.size());
    }
    PageResult<RunRow> runs(Caller c,WorkspaceQuery q){
        PageResult.offset(q.page(),q.size());q.window();String p=q.pattern();
        if(q.status()!=null&&!Set.of("QUEUED","RUNNING","WAITING_HUMAN","APPROVED_FOR_MANUAL","REJECTED","COMPLETED","FAILED","CANCELLED").contains(q.status()))throw ApiError.badRequest("INVALID_AGENT_STATUS");
        boolean reportRole=c.roles().contains("ON_CALL")||c.roles().contains("AUDITOR");
        var values=args(c.isAdmin(),c.userId(),reportRole,c.userId(),p,p,q.status(),q.status(),q.fromText(),q.fromText(),q.toText(),q.toText());
        String order=q.order(Map.of("created_desc","r.created_at DESC,r.id","created_asc","r.created_at ASC,r.id","name_asc","t.name ASC,r.id"),"r.created_at DESC,r.id");
        var items=db.query("SELECT r.*,t.name target_name,ap.status approval_status,o.state delivery_state,o.attempts"+RUNS+" ORDER BY "+order+" LIMIT ? OFFSET ?",(r,n)->new RunRow(r.getObject("id",UUID.class),r.getObject("incident_id",UUID.class),r.getObject("target_id",UUID.class),r.getString("target_name"),r.getObject("requested_by",UUID.class),r.getString("status"),r.getString("model_name"),r.getString("graph_version"),instant(r,"created_at"),instant(r,"completed_at"),r.getString("approval_status"),r.getString("delivery_state"),r.getString("error_code"),r.getInt("attempts")),paging(values,q));
        return new PageResult<>(items,db.queryForObject("SELECT count(*)"+RUNS,Long.class,values.toArray()),q.page(),q.size());
    }
    SearchResult search(Caller c,String text){
        if(text==null||text.strip().length()<2)throw ApiError.badRequest("INVALID_SEARCH_QUERY");
        var q=new WorkspaceQuery(text,null,null,null,null,0,8);var result=new ArrayList<SearchItem>();
        for(var t:targets(c,q).items())result.add(new SearchItem("TARGET",t.id(),t.name(),t.code()+" · "+t.environment(),"/targets/"+t.id()));
        for(var i:incidents(c,q).items())result.add(new SearchItem("INCIDENT",i.id(),i.targetName(),i.summary(),"/incidents/"+i.id()));
        for(var b:books(c,q,null).items())result.add(new SearchItem("RUNBOOK",b.id(),b.title(),b.targetName()+" · v"+b.latestVersion(),"/runbooks/"+b.id()));
        for(var r:runs(c,q).items())result.add(new SearchItem("AGENT",r.id(),r.targetName(),r.status()+" · "+r.id().toString().substring(0,8),"/agent-runs/"+r.id()));
        return new SearchResult(result,8);
    }
    Activity activity(Caller c,WorkspaceQuery q){
        Instant to=q.to()==null?Instant.now():q.to(),from=q.from()==null?to.minus(Duration.ofDays(1)):q.from();
        var window=new WorkspaceQuery(null,null,from,to,null,0,1);window.window();String bucket=Duration.between(from,to).compareTo(Duration.ofDays(2))>0?"day":"hour";
        String where=" FROM incident i JOIN service_target t ON t.id=i.target_id WHERE "+ACL+" AND i.opened_at>=?::timestamptz AND i.opened_at<=?::timestamptz";
        var points=db.query("SELECT date_trunc(?,i.opened_at AT TIME ZONE 'UTC') AT TIME ZONE 'UTC' bucket,count(*) count"+where+" GROUP BY 1 ORDER BY 1",(r,n)->new ActivityPoint(instant(r,"bucket"),r.getLong("count")),bucket,c.isAdmin(),c.userId(),from.toString(),to.toString());
        return new Activity(from,to,bucket,points,points.stream().mapToLong(ActivityPoint::count).sum());
    }
}

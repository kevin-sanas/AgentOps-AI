package dev.agentops.integrations;

import dev.agentops.access.Caller;
import dev.agentops.audit.AuditService;
import dev.agentops.shared.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KumaService {
    private final JdbcTemplate db;private final AuditService audit;private final KumaEndpointPolicy policy;private final boolean enabled;
    public KumaService(JdbcTemplate db,AuditService audit,KumaEndpointPolicy policy,@Value("${agentops.kuma.enabled:false}") boolean enabled){this.db=db;this.audit=audit;this.policy=policy;this.enabled=enabled;}
    public record Config(UUID id,UUID targetId,String baseUrl,boolean enabled,boolean smokePassed,int version){}
    public record Delivery(UUID id,UUID checkRunId,String kind,String outcome,String state,int attempts,Integer httpStatus,String errorCode,Instant createdAt){}
    private static Config map(ResultSet r,int n)throws SQLException{return new Config(r.getObject("id",UUID.class),r.getObject("target_id",UUID.class),r.getString("base_url"),r.getBoolean("enabled"),r.getBoolean("smoke_passed"),r.getInt("version"));}
    private void admin(Caller caller){
        if(db.queryForList("SELECT id FROM app_user WHERE id=? FOR SHARE",caller.userId()).isEmpty())throw ApiError.notFound();
        if(!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM app_user u JOIN user_role ur ON ur.user_id=u.id JOIN app_role r ON r.id=ur.role_id WHERE u.id=? AND u.active AND r.code='OPS_ADMIN')",Boolean.class,caller.userId())))throw new ApiError(403,"FORBIDDEN");
    }
    @Transactional public List<Config> list(Caller caller,UUID target){admin(caller);return db.query("SELECT * FROM integration_config WHERE target_id=?",KumaService::map,target);}
    private Config lock(UUID id){var rows=db.query("SELECT * FROM integration_config WHERE id=? FOR UPDATE",KumaService::map,id);if(rows.isEmpty())throw ApiError.notFound();return rows.getFirst();}
    @Transactional public Config create(Caller caller,KumaController.Create input,String request){
        admin(caller);String origin=policy.validate(input.baseUrl());KumaEndpointPolicy.reference(input.secretRef());
        if(db.queryForList("SELECT id FROM service_target WHERE id=? FOR UPDATE",input.targetId()).isEmpty())throw ApiError.notFound();
        if(Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM integration_config WHERE target_id=?)",Boolean.class,input.targetId())))throw new ApiError(409,"KUMA_MAPPING_EXISTS");
        UUID id=UUID.randomUUID();db.update("INSERT INTO integration_config(id,target_id,base_url,secret_ref) VALUES(?,?,?,?)",id,input.targetId(),origin,input.secretRef());
        audit.record(caller.userId(),"KUMA_CONFIG_CREATED","INTEGRATION",id,request,Map.of("targetId",input.targetId(),"reason",input.reason()));return lock(id);
    }
    @Transactional public Config state(Caller caller,UUID id,KumaController.State input,String request){
        admin(caller);Config config=lock(id);if(config.version()!=input.version())throw new ApiError(409,"KUMA_VERSION_CONFLICT");
        if(input.enabled()&&(!enabled||!config.smokePassed()))throw new ApiError(409,"KUMA_SMOKE_REQUIRED");
        db.update("UPDATE integration_config SET enabled=?,version=version+1,lease_token=NULL,lease_until=NULL WHERE id=?",input.enabled(),id);
        db.update("UPDATE integration_delivery SET state='SUPERSEDED' WHERE integration_id=? AND state='PENDING'",id);
        audit.record(caller.userId(),"KUMA_STATE_CHANGED","INTEGRATION",id,request,Map.of("enabled",input.enabled(),"reason",input.reason()));return lock(id);
    }
    @Transactional public Map<String,Object> smoke(Caller caller,UUID id,String reason,String request){
        admin(caller);lock(id);if(!enabled)throw new ApiError(409,"KUMA_DISABLED");
        var pending=db.queryForList("SELECT id FROM integration_delivery WHERE integration_id=? AND kind='SMOKE' AND state='PENDING'",UUID.class,id);
        UUID delivery=pending.isEmpty()?UUID.randomUUID():pending.getFirst();
        if(pending.isEmpty())db.update("INSERT INTO integration_delivery(id,integration_id,kind,outcome) VALUES(?,?,'SMOKE','UP')",delivery,id);
        audit.record(caller.userId(),"KUMA_SMOKE_REQUESTED","INTEGRATION",id,request,Map.of("deliveryId",delivery,"reason",reason));return Map.of("deliveryId",delivery,"state","PENDING");
    }
    @Transactional public PageResult<Delivery> deliveries(Caller caller,UUID id,int page,int size){
        admin(caller);if(db.queryForObject("SELECT count(*) FROM integration_config WHERE id=?",Integer.class,id)==0)throw ApiError.notFound();int offset=PageResult.offset(page,size);
        var items=db.query("SELECT * FROM integration_delivery WHERE integration_id=? ORDER BY created_at DESC,id LIMIT ? OFFSET ?",(r,n)->new Delivery(r.getObject("id",UUID.class),r.getObject("check_run_id",UUID.class),r.getString("kind"),r.getString("outcome"),r.getString("state"),r.getInt("attempts"),(Integer)r.getObject("last_http_status"),r.getString("last_error_code"),r.getTimestamp("created_at").toInstant()),id,size,offset);
        return new PageResult<>(items,db.queryForObject("SELECT count(*) FROM integration_delivery WHERE integration_id=?",Long.class,id),page,size);
    }
}

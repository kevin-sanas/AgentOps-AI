package dev.agentops.integrations;

import java.sql.Timestamp;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Poll committed observations, independently of the core check/incident transaction. */
@Service
public class KumaDeliveryStore {
    private final JdbcTemplate db;private final boolean enabled;
    public KumaDeliveryStore(JdbcTemplate db,@Value("${agentops.kuma.enabled:false}") boolean enabled){this.db=db;this.enabled=enabled;}
    public record Task(UUID deliveryId,UUID integrationId,UUID checkRunId,String kind,String outcome,Integer latencyMs,UUID leaseToken,int attempt,int version,String baseUrl,String secretRef){}
    @Transactional public void enqueueLatest(){
        if(!enabled)return;
        var ids=db.queryForList("SELECT id FROM integration_config WHERE enabled ORDER BY last_polled_at NULLS FIRST,id LIMIT 32 FOR UPDATE SKIP LOCKED",UUID.class);
        for(UUID id:ids){
            db.update("UPDATE integration_config SET last_polled_at=clock_timestamp() WHERE id=?",id);
            var rows=db.queryForList("SELECT r.id,r.outcome,r.latency_ms FROM integration_config i JOIN service_target t ON t.id=i.target_id JOIN check_definition d ON d.target_id=t.id JOIN check_run r ON r.check_definition_id=d.id WHERE i.id=? AND t.active AND d.state='ACTIVE' AND r.config_version=d.config_version AND r.target_config_version=t.config_version AND r.completed_at>=i.created_at AND r.completed_at>=clock_timestamp()-make_interval(secs=>GREATEST(120,d.interval_seconds*2)) ORDER BY r.completed_at DESC,r.id DESC LIMIT 1",id);
            // An unknown/stale observation invalidates unsent old status instead of fabricating UP.
            UUID latest=rows.isEmpty()?null:(UUID)rows.getFirst().get("id");
            db.update("UPDATE integration_delivery SET state='SUPERSEDED' WHERE integration_id=? AND kind='CHECK' AND state='PENDING' AND (?::uuid IS NULL OR check_run_id<>?) AND NOT EXISTS(SELECT 1 FROM integration_config i WHERE i.id=? AND i.lease_token IS NOT NULL AND i.lease_until>clock_timestamp())",id,latest,latest,id);
            if(latest==null||!List.of("UP","DOWN").contains(rows.getFirst().get("outcome")))continue;
            var row=rows.getFirst();db.update("INSERT INTO integration_delivery(id,integration_id,check_run_id,kind,outcome,latency_ms) VALUES(?,?,?,'CHECK',?,?) ON CONFLICT(integration_id,check_run_id) DO NOTHING",UUID.randomUUID(),id,latest,row.get("outcome"),row.get("latency_ms"));
        }
    }
    @Transactional public Optional<Task> claim(){
        if(!enabled)return Optional.empty();
        var configs=db.queryForList("SELECT i.* FROM integration_config i WHERE (i.lease_token IS NULL OR i.lease_until<=clock_timestamp()) AND EXISTS(SELECT 1 FROM integration_delivery d WHERE d.integration_id=i.id AND d.state='PENDING' AND d.available_at<=clock_timestamp() AND (i.enabled OR d.kind='SMOKE')) ORDER BY i.id LIMIT 1 FOR UPDATE SKIP LOCKED");
        if(configs.isEmpty())return Optional.empty();var config=configs.getFirst();UUID id=(UUID)config.get("id");
        // Polling can race completion. Validate again under the integration lock before any network send.
        var latest=db.queryForList("SELECT r.id,r.outcome FROM integration_config i JOIN service_target t ON t.id=i.target_id JOIN check_definition d ON d.target_id=t.id JOIN check_run r ON r.check_definition_id=d.id WHERE i.id=? AND t.active AND d.state='ACTIVE' AND r.config_version=d.config_version AND r.target_config_version=t.config_version AND r.completed_at>=i.created_at AND r.completed_at>=clock_timestamp()-make_interval(secs=>GREATEST(120,d.interval_seconds*2)) ORDER BY r.completed_at DESC,r.id DESC LIMIT 1",id);
        UUID valid=latest.isEmpty()||!List.of("UP","DOWN").contains(latest.getFirst().get("outcome"))?null:(UUID)latest.getFirst().get("id");
        db.update("UPDATE integration_delivery SET state='SUPERSEDED' WHERE integration_id=? AND kind='CHECK' AND state='PENDING' AND (?::uuid IS NULL OR check_run_id<>?)",id,valid,valid);
        db.update("UPDATE integration_delivery SET state='DEAD',last_error_code='KUMA_LEASE_EXHAUSTED' WHERE integration_id=? AND state='PENDING' AND attempts>=5",id);
        var deliveries=db.queryForList("SELECT * FROM integration_delivery WHERE integration_id=? AND state='PENDING' AND attempts<5 AND available_at<=clock_timestamp() AND (? OR kind='SMOKE') ORDER BY CASE WHEN kind='SMOKE' THEN 0 ELSE 1 END,created_at,id LIMIT 1 FOR UPDATE",id,config.get("enabled"));
        if(deliveries.isEmpty())return Optional.empty();var d=deliveries.getFirst();UUID token=UUID.randomUUID(),delivery=(UUID)d.get("id");int attempt=((Number)d.get("attempts")).intValue()+1;
        db.update("UPDATE integration_config SET lease_token=?,lease_until=clock_timestamp()+interval '15 seconds' WHERE id=?",token,id);
        db.update("UPDATE integration_delivery SET attempts=? WHERE id=?",attempt,delivery);
        return Optional.of(new Task(delivery,id,(UUID)d.get("check_run_id"),(String)d.get("kind"),(String)d.get("outcome"),(Integer)d.get("latency_ms"),token,attempt,((Number)config.get("version")).intValue(),(String)config.get("base_url"),(String)config.get("secret_ref")));
    }
    @Transactional public boolean complete(Task task,UptimeKumaPushAdapter.Result result){
        var rows=db.queryForList("SELECT id FROM integration_config WHERE id=? AND version=? AND lease_token=? FOR UPDATE",task.integrationId(),task.version(),task.leaseToken());
        if(rows.isEmpty()||!Boolean.TRUE.equals(db.queryForObject("SELECT lease_until>clock_timestamp() FROM integration_config WHERE id=?",Boolean.class,task.integrationId())))return false;
        boolean retry=!result.delivered()&&result.retryable()&&task.attempt()<5;int[] delay={2,10,60,300,300};
        db.update("UPDATE integration_delivery SET state=?,last_http_status=?,last_error_code=?,available_at=clock_timestamp()+make_interval(secs=>?) WHERE id=?",result.delivered()?"DELIVERED":retry?"PENDING":"DEAD",result.httpStatus(),result.errorCode(),delay[task.attempt()-1],task.deliveryId());
        db.update("INSERT INTO integration_delivery_attempt(id,delivery_id,attempt_no,http_status,error_code) VALUES(?,?,?,?,?)",UUID.randomUUID(),task.deliveryId(),task.attempt(),result.httpStatus(),result.errorCode());
        db.update("UPDATE integration_config SET lease_token=NULL,lease_until=NULL,smoke_passed=CASE WHEN ?='SMOKE' THEN ? ELSE smoke_passed END WHERE id=?",task.kind(),result.delivered(),task.integrationId());return true;
    }
}

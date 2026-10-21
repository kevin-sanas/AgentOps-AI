package dev.agentops;

import dev.agentops.monitoring.*;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import tools.jackson.databind.json.JsonMapper;

/** Opt-in browser fixture. Never run against a configured or existing database. Not a default test. */
public final class UiPreview {
    public static void main(String[] ignored)throws Exception{
        System.setProperty("java.io.tmpdir",Path.of("target","tmp").toAbsolutePath().toString());
        Path info=Path.of("target/ui-preview.json"),stop=Path.of("target/ui-preview.stop"),command=Path.of("target/ui-preview.command");
        Files.deleteIfExists(stop);Files.deleteIfExists(command);
        String internal=UUID.randomUUID().toString()+UUID.randomUUID();
        try(var pg=PostgresFixture.start();var python=PythonFixture.start(pg,internal);var context=SpringApplication.run(AgentOpsApplication.class,
            "--server.port=0","--server.address=127.0.0.1","--agentops.monitoring.enabled=false",
            "--spring.datasource.url=jdbc:postgresql://localhost:"+pg.getPort()+"/postgres","--spring.datasource.username=postgres","--spring.datasource.password=postgres",
            "--agentops.security.session-pepper="+UUID.randomUUID()+UUID.randomUUID(),"--agentops.security.internal-token="+internal,
            "--agentops.agent.enabled=true","--agentops.agent.origin=http://127.0.0.1:"+python.port,
            "--agentops.bootstrap.username=","--agentops.bootstrap.password=")){
            JdbcTemplate db=context.getBean(JdbcTemplate.class);String password=UUID.randomUUID().toString();
            UUID admin=UUID.randomUUID(),observer=UUID.randomUUID(),recoveryAdmin=UUID.randomUUID();String hash=new BCryptPasswordEncoder(4).encode(password);
            db.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES(?,'preview-admin',?,'验收管理员'),(?,'preview-observer',?,'只读观察员')",admin,hash,observer,hash);
            for(String role:List.of("OPS_ADMIN","ON_CALL"))db.update("INSERT INTO user_role SELECT ?,id FROM app_role WHERE code=?",admin,role);
            db.update("INSERT INTO user_role SELECT ?,id FROM app_role WHERE code='OBSERVER'",observer);
            db.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES(?,'preview-recovery-admin',?,'投递恢复管理员')",recoveryAdmin,hash);
            db.update("INSERT INTO user_role SELECT ?,id FROM app_role WHERE code='OPS_ADMIN'",recoveryAdmin);
            String[] names={"订单 API","统一网关","支付回调","通知服务","报表服务","账户中心"};
            String[] codes={"orders-api","gateway","payments","notifications","reports","accounts"};
            UUID primary=null,primaryTarget=null;
            for(int n=0;n<names.length;n++){
                UUID target=UUID.randomUUID(),check=UUID.randomUUID();boolean down=n==0,pending=n==1,unknown=n==4,paused=n==3;
                db.update("INSERT INTO service_target(id,code,name,environment,base_url,owner_team,created_by) VALUES(?,?,?,? ,?,'平台工程',?)",target,codes[n],names[n],n==4?"staging":"production","https://"+codes[n]+".example.test/health",admin);
                db.update("INSERT INTO target_access VALUES(?,?,true,true)",target,admin);
                if(n<2)db.update("INSERT INTO target_access VALUES(?,?,true,false)",target,observer);
                db.update("INSERT INTO check_definition(id,target_id,method,interval_seconds,timeout_ms,expected_statuses,fail_threshold,recover_threshold,state,failure_count,success_count,next_run_at) VALUES(?,?,'GET',60,3000,ARRAY[200],3,2,?,?,?,now()+interval '60 seconds')",check,target,unknown?"DISABLED":paused?"PAUSED":"ACTIVE",down?3:pending?2:0,unknown||paused||down||pending?0:3);
                UUID latest=null;Instant completed=Instant.now();
                if(!unknown)for(int r=0;r<3;r++){
                    latest=UUID.randomUUID();Instant time=completed.minusSeconds(10L+r*20L);
                    boolean failed=down||pending&&r<2;
                    db.update("INSERT INTO check_run(id,check_definition_id,config_version,target_config_version,started_at,completed_at,scheduled_at,outcome,status_code,latency_ms,error_code,resolved_ips) VALUES(?,?,1,1,?,?,?,?,?,?,?,ARRAY['93.184.216.34'])",latest,check,Timestamp.from(time.minusMillis(48)),Timestamp.from(time),Timestamp.from(time),failed?"DOWN":"UP",failed?503:200,down?120:48,failed?"UNEXPECTED_STATUS":null);
                }
                if(down){
                    primary=check;primaryTarget=target;UUID incident=UUID.randomUUID();
                    db.update("INSERT INTO incident(id,target_id,last_failure_at,summary) VALUES(?,?,now(),'健康检查连续 3 次返回 503，等待值班人员确认。')",incident,target);
                    db.update("INSERT INTO incident_check_snapshot VALUES(?,?,1,2,0)",incident,check);
                    context.getBean(dev.agentops.incidents.IncidentService.class).event(incident,null,"INCIDENT_OPENED","本条记录由独立验收 fixture 生成。",Map.of("checkRunId",latest,"checkId",check));
                }
            }
            UUID incident=db.queryForObject("SELECT id FROM incident WHERE target_id=?",UUID.class,primaryTarget);
            var caller=new dev.agentops.access.Caller(admin,UUID.randomUUID(),"preview-admin","验收管理员",Set.of("OPS_ADMIN","ON_CALL"));
            new org.springframework.transaction.support.TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class)).execute(status->{
                context.getBean(dev.agentops.agent.AgentService.class).start(caller,incident,"fixture-dead",UUID.randomUUID().toString());
                var outbox=context.getBean(dev.agentops.agent.AgentOutbox.class);outbox.fail(outbox.claim().orElseThrow(),"AGENT_HTTP_400",false);return null;
            });
            new org.springframework.transaction.support.TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class)).execute(status->{
                context.getBean(dev.agentops.agent.AgentService.class).start(caller,incident,"fixture-ambiguous",UUID.randomUUID().toString());
                var outbox=context.getBean(dev.agentops.agent.AgentOutbox.class);outbox.fail(outbox.claim().orElseThrow(),"AGENT_MODEL_OUTCOME_UNKNOWN",false);return null;
            });
            int port=((ServletWebServerApplicationContext)context).getWebServer().getPort();
            Files.writeString(info,new JsonMapper().writeValueAsString(Map.of("port",port,"username","preview-admin","observer","preview-observer","recoveryAdmin","preview-recovery-admin","password",password,"targetId",primaryTarget,"checkId",primary)));
            System.out.println("UI_PREVIEW_READY port="+port+" (credentials in ignored target fixture file)");
            long until=System.nanoTime()+java.time.Duration.ofMinutes(30).toNanos();
            while(!Files.exists(stop)&&System.nanoTime()<until){
                if(Files.exists(command)){
                    String action=Files.readString(command).trim();Files.delete(command);
                    if(List.of("recover","pending","pending-fail").contains(action)){
                      UUID selected=action.equals("recover")?primary:db.queryForObject("SELECT d.id FROM check_definition d JOIN service_target t ON t.id=d.target_id WHERE t.code='gateway'",UUID.class);
                      UUID selectedTarget=db.queryForObject("SELECT target_id FROM check_definition WHERE id=?",UUID.class,selected);
                      List<String> outcomes=action.equals("recover")?List.of("UP","UP","UP","UP"):action.equals("pending")?List.of("UP","DOWN","DOWN"):List.of("DOWN");
                      db.update("UPDATE check_definition SET next_run_at=now()+interval '1 day' WHERE id<>?",selected);
                      for(String outcome:outcomes){
                        db.update("UPDATE check_definition SET next_run_at=now()-interval '1 second' WHERE id=?",selected);
                        db.update("UPDATE service_target SET last_probe_started_at=now()-interval '2 minutes' WHERE id=?",selectedTarget);
                        db.update("UPDATE probe_budget SET window_started=now()-interval '2 seconds',claimed=0");
                        var store=context.getBean(MonitoringStore.class);var task=store.claim(1).getFirst();var now=Instant.now();
                        if(!task.checkId().equals(selected))throw new IllegalStateException("Unexpected fixture check");
                        store.complete(task,now,now,new ProbeResult(outcome,outcome.equals("UP")?200:503,35,outcome.equals("UP")?null:"UNEXPECTED_STATUS",List.of("93.184.216.34"),"NOT_FOLLOWED"));
                      }
                    }
                }
                Thread.sleep(200);
            }
        }finally{Files.deleteIfExists(info);Files.deleteIfExists(stop);Files.deleteIfExists(command);}
    }
}

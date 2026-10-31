package dev.agentops;

import dev.agentops.monitoring.MonitoringStore;
import dev.agentops.monitoring.ProbeResult;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Real API/worker/checkpoint journey; only the probe observations and initial identities are synthetic. */
class AcceptanceJourneyTest {
    final JsonMapper json=new JsonMapper();
    final List<Map<String,Object>> steps=new ArrayList<>();
    final UUID target=UUID.randomUUID(),adminId=UUID.randomUUID(),operatorId=UUID.randomUUID(),readerId=UUID.randomUUID();
    final String password=UUID.randomUUID().toString(),internal=UUID.randomUUID()+"-"+UUID.randomUUID();
    JdbcTemplate db;MonitoringStore store;HttpClient http;int port;
    String admin,operator,reader,incident,book,run,check;
    JsonNode frozen;
    @FunctionalInterface interface Action {void run()throws Exception;}

    @Test void coreIncidentDiagnosisReviewAndRecoveryJourney()throws Exception{
        String result="FAIL";
        try(var pg=PostgresFixture.start();var python=PythonFixture.start(pg,internal);
            var context=(ServletWebServerApplicationContext)SpringApplication.run(AgentOpsApplication.class,
                "--server.port=0","--server.address=127.0.0.1","--spring.main.banner-mode=off","--logging.level.root=WARN",
                "--spring.datasource.url=jdbc:postgresql://localhost:"+pg.getPort()+"/postgres",
                "--spring.datasource.username=postgres","--spring.datasource.password=postgres",
                "--agentops.security.session-pepper="+UUID.randomUUID()+UUID.randomUUID(),"--agentops.security.internal-token="+internal,
                "--agentops.bootstrap.username=","--agentops.bootstrap.password=","--agentops.monitoring.enabled=false",
                "--agentops.kuma.enabled=false","--agentops.agent.enabled=true",
                "--agentops.agent.origin=http://127.0.0.1:"+python.port);
            var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()){
            db=context.getBean(JdbcTemplate.class);store=context.getBean(MonitoringStore.class);port=context.getWebServer().getPort();http=client;
            step("bootstrap","三个独立角色登录；仅初始化本次随机数据库。",()->{
                String hash=new BCryptPasswordEncoder(4).encode(password);
                for(var entry:Map.of(adminId,"accept-admin",operatorId,"accept-operator",readerId,"accept-reader").entrySet()){
                    db.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES(?,?,?,?)",entry.getKey(),entry.getValue(),hash,entry.getValue());
                    String role=entry.getKey().equals(adminId)?"OPS_ADMIN":entry.getKey().equals(operatorId)?"ON_CALL":"OBSERVER";
                    db.update("INSERT INTO user_role SELECT ?,id FROM app_role WHERE code=?",entry.getKey(),role);
                }
                db.update("INSERT INTO service_target(id,code,name,environment,base_url,created_by) VALUES(?,'acceptance-fixture','虚构验收服务','test','https://acceptance.example.test/health',?)",target,adminId);
                db.update("INSERT INTO target_access VALUES(?,?,true,true),(?,?,true,false)",target,operatorId,target,readerId);
                admin=login("accept-admin");operator=login("accept-operator");reader=login("accept-reader");
            });
            step("check-enabled","公共API新建检查默认禁用，再按版本显式启用。",()->{
                var config=Map.of("method","GET","intervalSeconds",60,"timeoutMs",3000,"expectedStatuses",List.of(200),"failThreshold",3,"recoverThreshold",2);
                var created=ok("POST","/targets/"+target+"/checks",admin,config,"check-create",201);
                assertThat(created.path("state").asText()).isEqualTo("DISABLED");check=created.path("id").asText();
                ok("PUT","/targets/"+target+"/checks/"+check+"/state",admin,Map.of("configVersion",created.path("configVersion").asLong(),"state","ACTIVE","reason","启用合成验收检查"),null,200);
            });
            step("threshold-progress","连续两条合成503只显示异常进度，不提前产生事故。",()->{
                sample("DOWN");sample("DOWN");
                assertThat(get("/incidents/pending-alerts",operator).path("items").get(0).path("failureCount").asInt()).isEqualTo(2);
                assertThat(get("/incidents",operator).path("total").asInt()).isZero();
            });
            step("incident-open","第三条合成503经实际聚合器产生唯一OPEN事故。",()->{
                sample("DOWN");var rows=get("/incidents",operator);assertThat(rows.path("total").asInt()).isEqualTo(1);
                incident=rows.path("items").get(0).path("id").asText();assertIncident("OPEN");
                assertThat(get("/incidents/pending-alerts",operator).path("total").asInt()).isZero();
            });
            step("role-boundaries","纯管理员、观察员均不能替代值班人员处理事故。",()->{
                var body=Map.of("version",0,"action","ACK","reason","合成权限验收");
                ok("POST","/incidents/"+incident+"/transitions",admin,body,null,403);
                ok("POST","/incidents/"+incident+"/transitions",reader,body,null,403);
            });
            step("incident-investigation","值班人员确认并开始排查，保留版本及理由。",()->{transition("ACK");transition("INVESTIGATE");assertIncident("INVESTIGATING");});
            step("published-runbook","通过公共API发布含两个稳定段落的虚构手册。",()->{
                book=ok("POST","/targets/"+target+"/runbooks",admin,Map.of("title","虚构503验收手册","markdown","# 检查事实\n核对最近检查结果。\n## 人工排查\n在线下确认依赖状态。"),"book-create",201).path("id").asText();
                ok("POST","/runbooks/"+book+"/publish",admin,Map.of("revision",1,"reason","发布虚构验收手册"),null,200);
            });
            step("diagnosis-idempotency","重复相同发起键返回同一runId，只有一条诊断事实。",()->{
                String path="/incidents/"+incident+"/agent-runs";
                run=ok("POST",path,operator,Map.of(),"diagnosis-create",202).path("runId").asText();
                assertThat(ok("POST",path,operator,Map.of(),"diagnosis-create",202).path("runId").asText()).isEqualTo(run);
                assertThat(db.queryForObject("SELECT count(*) FROM agent_run",Integer.class)).isEqualTo(1);
            });
            step("waiting-human","实际后台Outbox经内部HTTP调用Python并保存PostgreSQL检查点。",()->{
                awaitRun("WAITING_HUMAN");frozen=get("/agent-runs/"+run,operator);
                assertThat(frozen.path("approval").path("status").asText()).isEqualTo("PENDING");
            });
            step("frozen-citations","报告准确引用本次三条检查和两个已发布手册段落。",()->{
                assertThat(frozen.path("checkEvidence").size()).isEqualTo(3);
                assertThat(frozen.path("runbookEvidence").size()).isEqualTo(2);
                assertThat(frozen.path("result").path("citations").size()).isEqualTo(5);
                assertThat(frozen.path("runbookEvidence").get(0).path("runbookId").asText()).isEqualTo(book);
                assertThat(frozen.path("runbookEvidence").get(0).path("version").asInt()).isEqualTo(1);
            });
            step("human-approval","观察员审阅被拒绝；值班批准可幂等重放，后台恢复到待线下处理。",()->{
                var approval=frozen.path("approval");var body=Map.of("approvalRequestId",approval.path("id").asText(),"version",approval.path("version").asLong(),"decision","APPROVED","reason","仅批准人员在线下核对，无自动操作");
                ok("POST","/agent-runs/"+run+"/approval",reader,body,null,403);
                ok("POST","/agent-runs/"+run+"/approval",operator,body,null,200);
                ok("POST","/agent-runs/"+run+"/approval",operator,body,null,200);
                awaitRun("APPROVED_FOR_MANUAL");assertIncident("INVESTIGATING");
            });
            step("manual-report","如实回填NOT_EXECUTED，重复提交不重复写；诊断完成但事故仍在排查。",()->{
                var body=Map.of("outcome","NOT_EXECUTED","note","本次仅验证合成验收链路，未执行线下修复。");
                ok("POST","/agent-runs/"+run+"/manual-action-report",operator,body,"manual-report",200);
                ok("POST","/agent-runs/"+run+"/manual-action-report",operator,body,"manual-report",200);
                var complete=get("/agent-runs/"+run,operator);assertThat(complete.path("status").asText()).isEqualTo("COMPLETED");
                assertThat(complete.path("result")).isEqualTo(frozen.path("result"));assertIncident("INVESTIGATING");
                assertThat(db.queryForObject("SELECT count(*) FROM manual_action_report",Integer.class)).isEqualTo(1);
            });
            step("observer-report-denied","观察员可读事件与检查，但不能读取他人的诊断报告。",()->{
                ok("GET","/agent-runs/"+run,reader,null,null,404);
                assertThat(get("/workspace/agent-runs",reader).path("total").asInt()).isZero();
                assertThat(get("/incidents/"+incident,reader).path("canOperate").asBoolean()).isFalse();
            });
            step("recovery-monitoring","两条合成UP满足恢复阈值，进入MONITORING而非立即解决。",()->{sample("UP");sample("UP");assertIncident("MONITORING");});
            step("incident-closed","再两条UP满足观察窗口，自动解决后由值班人员明确关闭。",()->{
                sample("UP");sample("UP");assertIncident("RESOLVED");transition("CLOSE");assertIncident("CLOSED");
            });
            step("timeline-audit","同一事故的发现、人审、回填、恢复与关闭均可追溯。",()->{
                var events=get("/incidents/"+incident+"/events?size=100",operator).path("items");
                var types=new HashSet<String>();events.forEach(e->types.add(e.path("eventType").asText()));
                assertThat(types).contains("INCIDENT_OPENED","INCIDENT_ACKNOWLEDGED","INCIDENT_INVESTIGATING","AGENT_RUN_REQUESTED","AGENT_WAITING_HUMAN","AGENT_REVIEW_DECIDED","AGENT_MANUAL_REPORTED","INCIDENT_MONITORING","INCIDENT_RESOLVED","INCIDENT_CLOSED");
                assertThat(db.queryForObject("SELECT count(*) FROM audit_event WHERE subject_id=? AND action='AGENT_REVIEW_DECIDED'",Integer.class,UUID.fromString(run))).isEqualTo(1);
                assertThat(db.queryForObject("SELECT count(*) FROM incident",Integer.class)).isEqualTo(1);
            });
        }finally{
            // A successful body plus successful resource closure is required before the final PASS is written below.
            writeReport(result);
        }
        result="PASS";writeReport(result);
    }
    void step(String id,String detail,Action action)throws Exception{
        long start=System.nanoTime();String status="FAIL";
        try{action.run();status="PASS";}finally{steps.add(Map.of("id",id,"status",status,"detail",detail,"elapsedMs",(System.nanoTime()-start)/1_000_000));}
    }
    void writeReport(String status)throws Exception{
        Path file=Path.of(System.getProperty("agentops.acceptance.report","target/acceptance-journey.json"));Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file,json.writeValueAsString(Map.of("schemaVersion",1,"status",status,"sampleMode","synthetic","model","mock","steps",steps)));
    }
    HttpResponse<String> request(String method,String path,String token,Object body,String key)throws Exception{
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path)).timeout(Duration.ofSeconds(10)).header("Content-Type","application/json");
        if(token!=null)builder.header("Authorization","Bearer "+token);if(key!=null)builder.header("Idempotency-Key",key);
        return http.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode ok(String method,String path,String token,Object body,String key,int status)throws Exception{
        var response=request(method,path,token,body,key);assertThat(response.statusCode()).as(method+" "+path).isEqualTo(status);
        return response.body().isBlank()?json.createObjectNode():json.readTree(response.body());
    }
    JsonNode get(String path,String token)throws Exception{return ok("GET",path,token,null,null,200);}
    String login(String username)throws Exception{return ok("POST","/auth/login",null,Map.of("username",username,"password",password),null,200).path("token").asText();}
    void assertIncident(String status)throws Exception{assertThat(get("/incidents/"+incident,operator).path("incident").path("status").asText()).isEqualTo(status);}
    void transition(String action)throws Exception{
        long version=get("/incidents/"+incident,operator).path("incident").path("version").asLong();
        ok("POST","/incidents/"+incident+"/transitions",operator,Map.of("version",version,"action",action,"reason","虚构核心流程验收："+action),null,200);
    }
    void awaitRun(String status){await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).untilAsserted(()->assertThat(get("/agent-runs/"+run,operator).path("status").asText()).isEqualTo(status));}
    void sample(String outcome){
        db.update("UPDATE check_definition SET next_run_at=now()-interval '1 second' WHERE id=?",UUID.fromString(check));
        db.update("UPDATE service_target SET last_probe_started_at=now()-interval '120 seconds' WHERE id=?",target);
        db.update("UPDATE probe_budget SET window_started=now()-interval '2 seconds',claimed=0");
        var tasks=store.claim(1);assertThat(tasks).hasSize(1);var now=Instant.now();
        assertThat(store.complete(tasks.getFirst(),now,now,new ProbeResult(outcome,outcome.equals("UP")?200:503,12,outcome.equals("UP")?null:"UNEXPECTED_STATUS",List.of("93.184.216.34"),"NOT_FOLLOWED"))).isTrue();
    }
}

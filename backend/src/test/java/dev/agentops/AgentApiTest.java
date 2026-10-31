package dev.agentops;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"agentops.monitoring.enabled=false","agentops.agent.enabled=false"})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class AgentApiTest {
    static final EmbeddedPostgres PG=PostgresFixture.start();
    static final UUID ADMIN=UUID.randomUUID(),OPERATOR=UUID.randomUUID(),READER=UUID.randomUUID(),TARGET=UUID.randomUUID(),INCIDENT=UUID.randomUUID();
    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    final JsonMapper json=new JsonMapper();
    final HttpClient http=HttpClient.newHttpClient();
    String password,admin,operator,reader;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",()->"jdbc:postgresql://localhost:"+PG.getPort()+"/postgres");
        r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"postgres");
        r.add("agentops.security.session-pepper",()->"fixture-pepper-"+TARGET);r.add("agentops.security.internal-token",()->"fixture-internal-"+TARGET);
    }
    @AfterAll static void close()throws Exception{PG.close();}
    @BeforeEach void seed()throws Exception{
        db.execute("TRUNCATE app_user CASCADE");password=UUID.randomUUID().toString();
        for(var e:Map.of(ADMIN,"admin",OPERATOR,"operator",READER,"reader").entrySet())db.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES(?,?,?,?)",e.getKey(),e.getValue(),new BCryptPasswordEncoder(4).encode(password),e.getValue());
        role(ADMIN,"OPS_ADMIN");role(OPERATOR,"ON_CALL");role(READER,"OBSERVER");
        db.update("INSERT INTO service_target(id,code,name,environment,base_url,created_by) VALUES(?,'fixture','Fixture','test','https://example.com/health',?)",TARGET,ADMIN);
        db.update("INSERT INTO target_access(target_id,user_id,can_read,can_operate) VALUES(?,?,true,true),(?,?,true,false)",TARGET,OPERATOR,TARGET,READER);
        db.update("INSERT INTO incident(id,target_id,last_failure_at,summary) VALUES(?,?,now(),'Fixture outage')",INCIDENT,TARGET);
        admin=login("admin");operator=login("operator");reader=login("reader");
    }
    void role(UUID id,String code){db.update("INSERT INTO user_role SELECT ?,id FROM app_role WHERE code=?",id,code);}
    HttpResponse<String> request(String method,String path,String token,Object body,String key)throws Exception{
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path)).header("Content-Type","application/json");
        if(token!=null)b.header("Authorization","Bearer "+token);if(key!=null)b.header("Idempotency-Key",key);
        return http.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> req(String m,String p,String t,Object b)throws Exception{return request(m,p,t,b,null);}
    JsonNode ok(HttpResponse<String> r,int status){assertThat(r.statusCode()).as(r.body()).isEqualTo(status);return json.readTree(r.body());}
    String login(String user)throws Exception{return ok(req("POST","/auth/login",null,Map.of("username",user,"password",password)),200).path("token").asText();}
    String base(){return "/targets/"+TARGET+"/runbooks";}
    String createBook()throws Exception{return ok(request("POST",base(),admin,Map.of("title","503 排查","markdown","# 观测\n比较最近两次检查。\n## 依赖\n人工确认上游状态。"),"book"),201).path("id").asText();}
    @Test void publishedVersionsAreImmutableAndOldCitationsSurviveDisable()throws Exception{
        String id=createBook();String p="/runbooks/"+id;
        var v1=ok(req("POST",p+"/publish",admin,Map.of("revision",1,"reason","发布首版")),200);
        var first=ok(req("GET",p+"/versions/1",reader,null),200);
        assertThat(first.path("sections").size()).isEqualTo(2);
        String section=first.path("sections").get(0).path("id").asText();assertThat(section).isNotBlank();
        assertThat(first.path("sections").get(0).path("hash").asText()).hasSize(64);
        long revision=v1.path("revision").asLong();
        ok(req("PUT",p+"/draft",admin,Map.of("revision",revision,"title","503 排查新版","markdown","# 新观测\n查看新的证据。","reason","更新内容")),200);
        ok(req("POST",p+"/publish",admin,Map.of("revision",revision+1,"reason","发布二版")),200);
        assertThat(ok(req("GET",p+"/versions/1",reader,null),200)).isEqualTo(first);
        var current=ok(req("GET",p,admin,null),200);
        ok(req("PUT",p+"/state",admin,Map.of("revision",current.path("revision").asLong(),"enabled",false,"reason","暂时停用")),200);
        assertThat(ok(req("GET",p+"/versions/1",reader,null),200).path("sections").get(0).path("id").asText()).isEqualTo(section);
    }
    @Test void runbooksEnforcePermissionsIdempotencyRevisionAndAuditRollback()throws Exception{
        assertThat(request("POST",base(),reader,Map.of("title","x","markdown","body"),"forbidden").statusCode()).isEqualTo(403);
        String id=createBook();assertThat(createBook()).isEqualTo(id);
        assertThat(req("PUT","/runbooks/"+id+"/draft",admin,Map.of("revision",99,"title","x","markdown","x","reason","conflict")).statusCode()).isEqualTo(409);
        db.update("DELETE FROM target_access WHERE user_id=?",READER);
        assertThat(req("GET","/runbooks/"+id,reader,null).statusCode()).isEqualTo(404);
        db.execute("ALTER TABLE audit_event ADD CONSTRAINT fixture_reject_book_audit CHECK(action<>'RUNBOOK_PUBLISHED') NOT VALID");
        try{assertThat(req("POST","/runbooks/"+id+"/publish",admin,Map.of("revision",1,"reason","fixture failure")).statusCode()).isGreaterThanOrEqualTo(400);
            assertThat(db.queryForObject("SELECT count(*) FROM runbook_version",Integer.class)).isZero();
        }finally{db.execute("ALTER TABLE audit_event DROP CONSTRAINT fixture_reject_book_audit");}
    }
    @Test void diagnosisIsAtomicIdempotentAndAlwaysChecksCurrentAcl()throws Exception{
        String path="/incidents/"+INCIDENT+"/agent-runs";
        assertThat(request("POST",path,admin,Map.of(),"admin").statusCode()).isIn(403,404);
        assertThat(request("POST",path,reader,Map.of(),"reader").statusCode()).isIn(403,404);
        var first=ok(request("POST",path,operator,Map.of(),"run-1"),202);
        UUID id=UUID.fromString(first.path("runId").asText());
        assertThat(first.path("status").asText()).isEqualTo("QUEUED");
        assertThat(ok(request("POST",path,operator,Map.of(),"run-1"),202).path("runId").asText()).isEqualTo(id.toString());
        assertThat(db.queryForObject("SELECT count(*) FROM outbox_event",Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM agent_step WHERE run_id=?",Integer.class,id)).isEqualTo(6);
        assertThat(db.queryForObject("SELECT model_name FROM agent_run WHERE id=?",String.class,id)).isEqualTo("mock");
        assertThat(ok(req("GET","/agent-runs/"+id,operator,null),200).path("input").isMissingNode()).isTrue();
        assertThat(req("GET","/agent-runs/"+id,reader,null).statusCode()).isEqualTo(404);
        db.update("DELETE FROM target_access WHERE user_id=?",OPERATOR);
        assertThat(request("POST",path,operator,Map.of(),"run-1").statusCode()).isEqualTo(404);
        assertThat(req("GET","/agent-runs/"+id,operator,null).statusCode()).isEqualTo(404);
    }
    @Test void failedAuditCannotLeaveAnOrphanRunOrOutbox()throws Exception{
        db.execute("ALTER TABLE audit_event ADD CONSTRAINT fixture_reject_agent_audit CHECK(action<>'AGENT_RUN_REQUESTED') NOT VALID");
        try{assertThat(request("POST","/incidents/"+INCIDENT+"/agent-runs",operator,Map.of(),"rollback").statusCode()).isGreaterThanOrEqualTo(400);
            assertThat(db.queryForObject("SELECT count(*) FROM agent_run",Integer.class)).isZero();
            assertThat(db.queryForObject("SELECT count(*) FROM outbox_event",Integer.class)).isZero();
        }finally{db.execute("ALTER TABLE audit_event DROP CONSTRAINT fixture_reject_agent_audit");}
    }
    @Test void realJavaOutboxToPythonCheckpointReportKeepsPublishedCitations()throws Exception{
        String book=createBook();ok(req("POST","/runbooks/"+book+"/publish",admin,Map.of("revision",1,"reason","发布诊断依据")),200);
        UUID id=UUID.fromString(ok(request("POST","/incidents/"+INCIDENT+"/agent-runs",operator,Map.of(),"e2e"),202).path("runId").asText());
        var outbox=context.getBean(dev.agentops.agent.AgentOutbox.class);
        try(var python=PythonFixture.start(PG,"fixture-internal-"+TARGET)){
            var client=new dev.agentops.agent.AgentClient(context.getBean(dev.agentops.access.SecuritySettings.class),context.getBean(JsonMapper.class),"http://127.0.0.1:"+python.port);
            var task=outbox.claim().orElseThrow();var result=client.reconcile(task);
            assertThat(result.status()).isEqualTo("WAITING_HUMAN");assertThat(outbox.complete(task,result.result())).isTrue();
            var run=ok(req("GET","/agent-runs/"+id,operator,null),200);assertThat(run.path("status").asText()).isEqualTo("WAITING_HUMAN");
            assertThat(run.path("result").path("citations").size()).isEqualTo(2);
            var approval=run.path("approval");assertThat(approval.path("outputHash").asText()).hasSize(64);
            var decision=Map.of("approvalRequestId",approval.path("id").asText(),"version",approval.path("version").asLong(),"decision","APPROVED","reason","人工核对证据后同意线下处理");
            assertThat(req("POST","/agent-runs/"+id+"/approval",reader,decision).statusCode()).isIn(403,404);
            ok(req("POST","/agent-runs/"+id+"/approval",operator,decision),200);
            ok(req("POST","/agent-runs/"+id+"/approval",operator,decision),200);
            var conflict=new HashMap<String,Object>(decision);conflict.put("decision","REJECTED");
            assertThat(req("POST","/agent-runs/"+id+"/approval",operator,conflict).statusCode()).isEqualTo(409);
            var resume=outbox.claim().orElseThrow();var approved=client.reconcile(resume);
            assertThat(approved.status()).isEqualTo("APPROVED_FOR_MANUAL");
            // Approval receipt must retain the exact report and expose only manual follow-up.
            assertThat(outbox.complete(resume,approved.result())).isTrue();
            var manual=Map.of("outcome","NOT_EXECUTED","note","观察到服务已恢复，本次未在线下执行操作。");
            ok(request("POST","/agent-runs/"+id+"/manual-action-report",operator,manual,"manual-1"),200);
            ok(request("POST","/agent-runs/"+id+"/manual-action-report",operator,manual,"manual-1"),200);
            var done=ok(req("GET","/agent-runs/"+id,operator,null),200);
            assertThat(done.path("status").asText()).isEqualTo("COMPLETED");
            assertThat(done.path("result")).isEqualTo(run.path("result"));
            assertThat(done.path("manualReport").path("outcome").asText()).isEqualTo("NOT_EXECUTED");
            assertThat(db.queryForObject("SELECT count(*) FROM audit_event WHERE subject_id=? AND action='AGENT_REVIEW_DECIDED'",Integer.class,id)).isEqualTo(1);
            assertThat(run.path("runbookEvidence").get(0).path("runbookId").asText()).isEqualTo(book);
            // A disabled source remains accessible through the frozen version citation.
            var current=ok(req("GET","/runbooks/"+book,admin,null),200);
            ok(req("PUT","/runbooks/"+book+"/state",admin,Map.of("revision",current.path("revision").asLong(),"enabled",false,"reason","停止新检索")),200);
            assertThat(ok(req("GET","/runbooks/"+book+"/versions/1",operator,null),200).path("sections").size()).isEqualTo(2);
            var second=ok(request("POST","/incidents/"+INCIDENT+"/agent-runs",operator,Map.of(),"after-disable"),202);
            var secondTask=outbox.claim().orElseThrow();assertThat(secondTask.input().runbooks()).isEmpty();
        }
    }
    @Autowired org.springframework.context.ApplicationContext context;
    UUID waitingReview()throws Exception{
        UUID id=UUID.fromString(ok(request("POST","/incidents/"+INCIDENT+"/agent-runs",operator,Map.of(),UUID.randomUUID().toString()),202).path("runId").asText());
        var outbox=context.getBean(dev.agentops.agent.AgentOutbox.class);var task=outbox.claim().orElseThrow();
        var report=new dev.agentops.agent.AgentContracts.Result(id,"COMPLETED",task.input().graphVersion(),"mock","没有检查证据",List.of(),List.of(),List.of(new dev.agentops.agent.AgentContracts.NextStep("人工核对检查历史","LOW",true)),List.of(),List.of("insufficient_evidence"),dev.agentops.agent.AgentContracts.NODES);
        assertThat(outbox.complete(task,json.valueToTree(report))).isTrue();return id;
    }
    Map<String,Object> decision(UUID id,String value)throws Exception{
        var a=ok(req("GET","/agent-runs/"+id,operator,null),200).path("approval");
        return Map.of("approvalRequestId",a.path("id").asText(),"version",a.path("version").asLong(),"decision",value,"reason","测试人工审阅理由");
    }
    @Test void concurrentOppositeDecisionsCommitExactlyOneAndRejectionCannotReportManualAction()throws Exception{
        UUID id=waitingReview();var approve=decision(id,"APPROVED");var reject=decision(id,"REJECTED");
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var a=pool.submit(()->req("POST","/agent-runs/"+id+"/approval",operator,approve));
            var b=pool.submit(()->req("POST","/agent-runs/"+id+"/approval",operator,reject));
            assertThat(List.of(a.get().statusCode(),b.get().statusCode())).containsExactlyInAnyOrder(200,409);
        }
        assertThat(db.queryForObject("SELECT count(*) FROM audit_event WHERE subject_id=? AND action='AGENT_REVIEW_DECIDED'",Integer.class,id)).isEqualTo(1);
        assertThat(request("POST","/agent-runs/"+id+"/manual-action-report",operator,Map.of("outcome","EXECUTED","note","尚未同步"),"premature").statusCode()).isEqualTo(409);
        if("REJECTED".equals(db.queryForObject("SELECT status FROM approval_request WHERE run_id=?",String.class,id))){
            var outbox=context.getBean(dev.agentops.agent.AgentOutbox.class);var task=outbox.claim().orElseThrow();
            var frozen=ok(req("GET","/agent-runs/"+id,operator,null),200).path("result");outbox.complete(task,frozen);
            assertThat(request("POST","/agent-runs/"+id+"/manual-action-report",operator,Map.of("outcome","EXECUTED","note","不能回填被拒绝任务"),"rejected").statusCode()).isEqualTo(409);
        }
    }
    @Test void expiryCancelsDurablyEvenAfterRequesterLosesAuthority()throws Exception{
        UUID id=waitingReview();var command=decision(id,"APPROVED");
        db.update("UPDATE approval_request SET expires_at=clock_timestamp()-interval '1 second' WHERE run_id=?",id);
        assertThat(req("POST","/agent-runs/"+id+"/approval",operator,command).statusCode()).isEqualTo(409);
        db.update("DELETE FROM target_access WHERE user_id=?",OPERATOR);
        var outbox=context.getBean(dev.agentops.agent.AgentOutbox.class);var task=outbox.claim().orElseThrow();assertThat(task.phase()).isEqualTo("CANCEL");
        assertThat(outbox.complete(task,null)).isTrue();
        assertThat(db.queryForObject("SELECT status FROM approval_request WHERE run_id=?",String.class,id)).isEqualTo("EXPIRED");
        assertThat(db.queryForObject("SELECT status FROM agent_run WHERE id=?",String.class,id)).isEqualTo("CANCELLED");
    }
    @Test void failedDecisionAuditRollsBackDecisionAndResumeDeliveryTogether()throws Exception{
        UUID id=waitingReview();var command=decision(id,"APPROVED");
        db.execute("ALTER TABLE audit_event ADD CONSTRAINT fixture_review_audit CHECK(action<>'AGENT_REVIEW_DECIDED') NOT VALID");
        try{
            assertThat(req("POST","/agent-runs/"+id+"/approval",operator,command).statusCode()).isGreaterThanOrEqualTo(400);
            assertThat(db.queryForObject("SELECT status FROM approval_request WHERE run_id=?",String.class,id)).isEqualTo("PENDING");
            assertThat(db.queryForObject("SELECT phase||':'||state FROM outbox_event WHERE run_id=?",String.class,id)).isEqualTo("INVOKE:DONE");
        }finally{db.execute("ALTER TABLE audit_event DROP CONSTRAINT fixture_review_audit");}
    }
    @Test void readOnlyOnCallCannotApproveAndRevokedApproverCannotResume()throws Exception{
        UUID id=waitingReview();role(READER,"ON_CALL");reader=login("reader");
        assertThat(req("GET","/agent-runs/"+id,reader,null).statusCode()).isEqualTo(200);
        var command=decision(id,"APPROVED");
        assertThat(req("POST","/agent-runs/"+id+"/approval",reader,command).statusCode()).isIn(403,404);
        ok(req("POST","/agent-runs/"+id+"/approval",operator,command),200);
        db.update("DELETE FROM target_access WHERE user_id=?",OPERATOR);
        var outbox=context.getBean(dev.agentops.agent.AgentOutbox.class);assertThat(outbox.claim()).isEmpty();
        assertThat(db.queryForObject("SELECT status FROM agent_run WHERE id=?",String.class,id)).isEqualTo("WAITING_HUMAN");
        assertThat(db.queryForObject("SELECT state FROM outbox_event WHERE run_id=?",String.class,id)).isEqualTo("DEAD");
        assertThat(db.queryForObject("SELECT status FROM approval_request WHERE run_id=?",String.class,id)).isEqualTo("APPROVED");
        ok(req("POST","/agent-runs/"+id+"/retry",admin,Map.of("reason","修复审批者权限后同步")),202);
        db.update("INSERT INTO target_access VALUES(?,?,true,true)",TARGET,OPERATOR);
        assertThat(outbox.claim().orElseThrow().phase()).isEqualTo("RESUME");
    }
    @Test void ambiguousModelOutcomeCannotBeForcedBackIntoTheQueue()throws Exception{
        UUID id=UUID.fromString(ok(request("POST","/incidents/"+INCIDENT+"/agent-runs",operator,Map.of(),"ambiguous-model"),202).path("runId").asText());
        var outbox=context.getBean(dev.agentops.agent.AgentOutbox.class);outbox.fail(outbox.claim().orElseThrow(),"AGENT_MODEL_OUTCOME_UNKNOWN",false);
        var response=req("POST","/agent-runs/"+id+"/retry",admin,Map.of("reason","不能强制重发不确定调用"));
        assertThat(response.statusCode()).isEqualTo(409);assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo("AGENT_MODEL_OUTCOME_UNKNOWN");
        assertThat(db.queryForObject("SELECT state FROM outbox_event WHERE run_id=?",String.class,id)).isEqualTo("DEAD");
    }
    @Test void manualAuditRollbackAndConflictCannotAlterFrozenEvidence()throws Exception{
        UUID id=waitingReview();ok(req("POST","/agent-runs/"+id+"/approval",operator,decision(id,"APPROVED")),200);
        var outbox=context.getBean(dev.agentops.agent.AgentOutbox.class);var task=outbox.claim().orElseThrow();var frozen=ok(req("GET","/agent-runs/"+id,operator,null),200).path("result");outbox.complete(task,frozen);
        var report=Map.of("outcome","FAILED","note","线下尝试未成功，保留事故继续排查。");
        db.execute("ALTER TABLE audit_event ADD CONSTRAINT fixture_manual_audit CHECK(action<>'AGENT_MANUAL_REPORTED') NOT VALID");
        try{
            assertThat(request("POST","/agent-runs/"+id+"/manual-action-report",operator,report,"manual").statusCode()).isGreaterThanOrEqualTo(400);
            assertThat(db.queryForObject("SELECT count(*) FROM manual_action_report WHERE run_id=?",Integer.class,id)).isZero();
            assertThat(db.queryForObject("SELECT status FROM agent_run WHERE id=?",String.class,id)).isEqualTo("APPROVED_FOR_MANUAL");
        }finally{db.execute("ALTER TABLE audit_event DROP CONSTRAINT fixture_manual_audit");}
        ok(request("POST","/agent-runs/"+id+"/manual-action-report",operator,report,"manual"),200);
        assertThat(request("POST","/agent-runs/"+id+"/manual-action-report",operator,Map.of("outcome","EXECUTED","note","更改结果"),"manual").statusCode()).isEqualTo(409);
        assertThat(ok(req("GET","/agent-runs/"+id,operator,null),200).path("result")).isEqualTo(frozen);
        assertThat(db.queryForObject("SELECT status FROM incident WHERE id=?",String.class,INCIDENT)).isEqualTo("OPEN");
    }
    @Test void pureAdministratorCanRecoverDeadDeliveryWithoutReadingReportEvidence()throws Exception{
        UUID id=UUID.fromString(ok(request("POST","/incidents/"+INCIDENT+"/agent-runs",operator,Map.of(),"recover"),202).path("runId").asText());
        var outbox=context.getBean(dev.agentops.agent.AgentOutbox.class);outbox.fail(outbox.claim().orElseThrow(),"AGENT_HTTP_400",false);
        assertThat(req("GET","/agent-runs/"+id,admin,null).statusCode()).isEqualTo(404);
        assertThat(req("GET","/admin/agent-deliveries",reader,null).statusCode()).isEqualTo(403);
        var deliveries=ok(req("GET","/admin/agent-deliveries",admin,null),200);
        assertThat(deliveries.path("items").get(0).path("runId").asText()).isEqualTo(id.toString());
        assertThat(deliveries.path("items").get(0).path("result").isMissingNode()).isTrue();
        assertThat(deliveries.path("items").get(0).path("input").isMissingNode()).isTrue();
        ok(req("POST","/agent-runs/"+id+"/retry",admin,Map.of("reason","内部服务已恢复")),202);
        assertThat(ok(req("GET","/admin/agent-deliveries",admin,null),200).path("total").asInt()).isZero();
    }
    @Test void roleRevokedWhileWaitingForUserLockCannotCreateAnAgentRun()throws Exception{
        try(var revocation=db.getDataSource().getConnection()){
            revocation.setAutoCommit(false);
            try(var lock=revocation.prepareStatement("SELECT id FROM app_user WHERE id=? FOR UPDATE")){lock.setObject(1,OPERATOR);lock.executeQuery().close();}
            try(var remove=revocation.prepareStatement("DELETE FROM user_role WHERE user_id=?")){remove.setObject(1,OPERATOR);remove.executeUpdate();}
            try(var pool=java.util.concurrent.Executors.newSingleThreadExecutor()){
                var waiting=pool.submit(()->request("POST","/incidents/"+INCIDENT+"/agent-runs",operator,Map.of(),"revoked-during-lock"));
                try{org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(()->Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%app_user%FOR SHARE%')",Boolean.class)));}
                finally{revocation.commit();}
                assertThat(waiting.get(5,java.util.concurrent.TimeUnit.SECONDS).statusCode()).isIn(403,404);
                assertThat(db.queryForObject("SELECT count(*) FROM agent_run",Integer.class)).isZero();
            }
        }
    }
}

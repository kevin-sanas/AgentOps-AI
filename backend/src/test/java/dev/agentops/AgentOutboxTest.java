package dev.agentops;

import dev.agentops.agent.*;
import dev.agentops.access.Caller;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import java.time.*;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"agentops.monitoring.enabled=false","agentops.agent.enabled=false"})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class AgentOutboxTest {
    static final io.zonky.test.db.postgres.embedded.EmbeddedPostgres PG=PostgresFixture.start();
    static final UUID USER=UUID.randomUUID(),TARGET=UUID.randomUUID(),INCIDENT=UUID.randomUUID();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->"jdbc:postgresql://localhost:"+PG.getPort()+"/postgres");r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"postgres");r.add("agentops.security.session-pepper",()->"test-pepper-"+TARGET);r.add("agentops.security.internal-token",()->"test-internal-"+TARGET);}
    @AfterAll static void close()throws Exception{PG.close();}
    @Autowired JdbcTemplate db;@Autowired AgentService runs;@Autowired AgentOutbox outbox;@Autowired JsonMapper json;
    Caller caller=new Caller(USER,UUID.randomUUID(),"fixture","fixture",Set.of("ON_CALL","OPS_ADMIN"));
    @BeforeEach void seed(){
        db.execute("TRUNCATE app_user CASCADE");
        db.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES(?,'fixture','unused','Fixture')",USER);
        db.update("INSERT INTO user_role SELECT ?,id FROM app_role WHERE code='ON_CALL'",USER);
        db.update("INSERT INTO service_target(id,code,name,environment,base_url,created_by) VALUES(?,'fixture','Fixture','test','https://example.com',?)",TARGET,USER);
        db.update("INSERT INTO target_access(target_id,user_id,can_read,can_operate) VALUES(?,?,true,true)",TARGET,USER);
        db.update("INSERT INTO incident(id,target_id,last_failure_at,summary) VALUES(?,?,now(),'Outage')",INCIDENT,TARGET);
    }
    UUID start(String key){return runs.start(caller,INCIDENT,key,UUID.randomUUID().toString()).runId();}
    @Test void concurrentClaimIsUniqueAndExpiredLeaseCannotComplete()throws Exception{
        UUID id=start("run");
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var a=pool.submit(()->outbox.claim());var b=pool.submit(()->outbox.claim());
            var all=new ArrayList<AgentOutbox.Task>();a.get().ifPresent(all::add);b.get().ifPresent(all::add);assertThat(all).hasSize(1);
            var old=all.getFirst();db.update("UPDATE outbox_event SET lease_until=now()-interval '1 second' WHERE run_id=?",id);
            assertThat(outbox.complete(old,report(old))).isFalse();
            var fresh=outbox.claim().orElseThrow();assertThat(fresh.leaseToken()).isNotEqualTo(old.leaseToken());
            assertThat(outbox.complete(old,report(old))).isFalse();assertThat(outbox.complete(fresh,report(fresh))).isTrue();
            assertThat(outbox.complete(fresh,report(fresh))).isFalse();
            assertThat(runs.get(caller,id).status()).isEqualTo("COMPLETED");
            assertThat(db.queryForObject("SELECT count(*) FROM incident_event WHERE event_type='AGENT_RUN_COMPLETED'",Integer.class)).isEqualTo(1);
        }
    }
    tools.jackson.databind.JsonNode report(AgentOutbox.Task task){return json.valueToTree(new AgentContracts.Result(task.runId(),"COMPLETED",task.input().graphVersion(),"mock","没有检查证据",List.of(),List.of(),List.of(),List.of(),List.of("insufficient_evidence"),AgentContracts.NODES));}
    @Test void failuresHaveBoundedBackoffDeadStateAndExplicitAdminRetry(){
        UUID id=start("run");
        for(int i=1;i<=5;i++){
            var task=outbox.claim().orElseThrow();assertThat(task.attempt()).isEqualTo(i);
            outbox.fail(task,"AGENT_UNAVAILABLE",true);
            var event=db.queryForMap("SELECT state,available_at FROM outbox_event WHERE run_id=?",id);
            assertThat(event.get("state")).isEqualTo(i==5?"DEAD":"PENDING");
            if(i<5)assertThat(((java.sql.Timestamp)event.get("available_at")).toInstant()).isAfter(Instant.now());
            db.update("UPDATE outbox_event SET available_at=now()-interval '1 second' WHERE run_id=?",id);
        }
        assertThat(outbox.claim()).isEmpty();assertThat(runs.get(caller,id).status()).isEqualTo("FAILED");
        runs.retry(caller,id,"已修复内部服务",UUID.randomUUID().toString());
        assertThat(outbox.claim().orElseThrow().attempt()).isEqualTo(1);
    }
    @Test void rejectsFabricatedEvidenceAndDoesNotConsumeRetriesForRunningState(){
        UUID id=start("run");var task=outbox.claim().orElseThrow();
        var invalid=json.readTree(json.writeValueAsString(report(task)).replace("\"evidenceIds\":[]","\"evidenceIds\":[\"check:"+UUID.randomUUID()+"\"]"));
        assertThatThrownBy(()->outbox.complete(task,invalid)).hasMessageContaining("AGENT_SCHEMA_INVALID");
        outbox.pending(task);assertThat(db.queryForObject("SELECT attempts FROM outbox_event WHERE run_id=?",Integer.class,id)).isZero();
        db.update("UPDATE outbox_event SET available_at=now()-interval '1 second' WHERE run_id=?",id);
        var again=outbox.claim().orElseThrow();outbox.fail(again,"AGENT_SCHEMA_INVALID",false);
        assertThat(runs.get(caller,id).deliveryState()).isEqualTo("DEAD");
    }
    @Test void revokedOperatorIsRejectedBeforeAnyAgentCall(){
        UUID id=start("run");db.update("DELETE FROM target_access WHERE user_id=?",USER);
        assertThat(outbox.claim()).isEmpty();assertThat(db.queryForObject("SELECT error_code FROM agent_run WHERE id=?",String.class,id)).isEqualTo("AUTHORIZATION_REVOKED");
    }
    @Test void javaIndependentlyRejectsSensitiveOutputBeforeSavingAReport(){
        start("sensitive");var task=outbox.claim().orElseThrow();
        var sensitive=json.readTree(json.writeValueAsString(report(task)).replace("没有检查证据","password=synthetic-secret-123"));
        assertThatThrownBy(()->outbox.complete(task,sensitive)).hasMessageContaining("AGENT_SCHEMA_INVALID");
        assertThat(db.queryForObject("SELECT result IS NULL FROM agent_run WHERE id=?",Boolean.class,task.runId())).isTrue();
    }
}

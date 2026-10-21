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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties="agentops.monitoring.enabled=false")
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@org.springframework.context.annotation.Import(MonitoringApiTest.DnsFixtures.class)
class MonitoringApiTest {
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    static class DnsFixtures {
        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary
        dev.agentops.targets.TargetUrlPolicy fixturePolicy(){return new dev.agentops.targets.TargetUrlPolicy(h->new java.net.InetAddress[]{java.net.InetAddress.getByName(h.equals("private.example.test")?"10.20.1.4":"93.184.216.34")},List.of("10.20.0.0/16"));}
    }
    static final EmbeddedPostgres PG=PostgresFixture.start();
    static final UUID ADMIN=UUID.randomUUID(), READER=UUID.randomUUID(), OPERATOR=UUID.randomUUID(), TARGET=UUID.randomUUID();
    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired dev.agentops.monitoring.MonitoringStore store;
    @Test void pendingAlertsTrackThresholdProgressAndMoveIntoTheIncidentQueue()throws Exception{
        UUID check=activeCheck();sample(check,"DOWN");
        var pending=json.readTree(request("GET","/incidents/pending-alerts",reader,null).body());
        assertThat(pending.path("total").asInt()).isEqualTo(1);
        assertThat(pending.path("items").get(0).path("failureCount").asInt()).isEqualTo(1);
        assertThat(pending.path("items").get(0).path("failThreshold").asInt()).isEqualTo(3);
        sample(check,"DOWN");
        var response=request("GET","/incidents/pending-alerts",reader,null);assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(json.readTree(response.body()).path("items").get(0).path("failureCount").asInt()).isEqualTo(2);
        assertThat(json.readTree(request("GET","/incidents",reader,null).body()).path("total").asInt()).isZero();
        sample(check,"UP");
        assertThat(json.readTree(request("GET","/incidents/pending-alerts",reader,null).body()).path("total").asInt()).isZero();
        for(int n=0;n<3;n++)sample(check,"DOWN");
        assertThat(json.readTree(request("GET","/incidents",reader,null).body()).path("total").asInt()).isEqualTo(1);
        assertThat(json.readTree(request("GET","/incidents/pending-alerts",reader,null).body()).path("total").asInt()).isZero();
    }
    @Test void pendingAlertsRespectAclConfigurationVersionFreshnessAndPagination()throws Exception{
        UUID check=activeCheck();sample(check,"DOWN");
        assertThat(request("GET","/incidents/pending-alerts",null,null).statusCode()).isEqualTo(401);
        assertThat(json.readTree(request("GET","/incidents/pending-alerts",operator,null).body()).path("total").asInt()).isZero();
        assertThat(json.readTree(request("GET","/incidents/pending-alerts",admin,null).body()).path("total").asInt()).isEqualTo(1);
        assertThat(request("GET","/incidents/pending-alerts?size=101",reader,null).statusCode()).isEqualTo(400);
        db.update("UPDATE service_target SET config_version=config_version+1 WHERE id=?",TARGET);
        assertThat(json.readTree(request("GET","/incidents/pending-alerts",reader,null).body()).path("total").asInt()).isZero();
        sample(check,"DOWN");
        assertThat(json.readTree(request("GET","/incidents/pending-alerts",reader,null).body()).path("total").asInt()).isEqualTo(1);
        db.update("UPDATE check_run SET completed_at=completed_at-interval '1 hour',started_at=started_at-interval '1 hour'");
        assertThat(json.readTree(request("GET","/incidents/pending-alerts",reader,null).body()).path("total").asInt()).isZero();
    }
    @Test void resultWaitingForTargetLockCannotUseTransactionStartToExtendLease()throws Exception{
        UUID check=activeCheck();due(check);var task=claimOne();
        try(var blocker=db.getDataSource().getConnection()){
            blocker.setAutoCommit(false);
            try(var statement=blocker.prepareStatement("SELECT id FROM service_target WHERE id=? FOR UPDATE")){statement.setObject(1,TARGET);statement.executeQuery().close();}
            try(var pool=java.util.concurrent.Executors.newSingleThreadExecutor()){
                var waiting=pool.submit(()->{var now=java.time.Instant.now();return store.complete(task,now,now,observation("UP"));});
                try{
                    org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(()->Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%service_target%FOR UPDATE%')",Boolean.class)));
                    try(var statement=blocker.prepareStatement("UPDATE check_definition SET lease_until=clock_timestamp()-interval '1 millisecond' WHERE id=?")){statement.setObject(1,check);statement.executeUpdate();}
                }finally{blocker.commit();}
                assertThat(waiting.get(5,java.util.concurrent.TimeUnit.SECONDS)).isFalse();
            }
        }
        assertThat(db.queryForObject("SELECT count(*) FROM check_run",Integer.class)).isZero();
    }
    @Test void overviewRequiresFreshEvidenceForBothConfigurationVersionsAndResume()throws Exception{
        UUID check=activeCheck();sample(check,"UP");
        assertThat(json.readTree(request("GET","/overview",reader,null).body()).path("up").asInt()).isEqualTo(1);
        assertThat(request("PUT","/targets/"+TARGET+"/network-policy",admin,Map.of("version",1,"baseUrl","https://changed.example.com/health","allowHttp",false,"allowedPrivateCidrs",List.of(),"reason","Change endpoint")).statusCode()).isEqualTo(200);
        assertThat(json.readTree(request("GET","/overview",reader,null).body()).path("unknown").asInt()).isEqualTo(1);
        sample(check,"UP");
        assertThat(json.readTree(request("GET","/overview",reader,null).body()).path("up").asInt()).isEqualTo(1);
        assertThat(request("PUT",checks()+"/"+check,admin,Map.of("configVersion",2,"config",config(),"reason","Change expected result")).statusCode()).isEqualTo(200);
        assertThat(json.readTree(request("GET","/overview",reader,null).body()).path("unknown").asInt()).isEqualTo(1);
        sample(check,"UP");
        assertThat(request("PUT",checks()+"/"+check+"/state",admin,Map.of("configVersion",3,"state","PAUSED","reason","Maintenance")).statusCode()).isEqualTo(200);
        assertThat(request("PUT",checks()+"/"+check+"/state",admin,Map.of("configVersion",4,"state","ACTIVE","reason","Resume")).statusCode()).isEqualTo(200);
        assertThat(json.readTree(request("GET","/overview",reader,null).body()).path("unknown").asInt()).isEqualTo(1);
        sample(check,"UP");
        assertThat(json.readTree(request("GET","/overview",reader,null).body()).path("up").asInt()).isEqualTo(1);
    }
    final JsonMapper json=new JsonMapper();
    final HttpClient http=HttpClient.newHttpClient();
    String password,admin,reader,operator;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->"jdbc:postgresql://localhost:"+PG.getPort()+"/postgres");
        r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"postgres");
        r.add("agentops.security.session-pepper",()->"test-pepper-"+TARGET);
        r.add("agentops.security.internal-token",()->"test-internal-"+TARGET);
    }
    @AfterAll static void close() throws Exception { PG.close(); }
    @BeforeEach void seed() throws Exception {
        db.execute("TRUNCATE app_user CASCADE");db.execute("TRUNCATE probe_host_lease");
        db.update("UPDATE probe_slot SET lease_token=NULL,lease_until=now()");db.update("UPDATE probe_budget SET claimed=0,window_started=now()-interval '2 seconds'");
        password=UUID.randomUUID().toString();
        for (var entry:Map.of(ADMIN,"admin",READER,"reader",OPERATOR,"operator").entrySet()) {
            db.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES(?,?,?,?)",entry.getKey(),entry.getValue(),new BCryptPasswordEncoder(4).encode(password),entry.getValue());
        }
        role(ADMIN,"OPS_ADMIN");role(READER,"OBSERVER");role(OPERATOR,"ON_CALL");
        db.update("INSERT INTO service_target(id,code,name,environment,base_url,created_by) VALUES(?,'fixture','Fixture','test','https://example.com/health',?)",TARGET,ADMIN);
        db.update("INSERT INTO target_access(target_id,user_id,can_read,can_operate) VALUES(?,?,true,false)",TARGET,READER);
        admin=login("admin");reader=login("reader");operator=login("operator");
    }
    void role(UUID id,String code) { db.update("INSERT INTO user_role SELECT ?,id FROM app_role WHERE code=?",id,code); }
    HttpResponse<String> request(String method,String path,String token,Object body,String key) throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path)).header("Content-Type","application/json");
        if(token!=null)b.header("Authorization","Bearer "+token);if(key!=null)b.header("Idempotency-Key",key);
        return http.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> request(String m,String p,String t,Object b) throws Exception {return request(m,p,t,b,null);}
    String login(String name) throws Exception {return json.readTree(request("POST","/auth/login",null,Map.of("username",name,"password",password)).body()).path("token").asText();}
    String checks(){return "/targets/"+TARGET+"/checks";}
    Map<String,Object> config(){return Map.of("method","GET","intervalSeconds",60,"timeoutMs",3000,"expectedStatuses",List.of(200),"failThreshold",3,"recoverThreshold",2);}
    JsonNode create() throws Exception {
        var response=request("POST",checks(),admin,config(),UUID.randomUUID().toString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);return json.readTree(response.body());
    }
    @Test void checksAreIdempotentDisabledAndResourceScoped() throws Exception {
        var response=request("POST",checks(),admin,config(),"one-check");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(request("POST",checks(),admin,config(),"one-check").body()).isEqualTo(response.body());
        assertThat(json.readTree(response.body()).path("state").asText()).isEqualTo("DISABLED");
        assertThat(json.readTree(request("GET",checks(),reader,null).body()).path("items").size()).isEqualTo(1);
        assertThat(request("GET",checks(),operator,null).statusCode()).isEqualTo(404);
        assertThat(request("POST",checks(),reader,config(),"denied").statusCode()).isEqualTo(403);
        assertThat(request("GET",checks()+"/history",reader,null).statusCode()).isEqualTo(200);
    }
    @Test void definitionsValidateLimitsAndVersionedStateTransitions() throws Exception {
        var bad=new HashMap<>(config());bad.put("intervalSeconds",1);
        assertThat(request("POST",checks(),admin,bad,"too-fast").statusCode()).isEqualTo(400);
        var c=create();String path=checks()+"/"+c.path("id").asText()+"/state";
        var active=request("PUT",path,admin,Map.of("configVersion",1,"state","ACTIVE","reason","Start monitoring"));
        assertThat(active.statusCode()).as(active.body()).isEqualTo(200);
        assertThat(json.readTree(active.body()).path("configVersion").asInt()).isEqualTo(2);
        assertThat(request("PUT",path,admin,Map.of("configVersion",1,"state","PAUSED","reason","stale")).statusCode()).isEqualTo(409);
        assertThat(request("PUT",path,admin,Map.of("configVersion",2,"state","PAUSED","reason","Maintenance")).statusCode()).isEqualTo(200);
        assertThat(db.queryForObject("SELECT count(*) FROM audit_event WHERE action='CHECK_STATE_CHANGED'",Integer.class)).isEqualTo(2);
    }
    @Test void explicitHttpApprovalAndNetworkPolicyUseVersionAndAudit() throws Exception {
        String path="/targets/"+TARGET+"/network-policy";
        var body=Map.of("version",1,"baseUrl","http://example.com/health","allowHttp",true,"allowedPrivateCidrs",List.of(),"reason","Approved unauthenticated health endpoint");
        var ok=request("PUT",path,admin,body);assertThat(ok.statusCode()).as(ok.body()).isEqualTo(200);
        assertThat(json.readTree(ok.body()).path("baseUrl").asText()).startsWith("http:");
        assertThat(request("PUT",path,admin,body).statusCode()).isEqualTo(409);
        assertThat(request("PUT",path,reader,body).statusCode()).isEqualTo(403);
        assertThat(db.queryForObject("SELECT count(*) FROM target_network_policy",Integer.class)).isEqualTo(1);
    }
    UUID incident(){UUID id=UUID.randomUUID();db.update("INSERT INTO incident(id,target_id,last_failure_at,summary) VALUES(?,?,now(),'Synthetic failure')",id,TARGET);return id;}
    void operate(){db.update("INSERT INTO target_access VALUES(?,?,true,true)",TARGET,OPERATOR);}
    HttpResponse<String> transition(UUID id,String token,long version,String action)throws Exception{return request("POST","/incidents/"+id+"/transitions",token,Map.of("version",version,"action",action,"reason","Operator investigation"));}
    @Test void incidentTransitionsRequireOnCallAndOperateAndCurrentVersion()throws Exception{
        UUID id=incident();
        assertThat(request("GET","/incidents/"+id,operator,null).statusCode()).isEqualTo(404);
        assertThat(json.readTree(request("GET","/incidents",reader,null).body()).path("items").size()).isEqualTo(1);
        assertThat(transition(id,admin,0,"ACK").statusCode()).isEqualTo(403);
        assertThat(transition(id,reader,0,"ACK").statusCode()).isEqualTo(403);
        assertThat(transition(id,operator,0,"ACK").statusCode()).isEqualTo(404);
        operate();var ack=transition(id,operator,0,"ACK");assertThat(ack.statusCode()).as(ack.body()).isEqualTo(200);
        assertThat(transition(id,operator,0,"INVESTIGATE").statusCode()).isEqualTo(409);
        assertThat(transition(id,operator,1,"INVESTIGATE").statusCode()).isEqualTo(200);
        var detail=json.readTree(request("GET","/incidents/"+id,reader,null).body());
        assertThat(detail.path("incident").path("status").asText()).isEqualTo("INVESTIGATING");
        assertThat(detail.path("events").size()).isEqualTo(2);
    }
    @Test void incidentStateMachineRequiresVerifiedRecoveryAndNewFaultWindow()throws Exception{
        UUID id=incident();operate();
        assertThat(transition(id,operator,0,"RESOLVE").statusCode()).isEqualTo(409);
        assertThat(transition(id,operator,0,"ACK").statusCode()).isEqualTo(200);
        assertThat(transition(id,operator,1,"INVESTIGATE").statusCode()).isEqualTo(200);
        assertThat(transition(id,operator,2,"RESOLVE").statusCode()).isEqualTo(409);
        db.update("UPDATE incident SET recovered_at=now() WHERE id=?",id);
        assertThat(transition(id,operator,2,"RESOLVE").statusCode()).isEqualTo(200);
        assertThat(transition(id,operator,3,"CLOSE").statusCode()).isEqualTo(200);
        assertThat(transition(id,operator,4,"ACK").statusCode()).isEqualTo(409);
        assertThat(json.readTree(request("GET","/incidents/"+id,reader,null).body()).path("incident").path("closedAt").isNull()).isFalse();
    }
    @Test void activeIncidentFreezesPolicyAndDefinitionButAllowsPause()throws Exception{
        var c=create();String path=checks()+"/"+c.path("id").asText();
        request("PUT",path+"/state",admin,Map.of("configVersion",1,"state","ACTIVE","reason","Start"));incident();
        assertThat(request("PUT",path,admin,Map.of("configVersion",2,"config",config(),"reason","threshold edit")).statusCode()).isEqualTo(409);
        assertThat(request("POST",checks(),admin,config(),"during-incident").statusCode()).isEqualTo(409);
        assertThat(request("PUT",path+"/state",admin,Map.of("configVersion",2,"state","PAUSED","reason","Maintenance")).statusCode()).isEqualTo(200);
    }
    @Test void concurrentIncidentTransitionHasOneWinner()throws Exception{
        UUID id=incident();operate();
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var a=pool.submit(()->transition(id,operator,0,"ACK").statusCode());var b=pool.submit(()->transition(id,operator,0,"ACK").statusCode());
            assertThat(List.of(a.get(),b.get())).containsExactlyInAnyOrder(200,409);
        }
        assertThat(db.queryForObject("SELECT count(*) FROM incident_event WHERE incident_id=?",Integer.class,id)).isEqualTo(1);
    }
    UUID activeCheck()throws Exception{
        var c=create();UUID id=UUID.fromString(c.path("id").asText());
        assertThat(request("PUT",checks()+"/"+id+"/state",admin,Map.of("configVersion",1,"state","ACTIVE","reason","Start")).statusCode()).isEqualTo(200);return id;
    }
    void due(UUID id){
        db.update("UPDATE check_definition SET next_run_at=now()-interval '1 second' WHERE id=?",id);
        db.update("UPDATE service_target SET last_probe_started_at=now()-interval '120 seconds' WHERE id=?",TARGET);
        db.update("UPDATE probe_budget SET window_started=now()-interval '2 seconds',claimed=0");
    }
    dev.agentops.monitoring.ProbeResult observation(String outcome){return new dev.agentops.monitoring.ProbeResult(outcome,outcome.equals("UP")?200:503,7,outcome.equals("UP")?null:"UNEXPECTED_STATUS",List.of("93.184.216.34"),"NOT_FOLLOWED");}
    void sample(UUID id,String outcome){
        due(id);var tasks=store.claim(4);assertThat(tasks).hasSize(1);var task=tasks.getFirst();assertThat(task.checkId()).isEqualTo(id);
        var now=java.time.Instant.now();assertThat(store.complete(task,now,now.plusMillis(7),observation(outcome))).isTrue();
    }
    dev.agentops.monitoring.MonitoringStore.Task claimOne(){var tasks=store.claim(1);assertThat(tasks).hasSize(1);return tasks.getFirst();}
    @Test void schedulerAggregatesFailuresAndHumanRecoveryWithoutSkippingStates()throws Exception{
        UUID id=activeCheck();
        for(int n=0;n<2;n++)sample(id,"DOWN");assertThat(db.queryForObject("SELECT count(*) FROM incident",Integer.class)).isZero();
        sample(id,"DOWN");UUID incident=db.queryForObject("SELECT id FROM incident",UUID.class);operate();
        sample(id,"UP");sample(id,"UP");assertThat(db.queryForObject("SELECT status FROM incident",String.class)).isEqualTo("OPEN");
        long v=db.queryForObject("SELECT version FROM incident",Long.class);
        assertThat(transition(incident,operator,v,"ACK").statusCode()).isEqualTo(200);
        assertThat(transition(incident,operator,v+1,"INVESTIGATE").statusCode()).isEqualTo(200);
        sample(id,"UP");assertThat(db.queryForObject("SELECT status FROM incident",String.class)).isEqualTo("MONITORING");
        sample(id,"DOWN");assertThat(db.queryForObject("SELECT status FROM incident",String.class)).isEqualTo("INVESTIGATING");
        sample(id,"UP");sample(id,"UP");assertThat(db.queryForObject("SELECT status FROM incident",String.class)).isEqualTo("MONITORING");
        sample(id,"UP");sample(id,"UP");assertThat(db.queryForObject("SELECT status FROM incident",String.class)).isEqualTo("RESOLVED");
        for(int n=0;n<3;n++)sample(id,"DOWN");assertThat(db.queryForObject("SELECT count(*) FROM incident",Integer.class)).isEqualTo(2);
    }
    @Test void expiredWorkerCannotCommitAndRetryKeepsExecutionIdentity()throws Exception{
        UUID id=activeCheck();var old=claimOne();
        db.update("UPDATE check_definition SET lease_until=now()-interval '1 second' WHERE id=?",id);
        db.update("UPDATE probe_host_lease SET lease_until=now()-interval '1 second'");db.update("UPDATE probe_slot SET lease_until=now()-interval '1 second'");due(id);
        var retry=claimOne();assertThat(retry.executionId()).isEqualTo(old.executionId());assertThat(retry.leaseToken()).isNotEqualTo(old.leaseToken());
        var now=java.time.Instant.now();assertThat(store.complete(old,now,now,observation("DOWN"))).isFalse();
        assertThat(store.complete(retry,now,now,observation("DOWN"))).isTrue();assertThat(store.complete(retry,now,now,observation("DOWN"))).isFalse();
        assertThat(db.queryForObject("SELECT count(*) FROM check_run",Integer.class)).isEqualTo(1);
        assertThat(store.claim(1)).isEmpty();
    }
    @Test void perTargetRateLimitSurvivesDisableAndReenable()throws Exception{
        UUID id=activeCheck();var task=claimOne();var now=java.time.Instant.now();store.complete(task,now,now,observation("UP"));
        assertThat(request("PUT",checks()+"/"+id+"/state",admin,Map.of("configVersion",2,"state","DISABLED","reason","Reconfigure")).statusCode()).isEqualTo(200);
        assertThat(request("PUT",checks()+"/"+id+"/state",admin,Map.of("configVersion",3,"state","ACTIVE","reason","Enable")).statusCode()).isEqualTo(200);
        assertThat(store.claim(4)).isEmpty();
    }
    @Test void pauseInterruptsRecoveryAndResumeNeedsFreshSamples()throws Exception{
        UUID id=activeCheck();for(int n=0;n<3;n++)sample(id,"DOWN");UUID incident=db.queryForObject("SELECT id FROM incident",UUID.class);operate();
        transition(incident,operator,0,"ACK");transition(incident,operator,1,"INVESTIGATE");sample(id,"UP");sample(id,"UP");
        assertThat(db.queryForObject("SELECT status FROM incident",String.class)).isEqualTo("MONITORING");
        assertThat(request("PUT",checks()+"/"+id+"/state",admin,Map.of("configVersion",2,"state","PAUSED","reason","Maintenance")).statusCode()).isEqualTo(200);
        assertThat(db.queryForObject("SELECT status FROM incident",String.class)).isEqualTo("INVESTIGATING");
        assertThat(db.queryForObject("SELECT recovered_at IS NULL FROM incident",Boolean.class)).isTrue();
        request("PUT",checks()+"/"+id+"/state",admin,Map.of("configVersion",3,"state","ACTIVE","reason","Resume"));
        sample(id,"UP");assertThat(db.queryForObject("SELECT status FROM incident",String.class)).isEqualTo("INVESTIGATING");
        sample(id,"UP");assertThat(db.queryForObject("SELECT status FROM incident",String.class)).isEqualTo("MONITORING");
    }
    @Test void aNewCheckCannotJoinFrozenIncidentByActivation()throws Exception{
        UUID disabled=UUID.fromString(create().path("id").asText());incident();
        assertThat(request("PUT",checks()+"/"+disabled+"/state",admin,Map.of("configVersion",1,"state","ACTIVE","reason","Start")).statusCode()).isEqualTo(409);
    }
    @Test void everyActiveCheckMustRecoverAndFailureDoesNotDuplicateIncident()throws Exception{
        UUID first=activeCheck(),second=activeCheck();
        // Select exactly one definition for each synthetic observation; no real network is used.
        db.update("UPDATE check_definition SET next_run_at=now()+interval '1 day' WHERE id=?",second);
        for(int n=0;n<3;n++)sample(first,"DOWN");
        for(int n=0;n<3;n++)sample(second,"DOWN");
        assertThat(db.queryForObject("SELECT count(*) FROM incident",Integer.class)).isEqualTo(1);
        sample(first,"UP");sample(first,"UP");assertThat(db.queryForObject("SELECT recovered_at IS NULL FROM incident",Boolean.class)).isTrue();
        sample(second,"UP");sample(second,"UP");assertThat(db.queryForObject("SELECT recovered_at IS NOT NULL FROM incident",Boolean.class)).isTrue();
    }
    @Test void concurrentClaimHasOneOwnerAndFourGlobalSlotsBoundTheRate()throws Exception{
        activeCheck();
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var a=pool.submit(()->store.claim(4));var b=pool.submit(()->store.claim(4));assertThat(a.get().size()+b.get().size()).isEqualTo(1);
        }
        db.update("UPDATE check_definition SET state='DISABLED'");db.update("UPDATE probe_slot SET lease_token=NULL");db.execute("TRUNCATE probe_host_lease");db.update("UPDATE probe_budget SET claimed=0,window_started=now()");
        for(int n=0;n<5;n++){
            UUID t=UUID.randomUUID();db.update("INSERT INTO service_target(id,code,name,environment,base_url,created_by) VALUES(?,?,?,'test',?,?)",t,"limit-"+n,"Limit", "https://host-"+n+".example.com/health",ADMIN);
            db.update("INSERT INTO check_definition(id,target_id,method,interval_seconds,timeout_ms,expected_statuses,fail_threshold,recover_threshold,state) VALUES(?,?,'GET',60,3000,ARRAY[200],3,2,'ACTIVE')",UUID.randomUUID(),t);
        }
        var batch=store.claim(4);assertThat(batch).hasSize(4);assertThat(store.claim(4)).isEmpty();
        var now=java.time.Instant.now();for(var task:batch)store.complete(task,now,now,observation("UP"));
        // Keep the fixed global window live deterministically while testing budget exhaustion.
        db.update("UPDATE probe_budget SET window_started=now()");assertThat(store.claim(4)).isEmpty();
        db.update("UPDATE probe_budget SET window_started=now()-interval '2 seconds'");assertThat(store.claim(4)).hasSize(1);
    }
    @Test void observationAuditFailureRollsBackResultCountersAndIncident()throws Exception{
        UUID id=activeCheck();sample(id,"DOWN");sample(id,"DOWN");due(id);var task=claimOne();var now=java.time.Instant.now();
        db.execute("CREATE FUNCTION reject_system_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action='INCIDENT_OPENED' THEN RAISE EXCEPTION 'synthetic audit rejection'; END IF; RETURN NEW; END $$");
        db.execute("CREATE TRIGGER reject_system_audit BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION reject_system_audit()");
        try{
            org.assertj.core.api.Assertions.assertThatThrownBy(()->store.complete(task,now,now,observation("DOWN"))).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(db.queryForObject("SELECT count(*) FROM check_run",Integer.class)).isEqualTo(2);
            assertThat(db.queryForObject("SELECT count(*) FROM incident",Integer.class)).isZero();
            assertThat(db.queryForObject("SELECT failure_count FROM check_definition WHERE id=?",Integer.class,id)).isEqualTo(2);
        }finally{db.execute("DROP TRIGGER reject_system_audit ON audit_event");db.execute("DROP FUNCTION reject_system_audit()");}
        assertThat(store.complete(task,now,now,observation("DOWN"))).isTrue();
    }
    @Test void privateAndHttpTargetsCanBeRegisteredAtomicallyWithExplicitApproval()throws Exception{
        var input=Map.of("code","private-demo","name","Private fixture","environment","test","baseUrl","http://private.example.test/health","allowHttp",true,"allowedPrivateCidrs",List.of("10.20.1.0/24"),"networkReason","Approved private health endpoint");
        var created=request("POST","/targets",admin,input,"private-target");assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        var target=json.readTree(created.body());assertThat(target.path("networkPolicy").asText()).isEqualTo("APPROVED_PRIVATE_CIDR");
        assertThat(request("POST","/targets",admin,input,"private-target").body()).isEqualTo(created.body());
        assertThat(db.queryForObject("SELECT count(*) FROM target_network_policy WHERE target_id=?",Integer.class,UUID.fromString(target.path("id").asText()))).isEqualTo(1);
        var forbidden=new HashMap<String,Object>(input);forbidden.put("code","blocked-demo");forbidden.put("allowedPrivateCidrs",List.of("10.0.0.0/8"));
        assertThat(request("POST","/targets",admin,forbidden,"blocked-target").statusCode()).isEqualTo(400);
        assertThat(db.queryForObject("SELECT count(*) FROM service_target WHERE code='blocked-demo'",Integer.class)).isZero();
    }
    @Test void scheduledDispatchRevalidatesDnsAndPreservesUnknownObservation()throws Exception{
        UUID id=activeCheck();var lookups=new java.util.concurrent.atomic.AtomicInteger();var requests=new java.util.concurrent.atomic.AtomicInteger();
        var policy=new dev.agentops.targets.TargetUrlPolicy(h->new java.net.InetAddress[]{java.net.InetAddress.getByName(lookups.getAndIncrement()==0?"93.184.216.34":"127.0.0.1")});
        try(var fake=new dev.agentops.monitoring.SafeHttpProbe(){@Override public dev.agentops.monitoring.ProbeResult execute(dev.agentops.targets.TargetUrlPolicy.Endpoint e,String m,List<Integer> s,int t){requests.incrementAndGet();return observation("DOWN");}};
            var scheduler=new dev.agentops.monitoring.MonitoringScheduler(store,policy,fake)){
            scheduler.ready();scheduler.tick();org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(()->assertThat(db.queryForObject("SELECT count(*) FROM check_run",Integer.class)).isEqualTo(1));
            due(id);scheduler.tick();org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(()->assertThat(db.queryForObject("SELECT count(*) FROM check_run",Integer.class)).isEqualTo(2));
            assertThat(requests).hasValue(1);assertThat(lookups).hasValue(2);
            assertThat(db.queryForObject("SELECT count(*) FROM check_run WHERE outcome='UNKNOWN' AND error_code='TARGET_URL_BLOCKED'",Integer.class)).isEqualTo(1);
            assertThat(db.queryForObject("SELECT count(*) FROM incident",Integer.class)).isZero();
        }
    }
    @Test void overviewAndGrantListRespectResourceScopeAndStaleEvidence()throws Exception{
        var overview=request("GET","/overview",reader,null);assertThat(overview.statusCode()).isEqualTo(200);
        assertThat(json.readTree(overview.body()).path("totalTargets").asInt()).isEqualTo(1);
        assertThat(json.readTree(request("GET","/overview",operator,null).body()).path("totalTargets").asInt()).isZero();
        UUID check=activeCheck();sample(check,"UP");
        assertThat(json.readTree(request("GET","/overview",reader,null).body()).path("up").asInt()).isEqualTo(1);
        db.update("UPDATE check_run SET started_at=started_at-interval '1 hour',completed_at=completed_at-interval '1 hour'");
        assertThat(json.readTree(request("GET","/overview",reader,null).body()).path("unknown").asInt()).isEqualTo(1);
        assertThat(request("GET","/targets/"+TARGET+"/access",reader,null).statusCode()).isEqualTo(403);
        var access=request("GET","/targets/"+TARGET+"/access",admin,null);assertThat(access.statusCode()).isEqualTo(200);
        assertThat(json.readTree(access.body()).path("items").get(0).path("userId").asText()).isEqualTo(READER.toString());
        assertThat(json.readTree(request("GET","/targets/"+TARGET+"/access?userId="+OPERATOR,admin,null).body()).path("items").size()).isZero();
    }
    @Test void timelinePreservesInsertionOrderWhenEventsHaveIdenticalTimestamps()throws Exception{
        UUID check=activeCheck();for(int n=0;n<3;n++)sample(check,"DOWN");UUID id=db.queryForObject("SELECT id FROM incident",UUID.class);operate();
        transition(id,operator,0,"ACK");transition(id,operator,1,"INVESTIGATE");sample(check,"UP");sample(check,"UP");
        db.update("UPDATE incident_event SET created_at='2030-01-01T00:00:00Z',id='ffffffff-ffff-ffff-ffff-ffffffffffff' WHERE event_type='RECOVERY_DETECTED'");
        db.update("UPDATE incident_event SET created_at='2030-01-01T00:00:00Z',id='00000000-0000-0000-0000-000000000000' WHERE event_type='INCIDENT_MONITORING'");
        var events=json.readTree(request("GET","/incidents/"+id+"/events",reader,null).body()).path("items");
        assertThat(events.get(0).path("eventType").asText()).isEqualTo("INCIDENT_MONITORING");assertThat(events.get(1).path("eventType").asText()).isEqualTo("RECOVERY_DETECTED");
    }
}

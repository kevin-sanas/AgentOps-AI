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
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"agentops.monitoring.enabled=false","agentops.kuma.enabled=false"})
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@org.springframework.context.annotation.Import(KumaApiTest.QueueFixture.class)
class KumaApiTest {
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    static class QueueFixture {
        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary
        dev.agentops.integrations.KumaDeliveryStore fixtureDeliveryStore(JdbcTemplate db){return new dev.agentops.integrations.KumaDeliveryStore(db,true);}
    }
    static final EmbeddedPostgres PG=PostgresFixture.start();
    static final UUID ADMIN=UUID.randomUUID(),READER=UUID.randomUUID(),TARGET=UUID.randomUUID();
    final JsonMapper json=new JsonMapper();final HttpClient http=HttpClient.newHttpClient();
    @LocalServerPort int port;@Autowired JdbcTemplate db;
    @Autowired dev.agentops.integrations.KumaDeliveryStore deliveryStore;
    String admin,reader,password;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",()->"jdbc:postgresql://localhost:"+PG.getPort()+"/postgres");
        r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"postgres");
        r.add("agentops.security.session-pepper",()->"fixture-pepper-"+TARGET);
        r.add("agentops.security.internal-token",()->"fixture-internal-"+TARGET);
        r.add("agentops.kuma.base-url",()->"https://kuma.example.test");
    }
    @AfterAll static void stop()throws Exception{PG.close();}
    @BeforeEach void seed()throws Exception{
        db.execute("TRUNCATE app_user CASCADE");password=UUID.randomUUID().toString();
        for(var user:Map.of(ADMIN,"admin",READER,"reader").entrySet()){
            db.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES(?,?,?,?)",user.getKey(),user.getValue(),new BCryptPasswordEncoder(4).encode(password),user.getValue());
            db.update("INSERT INTO user_role SELECT ?,id FROM app_role WHERE code=?",user.getKey(),user.getKey().equals(ADMIN)?"OPS_ADMIN":"OBSERVER");
        }
        db.update("INSERT INTO service_target(id,code,name,environment,base_url,created_by) VALUES(?,'fixture','Fixture','test','https://example.com/health',?)",TARGET,ADMIN);
        admin=login("admin");reader=login("reader");
    }
    HttpResponse<String> request(String method,String path,String token,Object body)throws Exception{
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path)).header("Content-Type","application/json");
        if(token!=null)b.header("Authorization","Bearer "+token);
        return http.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    String login(String user)throws Exception{return json.readTree(request("POST","/auth/login",null,Map.of("username",user,"password",password)).body()).path("token").asText();}
    Map<String,Object> config(){return Map.of("targetId",TARGET,"baseUrl","https://kuma.example.test","secretRef","env:KUMA_PUSH_TOKEN_FIXTURE","reason","Fixture configuration");}
    @Test void adminCreatesDisabledMappingWithoutReturningSecretReference()throws Exception{
        var result=request("POST","/integrations/uptime-kuma",admin,config());
        assertThat(result.statusCode()).as(result.body()).isEqualTo(201);
        var item=json.readTree(result.body());assertThat(item.path("enabled").asBoolean()).isFalse();
        assertThat(result.body()).doesNotContain("KUMA_PUSH_TOKEN_FIXTURE","secretRef");
        assertThat(request("GET","/integrations/uptime-kuma?targetId="+TARGET,admin,null).statusCode()).isEqualTo(200);
        assertThat(request("POST","/integrations/uptime-kuma",admin,config()).statusCode()).isEqualTo(409);
    }
    @Test void configurationAndDeliveriesAreAdminOnly()throws Exception{
        assertThat(request("POST","/integrations/uptime-kuma",reader,config()).statusCode()).isEqualTo(403);
        assertThat(request("GET","/integrations/uptime-kuma?targetId="+TARGET,reader,null).statusCode()).isEqualTo(403);
        assertThat(request("GET","/integrations/uptime-kuma?targetId="+TARGET,null,null).statusCode()).isEqualTo(401);
    }
    @Test void deploymentOriginAndReferenceAreStrictlyValidated()throws Exception{
        for(var value:List.of("https://other.example.test","http://kuma.example.test","https://kuma.example.test/path","https://kuma.example.test?token=bad")){
            var body=new HashMap<>(config());body.put("baseUrl",value);
            assertThat(request("POST","/integrations/uptime-kuma",admin,body).statusCode()).isEqualTo(400);
        }
        var bad=new HashMap<>(config());bad.put("secretRef","env:DB_PASSWORD");
        assertThat(request("POST","/integrations/uptime-kuma",admin,bad).statusCode()).isEqualTo(400);
    }
    @Test void enablingRequiresSmokeAndGlobalOffPreventsSmoke()throws Exception{
        var created=request("POST","/integrations/uptime-kuma",admin,config());assertThat(created.statusCode()).isEqualTo(201);
        String id=json.readTree(created.body()).path("id").asText();
        assertThat(request("PUT","/integrations/uptime-kuma/"+id,admin,Map.of("version",1,"enabled",true,"reason","Enable")).statusCode()).isEqualTo(409);
        assertThat(request("POST","/integrations/uptime-kuma/"+id+"/test",admin,Map.of("reason","Smoke")).statusCode()).isEqualTo(409);
    }
    UUID mapping()throws Exception{
        UUID id=UUID.fromString(json.readTree(request("POST","/integrations/uptime-kuma",admin,config()).body()).path("id").asText());
        db.update("UPDATE integration_config SET enabled=true,smoke_passed=true WHERE id=?",id);return id;
    }
    UUID sample(String outcome){
        UUID check=UUID.randomUUID(),run=UUID.randomUUID();
        db.update("INSERT INTO check_definition(id,target_id,method,interval_seconds,timeout_ms,expected_statuses,fail_threshold,recover_threshold,state) VALUES(?,?,'GET',60,3000,ARRAY[200],3,2,'ACTIVE')",check,TARGET);
        db.update("INSERT INTO check_run(id,check_definition_id,config_version,target_config_version,started_at,completed_at,scheduled_at,outcome,status_code,latency_ms,resolved_ips,redirect_decision) VALUES(?,?,1,1,now(),now(),now(),?,200,5,ARRAY['93.184.216.34'],'NOT_FOLLOWED')",run,check,outcome);return run;
    }
    @Test void pollerIsOffByDefaultAndNewestSampleReplacesPendingDelivery()throws Exception{
        mapping();UUID old=sample("DOWN");
        var off=new dev.agentops.integrations.KumaDeliveryStore(db,false);off.enqueueLatest();assertThat(off.claim()).isEmpty();
        assertThat(db.queryForObject("SELECT count(*) FROM integration_delivery",Integer.class)).isZero();
        var store=deliveryStore;store.enqueueLatest();store.enqueueLatest();
        assertThat(db.queryForObject("SELECT count(*) FROM integration_delivery",Integer.class)).isEqualTo(1);
        UUID latest=sample("UP");store.enqueueLatest();var task=store.claim().orElseThrow();
        assertThat(task.checkRunId()).isEqualTo(latest);assertThat(task.outcome()).isEqualTo("UP");assertThat(store.claim()).isEmpty();
        assertThat(db.queryForObject("SELECT state FROM integration_delivery WHERE check_run_id=?",String.class,old)).isEqualTo("SUPERSEDED");
        assertThat(store.complete(task,new dev.agentops.integrations.UptimeKumaPushAdapter.Result(true,200,null,false))).isTrue();
    }
    @Test void expiredWorkerCannotCommitAndRestartRecoversSameDelivery()throws Exception{
        UUID id=mapping();sample("DOWN");var store=deliveryStore;store.enqueueLatest();var old=store.claim().orElseThrow();
        db.update("UPDATE integration_config SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",id);
        var restarted=deliveryStore;var current=restarted.claim().orElseThrow();
        assertThat(current.deliveryId()).isEqualTo(old.deliveryId());
        var success=new dev.agentops.integrations.UptimeKumaPushAdapter.Result(true,200,null,false);
        assertThat(store.complete(old,success)).isFalse();assertThat(restarted.complete(current,success)).isTrue();
        assertThat(db.queryForObject("SELECT count(*) FROM integration_delivery_attempt",Integer.class)).isEqualTo(1);
    }
    @Test void unavailableKumaRetainsChecksAndIncidentAndUnknownNeverPushesUp()throws Exception{
        mapping();sample("DOWN");UUID incident=UUID.randomUUID();db.update("INSERT INTO incident(id,target_id,summary,last_failure_at) VALUES(?,?,'Fixture down',now())",incident,TARGET);
        var store=deliveryStore;store.enqueueLatest();var task=store.claim().orElseThrow();
        assertThat(store.complete(task,new dev.agentops.integrations.UptimeKumaPushAdapter.Result(false,503,"KUMA_PUSH_UNAVAILABLE",true))).isTrue();
        assertThat(db.queryForObject("SELECT status FROM incident WHERE id=?",String.class,incident)).isEqualTo("OPEN");
        assertThat(db.queryForObject("SELECT count(*) FROM check_run",Integer.class)).isEqualTo(1);
        sample("UNKNOWN");store.enqueueLatest();assertThat(store.claim()).isEmpty();
        assertThat(db.queryForObject("SELECT count(*) FROM integration_delivery WHERE state='PENDING'",Integer.class)).isZero();
    }
    @Test void versionConflictDisableAndAuditFailureAreAtomic()throws Exception{
        UUID id=mapping();sample("UP");var store=deliveryStore;store.enqueueLatest();var task=store.claim().orElseThrow();
        assertThat(request("PUT","/integrations/uptime-kuma/"+id,admin,Map.of("version",9,"enabled",false,"reason","stale")).statusCode()).isEqualTo(409);
        assertThat(request("PUT","/integrations/uptime-kuma/"+id,admin,Map.of("version",1,"enabled",false,"reason","Stop")).statusCode()).isEqualTo(200);
        assertThat(store.complete(task,new dev.agentops.integrations.UptimeKumaPushAdapter.Result(true,200,null,false))).isFalse();
        assertThat(request("GET","/integrations/uptime-kuma/"+id+"/deliveries?size=101",admin,null).statusCode()).isEqualTo(400);
        db.execute("ALTER TABLE audit_event ADD CONSTRAINT g5_fixture_failure CHECK(action<>'KUMA_STATE_CHANGED') NOT VALID");
        try{
            assertThat(request("PUT","/integrations/uptime-kuma/"+id,admin,Map.of("version",2,"enabled",false,"reason","Fail audit")).statusCode()).isEqualTo(409);
            assertThat(db.queryForObject("SELECT version FROM integration_config WHERE id=?",Integer.class,id)).isEqualTo(2);
        }finally{db.execute("ALTER TABLE audit_event DROP CONSTRAINT g5_fixture_failure");}
    }
    @Test void failedOldDownCannotBeClaimedAfterNewUpWasDelivered()throws Exception{
        mapping();sample("DOWN");var store=deliveryStore;store.enqueueLatest();var down=store.claim().orElseThrow();
        UUID latest=sample("UP");store.enqueueLatest();
        store.complete(down,new dev.agentops.integrations.UptimeKumaPushAdapter.Result(false,503,"KUMA_PUSH_UNAVAILABLE",true));
        var up=store.claim().orElseThrow();assertThat(up.checkRunId()).isEqualTo(latest);
        store.enqueueLatest(); // polling while UP holds the integration lease
        store.complete(up,new dev.agentops.integrations.UptimeKumaPushAdapter.Result(true,200,null,false));
        db.update("UPDATE integration_delivery SET available_at=clock_timestamp()-interval '1 second' WHERE id=?",down.deliveryId());
        assertThat(store.claim()).as("claim must recheck latest sample without depending on another poll").isEmpty();
    }
    @Autowired org.springframework.context.ApplicationContext context;
    @Test void kumaAndCoreDnsHaveIndependentBoundedWorkers(){
        assertThat(context.containsBean("kumaDns")).isTrue();
        assertThat(context.getBean("kumaDns")).isNotSameAs(context.getBean("safeDns"));
    }
}

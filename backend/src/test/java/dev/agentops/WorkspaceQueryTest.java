package dev.agentops;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.net.*;
import java.net.http.*;
import java.time.Instant;
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

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="agentops.monitoring.enabled=false")
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class WorkspaceQueryTest {
    static final EmbeddedPostgres PG=PostgresFixture.start();
    static final UUID ADMIN=UUID.randomUUID(),READER=UUID.randomUUID(),OPERATOR=UUID.randomUUID(),VISIBLE=UUID.randomUUID(),HIDDEN=UUID.randomUUID();
    @LocalServerPort int port;@Autowired JdbcTemplate db;
    final JsonMapper json=new JsonMapper();final HttpClient http=HttpClient.newHttpClient();
    String admin,reader,operator,password;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",()->"jdbc:postgresql://localhost:"+PG.getPort()+"/postgres");r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"postgres");
        r.add("agentops.security.session-pepper",()->"fixture-pepper-"+VISIBLE);r.add("agentops.security.internal-token",()->"fixture-internal-"+VISIBLE);
    }
    @AfterAll static void stop()throws Exception{PG.close();}
    @BeforeEach void seed()throws Exception{
        db.execute("TRUNCATE app_user CASCADE");password=UUID.randomUUID().toString();
        for(var entry:Map.of(ADMIN,"admin",READER,"reader",OPERATOR,"operator").entrySet()){
            db.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES(?,?,?,?)",entry.getKey(),entry.getValue(),new BCryptPasswordEncoder(4).encode(password),entry.getValue());
            db.update("INSERT INTO user_role SELECT ?,id FROM app_role WHERE code=?",entry.getKey(),entry.getKey().equals(ADMIN)?"OPS_ADMIN":entry.getKey().equals(READER)?"OBSERVER":"ON_CALL");
        }
        for(var entry:Map.of(VISIBLE,"Alpha 100% ready",HIDDEN,"Secret hidden fixture").entrySet()){
            db.update("INSERT INTO service_target(id,code,name,environment,base_url,created_by) VALUES(?,?,?,'test','https://example.com/health',?)",entry.getKey(),"fixture-"+entry.getKey(),entry.getValue(),ADMIN);
            UUID incident=UUID.randomUUID();db.update("INSERT INTO incident(id,target_id,summary,last_failure_at,opened_at) VALUES(?,?,?,now(),now()-interval '30 minutes')",incident,entry.getKey(),entry.getValue()+" incident");
            db.update("INSERT INTO runbook(id,target_id,title,draft,created_by) VALUES(?,?,?,'Synthetic instructions',?)",UUID.randomUUID(),entry.getKey(),entry.getValue()+" handbook",ADMIN);
            UUID run=UUID.randomUUID();
            db.update("INSERT INTO agent_run(id,incident_id,target_id,requested_by,request_id,graph_version,input) VALUES(?,?,?,?,'fixture','readonly-review-v2','{}')",run,incident,entry.getKey(),OPERATOR);
            db.update("INSERT INTO outbox_event(id,run_id,type) VALUES(?,?,'AGENT_RUN_REQUESTED')",UUID.randomUUID(),run);
        }
        for(UUID user:List.of(READER,OPERATOR))db.update("INSERT INTO target_access VALUES(?,?,true,false)",VISIBLE,user);
        admin=login("admin");reader=login("reader");operator=login("operator");
    }
    HttpResponse<String> request(String method,String path,String token,Object body)throws Exception{
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path)).header("Content-Type","application/json");if(token!=null)b.header("Authorization","Bearer "+token);
        return http.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    String login(String name)throws Exception{return json.readTree(request("POST","/auth/login",null,Map.of("username",name,"password",password)).body()).path("token").asText();}
    JsonNode get(String path,String token)throws Exception{var r=request("GET","/workspace"+path,token,null);assertThat(r.statusCode()).as(r.body()).isEqualTo(200);return json.readTree(r.body());}
    @Test void searchIsGlobalAcrossAuthorizedResourcesAndLiteralPercent()throws Exception{
        var data=get("/search?q=Alpha",reader);assertThat(data.path("items").size()).isEqualTo(3);assertThat(data.toString()).doesNotContain(HIDDEN.toString(),"Secret hidden");
        assertThat(get("/search?q="+URLEncoder.encode("100%",java.nio.charset.StandardCharsets.UTF_8),reader).path("items").size()).isEqualTo(3);
        assertThat(get("/search?q=Secret",reader).path("items").size()).isZero();assertThat(get("/search?q=Secret",admin).path("items").size()).isEqualTo(3);
        assertThat(get("/search?q=Alpha",operator).path("items").size()).isEqualTo(4);
    }
    @Test void serverFilteringSortingAndPaginationShareTheSameAclAndCount()throws Exception{
        var targets=get("/targets?q=fixture&sort=name_asc&size=1",admin);assertThat(targets.path("total").asInt()).isEqualTo(2);assertThat(targets.path("items").get(0).path("id").asText()).isEqualTo(VISIBLE.toString());
        assertThat(get("/targets?sort=name_asc&page=1&size=1",admin).path("items").get(0).path("id").asText()).isEqualTo(HIDDEN.toString());
        assertThat(get("/targets?q=Secret",reader).path("total").asInt()).isZero();assertThat(get("/runbooks?q=Alpha",reader).path("total").asInt()).isEqualTo(1);
    }
    @Test void taskIndexNeverExpandsReportPermissionsForAdminOrObserver()throws Exception{
        assertThat(get("/agent-runs",reader).path("total").asInt()).isZero();
        assertThat(get("/agent-runs",admin).path("total").asInt()).isZero();
        var data=get("/agent-runs?status=QUEUED",operator);assertThat(data.path("total").asInt()).isEqualTo(1);assertThat(data.toString()).doesNotContain(HIDDEN.toString());
    }
    String range(){return "from="+Instant.now().minusSeconds(3600)+"&to="+Instant.now();}
    @Test void timeFilteringAndEventAggregationUseAllVisibleRows()throws Exception{
        db.update("INSERT INTO incident(id,target_id,summary,last_failure_at,opened_at,status) VALUES(?,?,'Old fixture',now()-interval '3 days',now()-interval '3 days','CLOSED')",UUID.randomUUID(),VISIBLE);
        assertThat(get("/incidents?"+range(),reader).path("total").asInt()).isEqualTo(1);
        assertThat(get("/incidents?status=CLOSED",reader).path("total").asInt()).isEqualTo(1);
        assertThat(get("/activity?"+range(),reader).path("total").asInt()).isEqualTo(1);
        assertThat(get("/activity?"+range(),admin).path("total").asInt()).isEqualTo(2);
    }
    @Test void anonymousInvalidSortPaginationAndTimeRangesAreRejected()throws Exception{
        assertThat(request("GET","/workspace/search?q=Alpha",null,null).statusCode()).isEqualTo(401);
        for(String path:List.of("/targets?sort=unsupported","/incidents?size=101","/agent-runs?status=INVALID","/search?q=a","/incidents?from=2026-10-21T00:00:00Z&to=2026-10-20T00:00:00Z","/activity?from=2026-01-01T00:00:00Z&to=2026-10-01T00:00:00Z"))assertThat(request("GET","/workspace"+path,reader,null).statusCode()).as(path).isEqualTo(400);
    }
}

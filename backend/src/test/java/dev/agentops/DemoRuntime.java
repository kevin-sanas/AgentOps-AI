package dev.agentops;

import dev.agentops.access.Caller;
import dev.agentops.monitoring.*;
import dev.agentops.runbooks.*;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import tools.jackson.databind.json.JsonMapper;

/** Opt-in disposable demonstration. Never uses a configured database or probes an external target. */
public final class DemoRuntime {
    final JsonMapper json=new JsonMapper();
    final UUID target=UUID.randomUUID(),check=UUID.randomUUID(),admin=UUID.randomUUID(),observer=UUID.randomUUID();
    final Path directory;JdbcTemplate db;MonitoringStore store;String mode="UP";
    DemoRuntime(Path directory){this.directory=directory;}
    public static void main(String[] ignored)throws Exception{
        Path root=Path.of("..").toAbsolutePath().normalize(),base=root.resolve(".cache/demo");
        Path directory=Path.of(System.getProperty("agentops.demo.directory")).toAbsolutePath().normalize();
        if(!directory.startsWith(base)||directory.equals(base)||Files.exists(directory.resolve("postgres")))throw new IllegalArgumentException("Use a new project .cache/demo session directory");
        var parent=ProcessHandle.of(Long.parseLong(System.getProperty("agentops.demo.parent"))).orElseThrow();
        if(!parent.isAlive())throw new IllegalStateException("Demo parent is not running");
        System.setProperty("java.io.tmpdir",root.resolve("backend/target/tmp").toString());
        new DemoRuntime(directory).run(parent);
    }
    void run(ProcessHandle parent)throws Exception{
        boolean live=Boolean.getBoolean("agentops.demo.deepseek");
        String modelName=live?"deepseek-flash":"mock";
        if(live&&(System.getenv("AGENT_LLM_API_KEY")==null||System.getenv("AGENT_LLM_API_KEY").isBlank()))throw new IllegalArgumentException("LIVE_MODEL_KEY_REQUIRED");
        String token=UUID.randomUUID()+"-"+UUID.randomUUID(),adminPassword=UUID.randomUUID().toString(),observerPassword=UUID.randomUUID().toString();
        Instant expires=Instant.now().plusSeconds(7200);
        try(var pg=EmbeddedPostgres.builder().setDataDirectory(directory.resolve("postgres")).setCleanDataDirectory(true).setPort(0).start();
            var python=live?PythonFixture.startLiveDeepSeek(pg,token,System.getenv("AGENT_LLM_API_KEY"),directory):PythonFixture.start(pg,token);
            var context=(ServletWebServerApplicationContext)SpringApplication.run(AgentOpsApplication.class,
                "--server.port=0","--server.address=127.0.0.1","--spring.main.banner-mode=off","--logging.level.root=WARN",
                "--spring.datasource.url=jdbc:postgresql://localhost:"+pg.getPort()+"/postgres","--spring.datasource.username=postgres","--spring.datasource.password=postgres",
                "--agentops.security.session-pepper="+UUID.randomUUID()+UUID.randomUUID(),"--agentops.security.internal-token="+token,
                "--agentops.bootstrap.username=","--agentops.bootstrap.password=","--agentops.monitoring.enabled=false","--agentops.kuma.enabled=false",
                "--agentops.agent.enabled=true","--agentops.agent.model-name="+modelName,"--agentops.agent.origin=http://127.0.0.1:"+python.port)){
            db=context.getBean(JdbcTemplate.class);store=context.getBean(MonitoringStore.class);
            seed(context,adminPassword,observerPassword,modelName);sample("UP");
            if(live){mode="DOWN";for(int n=0;n<3;n++)sample(mode);}
            write("ready.json",Map.of("schemaVersion",1,"demo",true,"backendOrigin","http://127.0.0.1:"+context.getWebServer().getPort(),"expiresAt",expires.toString(),"targetId",target,"checkId",check,
                "admin",Map.of("username","demo-admin","password",adminPassword),"observer",Map.of("username","demo-observer","password",observerPassword),"modelName",modelName));
            System.out.println("DEMO_READY (temporary credentials are in the ignored session manifest)");
            long next=System.nanoTime()+Duration.ofSeconds(60).toNanos();
            while(parent.isAlive()&&!Files.exists(directory.resolve("stop"))&&Instant.now().isBefore(expires)){
                if(Files.exists(directory.resolve("command.json")))command();
                if(System.nanoTime()>=next){try{sample(mode);}catch(Exception e){System.out.println("DEMO_SAMPLE_SKIPPED: check configuration may have changed");}next=System.nanoTime()+Duration.ofSeconds(60).toNanos();}
                Thread.sleep(100);
            }
        }finally{
            for(String file:List.of("ready.json","ready.json.tmp","command.json","reply.json","reply.json.tmp","stop"))Files.deleteIfExists(directory.resolve(file));
        }
    }
    void seed(ServletWebServerApplicationContext context,String adminPassword,String observerPassword,String modelName){
        var encoder=new BCryptPasswordEncoder(4);
        db.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES(?,'demo-admin',?,'演示管理员'),(?,'demo-observer',?,'演示观察员')",admin,encoder.encode(adminPassword),observer,encoder.encode(observerPassword));
        for(String role:List.of("OPS_ADMIN","ON_CALL"))db.update("INSERT INTO user_role SELECT ?,id FROM app_role WHERE code=?",admin,role);
        db.update("INSERT INTO user_role SELECT ?,id FROM app_role WHERE code='OBSERVER'",observer);
        db.update("INSERT INTO service_target(id,code,name,environment,base_url,owner_team,created_by) VALUES(?,'interactive-demo','交互式故障验收服务','demo','https://demo.agentops.example.test/health','演示环境',?)",target,admin);
        db.update("INSERT INTO target_access VALUES(?,?,true,true),(?,?,true,false)",target,admin,target,observer);
        db.update("INSERT INTO check_definition(id,target_id,method,interval_seconds,timeout_ms,expected_statuses,fail_threshold,recover_threshold,state) VALUES(?,?,'GET',60,3000,ARRAY[200],3,2,'ACTIVE')",check,target);
        var caller=new Caller(admin,UUID.randomUUID(),"demo-admin","演示管理员",Set.of("OPS_ADMIN","ON_CALL"));
        var books=context.getBean(RunbookService.class);
        var book=books.create(caller,target,new RunbookController.Create("交互式演示验收手册","# 合成检查证据\n此目标不发送外部探测。终端down注入三条503，up注入四条200。\n## 人工流程\n确认事故、开始排查、发起"+modelName+"诊断；批准后如实回填未执行。恢复证据不会代替人工确认；健康检查不能单独确认根因。"),UUID.randomUUID().toString(),"demo-setup");
        books.publish(caller,book.id(),new RunbookController.Publish(book.revision(),"发布独立演示手册"),"demo-setup");
    }
    void command()throws Exception{
        String requestId="invalid";
        try{
            var command=json.readTree(Files.readString(directory.resolve("command.json")));Files.delete(directory.resolve("command.json"));
            requestId=UUID.fromString(command.path("requestId").asText()).toString();String action=command.path("action").asText();int inserted=0;
            if(!Set.of("up","down","status").contains(action))throw new IllegalArgumentException("Unknown command");
            if(!action.equals("status")){String nextMode=action.equals("down")?"DOWN":"UP";inserted=nextMode.equals("DOWN")?3:4;for(int n=0;n<inserted;n++)sample(nextMode);mode=nextMode;}
            var incident=db.queryForList("SELECT status FROM incident WHERE target_id=? ORDER BY opened_at DESC,id LIMIT 1",String.class,target);
            write("reply.json",Map.of("requestId",requestId,"status","PASS","mode",mode,"inserted",inserted,"incidentStatus",incident.isEmpty()?"NONE":incident.getFirst()));
        }catch(Exception error){Files.deleteIfExists(directory.resolve("command.json"));write("reply.json",Map.of("requestId",requestId,"status","FAIL","code","DEMO_CHECK_UNAVAILABLE"));}
    }
    void sample(String value){
        db.update("UPDATE check_definition SET next_run_at=now()+interval '1 day' WHERE id<>?",check);
        db.update("UPDATE check_definition SET next_run_at=now()-interval '1 second' WHERE id=?",check);
        db.update("UPDATE service_target SET last_probe_started_at=now()-interval '120 seconds' WHERE id=?",target);
        db.update("UPDATE probe_budget SET window_started=now()-interval '2 seconds',claimed=0");
        var tasks=store.claim(1);if(tasks.isEmpty()||!tasks.getFirst().checkId().equals(check))throw new IllegalStateException("Demo check is unavailable");
        var now=Instant.now();if(!store.complete(tasks.getFirst(),now,now,new ProbeResult(value,value.equals("UP")?200:503,12,value.equals("UP")?null:"UNEXPECTED_STATUS",List.of("93.184.216.34"),"NOT_FOLLOWED")))throw new IllegalStateException("Demo sample was rejected");
    }
    void write(String name,Object value)throws Exception{
        Path temporary=directory.resolve(name+".tmp"),destination=directory.resolve(name);Files.writeString(temporary,json.writeValueAsString(value));
        Files.move(temporary,destination,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }
}

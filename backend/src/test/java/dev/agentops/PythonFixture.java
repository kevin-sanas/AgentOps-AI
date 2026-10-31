package dev.agentops;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Owns one Python process and a dedicated restricted database in an already disposable PG fixture. */
final class PythonFixture implements AutoCloseable {
    final int port;private final Process child;private final Path log;
    private PythonFixture(int port,Process child,Path log){this.port=port;this.child=child;this.log=log;}
    static PythonFixture start(EmbeddedPostgres pg,String token)throws Exception{
        return start(pg,token,Map.of());
    }
    static PythonFixture start(EmbeddedPostgres pg,String token,Map<String,String> modelEnvironment)throws Exception{
        return start(pg,token,modelEnvironment,"agentops_agent.app:from_environment");
    }
    static PythonFixture startLiveDeepSeek(EmbeddedPostgres pg,String token,Path directory)throws Exception{
        return start(pg,token,Map.of("DAWNWATCH_LIVE_DIRECTORY",directory.toString()),"deepseek_demo:from_environment");
    }
    private static PythonFixture start(EmbeddedPostgres pg,String token,Map<String,String> modelEnvironment,String factory)throws Exception{
        var db=new JdbcTemplate(new DriverManagerDataSource("jdbc:postgresql://localhost:"+pg.getPort()+"/postgres","postgres","postgres"));
        String suffix=UUID.randomUUID().toString().replace("-",""),name="agent_"+suffix,password=UUID.randomUUID().toString();
        db.execute("CREATE ROLE "+name+" LOGIN PASSWORD '"+password+"' NOSUPERUSER NOCREATEDB NOCREATEROLE");db.execute("CREATE DATABASE "+name+" OWNER "+name);
        int port;try(var socket=new java.net.ServerSocket(0,50,InetAddress.getByName("127.0.0.1"))){port=socket.getLocalPort();}
        Path root=Path.of("..").toAbsolutePath().normalize(),log=root.resolve("backend/target/tmp/python-"+suffix+".log");
        var process=new ProcessBuilder(AgentIntegrationTest.python(root).toString(),"-m","uvicorn",factory,"--factory","--host","127.0.0.1","--port",String.valueOf(port),"--no-access-log","--log-level","warning").directory(root.resolve("agent-service").toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        var env=process.environment();env.put("AGENT_CHECKPOINT_DSN","postgresql://"+name+":"+password+"@127.0.0.1:"+pg.getPort()+"/"+name);env.put("AGENT_INTERNAL_TOKEN",token);env.put("LANGSMITH_TRACING","false");env.put("LANGCHAIN_TRACING_V2","false");env.put("LANGCHAIN_TRACING","false");
        env.put("TEMP",root.resolve("agent-service/.tmp").toString());env.put("TMP",env.get("TEMP"));env.putAll(modelEnvironment);
        PythonFixture fixture=new PythonFixture(port,process.start(),log);
        try{var http=HttpClient.newHttpClient();long deadline=System.nanoTime()+Duration.ofSeconds(25).toNanos();
            while(System.nanoTime()<deadline&&fixture.child.isAlive()){
                try{var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/internal/v1/runs/"+UUID.randomUUID())).header("Authorization","Bearer "+token).header("X-Request-Id",UUID.randomUUID().toString()).timeout(Duration.ofSeconds(1)).build();if(http.send(request,HttpResponse.BodyHandlers.discarding()).statusCode()==404)return fixture;}catch(java.io.IOException ignored){}
                Thread.sleep(100);
            }
            throw new IllegalStateException("Python fixture did not start: "+Files.readString(log));
        }catch(Exception e){fixture.close();throw e;}
    }
    @Override public void close()throws Exception{
        // Windows venv python.exe is a launcher: stop its owned descendants before the launcher exits.
        var descendants=child.descendants().toList();descendants.reversed().forEach(ProcessHandle::destroy);
        child.destroy();if(!child.waitFor(5,java.util.concurrent.TimeUnit.SECONDS)){child.destroyForcibly();child.waitFor(5,java.util.concurrent.TimeUnit.SECONDS);}
        for(var process:descendants)if(process.isAlive()){process.destroyForcibly();process.onExit().get(5,java.util.concurrent.TimeUnit.SECONDS);}
        for(int n=0;;n++)try{Files.deleteIfExists(log);break;}catch(java.nio.file.FileSystemException e){if(n>=10)throw e;Thread.sleep(100);}
    }
}

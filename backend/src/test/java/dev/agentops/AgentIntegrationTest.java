package dev.agentops;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

class AgentIntegrationTest {
    @Test void pythonUsesRealPostgresCheckpointsWithRestrictedRoleAndSurvivesRestart()throws Exception{
        try(var pg=PostgresFixture.start()){
            var db=new JdbcTemplate(new DriverManagerDataSource("jdbc:postgresql://localhost:"+pg.getPort()+"/postgres","postgres","postgres"));
            String password=UUID.randomUUID().toString();
            db.execute("CREATE ROLE checkpoint_fixture LOGIN PASSWORD '"+password+"' NOSUPERUSER NOCREATEDB NOCREATEROLE");
            db.execute("CREATE DATABASE checkpoint_fixture OWNER checkpoint_fixture");db.execute("CREATE TABLE app_user(id uuid)");
            Path root=Path.of("..").toAbsolutePath().normalize();
            Path python=python(root);
            var builder=new ProcessBuilder(python.toString(),"-m","pytest","tests/test_postgres.py","-q","-p","no:cacheprovider").directory(root.resolve("agent-service").toFile()).redirectErrorStream(true);
            var env=builder.environment();env.put("AGENT_TEST_DSN","postgresql://checkpoint_fixture:"+password+"@127.0.0.1:"+pg.getPort()+"/checkpoint_fixture");env.put("AGENT_TEST_BUSINESS_DSN","postgresql://checkpoint_fixture:"+password+"@127.0.0.1:"+pg.getPort()+"/postgres");
            env.put("TEMP",root.resolve("agent-service/.tmp").toString());env.put("TMP",env.get("TEMP"));
            Path log=root.resolve("backend/target/python-integration.log");builder.redirectOutput(log.toFile());
            Process child=builder.start();try{
                assertThat(child.waitFor(90,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(child.exitValue()).as(Files.readString(log)).isZero();
            }finally{if(child.isAlive()){child.destroyForcibly();child.waitFor(5,java.util.concurrent.TimeUnit.SECONDS);}}
        }
    }
    static Path python(Path root){
        String configured=System.getenv("AGENT_TEST_PYTHON");
        Path python=configured!=null?Path.of(configured):root.resolve(System.getProperty("os.name").startsWith("Windows")?"agent-service/.venv/Scripts/python.exe":"agent-service/.venv/bin/python");
        assertThat(python).as("Install isolated agent-service test requirements or set AGENT_TEST_PYTHON").exists();return python;
    }
}

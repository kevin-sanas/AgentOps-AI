package dev.agentops.agent;
import com.sun.net.httpserver.HttpServer;
import dev.agentops.access.SecuritySettings;
import java.net.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class AgentClientTest {
    AgentOutbox.Task task(){UUID id=UUID.randomUUID(),target=UUID.randomUUID(),incident=UUID.randomUUID();var now=java.time.Instant.now();var input=new AgentContracts.Input(id,AgentContracts.GRAPH,new AgentContracts.Incident(incident,target,"OPEN",now),List.of(),List.of(),new AgentContracts.Constraints(List.of("get_check_history","get_published_runbook"),20),now);return new AgentOutbox.Task(UUID.randomUUID(),id,target,incident,UUID.randomUUID(),1,UUID.randomUUID().toString(),input);}
    AgentClient client(int port,long timeout){return new AgentClient(new SecuritySettings("test-pepper-0000000000000000000000","test-internal-00000000000000000000",Duration.ofMinutes(30)),new JsonMapper(),"http://127.0.0.1:"+port,Duration.ofMillis(timeout));}
    @Test void timeoutCoversBodyAfterHeadersAndReleasesSocket()throws Exception{
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",x->{try{x.sendResponseHeaders(200,0);x.getResponseBody().flush();Thread.sleep(700);x.getResponseBody().write("{}".getBytes());}catch(Exception ignored){}finally{x.close();}});server.start();
        try{var client=client(server.getAddress().getPort(),150);var task=task();long started=System.nanoTime();assertThatThrownBy(()->client.reconcile(task)).isInstanceOf(AgentClient.Failure.class).hasMessage("AGENT_UNAVAILABLE");assertThat(Duration.ofNanos(System.nanoTime()-started).toMillis()).isLessThan(600);}finally{server.stop(0);}
    }
    @Test void chunkedResponseCannotExceed64KiBAndRedirectsAreNotFollowed()throws Exception{
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",x->{try{x.sendResponseHeaders(200,0);x.getResponseBody().write(new byte[70000]);}catch(Exception ignored){}finally{x.close();}});server.start();
        try{assertThatThrownBy(()->client(server.getAddress().getPort(),3000).reconcile(task())).isInstanceOf(AgentClient.Failure.class).hasMessage("AGENT_SCHEMA_INVALID");}finally{server.stop(0);}
        var redirect=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);redirect.createContext("/",x->{x.getResponseHeaders().set("Location","http://127.0.0.1:1/secret");x.sendResponseHeaders(302,-1);x.close();});redirect.start();
        try{assertThatThrownBy(()->client(redirect.getAddress().getPort(),3000).reconcile(task())).hasMessage("AGENT_HTTP_302");}finally{redirect.stop(0);}
    }
    @Test void getAndReinvokeShareOneDeadlineRatherThanDoublingTheLeaseBudget()throws Exception{
        var original=task();var task=new AgentOutbox.Task(original.eventId(),original.runId(),original.targetId(),original.incidentId(),original.leaseToken(),2,original.requestId(),original.input());
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",x->{try{boolean post=x.getRequestMethod().equals("POST");Thread.sleep(post?250:220);byte[] body=("{\"runId\":\""+task.runId()+"\",\"graphVersion\":\"readonly-mock-v1\",\"status\":\""+(post?"COMPLETED":"RUNNING")+"\"}").getBytes();x.sendResponseHeaders(200,body.length);x.getResponseBody().write(body);}catch(Exception ignored){}finally{x.close();}});server.start();
        try{assertThatThrownBy(()->client(server.getAddress().getPort(),400).reconcile(task)).hasMessage("AGENT_UNAVAILABLE");}finally{server.stop(0);}
    }
    @Test void ambiguousModelResultIsTerminalAndHasAnActionableStableCode()throws Exception{
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",x->{byte[] body="{\"code\":\"MODEL_OUTCOME_UNKNOWN\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);x.sendResponseHeaders(409,body.length);x.getResponseBody().write(body);x.close();});server.start();
        try{
            assertThatThrownBy(()->client(server.getAddress().getPort(),3000).reconcile(task())).isInstanceOfSatisfying(AgentClient.Failure.class,e->{assertThat(e.code).isEqualTo("AGENT_MODEL_OUTCOME_UNKNOWN");assertThat(e.retryable).isFalse();});
        }finally{server.stop(0);}
    }
    @Test void credentialsCannotBeSentToNonLoopbackPlainHttp(){
        var settings=new SecuritySettings("test-pepper-0000000000000000000000","test-internal-00000000000000000000",Duration.ofMinutes(30));
        assertThatThrownBy(()->new dev.agentops.model.ModelProviderClient(settings,new JsonMapper(),"http://example.com",Duration.ofSeconds(1)))
            .isInstanceOf(IllegalStateException.class).hasMessage("Invalid agent service origin");
    }
}

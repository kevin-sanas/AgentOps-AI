package dev.agentops.agent;

import dev.agentops.access.SecuritySettings;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.List;
import java.nio.ByteBuffer;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class AgentClient {
    private final HttpClient http;private final URI origin;private final String token;private final JsonMapper json;private final Duration timeout;
    @org.springframework.beans.factory.annotation.Autowired
    public AgentClient(SecuritySettings settings,JsonMapper json,@Value("${agentops.agent.origin:http://127.0.0.1:8090}") String origin){this(settings,json,origin,Duration.ofSeconds(60));}
    AgentClient(SecuritySettings settings,JsonMapper json,String origin,Duration timeout){
        this.timeout=timeout;
        this.token=settings.internalToken();this.json=json;this.origin=validatedOrigin(origin);
        http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).proxy(new java.net.ProxySelector(){public java.util.List<java.net.Proxy> select(URI uri){return java.util.List.of(java.net.Proxy.NO_PROXY);}public void connectFailed(URI uri,java.net.SocketAddress address,java.io.IOException error){}}).build();
    }
    public static URI validatedOrigin(String value){
        URI origin=URI.create(value);String host=origin.getHost();
        boolean loopback=host!=null&&Set.of("127.0.0.1","localhost","::1","[::1]").contains(host.toLowerCase(java.util.Locale.ROOT));
        if(!Set.of("http","https").contains(origin.getScheme())||host==null||origin.getRawUserInfo()!=null||origin.getRawQuery()!=null||origin.getRawFragment()!=null||!(origin.getPath().isEmpty()||origin.getPath().equals("/"))||("http".equals(origin.getScheme())&&!loopback))throw new IllegalStateException("Invalid agent service origin");
        return origin;
    }
    public record Reply(String status,JsonNode result){}
    public static final class Failure extends RuntimeException {public final String code;public final boolean retryable;public Failure(String code,boolean retryable){super(code);this.code=code;this.retryable=retryable;}}
    public Reply reconcile(AgentOutbox.Task task){
        long deadline=System.nanoTime()+timeout.toNanos();
        // Query before resending an ambiguous previous request: Python checkpoints are authoritative for graph progress.
        if(task.attempt()>1){var existing=exchange(task,false,deadline);if(existing!=null&&terminal(task,existing.status()))return existing;}
        return exchange(task,true,deadline);
    }
    private boolean terminal(AgentOutbox.Task task,String status){return switch(task.phase()){
        case "RESUME"->status.equals("APPROVED_FOR_MANUAL")||status.equals("REJECTED");
        case "CANCEL"->status.equals("CANCELLED");
        default->status.equals("COMPLETED")||status.equals("WAITING_HUMAN");
    };}
    private Reply exchange(AgentOutbox.Task task,boolean invoke,long deadline){
        long remaining=deadline-System.nanoTime();if(remaining<=0)throw new Failure("AGENT_UNAVAILABLE",true);
        String action=switch(task.phase()){case "RESUME"->"resume";case "CANCEL"->"cancel";default->"invoke";};
        URI uri=origin.resolve("/internal/v1/runs/"+task.runId()+(invoke?"/"+action:""));
        var request=HttpRequest.newBuilder(uri).timeout(Duration.ofNanos(remaining)).header("Authorization","Bearer "+token).header("X-Request-Id",task.requestId()).header("Content-Type","application/json");
        if(invoke)request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(task.phase().equals("INVOKE")?task.input():task.payload()),StandardCharsets.UTF_8));else request.GET();
        CompletableFuture<HttpResponse<byte[]>> pending=null;
        try{
            pending=http.sendAsync(request.build(),info->new LimitedBody());
            var response=pending.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
                if(!invoke&&response.statusCode()==404)return null;
                if(response.statusCode()==409&&"MODEL_OUTCOME_UNKNOWN".equals(json.readTree(response.body()).path("code").asText()))throw new Failure("AGENT_MODEL_OUTCOME_UNKNOWN",false);
                if(response.statusCode()!=200&&response.statusCode()!=202)throw new Failure("AGENT_HTTP_"+response.statusCode(),response.statusCode()==429||response.statusCode()>=500);
                byte[] bytes=response.body();
                JsonNode body=json.readTree(bytes);
                if(!task.runId().toString().equals(body.path("runId").asText())||!task.input().graphVersion().equals(body.path("graphVersion").asText()))throw new Failure("AGENT_SCHEMA_INVALID",false);
                String status=body.path("status").asText();if(!Set.of("RUNNING","COMPLETED","WAITING_HUMAN","APPROVED_FOR_MANUAL","REJECTED","CANCELLED","FAILED").contains(status))throw new Failure("AGENT_SCHEMA_INVALID",false);
                if("RESUME".equals(task.phase())&&terminal(task,status)&&!("APPROVED".equals(task.payload().path("decision").asText())?"APPROVED_FOR_MANUAL":"REJECTED").equals(status))throw new Failure("AGENT_SCHEMA_INVALID",false);
                if(invoke&&!status.equals("RUNNING")&&!terminal(task,status))throw new Failure("AGENT_SCHEMA_INVALID",false);
                return new Reply(status,body.path("result"));
        }catch(Failure failure){throw failure;}catch(InterruptedException e){Thread.currentThread().interrupt();throw new Failure("AGENT_INTERRUPTED",true);}catch(TimeoutException e){throw new Failure("AGENT_UNAVAILABLE",true);}catch(ExecutionException e){
            Throwable cause=e;while(cause.getCause()!=null)cause=cause.getCause();
            throw new Failure(cause instanceof TooLarge?"AGENT_SCHEMA_INVALID":"AGENT_UNAVAILABLE",!(cause instanceof TooLarge));
        }catch(RuntimeException e){throw new Failure("AGENT_SCHEMA_INVALID",false);}finally{if(pending!=null&&!pending.isDone())pending.cancel(true);}
    }
    private static final class TooLarge extends RuntimeException{}
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();private Flow.Subscription subscription;private int size;
        public CompletionStage<byte[]> getBody(){return delegate.getBody();}
        public void onSubscribe(Flow.Subscription s){subscription=s;delegate.onSubscribe(s);}
        public void onNext(List<ByteBuffer> buffers){for(var b:buffers){size+=b.remaining();if(size>65536){subscription.cancel();delegate.onError(new TooLarge());return;}}delegate.onNext(buffers);}
        public void onError(Throwable error){delegate.onError(error);}
        public void onComplete(){delegate.onComplete();}
    }
}

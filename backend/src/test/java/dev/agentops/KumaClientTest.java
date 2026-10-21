package dev.agentops;

import com.sun.net.httpserver.HttpServer;
import dev.agentops.integrations.*;
import dev.agentops.targets.TargetUrlPolicy;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class KumaClientTest {
    @Test void uninterruptibleKumaDnsDoesNotConsumeCoreDnsWorkers()throws Exception{
        var entered=new java.util.concurrent.CountDownLatch(2);var release=new java.util.concurrent.CountDownLatch(1);
        try(var kuma=new dev.agentops.targets.SafeDns(host->{entered.countDown();while(release.getCount()>0){try{release.await();}catch(InterruptedException ignored){}}return new InetAddress[]{InetAddress.getByName("10.20.1.4")};});
            var core=new dev.agentops.targets.SafeDns(host->new InetAddress[]{InetAddress.getByName("93.184.216.34")});
            var callers=java.util.concurrent.Executors.newFixedThreadPool(2)){
            for(int n=0;n<2;n++)callers.submit(()->{try{kuma.resolve("kuma.example.test");}catch(Exception ignored){}});
            try{assertThat(entered.await(3,java.util.concurrent.TimeUnit.SECONDS)).isTrue();assertThat(core.resolve("core.example.test")[0].getHostAddress()).isEqualTo("93.184.216.34");}
            finally{release.countDown();}
        }
    }
    TargetUrlPolicy.Endpoint endpoint(HttpServer server)throws Exception{return new TargetUrlPolicy.Endpoint(URI.create("http://kuma.example.test:"+server.getAddress().getPort()),new InetAddress[]{InetAddress.getByName("127.0.0.1")});}
    @Test void pushUsesPostPinnedDnsFixedMessageAndOnlyApprovedFields()throws Exception{
        var calls=new AtomicInteger();var queries=new ArrayList<String>();var methods=new ArrayList<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/push/fixture-token",e->{calls.incrementAndGet();queries.add(e.getRequestURI().getQuery());methods.add(e.getRequestMethod());assertThat(e.getRequestHeaders().getFirst("Authorization")).isNull();byte[] body="{\"ok\":true}".getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();});server.start();
        try(var adapter=new UptimeKumaPushAdapter(new JsonMapper(),2000)){
            UUID delivery=UUID.randomUUID();
            assertThat(adapter.push(endpoint(server),"fixture-token","UP",17,delivery).delivered()).isTrue();
            assertThat(adapter.push(endpoint(server),"fixture-token","DOWN",null,delivery).delivered()).isTrue();
            assertThat(queries).allMatch(q->q.contains("msg=AgentOps")&&!q.contains("target="));
            assertThat(queries.getFirst()).contains("status=up","ping=17");assertThat(queries.getLast()).contains("status=down").doesNotContain("ping=");
            assertThat(calls.get()).isEqualTo(2);assertThat(methods).containsOnly("POST");
        }finally{server.stop(0);}
    }
    @Test void failuresAreStableAndNeverExposeRemoteBodyOrToken()throws Exception{
        var status=new AtomicInteger(401);var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",e->{byte[] body="{\"ok\":false,\"msg\":\"fixture-token secret path\"}".getBytes();e.sendResponseHeaders(status.get(),body.length);e.getResponseBody().write(body);e.close();});server.start();
        try(var adapter=new UptimeKumaPushAdapter(new JsonMapper(),2000)){
            var invalid=adapter.push(endpoint(server),"fixture-token","UP",1,UUID.randomUUID());
            assertThat(invalid.delivered()).isFalse();assertThat(invalid.retryable()).isFalse();assertThat(invalid.errorCode()).isEqualTo("KUMA_TOKEN_REJECTED");assertThat(invalid.toString()).doesNotContain("fixture-token","secret path");
            status.set(503);assertThat(adapter.push(endpoint(server),"fixture-token","DOWN",1,UUID.randomUUID()).retryable()).isTrue();
            status.set(404);var rejected=adapter.push(endpoint(server),"fixture-token","DOWN",1,UUID.randomUUID());
            assertThat(rejected.errorCode()).isEqualTo("KUMA_PUSH_REJECTED");assertThat(rejected.retryable()).isTrue();
            status.set(200);assertThat(adapter.push(endpoint(server),"fixture-token","UP",1,UUID.randomUUID()).errorCode()).isEqualTo("KUMA_RESPONSE_INVALID");
        }finally{server.stop(0);}
    }
    @Test void redirectAndOversizedResponsesAreRejectedWithoutFollowing()throws Exception{
        var requests=new AtomicInteger();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/push/fixture-token",e->{requests.incrementAndGet();e.getResponseHeaders().add("Location","/would-leak-token");e.sendResponseHeaders(302,-1);e.close();});
        server.createContext("/would-leak-token",e->{requests.incrementAndGet();e.sendResponseHeaders(200,-1);e.close();});
        server.createContext("/api/push/oversized-token",e->{e.sendResponseHeaders(200,0);try{e.getResponseBody().write(new byte[65537]);}catch(Exception ignored){}finally{e.close();}});server.start();
        try(var adapter=new UptimeKumaPushAdapter(new JsonMapper(),2000)){
            assertThat(adapter.push(endpoint(server),"fixture-token","UP",0,UUID.randomUUID()).errorCode()).isEqualTo("KUMA_REDIRECT_BLOCKED");assertThat(requests.get()).isEqualTo(1);
            assertThat(adapter.push(endpoint(server),"oversized-token","UP",0,UUID.randomUUID()).errorCode()).isEqualTo("KUMA_RESPONSE_TOO_LARGE");
        }finally{server.stop(0);}
    }
    @Test void absoluteDeadlineBoundsContinuousResponse()throws Exception{
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",e->{e.sendResponseHeaders(200,0);try{for(int n=0;n<50;n++){e.getResponseBody().write(' ');e.getResponseBody().flush();Thread.sleep(40);}}catch(Exception ignored){}finally{e.close();}});server.start();
        try(var adapter=new UptimeKumaPushAdapter(new JsonMapper(),250)){
            long start=System.nanoTime();assertThat(adapter.push(endpoint(server),"fixture-token","UP",0,UUID.randomUUID()).errorCode()).isEqualTo("KUMA_TIMEOUT");assertThat((System.nanoTime()-start)/1_000_000).isLessThan(1500);
        }finally{server.stop(0);}
    }
    @Test void deploymentOriginDoesNotPermitMixedDnsMetadataOrRebinding()throws Exception{
        var address=new java.util.concurrent.atomic.AtomicReference<>("10.20.1.4");
        var policy=new KumaEndpointPolicy("http://kuma.example.test:3001",true,List.of("10.20.0.0/16"),h->new InetAddress[]{InetAddress.getByName(address.get())});
        assertThat(policy.resolve("http://kuma.example.test:3001").uri().getPort()).isEqualTo(3001);
        for(String blocked:List.of("127.0.0.1","169.254.169.254","10.21.1.4","::1")){
            address.set(blocked);assertThatThrownBy(()->policy.resolve("http://kuma.example.test:3001")).hasMessage("TARGET_URL_BLOCKED");
        }
        var mixed=new KumaEndpointPolicy("https://kuma.example.test",false,List.of(),h->new InetAddress[]{InetAddress.getByName("93.184.216.34"),InetAddress.getByName("127.0.0.1")});
        assertThatThrownBy(()->mixed.resolve("https://kuma.example.test")).hasMessage("TARGET_URL_BLOCKED");
    }
}

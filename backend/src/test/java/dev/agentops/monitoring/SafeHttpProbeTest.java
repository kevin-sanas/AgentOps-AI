package dev.agentops.monitoring;

import com.sun.net.httpserver.HttpServer;
import dev.agentops.targets.TargetUrlPolicy;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

class SafeHttpProbeTest {
    HttpServer server;SafeHttpProbe probe;int port;java.util.concurrent.ExecutorService executor;
    @BeforeEach void start()throws Exception{
        server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);port=server.getAddress().getPort();
        executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();server.setExecutor(executor);server.start();probe=new SafeHttpProbe();
    }
    @AfterEach void close(){probe.close();server.stop(0);executor.close();}
    // Transport fixture intentionally bypasses policy. Separate policy tests prove loopback can never be approved.
    TargetUrlPolicy.Endpoint endpoint(String path)throws Exception{return new TargetUrlPolicy.Endpoint(URI.create("http://pinned.example.invalid:"+port+path),new InetAddress[]{InetAddress.getLoopbackAddress()});}
    @Test void pinnedAddressReachesOnlyFixtureAndPreservesOriginalHostWithoutCredentials()throws Exception{
        server.createContext("/health",exchange->{
            assertThat(exchange.getRequestHeaders().getFirst("Host")).isEqualTo("pinned.example.invalid:"+port);
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();assertThat(exchange.getRequestHeaders().getFirst("Cookie")).isNull();
            byte[] secret="fixture-body-that-must-not-be-retained".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,secret.length);exchange.getResponseBody().write(secret);exchange.close();
        });
        var result=probe.execute(endpoint("/health"),"GET",List.of(200),1000);
        assertThat(result.outcome()).isEqualTo("UP");assertThat(result.statusCode()).isEqualTo(200);assertThat(result.errorCode()).isNull();
        assertThat(result.toString()).doesNotContain("fixture-body-that-must-not-be-retained");
    }
    @Test void redirectsNeverFollowAndCannotBeAcceptedAsHealthy()throws Exception{
        AtomicInteger followed=new AtomicInteger();
        server.createContext("/redirect",e->{e.getResponseHeaders().add("Location","http://127.0.0.1:"+port+"/forbidden");e.sendResponseHeaders(302,-1);e.close();});
        server.createContext("/forbidden",e->{followed.incrementAndGet();e.sendResponseHeaders(200,-1);e.close();});
        var result=probe.execute(endpoint("/redirect"),"GET",List.of(302),1000);
        assertThat(result.outcome()).isEqualTo("DOWN");assertThat(result.errorCode()).isEqualTo("REDIRECT_BLOCKED");assertThat(followed).hasValue(0);
    }
    @Test void oversizedBodyStopsAtBoundAndClassifiesWithoutResponseText()throws Exception{
        server.createContext("/large",e->{e.sendResponseHeaders(200,65537);try{e.getResponseBody().write(new byte[65537]);}finally{e.close();}});
        assertThat(probe.execute(endpoint("/large"),"GET",List.of(200),1000).errorCode()).isEqualTo("RESPONSE_TOO_LARGE");
    }
    @Test void wholeRequestDeadlineBoundsSlowResponses()throws Exception{
        server.createContext("/slow",e->{try{Thread.sleep(1000);e.sendResponseHeaders(200,-1);}catch(Exception ignored){}finally{e.close();}});
        long start=System.nanoTime();var result=probe.execute(endpoint("/slow"),"GET",List.of(200),100);
        assertThat(result.errorCode()).isEqualTo("PROBE_TIMEOUT");assertThat((System.nanoTime()-start)/1_000_000).isLessThan(800);
    }
    @Test void chunkedBodiesAreBoundedEvenWithoutContentLength()throws Exception{
        server.createContext("/chunked",e->{e.sendResponseHeaders(200,0);try{e.getResponseBody().write(new byte[70000]);}catch(java.io.IOException ignored){}finally{e.close();}});
        assertThat(probe.execute(endpoint("/chunked"),"GET",List.of(200),1000).errorCode()).isEqualTo("RESPONSE_TOO_LARGE");
    }
    @Test void absoluteDeadlineStopsContinuouslyTricklingBody()throws Exception{
        server.createContext("/trickle",e->{e.sendResponseHeaders(200,0);try{for(int n=0;n<40;n++){e.getResponseBody().write(new byte[16]);e.getResponseBody().flush();Thread.sleep(40);}}catch(Exception ignored){}finally{e.close();}});
        long started=System.nanoTime();var result=probe.execute(endpoint("/trickle"),"GET",List.of(200),150);
        assertThat(result.errorCode()).isEqualTo("PROBE_TIMEOUT");assertThat((System.nanoTime()-started)/1_000_000).isLessThan(1000);
    }
    @Test void headUsesExpectedStatusWithoutReadingABody()throws Exception{
        server.createContext("/head",e->{assertThat(e.getRequestMethod()).isEqualTo("HEAD");e.sendResponseHeaders(204,-1);e.close();});
        assertThat(probe.execute(endpoint("/head"),"HEAD",List.of(204),1000).outcome()).isEqualTo("UP");
    }
}

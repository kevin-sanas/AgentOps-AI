package dev.agentops.monitoring;

import dev.agentops.targets.TargetUrlPolicy;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.net.*;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLContext;
import org.apache.hc.core5.ssl.SSLContexts;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.*;
import org.apache.hc.client5.http.impl.DefaultSchemePortResolver;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.*;
import org.apache.hc.client5.http.impl.routing.DefaultRoutePlanner;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.config.Http1Config;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;

@Component
public class SafeHttpProbe implements AutoCloseable {
    private final ScheduledThreadPoolExecutor deadlines=new ScheduledThreadPoolExecutor(1,Thread.ofPlatform().daemon(true).name("probe-deadline-",0).factory());
    private final SSLContext trust;
    public SafeHttpProbe(){this(SSLContexts.createDefault());}
    SafeHttpProbe(SSLContext trust){this.trust=trust;deadlines.setRemoveOnCancelPolicy(true);}
    public ProbeResult execute(TargetUrlPolicy.Endpoint endpoint,String method,List<Integer> statuses,int timeoutMs){
        if(!List.of("GET","HEAD").contains(method)||timeoutMs<100||timeoutMs>10000)throw new IllegalArgumentException("Invalid probe configuration");
        long started=System.nanoTime();
        var addresses=endpoint.addresses();if(addresses.length==0)throw new IllegalArgumentException("No approved address");
        List<String> ips=Arrays.stream(addresses).map(InetAddress::getHostAddress).toList();
        String host=endpoint.uri().getHost();var timeout=Timeout.ofMilliseconds(timeoutMs);
        DnsResolver pinned=new DnsResolver(){
            public InetAddress[] resolve(String name)throws UnknownHostException{if(!host.equalsIgnoreCase(name))throw new UnknownHostException("UNAPPROVED_HOST");return new InetAddress[]{addresses[0]};}
            public String resolveCanonicalHostname(String name)throws UnknownHostException{if(!host.equalsIgnoreCase(name))throw new UnknownHostException("UNAPPROVED_HOST");return host;}
        };
        var connection=PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(pinned)
            .setTlsSocketStrategy(ClientTlsStrategyBuilder.create().setSslContext(trust).buildClassic())
            .setConnectionFactory(ManagedHttpClientConnectionFactory.builder().http1Config(Http1Config.custom().setMaxHeaderCount(64).setMaxLineLength(8192).build()).build())
            .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(timeout).setSocketTimeout(timeout).build())
            .setMaxConnTotal(1).setMaxConnPerRoute(1).build();
        var request=new HttpUriRequestBase(method,endpoint.uri());AtomicBoolean expired=new AtomicBoolean();
        var deadline=deadlines.schedule(()->{expired.set(true);request.cancel();},timeoutMs,TimeUnit.MILLISECONDS);
        var client=HttpClients.custom().setConnectionManager(connection)
            .setRoutePlanner(new DefaultRoutePlanner(DefaultSchemePortResolver.INSTANCE))
            .setDefaultRequestConfig(RequestConfig.custom().setResponseTimeout(timeout).setConnectionRequestTimeout(timeout).setAuthenticationEnabled(false).build())
            .disableRedirectHandling().disableAutomaticRetries().disableCookieManagement().disableAuthCaching().disableContentCompression().setUserAgent("AgentOps-Health/0.1").build();
        ClassicHttpResponse response=null;Integer status=null;
        try{
            response=client.executeOpen(null,request,null);status=response.getCode();
            if(status>=300&&status<400)return result(started,"DOWN",status,"REDIRECT_BLOCKED",ips,"BLOCKED");
            var entity=response.getEntity();
            if(!method.equals("HEAD")&&entity!=null){
                if(entity.getContentLength()>65536)return result(started,"DOWN",status,"RESPONSE_TOO_LARGE",ips,"NOT_FOLLOWED");
                // Read one sentinel byte past the limit; cancel before closing so no unbounded drain occurs.
                InputStream body=entity.getContent();byte[] buffer=new byte[4096];int total=0,count;
                while((count=body.read(buffer,0,Math.min(buffer.length,65537-total)))!=-1){total+=count;if(total>65536)return result(started,"DOWN",status,"RESPONSE_TOO_LARGE",ips,"NOT_FOLLOWED");}
            }
            if(expired.get())return result(started,"DOWN",status,"PROBE_TIMEOUT",ips,"NOT_FOLLOWED");
            return result(started,statuses.contains(status)?"UP":"DOWN",status,statuses.contains(status)?null:"UNEXPECTED_STATUS",ips,"NOT_FOLLOWED");
        }catch(IOException e){
            String error=expired.get()||e instanceof java.net.SocketTimeoutException?"PROBE_TIMEOUT":e instanceof SSLException?"TLS_ERROR":"PROBE_IO_ERROR";
            return result(started,"DOWN",status,error,ips,"NOT_FOLLOWED");
        }finally{
            deadline.cancel(false);request.cancel();
            if(response!=null)try{response.close();}catch(IOException ignored){}
            client.close(CloseMode.IMMEDIATE);
        }
    }
    private ProbeResult result(long start,String outcome,Integer code,String error,List<String> ips,String redirect){return new ProbeResult(outcome,code,(int)Math.min(Integer.MAX_VALUE,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)),error,ips,redirect);}
    @PreDestroy public void close(){deadlines.shutdownNow();}
}

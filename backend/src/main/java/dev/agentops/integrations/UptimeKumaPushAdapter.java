package dev.agentops.integrations;

import dev.agentops.targets.TargetUrlPolicy;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.*;
import org.apache.hc.client5.http.impl.DefaultSchemePortResolver;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.*;
import org.apache.hc.client5.http.impl.routing.DefaultRoutePlanner;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.config.Http1Config;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** No URL, token, response body or exception message escapes this transport. */
@Component
public class UptimeKumaPushAdapter implements AutoCloseable {
    private final JsonMapper json;private final int timeoutMs;
    private final ScheduledThreadPoolExecutor deadlines=new ScheduledThreadPoolExecutor(1,Thread.ofPlatform().daemon(true).name("kuma-deadline-",0).factory());
    @Autowired public UptimeKumaPushAdapter(JsonMapper json){this(json,3000);}
    public UptimeKumaPushAdapter(JsonMapper json,int timeoutMs){this.json=json;this.timeoutMs=timeoutMs;deadlines.setRemoveOnCancelPolicy(true);}
    public record Result(boolean delivered,Integer httpStatus,String errorCode,boolean retryable){}
    public Result push(TargetUrlPolicy.Endpoint endpoint,String token,String outcome,Integer latency,UUID delivery){
        if(token==null||!token.matches("[A-Za-z0-9_-]{8,256}"))return new Result(false,null,"KUMA_SECRET_UNAVAILABLE",false);
        if(!List.of("UP","DOWN").contains(outcome))return new Result(false,null,"KUMA_OUTCOME_INVALID",false);
        String query="status="+outcome.toLowerCase(Locale.ROOT)+"&msg=Dawnwatch-"+delivery+(latency==null?"":"&ping="+Math.max(0,latency));
        URI uri=endpoint.uri().resolve("/api/push/"+token+"?"+query);String host=uri.getHost();var addresses=endpoint.addresses();
        DnsResolver pinned=new DnsResolver(){public InetAddress[] resolve(String name)throws UnknownHostException{if(!host.equalsIgnoreCase(name)||addresses.length==0)throw new UnknownHostException("UNAPPROVED_HOST");return new InetAddress[]{addresses[0]};}public String resolveCanonicalHostname(String name)throws UnknownHostException{if(!host.equalsIgnoreCase(name))throw new UnknownHostException("UNAPPROVED_HOST");return host;}};
        var timeout=Timeout.ofMilliseconds(timeoutMs);
        var connection=PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(pinned)
            .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(timeout).setSocketTimeout(timeout).build())
            .setConnectionFactory(ManagedHttpClientConnectionFactory.builder().http1Config(Http1Config.custom().setMaxLineLength(8192).setMaxHeaderCount(64).build()).build()).build();
        var request=new HttpUriRequestBase("POST",uri);var expired=new AtomicBoolean();
        var deadline=deadlines.schedule(()->{expired.set(true);request.cancel();},timeoutMs,TimeUnit.MILLISECONDS);
        var client=HttpClients.custom().setConnectionManager(connection).setRoutePlanner(new DefaultRoutePlanner(DefaultSchemePortResolver.INSTANCE))
            .setDefaultRequestConfig(RequestConfig.custom().setResponseTimeout(timeout).setConnectionRequestTimeout(timeout).setAuthenticationEnabled(false).build())
            .disableRedirectHandling().disableAutomaticRetries().disableCookieManagement().disableAuthCaching().disableContentCompression().setUserAgent("Dawnwatch-Kuma/0.1").build();
        ClassicHttpResponse response=null;Integer status=null;
        try{
            response=client.executeOpen(null,request,null);status=response.getCode();
            if(status>=300&&status<400)return new Result(false,status,"KUMA_REDIRECT_BLOCKED",false);
            if(status==401||status==403)return new Result(false,status,"KUMA_TOKEN_REJECTED",false);
            // Kuma 2.3.0's Push handler maps every processing exception to 404, not just invalid tokens.
            if(status==404)return new Result(false,status,"KUMA_PUSH_REJECTED",true);
            if(status!=200)return new Result(false,status,"KUMA_PUSH_UNAVAILABLE",status==429||status>=500);
            var entity=response.getEntity();if(entity==null)return new Result(false,status,"KUMA_RESPONSE_INVALID",false);
            if(entity.getContentLength()>65536)return new Result(false,status,"KUMA_RESPONSE_TOO_LARGE",false);
            var body=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int count;
            while((count=entity.getContent().read(buffer,0,Math.min(buffer.length,65537-body.size())))!=-1){body.write(buffer,0,count);if(body.size()>65536)return new Result(false,status,"KUMA_RESPONSE_TOO_LARGE",false);}
            if(expired.get())return new Result(false,status,"KUMA_TIMEOUT",true);
            try{var value=json.readTree(body.toByteArray());if(!value.isObject()||!value.path("ok").isBoolean()||!value.path("ok").asBoolean())return new Result(false,status,"KUMA_RESPONSE_INVALID",false);}
            catch(RuntimeException e){return new Result(false,status,"KUMA_RESPONSE_INVALID",false);}
            return new Result(true,status,null,false);
        }catch(IOException|RuntimeException e){return new Result(false,status,expired.get()||e instanceof java.net.SocketTimeoutException?"KUMA_TIMEOUT":"KUMA_PUSH_UNAVAILABLE",true);}
        finally{deadline.cancel(false);request.cancel();if(response!=null)try{response.close();}catch(IOException ignored){}client.close(CloseMode.IMMEDIATE);}
    }
    @PreDestroy public void close(){deadlines.shutdownNow();}
}

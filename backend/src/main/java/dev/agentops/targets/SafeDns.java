package dev.agentops.targets;

import jakarta.annotation.PreDestroy;
import java.net.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;

/** Bound even native DNS calls that ignore interruption: no unbounded threads/queue. */
@Component
public class SafeDns implements TargetUrlPolicy.Resolver,AutoCloseable {
    private final TargetUrlPolicy.Resolver resolver;
    public SafeDns(){this(InetAddress::getAllByName);}
    public SafeDns(TargetUrlPolicy.Resolver resolver){this.resolver=resolver;}
    private final ExecutorService workers=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),Thread.ofPlatform().daemon(true).name("target-dns-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
    public InetAddress[] resolve(String host)throws UnknownHostException{
        Future<InetAddress[]> task=null;
        try{task=workers.submit(()->resolver.resolve(host));return task.get(1,TimeUnit.SECONDS);}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new UnknownHostException("DNS_INTERRUPTED");}
        catch(ExecutionException|TimeoutException|RejectedExecutionException e){throw new UnknownHostException("DNS_UNAVAILABLE");}
        finally{if(task!=null&&!task.isDone())task.cancel(true);}
    }
    @PreDestroy public void close(){workers.shutdownNow();}
}

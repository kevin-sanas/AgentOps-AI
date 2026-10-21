package dev.agentops.monitoring;

import dev.agentops.targets.TargetUrlPolicy;
import dev.agentops.shared.ApiError;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(prefix="agentops.monitoring",name="enabled",havingValue="true",matchIfMissing=true)
public class MonitoringScheduler implements AutoCloseable {
    private static final Logger log=LoggerFactory.getLogger(MonitoringScheduler.class);
    private final MonitoringStore store;private final TargetUrlPolicy policy;private final SafeHttpProbe probe;
    private final Semaphore capacity=new Semaphore(4);
    private final ExecutorService workers=new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(4),Thread.ofPlatform().name("health-probe-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
    private volatile boolean ready,closing;private volatile Instant retryAfter=Instant.MIN;
    public MonitoringScheduler(MonitoringStore store,TargetUrlPolicy policy,SafeHttpProbe probe){this.store=store;this.policy=policy;this.probe=probe;}
    @EventListener(ApplicationReadyEvent.class) public void ready(){ready=true;}
    @Scheduled(fixedDelay=1000,initialDelay=1000) public synchronized void tick(){
        if(!ready||closing||Instant.now().isBefore(retryAfter))return;
        int available=capacity.drainPermits();if(available==0)return;
        List<MonitoringStore.Task> tasks;
        try{tasks=store.claim(available);}catch(RuntimeException e){capacity.release(available);retryAfter=Instant.now().plusSeconds(15);log.warn("Monitoring dispatch unavailable: {}",e.getClass().getSimpleName());return;}
        capacity.release(available-tasks.size());
        for(var task:tasks){
            try{workers.execute(()->run(task));}catch(RejectedExecutionException e){capacity.release();log.info("Monitoring task retained for lease recovery: {}",task.executionId());}
        }
    }
    private void run(MonitoringStore.Task task){
        try{
            Instant started=Instant.now();ProbeResult result;
            try{
                var endpoint=policy.resolve(task.baseUrl(),task.allowHttp(),task.privateCidrs());
                result=probe.execute(endpoint,task.definition().method(),task.definition().expectedStatuses(),task.definition().timeoutMs());
            }catch(ApiError e){result=new ProbeResult("UNKNOWN",null,0,e.code(),List.of(),"NOT_FOLLOWED");}
            catch(RuntimeException e){result=new ProbeResult("UNKNOWN",null,0,"PROBE_INTERNAL_ERROR",List.of(),"NOT_FOLLOWED");}
            store.complete(task,started,Instant.now(),result);
        }catch(RuntimeException e){log.warn("Monitoring result retained for lease recovery: {} ({})",task.executionId(),e.getClass().getSimpleName());}
        finally{capacity.release();}
    }
    @PreDestroy public void close(){
        closing=true;workers.shutdown();
        try{if(!workers.awaitTermination(12,TimeUnit.SECONDS))workers.shutdownNow();}
        catch(InterruptedException e){workers.shutdownNow();Thread.currentThread().interrupt();}
    }
}

package dev.agentops.integrations;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(prefix="agentops.kuma",name="enabled",havingValue="true")
public class KumaDispatcher {
    private static final Logger log=LoggerFactory.getLogger(KumaDispatcher.class);
    private final KumaDeliveryStore store;private final KumaEndpointPolicy policy;private final UptimeKumaPushAdapter adapter;private final Environment env;
    private final Semaphore capacity=new Semaphore(2);private final ExecutorService workers=Executors.newFixedThreadPool(2,Thread.ofPlatform().name("kuma-push-",0).factory());
    private volatile boolean closing;
    public KumaDispatcher(KumaDeliveryStore store,KumaEndpointPolicy policy,UptimeKumaPushAdapter adapter,Environment env){this.store=store;this.policy=policy;this.adapter=adapter;this.env=env;}
    @Scheduled(fixedDelay=3000,initialDelay=3000) public void tick(){
        if(closing)return;
        try{store.enqueueLatest();while(!closing&&capacity.tryAcquire()){
            java.util.Optional<KumaDeliveryStore.Task> task;
            try{task=store.claim();}catch(RuntimeException e){capacity.release();throw e;}
            if(task.isEmpty()){capacity.release();break;}
            try{workers.execute(()->deliver(task.get()));}catch(RejectedExecutionException e){capacity.release();}
        }}catch(RuntimeException e){log.warn("Kuma dispatch unavailable ({})",e.getClass().getSimpleName());}
    }
    private void deliver(KumaDeliveryStore.Task task){
        try{
            UptimeKumaPushAdapter.Result result;
            try{KumaEndpointPolicy.reference(task.secretRef());result=adapter.push(policy.resolve(task.baseUrl()),env.getProperty(task.secretRef().substring(4)),task.outcome(),task.latencyMs(),task.deliveryId());}
            catch(dev.agentops.shared.ApiError e){result=new UptimeKumaPushAdapter.Result(false,null,"KUMA_ORIGIN_BLOCKED",false);}
            catch(RuntimeException e){result=new UptimeKumaPushAdapter.Result(false,null,"KUMA_PUSH_UNAVAILABLE",true);}
            store.complete(task,result);
        }catch(RuntimeException e){log.warn("Kuma delivery retained for recovery: {} ({})",task.deliveryId(),e.getClass().getSimpleName());}
        finally{capacity.release();}
    }
    @PreDestroy public void close(){closing=true;workers.shutdown();try{if(!workers.awaitTermination(8,TimeUnit.SECONDS))workers.shutdownNow();}catch(InterruptedException e){Thread.currentThread().interrupt();workers.shutdownNow();}}
}

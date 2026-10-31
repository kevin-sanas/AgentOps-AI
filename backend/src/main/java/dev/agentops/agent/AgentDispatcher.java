package dev.agentops.agent;

import dev.agentops.model.ModelConfigurationStore;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;

@Component @EnableScheduling
@ConditionalOnProperty(name="agentops.agent.enabled",havingValue="true",matchIfMissing=false)
public class AgentDispatcher {
    private final AgentOutbox outbox;private final AgentClient client;private final ModelConfigurationStore configurations;
    private final Semaphore slots=new Semaphore(2);
    private final ExecutorService workers=Executors.newFixedThreadPool(2);
    public AgentDispatcher(AgentOutbox outbox,AgentClient client,ModelConfigurationStore configurations){this.outbox=outbox;this.client=client;this.configurations=configurations;}
    @org.springframework.beans.factory.annotation.Autowired ApprovalService approvals;
    @Scheduled(fixedDelay=3000) public void tick(){
        approvals.expireDue();
        for(int n=0;n<2&&slots.tryAcquire();n++){
            boolean submitted=false;
            try{var task=outbox.claim();if(task.isEmpty())return;workers.execute(()->{try{deliver(task.get());}finally{slots.release();}});submitted=true;}
            finally{if(!submitted)slots.release();}
        }
    }
    public void deliver(AgentOutbox.Task task){
        AgentClient.Reply reply=null;
        try{reply=client.reconcile(task,()->credential(task));switch(reply.status()){
            case "COMPLETED","WAITING_HUMAN","APPROVED_FOR_MANUAL","REJECTED","CANCELLED"->outbox.complete(task,reply.result(),reply.providerRequest());
            case "RUNNING"->outbox.pending(task,reply.providerRequest());
            default->outbox.fail(task,"AGENT_GRAPH_FAILED",false);
        }}catch(AgentClient.Failure e){outbox.fail(task,e.code,e.retryable,e.providerRequest);}catch(dev.agentops.shared.ApiError e){outbox.fail(task,"AGENT_SCHEMA_INVALID",false,reply==null?null:reply.providerRequest());}
    }
    private String credential(AgentOutbox.Task task){
        try{
            var saved=configurations.credential().orElseThrow(()->new AgentClient.Failure("MODEL_CONFIGURATION_REQUIRED",false));
            if(!ModelConfigurationStore.MODEL.equals(task.input().modelName())||!saved.model().equals(task.input().modelName()))throw new AgentClient.Failure("MODEL_CONFIGURATION_MISMATCH",false);
            return saved.apiKey();
        }catch(AgentClient.Failure failure){throw failure;}catch(IllegalStateException error){throw new AgentClient.Failure("MODEL_CONFIGURATION_UNAVAILABLE",false);}
    }
    @PreDestroy void close(){workers.shutdown();try{if(!workers.awaitTermination(65,TimeUnit.SECONDS))workers.shutdownNow();}catch(InterruptedException e){workers.shutdownNow();Thread.currentThread().interrupt();}}
}

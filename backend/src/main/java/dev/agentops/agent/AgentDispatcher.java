package dev.agentops.agent;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;

@Component @EnableScheduling
@ConditionalOnProperty(name="agentops.agent.enabled",havingValue="true",matchIfMissing=false)
public class AgentDispatcher {
    private final AgentOutbox outbox;private final AgentClient client;
    private final Semaphore slots=new Semaphore(2);
    private final ExecutorService workers=Executors.newFixedThreadPool(2);
    public AgentDispatcher(AgentOutbox outbox,AgentClient client){this.outbox=outbox;this.client=client;}
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
        try{var reply=client.reconcile(task);switch(reply.status()){
            case "COMPLETED","WAITING_HUMAN","APPROVED_FOR_MANUAL","REJECTED","CANCELLED"->outbox.complete(task,reply.result());
            case "RUNNING"->outbox.pending(task);
            default->outbox.fail(task,"AGENT_GRAPH_FAILED",false);
        }}catch(AgentClient.Failure e){outbox.fail(task,e.code,e.retryable);}catch(dev.agentops.shared.ApiError e){outbox.fail(task,"AGENT_SCHEMA_INVALID",false);}
    }
    @PreDestroy void close(){workers.shutdown();try{if(!workers.awaitTermination(65,TimeUnit.SECONDS))workers.shutdownNow();}catch(InterruptedException e){workers.shutdownNow();Thread.currentThread().interrupt();}}
}

package dev.agentops.monitoring;

import java.util.List;

public record ProbeResult(String outcome,Integer statusCode,int latencyMs,String errorCode,List<String> resolvedIps,String redirectDecision) {
    public ProbeResult {resolvedIps=List.copyOf(resolvedIps);}
}

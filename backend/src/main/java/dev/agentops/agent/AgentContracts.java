package dev.agentops.agent;

import dev.agentops.shared.ApiError;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The graph never chooses resources: both sides validate the same frozen evidence set. */
public final class AgentContracts {
    private AgentContracts(){}
    public static final String GRAPH="readonly-mock-v1";
    public static final String REVIEW_GRAPH="readonly-review-v2";
    public static final List<String> NODES=List.of("validate","context","retrieval","analyze","guard","finalize");
    public record Incident(UUID id,UUID targetId,String status,Instant openedAt){}
    public record Check(UUID runId,String outcome,Integer statusCode,Integer latencyMs,Instant completedAt,String errorCode){}
    public record Section(UUID runbookId,int version,UUID sectionId,String text,String hash){}
    public record Constraints(List<String> tools,int maxEvidence){}
    public record Input(UUID runId,String graphVersion,Incident incident,List<Check> checks,List<Section> runbooks,Constraints constraints,Instant capturedAt,String modelName){
        public Input{if(modelName==null)modelName="mock";}
        public Input(UUID id,String graph,Incident incident,List<Check> checks,List<Section> sections,Constraints limits,Instant at){this(id,graph,incident,checks,sections,limits,at,"mock");}
    }
    public record Hypothesis(String text,double confidence,List<String> evidenceIds){}
    public record NextStep(String text,String risk,Boolean requiresHumanReview){}
    public record Citation(String kind,UUID id,Instant time,Integer version,String hash){}
    public record Result(UUID runId,String status,String graphVersion,String modelName,String summary,List<Hypothesis> hypotheses,List<String> evidenceIds,List<NextStep> nextSteps,List<Citation> citations,List<String> uncertainties,List<String> steps){}
    private static void require(boolean condition){if(!condition)throw ApiError.badRequest("AGENT_SCHEMA_INVALID");}
    private static final java.util.regex.Pattern SENSITIVE=java.util.regex.Pattern.compile("(?is)-----BEGIN [A-Z ]*PRIVATE KEY-----|\\b(?:sk-[A-Za-z0-9_-]{12,}|Bearer\\s+[A-Za-z0-9._~+/-]{12,})|(?:password|passwd|api[_-]?key|access[_-]?token|secret)\\s*[:=]\\s*[^\\s,;\"']{4,}");
    private static boolean text(String value,int max){return value!=null&&!value.isBlank()&&value.length()<=max&&!SENSITIVE.matcher(value).find();}
    public static Result validate(JsonMapper json,Input input,JsonNode candidate){
        try{
            Result r=json.treeToValue(candidate,Result.class);
            require(r!=null&&input.runId().equals(r.runId())&&input.graphVersion().equals(r.graphVersion())&&input.modelName().equals(r.modelName())&&"COMPLETED".equals(r.status()));
            require(text(r.summary(),4000)&&NODES.equals(r.steps()));
            require(r.evidenceIds()!=null&&r.evidenceIds().size()<=25&&new HashSet<>(r.evidenceIds()).size()==r.evidenceIds().size());
            require(r.citations()!=null&&r.citations().size()<=25&&r.hypotheses()!=null&&r.hypotheses().size()<=10&&r.nextSteps()!=null&&r.nextSteps().size()<=10&&r.uncertainties()!=null&&!r.uncertainties().isEmpty()&&r.uncertainties().size()<=10);
            var checks=new HashMap<UUID,Check>();input.checks().forEach(c->checks.put(c.runId(),c));
            var sections=new HashMap<UUID,Section>();input.runbooks().forEach(s->sections.put(s.sectionId(),s));
            Set<String> refs=new HashSet<>();
            for(var cite:r.citations()){
                require(cite!=null&&cite.id()!=null);String ref;
                if("CHECK_RUN".equals(cite.kind())){var c=checks.get(cite.id());require(c!=null&&c.completedAt().equals(cite.time())&&cite.version()==null&&cite.hash()==null);ref="check:"+cite.id();}
                else{var s=sections.get(cite.id());require("RUNBOOK_SECTION".equals(cite.kind())&&s!=null&&Objects.equals(s.version(),cite.version())&&s.hash().equals(cite.hash())&&cite.time()==null);ref="runbook:"+cite.id();}
                require(refs.add(ref));
            }
            require(refs.equals(new HashSet<>(r.evidenceIds())));
            for(var h:r.hypotheses())require(h!=null&&text(h.text(),2000)&&Double.isFinite(h.confidence())&&h.confidence()>=0&&h.confidence()<=1&&h.evidenceIds()!=null&&!h.evidenceIds().isEmpty()&&h.evidenceIds().size()<=25&&refs.containsAll(h.evidenceIds()));
            for(var n:r.nextSteps()){
                require(n!=null&&text(n.text(),2000)&&Set.of("LOW","MEDIUM","HIGH").contains(n.risk())&&Boolean.TRUE.equals(n.requiresHumanReview()));
                boolean risky=java.util.regex.Pattern.compile("(?iu)\\b(delete|drop|truncate|restart|reboot|shutdown|kill|kubectl|chmod|sudo|rm\\s+-)\\b|删除|清空|重启|停机|停服|扩容|缩容|回滚|修改配置").matcher(n.text()).find();
                require(!risky||"HIGH".equals(n.risk()));
            }
            for(String u:r.uncertainties())require(text(u,2000));
            return r;
        }catch(ApiError e){throw e;}catch(RuntimeException e){throw ApiError.badRequest("AGENT_SCHEMA_INVALID");}
    }
}

package dev.agentops.workspace;

import dev.agentops.access.Caller;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/workspace")
@PreAuthorize("hasAnyRole('OPS_ADMIN','OBSERVER','ON_CALL','AUDITOR')")
public class WorkspaceController {
    private final WorkspaceQueries service;public WorkspaceController(WorkspaceQueries service){this.service=service;}
    @GetMapping("/search") Object search(@AuthenticationPrincipal Caller c,@RequestParam String q){return service.search(c,q);}
    @GetMapping("/targets") Object targets(@AuthenticationPrincipal Caller c,@RequestParam(required=false) String q,@RequestParam(required=false) String sort,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return service.targets(c,new WorkspaceQuery(q,null,null,null,sort,page,size));}
    @GetMapping("/incidents") Object incidents(@AuthenticationPrincipal Caller c,@RequestParam(required=false) String q,@RequestParam(required=false) String status,@RequestParam(required=false) Instant from,@RequestParam(required=false) Instant to,@RequestParam(required=false) String sort,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return service.incidents(c,new WorkspaceQuery(q,status,from,to,sort,page,size));}
    @GetMapping("/runbooks") Object books(@AuthenticationPrincipal Caller c,@RequestParam(required=false) String q,@RequestParam(required=false) UUID targetId,@RequestParam(required=false) String sort,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return service.books(c,new WorkspaceQuery(q,null,null,null,sort,page,size),targetId);}
    @GetMapping("/agent-runs") Object runs(@AuthenticationPrincipal Caller c,@RequestParam(required=false) String q,@RequestParam(required=false) String status,@RequestParam(required=false) Instant from,@RequestParam(required=false) Instant to,@RequestParam(required=false) String sort,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return service.runs(c,new WorkspaceQuery(q,status,from,to,sort,page,size));}
    @GetMapping("/activity") Object activity(@AuthenticationPrincipal Caller c,@RequestParam(required=false) Instant from,@RequestParam(required=false) Instant to){return service.activity(c,new WorkspaceQuery(null,null,from,to,null,0,1));}
}

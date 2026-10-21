package dev.agentops.runbooks;

import dev.agentops.access.Caller;
import dev.agentops.audit.AuditService;
import dev.agentops.shared.*;
import dev.agentops.targets.TargetService;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RunbookService {
    private final JdbcTemplate db;private final TargetService targets;private final Idempotency keys;private final AuditService audit;
    public RunbookService(JdbcTemplate db,TargetService targets,Idempotency keys,AuditService audit){this.db=db;this.targets=targets;this.keys=keys;this.audit=audit;}
    public record Book(UUID id,UUID targetId,String title,String markdown,long revision,int latestVersion,boolean enabled,boolean hasUnpublishedChanges){}
    public record Section(UUID id,int ordinal,String text,String hash){}
    public record Version(UUID runbookId,int version,String title,String markdown,Instant publishedAt,List<Section> sections){}
    private Book map(ResultSet r,int n)throws SQLException{return new Book(r.getObject("id",UUID.class),r.getObject("target_id",UUID.class),r.getString("title"),r.getString("draft"),r.getLong("revision"),r.getInt("latest_version"),r.getBoolean("enabled"),r.getObject("published_revision")==null||r.getLong("published_revision")!=r.getLong("revision"));}
    private Book find(UUID id){var rows=db.query("SELECT * FROM runbook WHERE id=?",this::map,id);if(rows.isEmpty())throw ApiError.notFound();return rows.getFirst();}
    public Book get(Caller c,UUID id){var book=find(id);targets.get(c,book.targetId());return book;}
    public PageResult<Book> list(Caller c,UUID target,int page,int size){targets.get(c,target);int offset=PageResult.offset(page,size);return new PageResult<>(db.query("SELECT * FROM runbook WHERE target_id=? ORDER BY created_at DESC,id LIMIT ? OFFSET ?",this::map,target,size,offset),db.queryForObject("SELECT count(*) FROM runbook WHERE target_id=?",Long.class,target),page,size);}
    @Transactional public Book create(Caller c,UUID target,RunbookController.Create input,String key,String requestId){
        targets.get(c,target);
        UUID id=keys.create(c.userId(),"CREATE_RUNBOOK:"+target,key,input,()->{UUID fresh=UUID.randomUUID();db.update("INSERT INTO runbook(id,target_id,title,draft,created_by) VALUES(?,?,?,?,?)",fresh,target,input.title(),input.markdown(),c.userId());audit.record(c.userId(),"RUNBOOK_CREATED","RUNBOOK",fresh,requestId,Map.of("targetId",target));return fresh;});return get(c,id);
    }
    private Book locked(Caller c,UUID id,long revision){db.queryForList("SELECT id FROM runbook WHERE id=? FOR UPDATE",id);var book=get(c,id);if(book.revision()!=revision)throw new ApiError(409,"RUNBOOK_VERSION_CONFLICT");return book;}
    @Transactional public Book draft(Caller c,UUID id,RunbookController.Draft input,String requestId){locked(c,id,input.revision());db.update("UPDATE runbook SET title=?,draft=?,revision=revision+1 WHERE id=?",input.title(),input.markdown(),id);audit.record(c.userId(),"RUNBOOK_DRAFT_UPDATED","RUNBOOK",id,requestId,Map.of("reason",input.reason()));return get(c,id);}
    @Transactional public Book state(Caller c,UUID id,RunbookController.State input,String requestId){locked(c,id,input.revision());db.update("UPDATE runbook SET enabled=?,published_revision=CASE WHEN published_revision=revision THEN revision+1 ELSE published_revision END,revision=revision+1 WHERE id=?",input.enabled(),id);audit.record(c.userId(),"RUNBOOK_STATE_CHANGED","RUNBOOK",id,requestId,Map.of("enabled",input.enabled(),"reason",input.reason()));return get(c,id);}
    @Transactional public Book publish(Caller c,UUID id,RunbookController.Publish input,String requestId){
        var book=locked(c,id,input.revision());if(!book.enabled())throw new ApiError(409,"RUNBOOK_DISABLED");if(!book.hasUnpublishedChanges())throw new ApiError(409,"RUNBOOK_ALREADY_PUBLISHED");
        var sections=split(book.markdown());int version=book.latestVersion()+1;
        db.update("INSERT INTO runbook_version(runbook_id,version,title,markdown,published_by) VALUES(?,?,?,?,?)",id,version,book.title(),book.markdown(),c.userId());
        for(int n=0;n<sections.size();n++)db.update("INSERT INTO runbook_section(id,runbook_id,version,ordinal,text,content_hash) VALUES(?,?,?,?,?,?)",UUID.randomUUID(),id,version,n,sections.get(n),hash(sections.get(n)));
        db.update("UPDATE runbook SET latest_version=?,published_revision=revision+1,revision=revision+1 WHERE id=?",version,id);
        audit.record(c.userId(),"RUNBOOK_PUBLISHED","RUNBOOK",id,requestId,Map.of("version",version,"reason",input.reason()));return get(c,id);
    }
    public Version version(Caller c,UUID id,int version){get(c,id);var rows=db.query("SELECT * FROM runbook_version WHERE runbook_id=? AND version=?",(r,n)->new Version(id,version,r.getString("title"),r.getString("markdown"),r.getTimestamp("published_at").toInstant(),db.query("SELECT * FROM runbook_section WHERE runbook_id=? AND version=? ORDER BY ordinal",(s,j)->new Section(s.getObject("id",UUID.class),s.getInt("ordinal"),s.getString("text"),s.getString("content_hash")),id,version)),id,version);if(rows.isEmpty())throw ApiError.notFound();return rows.getFirst();}
    private static List<String> split(String markdown){var sections=Arrays.stream(markdown.strip().split("(?m)(?=^#{1,6} )")).map(String::strip).filter(s->!s.isBlank()).toList();if(sections.isEmpty()||sections.size()>5||sections.stream().anyMatch(s->s.length()>6000))throw ApiError.badRequest("RUNBOOK_SECTION_LIMIT");return sections;}
    public static String hash(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}}
}

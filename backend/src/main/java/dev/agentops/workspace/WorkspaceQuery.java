package dev.agentops.workspace;

import dev.agentops.shared.ApiError;
import java.time.*;
import java.util.*;

/** Only values from explicit allowlists become SQL. Search wildcard characters stay literal. */
record WorkspaceQuery(String q,String status,Instant from,Instant to,String sort,int page,int size) {
    String pattern(){
        String text=q==null?"":q.strip();if(text.length()>100)throw ApiError.badRequest("INVALID_SEARCH_QUERY");
        return "%"+text.replace("!","!!").replace("%","!%").replace("_","!_")+"%";
    }
    void window(){
        if(from!=null&&to!=null&&(!from.isBefore(to)||Duration.between(from,to).compareTo(Duration.ofDays(31))>0))throw ApiError.badRequest("INVALID_TIME_RANGE");
    }
    String order(Map<String,String> allowed,String fallback){if(sort==null||sort.isBlank())return fallback;String order=allowed.get(sort);if(order==null)throw ApiError.badRequest("INVALID_SORT");return order;}
    String fromText(){return from==null?null:from.toString();}String toText(){return to==null?null:to.toString();}
}

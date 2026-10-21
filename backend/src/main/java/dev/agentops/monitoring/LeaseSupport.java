package dev.agentops.monitoring;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

final class LeaseSupport {
    private LeaseSupport(){}
    static void release(JdbcTemplate db,UUID token){
        if(token==null)return;
        db.update("UPDATE probe_host_lease SET lease_token=NULL,lease_until=now() WHERE lease_token=?",token);
        db.update("UPDATE probe_slot SET lease_token=NULL,lease_until=now() WHERE lease_token=?",token);
    }
}

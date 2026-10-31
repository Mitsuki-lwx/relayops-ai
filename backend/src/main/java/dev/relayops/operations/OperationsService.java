package dev.relayops.operations;

import dev.relayops.common.ApiException;
import dev.relayops.identity.Access;
import dev.relayops.job.DocumentInput;
import dev.relayops.messaging.ReadyMessage;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperationsService {
    private final JdbcTemplate jdbc;
    private final Access access;
    private final boolean retentionEnabled;
    private final int pendingLimit;

    public OperationsService(JdbcTemplate jdbc, Access access,
            @Value("${relayops.retention-enabled:false}") boolean retentionEnabled,
            @Value("${relayops.pending-limit:1000}") int pendingLimit) {
        this.jdbc=jdbc; this.access=access; this.retentionEnabled=retentionEnabled; this.pendingLimit=pendingLimit;
    }
    public record Queue(long pending, long claimed, long retried, Instant oldestPendingAt) {}
    public record Recovery(long expiredModelLeases, long expiredDeliveryLeases) {}
    public record Retention(boolean enabled, long expiredJobs, long expiredDeliveries, long clearedJobs, long clearedDeliveries) {}
    public record View(Instant observedAt, Map<String,Long> jobs, Map<String,Long> deliveries,
                       Queue aiOutbox, Queue deliveryOutbox, int pendingLimit, Recovery recovery, Retention retention) {}
    public record Event(String id, String operation, String resourceId, String actorId, String actorName, Instant createdAt) {}
    public record Page(List<Event> items, String nextBeforeId) {}

    private long count(String sql, Object... args) { return jdbc.queryForObject(sql,Long.class,args); }
    private Map<String,Long> counts(String sql, String workspace, List<String> states) {
        var result=new LinkedHashMap<String,Long>();
        for(String state:states)result.put(state,0L);
        for(var row:jdbc.queryForList(sql,workspace))result.put((String)row.get("state"),((Number)row.get("total")).longValue());
        return result;
    }
    private Queue queue(String table, String workspace) {
        // Only these fixed internal table names are accepted; no request data becomes SQL syntax.
        if(!List.of("outbox_event","delivery_outbox").contains(table))throw new IllegalArgumentException("Invalid outbox");
        return jdbc.queryForObject("SELECT COUNT(*) AS pending,COALESCE(SUM(status='CLAIMED'),0) AS claimed,COALESCE(SUM(publish_attempts>0),0) AS retried,MIN(created_at) AS oldest FROM "+table+" WHERE workspace_id=? AND status IN ('NEW','CLAIMED')",
                (rs,n)->new Queue(rs.getLong("pending"),rs.getLong("claimed"),rs.getLong("retried"),rs.getObject("oldest",LocalDateTime.class)==null?null:rs.getObject("oldest",LocalDateTime.class).toInstant(ZoneOffset.UTC)),workspace);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public View overview(String username, String workspace) {
        access.require(username,workspace,false,false,false);
        var observed=jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(3)",(rs,n)->rs.getObject(1,LocalDateTime.class));
        var jobs=counts("SELECT status AS state,COUNT(*) AS total FROM ai_job WHERE workspace_id=? GROUP BY status",workspace,List.of("QUEUED","RUNNING","SUCCEEDED","FAILED","UNKNOWN","CANCELLED"));
        var deliveries=counts("""
                SELECT r.status AS state,COUNT(*) AS total FROM delivery d JOIN delivery_run r ON r.workspace_id=d.workspace_id AND r.delivery_id=d.id
                WHERE d.workspace_id=? AND r.run_no=(SELECT MAX(x.run_no) FROM delivery_run x WHERE x.delivery_id=d.id) GROUP BY r.status
                """,workspace,List.of("PENDING","IN_FLIGHT","RETRY_WAIT","SUCCEEDED","DEAD"));
        var recovery=new Recovery(
                count("SELECT COUNT(*) FROM ai_job WHERE workspace_id=? AND status='RUNNING' AND lease_until<=?",workspace,observed),
                count("SELECT COUNT(*) FROM delivery_run WHERE workspace_id=? AND status='IN_FLIGHT' AND lease_until<=?",workspace,observed));
        var retention=new Retention(retentionEnabled,
                count("SELECT COUNT(*) FROM ai_job WHERE workspace_id=? AND payload_deleted_at IS NULL AND payload_expires_at<=?",workspace,observed),
                count("SELECT COUNT(*) FROM delivery WHERE workspace_id=? AND payload_deleted_at IS NULL AND payload_expires_at<=?",workspace,observed),
                count("SELECT COUNT(*) FROM ai_job WHERE workspace_id=? AND payload_deleted_at IS NOT NULL",workspace),
                count("SELECT COUNT(*) FROM delivery WHERE workspace_id=? AND payload_deleted_at IS NOT NULL",workspace));
        return new View(observed.toInstant(ZoneOffset.UTC),jobs,deliveries,queue("outbox_event",workspace),queue("delivery_outbox",workspace),pendingLimit,recovery,retention);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page audit(String username, String workspace, String operation, String resourceId, String beforeId, int limit) {
        var member=access.require(username,workspace,false,false,false);
        if(!member.role().equals("OWNER"))throw new ApiException(403,"FORBIDDEN","仅空间所有者可查询审计记录");
        if(limit<1 || limit>100)throw DocumentInput.invalid("每页数量需为1至100");
        if(operation!=null && !operation.matches("[A-Z][A-Z0-9_]{0,39}"))throw DocumentInput.invalid("事件类型格式无效");
        if(resourceId!=null && !ReadyMessage.uuid(resourceId))throw DocumentInput.invalid("资源ID需为有效UUID");
        Long before=null;
        if(beforeId!=null) {
            try {if(!beforeId.matches("[1-9][0-9]{0,18}"))throw new IllegalArgumentException();before=Long.parseLong(beforeId);}
            catch(Exception ex){throw DocumentInput.invalid("分页游标需为有效正整数");}
        }
        var sql=new StringBuilder("SELECT a.id,a.operation,a.resource_id,a.actor_id,u.username AS actor_name,a.created_at FROM audit_event a LEFT JOIN user_account u ON u.id=a.actor_id WHERE a.workspace_id=?");
        var args=new ArrayList<Object>();args.add(workspace);
        if(operation!=null){sql.append(" AND a.operation=?");args.add(operation);}
        if(resourceId!=null){sql.append(" AND a.resource_id=?");args.add(resourceId);}
        if(before!=null){sql.append(" AND a.id<?");args.add(before);}
        sql.append(" ORDER BY a.id DESC LIMIT ?");args.add(limit+1);
        var rows=jdbc.query(sql.toString(),(rs,n)->new Event(rs.getString("id"),rs.getString("operation"),rs.getString("resource_id"),rs.getString("actor_id"),rs.getString("actor_name"),rs.getObject("created_at",LocalDateTime.class).toInstant(ZoneOffset.UTC)),args.toArray());
        boolean more=rows.size()>limit;
        var items=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));
        return new Page(items,more?items.getLast().id():null);
    }
}

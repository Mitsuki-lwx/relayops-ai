package dev.relayops.messaging;

import dev.relayops.common.ApiException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class OutboxStore {
    private final JdbcTemplate jdbc;
    private final int pendingLimit;

    public OutboxStore(JdbcTemplate jdbc, @Value("${relayops.pending-limit}") int pendingLimit) {
        if (pendingLimit < 1 || pendingLimit > 10000) throw new IllegalArgumentException("Invalid pending limit");
        this.jdbc = jdbc; this.pendingLimit = pendingLimit;
    }

    public record Claimed(ReadyMessage envelope, String claimToken, int attempts) {}

    private static String table(String kind) {
        return switch(kind) { case "AI_EXECUTE" -> "outbox_event"; case "DELIVERY_SEND" -> "delivery_outbox"; default -> throw new ReadyMessage.Invalid("WRONG_ROUTE_KIND"); };
    }
    public void requireCapacity(String workspaceId) {
        int pending=0;
        for (String table:List.of("outbox_event","delivery_outbox"))
            pending+=jdbc.queryForList("SELECT id FROM "+table+" WHERE workspace_id=? AND status IN ('NEW','CLAIMED') LIMIT "+pendingLimit+" FOR UPDATE",String.class,workspaceId).size();
        if(pending>=pendingLimit) throw new ApiException(503,"QUEUE_CAPACITY_EXCEEDED","空间消息积压达到上限，请使用原幂等键稍后重试");
    }
    public void enqueue(String workspaceId,String jobId,long generation) { enqueue("AI_EXECUTE",workspaceId,jobId,generation); }
    public void enqueueDelivery(String workspaceId,String runId,long generation) { enqueue("DELIVERY_SEND",workspaceId,runId,generation); }
    private void enqueue(String kind,String workspaceId,String aggregateId,long generation) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Outbox requires a business transaction");
        jdbc.update("INSERT INTO "+table(kind)+"(id,workspace_id,aggregate_id,dispatch_generation,trace_id) VALUES (?,?,?,?,?)",UUID.randomUUID().toString(),workspaceId,aggregateId,generation,UUID.randomUUID().toString());
    }
    @Transactional public List<Claimed> claim() {
        var result=new ArrayList<Claimed>();
        for (String kind:List.of("AI_EXECUTE","DELIVERY_SEND")) {
            String table=table(kind),token=UUID.randomUUID().toString();
            jdbc.update("UPDATE "+table+" SET status='NEW',claim_token=NULL,claim_until=NULL,next_publish_at=CURRENT_TIMESTAMP(3),last_error='PUBLISH_LEASE_EXPIRED' WHERE status='CLAIMED' AND claim_until<=CURRENT_TIMESTAMP(3) LIMIT 25");
            var events=jdbc.query("SELECT * FROM "+table+" WHERE status='NEW' AND next_publish_at<=CURRENT_TIMESTAMP(3) ORDER BY next_publish_at,id LIMIT 25 FOR UPDATE SKIP LOCKED",
                (rs,n)->new Claimed(new ReadyMessage(1,rs.getString("id"),kind,rs.getString("workspace_id"),rs.getString("aggregate_id"),rs.getLong("dispatch_generation"),rs.getObject("created_at",LocalDateTime.class).toInstant(ZoneOffset.UTC),rs.getString("trace_id")),token,rs.getInt("publish_attempts")+1));
            for(var event:events) jdbc.update("UPDATE "+table+" SET status='CLAIMED',claim_token=?,claim_until=DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 30 SECOND),publish_attempts=publish_attempts+1 WHERE id=?",token,event.envelope().messageId());
            result.addAll(events);
        }
        return result;
    }

    public int published(Claimed event) {
        return jdbc.update("UPDATE " + table(event.envelope().kind()) + """
                 SET status = 'SENT', published_at = CURRENT_TIMESTAMP(3), claim_token = NULL, claim_until = NULL, last_error = NULL
                WHERE id = ? AND status = 'CLAIMED' AND claim_token = ? AND claim_until > CURRENT_TIMESTAMP(3)
                """, event.envelope().messageId(), event.claimToken());
    }

    public void failed(Claimed event, String code) {
        double base = Math.min(30, 1L << Math.min(event.attempts() - 1, 5));
        long delayMillis = (long) (base * 1000 * ThreadLocalRandom.current().nextDouble(1, 1.2));
        jdbc.update("UPDATE " + table(event.envelope().kind()) + """
                 SET status = 'NEW', next_publish_at = TIMESTAMPADD(MICROSECOND, ?, CURRENT_TIMESTAMP(3)),
                  claim_token = NULL, claim_until = NULL, last_error = ?
                WHERE id = ? AND status = 'CLAIMED' AND claim_token = ? AND claim_until > CURRENT_TIMESTAMP(3)
                """, delayMillis * 1000, code, event.envelope().messageId(), event.claimToken());
    }

    public void verify(ReadyMessage envelope) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table(envelope.kind()) + """
                 WHERE id = ? AND workspace_id = ? AND aggregate_id = ? AND dispatch_generation = ? AND trace_id = ?
                """, Integer.class, envelope.messageId(), envelope.workspaceId(), envelope.aggregateId(), envelope.dispatchGeneration(), envelope.traceId());
        if (count == null || count != 1) throw new ReadyMessage.Invalid("ENVELOPE_ASSOCIATION_INVALID");
    }
}

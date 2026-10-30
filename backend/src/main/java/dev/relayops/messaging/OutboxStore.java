package dev.relayops.messaging;

import dev.relayops.common.ApiException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
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

    public void requireCapacity(String workspaceId) {
        // Called under the admission budget lock; locking reads see committed current rows.
        var pending = jdbc.queryForList("SELECT id FROM outbox_event WHERE workspace_id = ? AND status IN ('NEW','CLAIMED') LIMIT " + pendingLimit + " FOR UPDATE", String.class, workspaceId);
        if (pending.size() >= pendingLimit) throw new ApiException(503, "QUEUE_CAPACITY_EXCEEDED", "空间消息积压达到上限，请使用原幂等键稍后重试");
    }

    public void enqueue(String workspaceId, String jobId, long generation) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Outbox requires a business transaction");
        jdbc.update("INSERT INTO outbox_event(id, workspace_id, aggregate_id, dispatch_generation, trace_id) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), workspaceId, jobId, generation, UUID.randomUUID().toString());
    }

    @Transactional
    public List<Claimed> claim() {
        jdbc.update("""
                UPDATE outbox_event SET status = 'NEW', claim_token = NULL, claim_until = NULL,
                  next_publish_at = CURRENT_TIMESTAMP(3), last_error = 'PUBLISH_LEASE_EXPIRED'
                WHERE status = 'CLAIMED' AND claim_until <= CURRENT_TIMESTAMP(3) LIMIT 50
                """);
        String token = UUID.randomUUID().toString();
        var events = jdbc.query("""
                SELECT * FROM outbox_event WHERE status = 'NEW' AND next_publish_at <= CURRENT_TIMESTAMP(3)
                ORDER BY next_publish_at, id LIMIT 50 FOR UPDATE SKIP LOCKED
                """, (rs, row) -> new Claimed(new ReadyMessage(1, rs.getString("id"), "AI_EXECUTE", rs.getString("workspace_id"),
                rs.getString("aggregate_id"), rs.getLong("dispatch_generation"),
                rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC), rs.getString("trace_id")), token, rs.getInt("publish_attempts") + 1));
        for (Claimed event : events) jdbc.update("""
                UPDATE outbox_event SET status = 'CLAIMED', claim_token = ?, claim_until = DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 30 SECOND),
                  publish_attempts = publish_attempts + 1 WHERE id = ?
                """, token, event.envelope().messageId());
        return events;
    }

    public int published(Claimed event) {
        return jdbc.update("""
                UPDATE outbox_event SET status = 'SENT', published_at = CURRENT_TIMESTAMP(3), claim_token = NULL, claim_until = NULL, last_error = NULL
                WHERE id = ? AND status = 'CLAIMED' AND claim_token = ? AND claim_until > CURRENT_TIMESTAMP(3)
                """, event.envelope().messageId(), event.claimToken());
    }

    public void failed(Claimed event, String code) {
        double base = Math.min(30, 1L << Math.min(event.attempts() - 1, 5));
        long delayMillis = (long) (base * 1000 * ThreadLocalRandom.current().nextDouble(1, 1.2));
        jdbc.update("""
                UPDATE outbox_event SET status = 'NEW', next_publish_at = TIMESTAMPADD(MICROSECOND, ?, CURRENT_TIMESTAMP(3)),
                  claim_token = NULL, claim_until = NULL, last_error = ?
                WHERE id = ? AND status = 'CLAIMED' AND claim_token = ? AND claim_until > CURRENT_TIMESTAMP(3)
                """, delayMillis * 1000, code, event.envelope().messageId(), event.claimToken());
    }

    public void verify(ReadyMessage envelope) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM outbox_event WHERE id = ? AND workspace_id = ? AND aggregate_id = ? AND dispatch_generation = ? AND trace_id = ?
                """, Integer.class, envelope.messageId(), envelope.workspaceId(), envelope.aggregateId(), envelope.dispatchGeneration(), envelope.traceId());
        if (count == null || count != 1) throw new ReadyMessage.Invalid("ENVELOPE_ASSOCIATION_INVALID");
    }
}

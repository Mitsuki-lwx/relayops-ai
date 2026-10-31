package dev.relayops.retention;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class PayloadRetention {
    private static final Logger LOG = LoggerFactory.getLogger(PayloadRetention.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final boolean enabled;

    public PayloadRetention(JdbcTemplate jdbc, TransactionTemplate tx,
            @Value("${relayops.retention-enabled:false}") boolean enabled) {
        this.jdbc = jdbc; this.tx = tx; this.enabled = enabled;
    }
    public record Batch(int jobs, int deliveries) {}

    @Scheduled(fixedDelayString = "${relayops.retention-interval-ms:60000}", initialDelayString = "${relayops.retention-interval-ms:60000}")
    public void tick() {
        if (!enabled) return;
        try {
            var batch = purge();
            if (batch.jobs() + batch.deliveries() > 0)
                LOG.info("operation=payload_retention jobs={} deliveries={}", batch.jobs(), batch.deliveries());
        } catch (Exception ex) { LOG.warn("operation=payload_retention_deferred exceptionType={}", ex.getClass().getSimpleName()); }
    }

    public Batch purge() {
        int jobs = 0, deliveries = 0;
        var jobIds = jdbc.queryForList("""
                SELECT id FROM ai_job WHERE payload_deleted_at IS NULL AND payload_expires_at<=CURRENT_TIMESTAMP(3)
                  AND status IN ('SUCCEEDED','FAILED','UNKNOWN','CANCELLED') ORDER BY payload_expires_at,id LIMIT 25
                """, String.class);
        for (String id : jobIds) {
            try { if (Boolean.TRUE.equals(tx.execute(s -> clearJob(id)))) jobs++; }
            catch (Exception ex) { LOG.warn("operation=job_payload_cleanup_deferred exceptionType={}", ex.getClass().getSimpleName()); }
        }
        var deliveryIds = jdbc.queryForList("""
                SELECT d.id FROM delivery d WHERE d.payload_deleted_at IS NULL AND d.payload_expires_at<=CURRENT_TIMESTAMP(3)
                  AND NOT EXISTS (SELECT 1 FROM delivery_run r WHERE r.delivery_id=d.id AND r.status NOT IN ('SUCCEEDED','DEAD'))
                ORDER BY d.payload_expires_at,d.id LIMIT 25
                """, String.class);
        for (String id : deliveryIds) {
            try { if (Boolean.TRUE.equals(tx.execute(s -> clearDelivery(id)))) deliveries++; }
            catch (Exception ex) { LOG.warn("operation=delivery_payload_cleanup_deferred exceptionType={}", ex.getClass().getSimpleName()); }
        }
        return new Batch(jobs, deliveries);
    }

    private boolean clearJob(String id) {
        var rows = jdbc.queryForList("""
                SELECT workspace_id FROM ai_job WHERE id=? AND payload_deleted_at IS NULL AND payload_expires_at<=CURRENT_TIMESTAMP(3)
                  AND status IN ('SUCCEEDED','FAILED','UNKNOWN','CANCELLED') FOR UPDATE SKIP LOCKED
                """, String.class, id);
        if (rows.isEmpty()) return false;
        jdbc.update("UPDATE ai_result SET result_cipher=NULL,payload_deleted_at=CURRENT_TIMESTAMP(3) WHERE job_id=?", id);
        jdbc.update("UPDATE ai_job SET input_cipher=NULL,payload_deleted_at=CURRENT_TIMESTAMP(3),version=version+1 WHERE id=?", id);
        audit(rows.getFirst(), id, "JOB_PAYLOAD_CLEARED");
        return true;
    }

    private boolean clearDelivery(String id) {
        var rows = jdbc.queryForList("""
                SELECT workspace_id FROM delivery WHERE id=? AND payload_deleted_at IS NULL AND payload_expires_at<=CURRENT_TIMESTAMP(3)
                FOR UPDATE SKIP LOCKED
                """, String.class, id);
        if (rows.isEmpty()) return false;
        // Lock the current run facts after the root. A replay or executor must acquire this root first.
        var states = jdbc.queryForList("SELECT status FROM delivery_run WHERE delivery_id=? ORDER BY run_no FOR UPDATE", String.class, id);
        if (states.isEmpty() || states.stream().anyMatch(state -> !state.equals("SUCCEEDED") && !state.equals("DEAD"))) return false;
        jdbc.update("UPDATE delivery SET payload_cipher=NULL,payload_deleted_at=CURRENT_TIMESTAMP(3),version=version+1 WHERE id=?", id);
        audit(rows.getFirst(), id, "DELIVERY_PAYLOAD_CLEARED");
        return true;
    }

    private void audit(String workspace, String id, String operation) {
        jdbc.update("INSERT INTO audit_event(workspace_id,resource_id,operation) VALUES (?,?,?)", workspace, id, operation);
    }
}

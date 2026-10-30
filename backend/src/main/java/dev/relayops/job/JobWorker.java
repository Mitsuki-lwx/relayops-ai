package dev.relayops.job;

import dev.relayops.common.PayloadCipher;
import dev.relayops.messaging.OutboxStore;
import dev.relayops.messaging.ReadyMessage;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Component
public class JobWorker {
    private static final Logger LOG = LoggerFactory.getLogger(JobWorker.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PayloadCipher cipher;
    private final JsonMapper json;
    private final ModelGateway model;
    private final LeaseHeartbeat heartbeat;
    private final boolean recoveryEnabled;
    private final OutboxStore outbox;

    public JobWorker(JdbcTemplate jdbc, TransactionTemplate tx, PayloadCipher cipher, JsonMapper json,
                     ModelGateway model, LeaseHeartbeat heartbeat, OutboxStore outbox, @Value("${relayops.recovery-enabled}") boolean recoveryEnabled) {
        this.jdbc = jdbc; this.tx = tx; this.cipher = cipher; this.json = json; this.model = model; this.outbox = outbox; this.recoveryEnabled = recoveryEnabled;
        this.heartbeat = heartbeat;
    }

    record Work(String id, String workspaceId, LocalDate date, String title, byte[] input, String language, String token, long generation, String modelProfile) {}

    @Scheduled(fixedDelay = 5000)
    public void tick() {
        if (!recoveryEnabled) return;
        try { recover(); }
        catch (Exception ex) { LOG.warn("operation=recovery_deferred exceptionType={}", ex.getClass().getSimpleName()); }
    }

    public boolean execute(ReadyMessage envelope) {
        try {
            Work work = tx.execute(status -> claim(envelope));
            if (work == null) return true;
            List<DocumentInput.Paragraph> paragraphs;
            ModelGateway.Prepared prepared;
            try {
                paragraphs = DocumentInput.paragraphs(cipher.decrypt(work.input(), work.workspaceId() + ":" + work.id() + ":input"));
                prepared = model.prepare(work.modelProfile(), work.title(), paragraphs, work.language());
            } catch (ModelGateway.Failure ex) {
                settle(work, null, ex.code, false);
                return true;
            } catch (Exception ex) {
                settle(work, null, "LOCAL_PRECHECK_FAILED", false);
                return true;
            }
            if (!Boolean.TRUE.equals(tx.execute(status -> markSent(work)))) return true;
            try (var lease = heartbeat.start(work, prepared.timeoutSeconds())) {
                // The call is deliberately outside any DB transaction. Never retry this line.
                DocumentResult result;
                try {
                    result = prepared.invoke();
                } catch (ModelGateway.Failure ex) {
                    settle(work, null, ex.code, true);
                    return true;
                } catch (Exception ex) {
                    // A generic adapter exception cannot prove that a request was not executed.
                    // Retain SENT; lease recovery will conservatively classify it UNKNOWN.
                    LOG.warn("operation=model_result_unknown jobId={}", work.id());
                    return false;
                }
                try {
                    DocumentResult.validate(result, paragraphs);
                    if (!work.modelProfile().equals(result.modelProfile())) throw new IllegalStateException("OUTPUT_INVALID");
                } catch (Exception ex) {
                    settle(work, null, "EVIDENCE_INVALID".equals(ex.getMessage()) ? "EVIDENCE_INVALID" : "OUTPUT_INVALID", true);
                    return true;
                }
                String response = json.writeValueAsString(result);
                // Retry persistence of the same response, never the model request.
                for (int attempt = 0; attempt < 3; attempt++) {
                    try { settle(work, response, null, true); return true; }
                    catch (Exception ex) { LOG.warn("operation=result_commit_retry jobId={} saveAttempt={}", work.id(), attempt + 1); }
                }
                return false;
            }
        } catch (ReadyMessage.Invalid ex) { throw ex; }
        catch (Exception ex) {
            LOG.warn("operation=worker_deferred exceptionType={}", ex.getClass().getSimpleName());
            return false;
        }
    }

    private Work claim(ReadyMessage envelope) {
        record Candidate(Work work, String status, boolean inTime) {}
        var jobs = jdbc.query("""
                SELECT *, (queue_expires_at > CURRENT_TIMESTAMP(3)) AS in_time
                FROM ai_job WHERE id = ? AND workspace_id = ? FOR UPDATE
                """, (rs, row) -> new Candidate(new Work(rs.getString("id"), rs.getString("workspace_id"), rs.getDate("accepted_date").toLocalDate(),
                rs.getString("title"), rs.getBytes("input_cipher"), rs.getString("output_language"), UUID.randomUUID().toString(), rs.getLong("dispatch_generation"), rs.getString("model_profile")),
                rs.getString("status"), rs.getBoolean("in_time")), envelope.aggregateId(), envelope.workspaceId());
        if (jobs.isEmpty()) throw new ReadyMessage.Invalid("AGGREGATE_NOT_FOUND");
        Candidate candidate = jobs.getFirst();
        Work work = candidate.work();
        if (envelope.dispatchGeneration() > work.generation()) throw new ReadyMessage.Invalid("FUTURE_GENERATION");
        if (envelope.dispatchGeneration() < work.generation() || !candidate.status().equals("QUEUED") || !candidate.inTime()) return null;
        jdbc.update("""
                UPDATE ai_job SET status = 'RUNNING', lease_token = ?, lease_until = DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL ? SECOND), version = version + 1
                WHERE id = ? AND status = 'QUEUED'
                """, work.token(), heartbeat.leaseSeconds(), work.id());
        jdbc.update("INSERT INTO ai_attempt(id, workspace_id, job_id, lease_token, status) VALUES (?, ?, ?, ?, 'PREPARED')",
                UUID.randomUUID().toString(), work.workspaceId(), work.id(), work.token());
        return work;
    }

    private void lockBudget(Work work) {
        jdbc.queryForObject("SELECT daily_limit FROM budget_account WHERE workspace_id = ? AND accepted_date = ? FOR UPDATE",
                Integer.class, work.workspaceId(), work.date());
    }

    private boolean owns(Work work) {
        return !jdbc.queryForList("""
                SELECT id FROM ai_job WHERE id = ? AND status = 'RUNNING' AND lease_token = ? AND dispatch_generation = ? AND lease_until > CURRENT_TIMESTAMP(3) FOR UPDATE
                """, work.id(), work.token(), work.generation()).isEmpty();
    }

    private boolean markSent(Work work) {
        lockBudget(work);
        if (!owns(work)) return false;
        int updated = jdbc.update("""
                UPDATE ai_attempt SET status = 'SENT', sent_at = CURRENT_TIMESTAMP(3)
                WHERE job_id = ? AND lease_token = ? AND status = 'PREPARED' AND sent_at IS NULL
                """, work.id(), work.token());
        if (updated != 1) throw new IllegalStateException("Invalid attempt transition");
        jdbc.update("UPDATE budget_account SET reserved = reserved - 1, spent = spent + 1 WHERE workspace_id = ? AND accepted_date = ?", work.workspaceId(), work.date());
        jdbc.update("UPDATE budget_entry SET state = 'SPENT' WHERE job_id = ? AND state = 'RESERVED'", work.id());
        return true;
    }

    private void settle(Work work, String response, String errorCode, boolean sent) {
        tx.executeWithoutResult(transaction -> {
            lockBudget(work);
            if (!owns(work)) return;
            if (response != null) {
                jdbc.update("INSERT INTO ai_result(job_id, workspace_id, result_cipher) VALUES (?, ?, ?)", work.id(), work.workspaceId(),
                        cipher.encrypt(response, work.workspaceId() + ":" + work.id() + ":result"));
            }
            String state = response != null ? "SUCCEEDED" : "FAILED";
            jdbc.update("UPDATE ai_attempt SET status = ?, completed_at = CURRENT_TIMESTAMP(3) WHERE job_id = ? AND lease_token = ? AND status = ?",
                    sent ? state : "ABORTED", work.id(), work.token(), sent ? "SENT" : "PREPARED");
            if (!sent) release(work);
            jdbc.update("""
                    UPDATE ai_job SET status = ?, error_code = ?, completed_at = CURRENT_TIMESTAMP(3), version = version + 1, lease_token = NULL, lease_until = NULL
                    WHERE id = ? AND lease_token = ?
                    """, state, errorCode, work.id(), work.token());
            jdbc.update("INSERT INTO audit_event(workspace_id, resource_id, operation) VALUES (?, ?, ?)", work.workspaceId(), work.id(), "JOB_" + state);
        });
    }

    private void release(Work work) {
        jdbc.update("UPDATE budget_account SET reserved = reserved - 1 WHERE workspace_id = ? AND accepted_date = ?", work.workspaceId(), work.date());
        jdbc.update("UPDATE budget_entry SET state = 'RELEASED' WHERE job_id = ? AND state = 'RESERVED'", work.id());
    }

    public void recover() {
        var candidates = jdbc.query("""
                SELECT * FROM ai_job WHERE (status = 'RUNNING' AND lease_until <= CURRENT_TIMESTAMP(3))
                  OR (status = 'QUEUED' AND queue_expires_at <= CURRENT_TIMESTAMP(3)) LIMIT 10
                """, (rs, row) -> new Work(rs.getString("id"), rs.getString("workspace_id"), rs.getDate("accepted_date").toLocalDate(),
                null, null, null, rs.getString("lease_token"), rs.getLong("dispatch_generation"), rs.getString("model_profile")));
        for (Work work : candidates) tx.executeWithoutResult(transaction -> {
            lockBudget(work);
            var rows = jdbc.queryForList("""
                    SELECT status FROM ai_job WHERE id = ? AND ((status = 'RUNNING' AND lease_until <= CURRENT_TIMESTAMP(3))
                      OR (status = 'QUEUED' AND queue_expires_at <= CURRENT_TIMESTAMP(3)))
                      AND dispatch_generation = ? AND lease_token <=> ? FOR UPDATE
                    """, work.id(), work.generation(), work.token());
            if (rows.isEmpty()) return;
            boolean sent = jdbc.queryForObject("SELECT COUNT(*) FROM ai_attempt WHERE job_id = ? AND sent_at IS NOT NULL", Integer.class, work.id()) > 0;
            boolean queued = rows.getFirst().get("status").equals("QUEUED");
            String next = sent ? "UNKNOWN" : queued ? "FAILED" : "QUEUED";
            String errorCode = sent ? "MODEL_RESULT_UNKNOWN" : queued ? "QUEUE_EXPIRED" : null;
            if (sent) {
                jdbc.update("UPDATE budget_account SET spent = spent - 1, uncertain = uncertain + 1 WHERE workspace_id = ? AND accepted_date = ?", work.workspaceId(), work.date());
                jdbc.update("UPDATE budget_entry SET state = 'UNCERTAIN' WHERE job_id = ? AND state = 'SPENT'", work.id());
            } else if (queued) release(work);
            jdbc.update("UPDATE ai_attempt SET status = ?, completed_at = CURRENT_TIMESTAMP(3) WHERE job_id = ? AND status IN ('SENT','PREPARED')", sent ? "UNKNOWN" : "ABORTED", work.id());
            jdbc.update("""
                    UPDATE ai_job SET status = ?, error_code = ?, lease_token = NULL, lease_until = NULL, version = version + 1,
                      dispatch_generation = dispatch_generation + ?,
                      completed_at = CASE WHEN ? = 'QUEUED' THEN NULL ELSE CURRENT_TIMESTAMP(3) END WHERE id = ?
                    """, next, errorCode, next.equals("QUEUED") ? 1 : 0, next, work.id());
            if (next.equals("QUEUED")) outbox.enqueue(work.workspaceId(), work.id(), work.generation() + 1);
            jdbc.update("INSERT INTO audit_event(workspace_id, resource_id, operation) VALUES (?, ?, ?)", work.workspaceId(), work.id(), "RECOVER_" + next);
        });
        recoverUnclaimed();
    }

    private void recoverUnclaimed() {
        var candidates = jdbc.queryForList("""
                SELECT j.id FROM ai_job j JOIN outbox_event o ON o.aggregate_id = j.id AND o.dispatch_generation = j.dispatch_generation
                WHERE j.status = 'QUEUED' AND j.queue_expires_at > CURRENT_TIMESTAMP(3) AND o.status = 'SENT'
                  AND o.published_at <= DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 120 SECOND) LIMIT 10
                """, String.class);
        for (String id : candidates) tx.executeWithoutResult(transaction -> {
            var rows = jdbc.queryForList("SELECT workspace_id, dispatch_generation FROM ai_job WHERE id = ? AND status = 'QUEUED' AND queue_expires_at > CURRENT_TIMESTAMP(3) FOR UPDATE", id);
            if (rows.isEmpty()) return;
            var row = rows.getFirst();
            long generation = ((Number) row.get("dispatch_generation")).longValue();
            var due = jdbc.queryForList("""
                    SELECT id FROM outbox_event WHERE aggregate_id = ? AND dispatch_generation = ? AND status = 'SENT'
                      AND published_at <= DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 120 SECOND) FOR UPDATE
                    """, id, generation);
            if (due.isEmpty()) return;
            jdbc.update("UPDATE ai_job SET dispatch_generation = dispatch_generation + 1, version = version + 1 WHERE id = ?", id);
            outbox.enqueue(row.get("workspace_id").toString(), id, generation + 1);
            jdbc.update("INSERT INTO audit_event(workspace_id, resource_id, operation) VALUES (?, ?, 'REDISPATCH_UNCLAIMED')", row.get("workspace_id"), id);
        });
    }
}

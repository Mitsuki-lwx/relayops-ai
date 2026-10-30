package dev.relayops.job;

import dev.relayops.common.ApiException;
import dev.relayops.common.PayloadCipher;
import dev.relayops.messaging.OutboxStore;
import dev.relayops.messaging.ReadyMessage;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "RELAYOPS_DATABASE_TESTS", matches = "true")
@SpringBootTest(properties = {"relayops.worker-enabled=false", "relayops.consumer-enabled=false", "relayops.relay-enabled=false", "relayops.recovery-enabled=false"})
class JobLifecycleTest {
    @Autowired JobService jobs;
    @Autowired JobWorker worker;
    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxStore outbox;
    @Autowired PayloadCipher cipher;
    @Autowired TransactionTemplate tx;
    String workspaceId;
    String applicationId;

    @BeforeEach
    void fixtures() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertTrue(connection.getMetaData().getURL().contains("/relayops_test?"), "Refuse to reset a non-test schema");
        }
        jdbc.update("UPDATE ai_job SET parent_job_id = NULL");
        for (String table : List.of("delivery_outbox", "delivery_attempt", "delivery_run", "replay_plan", "delivery", "job_destination", "destination", "quarantine_message", "outbox_event", "audit_event", "idempotency_record", "ai_result", "budget_entry", "ai_attempt", "ai_job", "budget_account")) jdbc.update("DELETE FROM " + table);
        workspaceId = jdbc.queryForObject("SELECT id FROM workspace WHERE code = 'a'", String.class);
        applicationId = jdbc.queryForObject("SELECT id FROM application WHERE workspace_id = ? LIMIT 1", String.class, workspaceId);
    }

    JobService.CreateJob request() { return new JobService.CreateJob(applicationId, "生命周期验证", "只有明确的新意图才再次调用模型。", List.of(), "zh-CN"); }
    String create() { return jobs.create("owner.a", workspaceId, UUID.randomUUID().toString(), request()).jobId(); }
    JobService.Job get(String id) { return jobs.get("owner.a", workspaceId, id); }
    JobService.RetryJob retryRequest(String id) { return new JobService.RetryJob(get(id).version(), "确认需要再次运行", true); }
    String credit(String id) { return jdbc.queryForObject("SELECT state FROM budget_entry WHERE job_id = ?", String.class, id); }
    ReadyMessage ready(String id) { return outbox.claim().stream().map(OutboxStore.Claimed::envelope).filter(e -> e.aggregateId().equals(id)).findFirst().orElseThrow(); }

    String failed() {
        String id = create();
        jdbc.update("UPDATE ai_job SET queue_expires_at = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND) WHERE id = ?", id);
        worker.recover();
        assertEquals("FAILED", get(id).status());
        return id;
    }

    String unknown() {
        String id = create();
        ReadyMessage message = ready(id);
        JobWorker.Work work = tx.execute(status -> ReflectionTestUtils.invokeMethod(worker, "claim", message));
        assertNotNull(work);
        Boolean sent = tx.execute(status -> ReflectionTestUtils.invokeMethod(worker, "markSent", work));
        assertEquals(Boolean.TRUE, sent);
        jdbc.update("UPDATE ai_job SET lease_until = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND) WHERE id = ?", id);
        worker.recover();
        assertEquals("UNKNOWN", get(id).status());
        return id;
    }

    @Test
    void cancelReleasesOnceAndBothSuccessSnapshotsRemainStable() {
        String createKey = UUID.randomUUID().toString(), cancelKey = UUID.randomUUID().toString();
        var accepted = jobs.create("owner.a", workspaceId, createKey, request());
        var command = new JobService.CancelJob(0L);
        var cancelled = jobs.cancel("owner.a", workspaceId, accepted.jobId(), cancelKey, command);
        assertEquals("CANCELLED", cancelled.status());
        assertEquals(1, cancelled.version());
        assertEquals(cancelled, jobs.cancel("owner.a", workspaceId, accepted.jobId(), cancelKey, command));
        assertEquals(accepted, jobs.create("owner.a", workspaceId, createKey, request()));
        assertEquals("RELEASED", credit(accepted.jobId()));
        assertEquals(0, jdbc.queryForObject("SELECT reserved FROM budget_account WHERE workspace_id = ?", Integer.class, workspaceId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE resource_id = ? AND operation = 'JOB_CANCELLED'", Integer.class, accepted.jobId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_record WHERE response_json IS NULL", Integer.class));
    }

    @Test
    void cancellationReleasesTheOriginalAdmissionDateOnly() {
        String id = create();
        LocalDate original = LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1);
        tx.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO budget_account(workspace_id,accepted_date,reserved) VALUES (?,?,1)", workspaceId, original);
            jdbc.update("UPDATE ai_job SET accepted_date = ? WHERE id = ?", original, id);
            jdbc.update("UPDATE budget_entry SET accepted_date = ? WHERE job_id = ?", original, id);
            jdbc.update("UPDATE budget_account SET reserved = 0 WHERE workspace_id = ? AND accepted_date <> ?", workspaceId, original);
        });
        jobs.cancel("owner.a", workspaceId, id, UUID.randomUUID().toString(), new JobService.CancelJob(0L));
        assertEquals(0, jdbc.queryForObject("SELECT reserved FROM budget_account WHERE workspace_id = ? AND accepted_date = ?", Integer.class, workspaceId, original));
        assertEquals(0, jdbc.queryForObject("SELECT SUM(reserved) FROM budget_account WHERE workspace_id = ?", Integer.class, workspaceId));
    }

    @Test
    void cancellationChecksPermissionsVersionsAndCurrentState() {
        String id = create();
        assertEquals(403, assertThrows(ApiException.class, () -> jobs.cancel("viewer.a", workspaceId, id, "viewer", new JobService.CancelJob(0L))).status);
        assertEquals(404, assertThrows(ApiException.class, () -> jobs.cancel("owner.b", workspaceId, id, "other", new JobService.CancelJob(0L))).status);
        assertEquals(422, assertThrows(ApiException.class, () -> jobs.cancel("owner.a", workspaceId, id, null, new JobService.CancelJob(0L))).status);
        assertEquals(422, assertThrows(ApiException.class, () -> jobs.cancel("owner.a", workspaceId, id, "missing", new JobService.CancelJob(null))).status);
        assertEquals("VERSION_CONFLICT", assertThrows(ApiException.class, () -> jobs.cancel("owner.a", workspaceId, id, "stale", new JobService.CancelJob(9L))).code);
        ReadyMessage message = ready(id);
        JobWorker.Work work = tx.execute(status -> ReflectionTestUtils.invokeMethod(worker, "claim", message));
        assertNotNull(work);
        assertEquals("STATE_CONFLICT", assertThrows(ApiException.class, () -> jobs.cancel("owner.a", workspaceId, id, "running", new JobService.CancelJob(get(id).version()))).code);
        assertEquals("RESERVED", credit(id));
        jdbc.update("UPDATE ai_job SET lease_until = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND) WHERE id = ?", id);
        worker.recover();
        jobs.cancel("owner.a", workspaceId, id, "cancelled", new JobService.CancelJob(get(id).version()));
        assertEquals(409, assertThrows(ApiException.class, () -> jobs.cancel("owner.a", workspaceId, id, "again", new JobService.CancelJob(get(id).version()))).status);
    }

    @Test
    void failedRerunUsesCurrentProfileIndependentEncryptionAndSameWorkspaceParent() {
        String source = failed();
        jdbc.update("UPDATE ai_job SET model_profile = 'retired-fake-v0' WHERE id = ?", source);
        var original = get(source);
        var child = jobs.retry("owner.a", workspaceId, source, UUID.randomUUID().toString(), retryRequest(source));
        assertNotEquals(source, child.jobId());
        assertEquals(source, get(child.jobId()).parentJobId());
        assertEquals("fake-extract-v1", get(child.jobId()).modelProfile());
        assertEquals(original, get(source));
        assertEquals("RELEASED", credit(source));
        assertEquals("RESERVED", credit(child.jobId()));
        byte[] sourceBytes = jdbc.queryForObject("SELECT input_cipher FROM ai_job WHERE id = ?", byte[].class, source);
        byte[] childBytes = jdbc.queryForObject("SELECT input_cipher FROM ai_job WHERE id = ?", byte[].class, child.jobId());
        assertFalse(java.util.Arrays.equals(sourceBytes, childBytes));
        assertEquals(cipher.decrypt(sourceBytes, workspaceId + ":" + source + ":input"), cipher.decrypt(childBytes, workspaceId + ":" + child.jobId() + ":input"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ?", Integer.class, child.jobId()));
        String otherSpace = jdbc.queryForObject("SELECT id FROM workspace WHERE code = 'b'", String.class);
        String otherApp = jdbc.queryForObject("SELECT id FROM application WHERE workspace_id = ? LIMIT 1", String.class, otherSpace);
        var foreign = jobs.create("owner.b", otherSpace, UUID.randomUUID().toString(), new JobService.CreateJob(otherApp, "外部空间", "不允许跨空间关联", List.of(), "zh-CN"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE ai_job SET parent_job_id = ? WHERE id = ?", foreign.jobId(), child.jobId()));
    }

    @Test
    void concurrentUnknownRerunsReserveOnlyOneNewCredit() throws Exception {
        String source = unknown(), key = UUID.randomUUID().toString();
        var original = get(source);
        var request = retryRequest(source);
        try (var pool = Executors.newFixedThreadPool(4)) {
            List<Callable<JobService.Accepted>> work = new ArrayList<>();
            for (int i = 0; i < 8; i++) work.add(() -> jobs.retry("owner.a", workspaceId, source, key, request));
            var results = pool.invokeAll(work);
            var first = results.getFirst().get();
            for (var result : results) assertEquals(first, result.get());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_job WHERE parent_job_id = ?", Integer.class, source));
        }
        assertEquals(original, get(source));
        assertEquals("UNCERTAIN", credit(source));
        assertEquals(1, jdbc.queryForObject("SELECT reserved FROM budget_account WHERE workspace_id = ?", Integer.class, workspaceId));
        assertEquals(1, jdbc.queryForObject("SELECT uncertain FROM budget_account WHERE workspace_id = ?", Integer.class, workspaceId));
    }

    @Test
    void rerunRejectsMissingAcknowledgmentReasonExpiredPayloadAndStaleVersion() {
        String source = failed();
        long version = get(source).version();
        for (var invalid : List.of(new JobService.RetryJob(version, "原因", false), new JobService.RetryJob(version, " ", true), new JobService.RetryJob(version, "字".repeat(301), true), new JobService.RetryJob(null, "原因", true)))
            assertEquals(422, assertThrows(ApiException.class, () -> jobs.retry("owner.a", workspaceId, source, UUID.randomUUID().toString(), invalid)).status);
        assertEquals(409, assertThrows(ApiException.class, () -> jobs.retry("owner.a", workspaceId, source, "stale", new JobService.RetryJob(version + 1, "原因", true))).status);
        jdbc.update("UPDATE ai_job SET payload_expires_at = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND) WHERE id = ?", source);
        assertEquals(410, assertThrows(ApiException.class, () -> jobs.retry("owner.a", workspaceId, source, "expired", retryRequest(source))).status);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_job WHERE parent_job_id = ?", Integer.class, source));
    }

    @Test
    void rerunRejectsActiveAndSuccessfulSourcesAndRollsBackQuotaDenial() {
        String queued = create();
        assertEquals(409, assertThrows(ApiException.class, () -> jobs.retry("owner.a", workspaceId, queued, "queued", retryRequest(queued))).status);
        jobs.cancel("owner.a", workspaceId, queued, "cancel", new JobService.CancelJob(0L));
        assertEquals(409, assertThrows(ApiException.class, () -> jobs.retry("owner.a", workspaceId, queued, "cancelled", retryRequest(queued))).status);
        String successful = create();
        assertTrue(worker.execute(ready(successful)));
        assertEquals(409, assertThrows(ApiException.class, () -> jobs.cancel("owner.a", workspaceId, successful, "success-cancel", new JobService.CancelJob(get(successful).version()))).status);
        assertEquals(409, assertThrows(ApiException.class, () -> jobs.retry("owner.a", workspaceId, successful, "success-retry", retryRequest(successful))).status);
        String source = unknown();
        jdbc.update("UPDATE budget_account SET daily_limit = reserved + spent + uncertain WHERE workspace_id = ?", workspaceId);
        var original = get(source);
        assertEquals(429, assertThrows(ApiException.class, () -> jobs.retry("owner.a", workspaceId, source, "quota", retryRequest(source))).status);
        assertEquals(original, get(source));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_job WHERE parent_job_id = ?", Integer.class, source));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_record WHERE operation = 'RERUN_JOB'", Integer.class));
    }

    @Test
    void revokedActorCannotReplayEitherCommandOrReadNewChild() {
        String source = unknown(), retryKey = UUID.randomUUID().toString(), cancelKey = UUID.randomUUID().toString();
        var request = retryRequest(source);
        var child = jobs.retry("operator.a", workspaceId, source, retryKey, request);
        var cancelRequest = new JobService.CancelJob(0L);
        jobs.cancel("operator.a", workspaceId, child.jobId(), cancelKey, cancelRequest);
        assertEquals(403, assertThrows(ApiException.class, () -> jobs.retry("viewer.a", workspaceId, source, "viewer", request)).status);
        assertEquals(404, assertThrows(ApiException.class, () -> jobs.retry("owner.b", workspaceId, source, "other", request)).status);
        String actor = jdbc.queryForObject("SELECT id FROM user_account WHERE username = 'operator.a'", String.class);
        jdbc.update("UPDATE workspace_member SET active = FALSE WHERE workspace_id = ? AND user_id = ?", workspaceId, actor);
        try {
            assertEquals(404, assertThrows(ApiException.class, () -> jobs.retry("operator.a", workspaceId, source, retryKey, request)).status);
            assertEquals(404, assertThrows(ApiException.class, () -> jobs.cancel("operator.a", workspaceId, child.jobId(), cancelKey, cancelRequest)).status);
            assertEquals(404, assertThrows(ApiException.class, () -> jobs.get("operator.a", workspaceId, child.jobId())).status);
        } finally { jdbc.update("UPDATE workspace_member SET active = TRUE WHERE workspace_id = ? AND user_id = ?", workspaceId, actor); }
    }

    @Test
    void idempotencyIncludesResourceReasonAndOperationAndDoesNotBypassTtl() {
        String first = create(), second = create(), key = UUID.randomUUID().toString();
        jobs.cancel("owner.a", workspaceId, first, key, new JobService.CancelJob(0L));
        assertEquals(409, assertThrows(ApiException.class, () -> jobs.cancel("owner.a", workspaceId, second, key, new JobService.CancelJob(0L))).status);
        jobs.create("owner.a", workspaceId, key, request()); // same key, different operation is independent.
        String source = failed(), retryKey = UUID.randomUUID().toString();
        var request = retryRequest(source);
        var child = jobs.retry("owner.a", workspaceId, source, retryKey, request);
        assertEquals(409, assertThrows(ApiException.class, () -> jobs.retry("owner.a", workspaceId, source, retryKey, new JobService.RetryJob(request.expectedVersion(), "不同原因", true))).status);
        jdbc.update("UPDATE ai_job SET payload_expires_at = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND) WHERE id = ?", source);
        assertEquals(410, assertThrows(ApiException.class, () -> jobs.retry("owner.a", workspaceId, source, retryKey, request)).status);
        assertEquals(source, get(child.jobId()).parentJobId());
    }
}

package dev.relayops.job;

import dev.relayops.common.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "RELAYOPS_DATABASE_TESTS", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(properties = {"relayops.worker-enabled=false", "relayops.consumer-enabled=false", "relayops.relay-enabled=false", "relayops.recovery-enabled=false"})
class DatabaseTest {
    @Autowired JobService jobs;
    @Autowired JdbcTemplate jdbc;
    @Autowired JobWorker worker;
    @Autowired FakeModel model;
    @Autowired TransactionTemplate tx;
    String workspaceId;
    String appId;

    @BeforeAll
    void resetDedicatedTestSchema() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertTrue(connection.getMetaData().getURL().contains("/relayops_test?"), "Refuse to reset a non-test database");
        }
        jdbc.update("UPDATE ai_job SET parent_job_id = NULL");
        for (String table : List.of("delivery_outbox", "delivery_attempt", "delivery_run", "replay_plan", "delivery", "job_destination", "destination", "quarantine_message", "outbox_event", "audit_event", "idempotency_record", "ai_result", "budget_entry", "ai_attempt", "ai_job", "budget_account")) jdbc.update("DELETE FROM " + table);
    }

    @BeforeEach
    void scope() {
        workspaceId = jdbc.queryForObject("SELECT id FROM workspace WHERE code = 'a'", String.class);
        appId = jdbc.queryForObject("SELECT id FROM application WHERE workspace_id = ? LIMIT 1", String.class, workspaceId);
    }

    JobService.CreateJob request() { return new JobService.CreateJob(appId, "集成验证", "只有事务提交后才能返回 202。", List.of(), "zh-CN"); }
    JobService.Accepted create() { return jobs.create("owner.a", workspaceId, UUID.randomUUID().toString(), request()); }

    @Test
    void concurrentSameIntentReservesOnlyOnce() throws Exception {
        String key = UUID.randomUUID().toString();
        try (var pool = Executors.newFixedThreadPool(4)) {
            List<Callable<JobService.Accepted>> work = new ArrayList<>();
            for (int i = 0; i < 8; i++) work.add(() -> jobs.create("owner.a", workspaceId, key, request()));
            var futures = pool.invokeAll(work);
            String id = futures.getFirst().get().jobId();
            for (var future : futures) assertEquals(id, future.get().jobId());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM budget_entry WHERE job_id = ? AND state = 'RESERVED'", Integer.class, id));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_record WHERE job_id = ?", Integer.class, id));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ?", Integer.class, id));
        }
    }

    @Test
    void conflictAndCurrentAuthorizationAreCheckedOnReplay() {
        String key = UUID.randomUUID().toString();
        jobs.create("owner.a", workspaceId, key, request());
        var changed = new JobService.CreateJob(appId, "另一个意图", "不同正文", List.of(), "zh-CN");
        assertEquals(409, assertThrows(ApiException.class, () -> jobs.create("owner.a", workspaceId, key, changed)).status);
        assertEquals(403, assertThrows(ApiException.class, () -> jobs.create("viewer.a", workspaceId, key, request())).status);
    }

    @Test
    void workspaceAndBodyBoundariesAreEnforced() {
        String id = create().jobId();
        assertEquals(404, assertThrows(ApiException.class, () -> jobs.get("owner.b", workspaceId, id)).status);
        assertEquals(id, jobs.get("viewer.a", workspaceId, id).jobId());
        assertEquals(403, assertThrows(ApiException.class, () -> jobs.result("viewer.a", workspaceId, id)).status);
        jdbc.update("UPDATE ai_job SET payload_expires_at = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND) WHERE id = ?", id);
        assertEquals(410, assertThrows(ApiException.class, () -> jobs.result("owner.a", workspaceId, id)).status);
    }

    @Test
    void sentCrashBecomesUnknownAndNeverInvokesModelOnRecovery() {
        String id = create().jobId();
        long calls = model.callCount();
        tx.executeWithoutResult(status -> {
            jdbc.update("UPDATE ai_job SET status = 'RUNNING', lease_token = ?, lease_until = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND) WHERE id = ?", UUID.randomUUID().toString(), id);
            jdbc.update("INSERT INTO ai_attempt(id,workspace_id,job_id,lease_token,status,sent_at) VALUES (?,?,?,?,'SENT',CURRENT_TIMESTAMP(3))", UUID.randomUUID().toString(), workspaceId, id, UUID.randomUUID().toString());
            jdbc.update("UPDATE budget_account b JOIN ai_job j ON j.workspace_id = b.workspace_id AND j.accepted_date = b.accepted_date SET b.reserved = b.reserved - 1, b.spent = b.spent + 1 WHERE j.id = ?", id);
            jdbc.update("UPDATE budget_entry SET state = 'SPENT' WHERE job_id = ?", id);
        });
        worker.recover(); worker.recover();
        assertEquals("UNKNOWN", jobs.get("owner.a", workspaceId, id).status());
        assertEquals("UNCERTAIN", jdbc.queryForObject("SELECT state FROM budget_entry WHERE job_id = ?", String.class, id));
        assertEquals(calls, model.callCount());
    }

    @Test
    void queueExpiryReleasesReservation() {
        String id = create().jobId();
        jdbc.update("UPDATE ai_job SET queue_expires_at = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND) WHERE id = ?", id);
        worker.recover();
        assertEquals("FAILED", jobs.get("owner.a", workspaceId, id).status());
        assertEquals("RELEASED", jdbc.queryForObject("SELECT state FROM budget_entry WHERE job_id = ?", String.class, id));
    }

    @Test
    void revokedMemberCannotReplayPreviouslyAcceptedIntent() {
        String key = UUID.randomUUID().toString();
        String id = jobs.create("operator.a", workspaceId, key, request()).jobId();
        String actor = jdbc.queryForObject("SELECT id FROM user_account WHERE username = 'operator.a'", String.class);
        jdbc.update("UPDATE workspace_member SET active = FALSE WHERE workspace_id = ? AND user_id = ?", workspaceId, actor);
        try {
            assertEquals(404, assertThrows(ApiException.class, () -> jobs.create("operator.a", workspaceId, key, request())).status);
            assertEquals(404, assertThrows(ApiException.class, () -> jobs.get("operator.a", workspaceId, id)).status);
        } finally {
            jdbc.update("UPDATE workspace_member SET active = TRUE WHERE workspace_id = ? AND user_id = ?", workspaceId, actor);
        }
    }

    @Test
    void competingNewIntentsCannotExceedDailyLimit() throws Exception {
        String testWorkspace = UUID.randomUUID().toString(), testApp = UUID.randomUUID().toString();
        String actor = jdbc.queryForObject("SELECT id FROM user_account WHERE username = 'owner.a'", String.class);
        String tenantId=jdbc.queryForObject("SELECT tenant_id FROM workspace WHERE id=?",String.class,workspaceId);
        jdbc.update("INSERT INTO workspace(id,code,name,tenant_id) VALUES (?,?,?,?)", testWorkspace, "test-" + UUID.randomUUID().toString().substring(0, 8), "隔离额度测试",tenantId);
        jdbc.update("INSERT INTO workspace_member(workspace_id,user_id,role) VALUES (?,?,'OWNER')", testWorkspace, actor);
        jdbc.update("INSERT INTO application(id,workspace_id,name) VALUES (?,?,'额度验证')", testApp, testWorkspace);
        var date = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        jdbc.update("INSERT INTO budget_account(workspace_id,accepted_date,daily_limit) VALUES (?,?,2)", testWorkspace, date);
        var request = new JobService.CreateJob(testApp, "额度竞争", "不允许超额受理。", List.of(), "zh-CN");
        try (var pool = Executors.newFixedThreadPool(4)) {
            List<Callable<Integer>> work = new ArrayList<>();
            for (int i = 0; i < 4; i++) work.add(() -> {
                try { jobs.create("owner.a", testWorkspace, UUID.randomUUID().toString(), request); return 202; }
                catch (ApiException ex) { return ex.status; }
            });
            var outcomes = new ArrayList<Integer>();
            for (var future : pool.invokeAll(work)) outcomes.add(future.get());
            assertEquals(2, outcomes.stream().filter(code -> code == 202).count());
            assertEquals(2, outcomes.stream().filter(code -> code == 429).count());
            assertEquals(2, jdbc.queryForObject("SELECT reserved FROM budget_account WHERE workspace_id = ? AND accepted_date = ?", Integer.class, testWorkspace, date));
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM ai_job WHERE workspace_id = ?", Integer.class, testWorkspace));
        } finally {
            // Preserve test records but remove the temporary space from the demo user's workspace list.
            jdbc.update("UPDATE workspace_member SET active = FALSE WHERE workspace_id = ? AND user_id = ?", testWorkspace, actor);
        }
    }
}

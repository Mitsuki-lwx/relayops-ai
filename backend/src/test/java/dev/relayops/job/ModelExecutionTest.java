package dev.relayops.job;

import dev.relayops.common.ApiException;
import dev.relayops.common.PayloadCipher;
import dev.relayops.messaging.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "RELAYOPS_MESSAGING_TESTS", matches = "true")
@SpringBootTest(properties = {"relayops.worker-enabled=false", "relayops.consumer-enabled=false", "relayops.relay-enabled=false", "relayops.recovery-enabled=false",
        "relayops.model.mode=compatible", "relayops.model.api-key=test-key-not-real", "relayops.model.name=fixture-model", "relayops.model.allow-local-http=true",
        "relayops.model.timeout-seconds=6", "relayops.lease-seconds=3"})
class ModelExecutionTest {
    static final StubProvider provider;
    static { try { provider = new StubProvider(); } catch (Exception ex) { throw new ExceptionInInitializerError(ex); } }
    @DynamicPropertySource static void endpoint(DynamicPropertyRegistry registry) { registry.add("relayops.model.endpoint", provider::endpoint); }
    @AfterAll static void stop() { provider.close(); }
    @Autowired JobService jobs;
    @Autowired JobWorker worker;
    @Autowired LeaseHeartbeat heartbeat;
    @Autowired ModelGateway gateway;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired OutboxStore outbox;
    @Autowired OutboxRelay relay;
    @Autowired RabbitTemplate rabbit;
    @Autowired AiConsumer consumer;
    @Autowired PayloadCipher cipher;
    String workspace, application;

    @BeforeEach void fixtures() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertTrue(connection.getMetaData().getURL().contains("/relayops_test?"), "Refuse to reset non-test database");
        }
        assertEquals("relayops-test", rabbit.getConnectionFactory().getVirtualHost());
        jdbc.update("UPDATE ai_job SET parent_job_id = NULL");
        for (String table : List.of("quarantine_message", "outbox_event", "audit_event", "idempotency_record", "ai_result", "budget_entry", "ai_attempt", "ai_job", "budget_account")) jdbc.update("DELETE FROM " + table);
        rabbit.execute(channel -> { channel.queuePurge(RabbitConfig.AI_QUEUE); return null; });
        provider.calls.set(0); provider.status = 200; provider.finish = "stop"; provider.raw = null;
        provider.content = provider.validAnalysis(); provider.delayMillis = 0; provider.slowBody = false; provider.disconnect = false;
        workspace = jdbc.queryForObject("SELECT id FROM workspace WHERE code='a'", String.class);
        application = jdbc.queryForObject("SELECT id FROM application WHERE workspace_id=? LIMIT 1", String.class, workspace);
    }
    JobService.CreateJob request() { return new JobService.CreateJob(application, "真实协议测试", "必须持久化任务，再返回受理。", List.of(), "zh-CN", true); }
    String create() { return jobs.create("owner.a", workspace, UUID.randomUUID().toString(), request()).jobId(); }
    ReadyMessage ready(String id) { return outbox.claim().stream().map(OutboxStore.Claimed::envelope).filter(e -> e.aggregateId().equals(id)).findFirst().orElseThrow(); }
    JobService.Job job(String id) { return jobs.get("owner.a", workspace, id); }
    String credit(String id) { return jdbc.queryForObject("SELECT state FROM budget_entry WHERE job_id=?", String.class, id); }
    int sent(String id) { return jdbc.queryForObject("SELECT COUNT(*) FROM ai_attempt WHERE job_id=? AND sent_at IS NOT NULL", Integer.class, id); }

    @Test void rejectsMissingExternalAcknowledgementWithoutAdmission() {
        var missing = new JobService.CreateJob(application, "未确认", "必须持久化任务", List.of(), "zh-CN");
        assertEquals(422, assertThrows(ApiException.class, () -> jobs.create("owner.a", workspace, "missing-ack", missing)).status);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_job", Integer.class));
        assertEquals(0, provider.calls.get());
    }
    @Test void compatibleResultIsEncryptedAndDuplicateMessageDoesNotCallAgain() {
        String id = create(); var envelope = ready(id);
        assertTrue(worker.execute(envelope)); assertTrue(worker.execute(envelope));
        assertEquals("SUCCEEDED", job(id).status()); assertEquals("SPENT", credit(id)); assertEquals(1, sent(id));
        assertEquals(1, provider.calls.get()); assertEquals(gateway.currentProfile().profileId(), job(id).modelProfile());
        byte[] encrypted = jdbc.queryForObject("SELECT result_cipher FROM ai_result WHERE job_id=?", byte[].class, id);
        assertTrue(cipher.decrypt(encrypted, workspace + ":" + id + ":result").contains("需先持久化"));
        jobs.result("owner.a", workspace, id);
    }
    @Test void obsoleteCompatibleProfileFailsBeforeSentAndReleasesCredit() {
        String id = create(); jdbc.update("UPDATE ai_job SET model_profile='compat-unavailable' WHERE id=?", id);
        assertTrue(worker.execute(ready(id)));
        assertEquals("MODEL_CONFIG_CHANGED", job(id).errorCode()); assertEquals("FAILED", job(id).status());
        assertEquals("RELEASED", credit(id)); assertEquals(0, sent(id)); assertEquals(0, provider.calls.get());
    }
    @Test void oldFakeTaskStillRunsAndExplicitRerunUsesCurrentProfile() {
        String old = create();
        jdbc.update("UPDATE ai_job SET model_profile='fake-extract-v1',prompt_version='extract.v1' WHERE id=?", old);
        assertTrue(worker.execute(ready(old))); assertEquals("SUCCEEDED", job(old).status()); assertEquals(0, provider.calls.get());
        String source = create();
        jdbc.update("UPDATE ai_job SET model_profile='fake-extract-v1',queue_expires_at=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id=?", source);
        worker.recover();
        var child = jobs.retry("owner.a", workspace, source, "new-model", new JobService.RetryJob(job(source).version(), "人工确认使用新配置", true));
        assertEquals(source, job(child.jobId()).parentJobId()); assertEquals(gateway.currentProfile().profileId(), job(child.jobId()).modelProfile());
        assertTrue(worker.execute(ready(child.jobId()))); assertEquals("SUCCEEDED", job(child.jobId()).status());
        assertEquals("FAILED", job(source).status()); assertEquals(1, provider.calls.get());
    }
    @Test void forgedEvidenceFailsWithoutSavingResultOrRetrying() {
        provider.content = provider.validAnalysis().replace("必须持久化任务", "原文中不存在的承诺");
        String id = create(); var envelope = ready(id);
        assertTrue(worker.execute(envelope)); assertTrue(worker.execute(envelope));
        assertEquals("FAILED", job(id).status()); assertEquals("EVIDENCE_INVALID", job(id).errorCode());
        assertEquals("SPENT", credit(id)); assertEquals(1, provider.calls.get());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_result WHERE job_id=?", Integer.class, id));
    }
    @Test void knownSupplierRejectionIsTerminalAndKeepsSentUsage() {
        provider.status = 401; provider.raw = "sensitive provider error";
        String id = create(); var envelope = ready(id);
        assertTrue(worker.execute(envelope)); assertTrue(worker.execute(envelope));
        assertEquals("MODEL_REJECTED", job(id).errorCode()); assertEquals("FAILED", job(id).status());
        assertEquals("SPENT", credit(id)); assertEquals(1, provider.calls.get());
    }
    @Test void timeoutRecoversToUnknownAndCannotResend() {
        provider.slowBody = true; provider.delayMillis = 9000;
        String id = create(); var envelope = ready(id);
        assertFalse(worker.execute(envelope)); assertEquals("RUNNING", job(id).status());
        jdbc.update("UPDATE ai_job SET lease_until=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id=?", id);
        worker.recover(); worker.recover(); assertTrue(worker.execute(envelope));
        assertEquals("UNKNOWN", job(id).status()); assertEquals("UNCERTAIN", credit(id));
        assertEquals(1, sent(id)); assertEquals(1, provider.calls.get());
    }
    @Test void heartbeatRejectsWrongTokenGenerationAndExpiredLease() {
        String id = create(); var envelope = ready(id);
        JobWorker.Work work = tx.execute(status -> ReflectionTestUtils.invokeMethod(worker, "claim", envelope));
        assertNotNull(work); assertEquals(1, heartbeat.renew(work));
        var wrongToken = new JobWorker.Work(work.id(), work.workspaceId(), work.date(), work.title(), work.input(), work.language(), "wrong-token", work.generation(), work.modelProfile());
        assertEquals(0, heartbeat.renew(wrongToken));
        jdbc.update("UPDATE ai_job SET dispatch_generation=dispatch_generation+1 WHERE id=?", id);
        assertEquals(0, heartbeat.renew(work));
        jdbc.update("UPDATE ai_job SET dispatch_generation=?,lease_until=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id=?", work.generation(), id);
        assertEquals(0, heartbeat.renew(work));
    }
    @Test void realBrokerAndHeartbeatCompleteALongerThanLeaseRequestExactlyOnce() throws Exception {
        provider.delayMillis = 4500;
        String id = create(); relay.publish(outbox.claim());
        try (var pool = Executors.newSingleThreadExecutor()) {
            var running = pool.submit(() -> rabbit.execute(channel -> {
                var delivery = channel.basicGet(RabbitConfig.AI_QUEUE, false);
                assertNotNull(delivery);
                var properties = new MessageProperties(); properties.setDeliveryTag(delivery.getEnvelope().getDeliveryTag()); properties.setMessageId(delivery.getProps().getMessageId());
                consumer.consume(new Message(delivery.getBody(), properties), channel); return true;
            }));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            while (provider.calls.get() == 0 && System.nanoTime() < deadline) Thread.sleep(20);
            assertEquals(1, provider.calls.get());
            Thread.sleep(3300); worker.recover(); assertEquals("RUNNING", job(id).status());
            assertEquals(Boolean.TRUE, running.get(10, TimeUnit.SECONDS));
        }
        assertEquals("SUCCEEDED", job(id).status()); assertEquals(1, sent(id)); assertEquals(1, provider.calls.get());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_result WHERE job_id=?", Integer.class, id));
    }
}

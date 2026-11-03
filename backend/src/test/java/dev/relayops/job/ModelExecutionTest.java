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
        for (String table : List.of("delivery_outbox", "delivery_attempt", "delivery_run", "replay_plan", "delivery", "job_destination", "destination", "quarantine_message", "outbox_event", "audit_event", "idempotency_record", "ai_result", "budget_entry", "ai_attempt", "ai_job", "budget_account")) jdbc.update("DELETE FROM " + table);
        jdbc.update("UPDATE workspace SET daily_limit = 20, budget_version = 0");
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

    @Test void ownerCanSetDailyLimitWithinUsageAndCeiling() {
        var initial = jobs.budgetPolicy("owner.a", workspace);
        assertEquals(20, initial.get("dailyLimit")); assertEquals(0L, initial.get("version")); assertEquals(100, initial.get("maxDailyLimit")); assertEquals(true, initial.get("canEdit"));
        assertEquals(false, jobs.budgetPolicy("viewer.a", workspace).get("canEdit"));
        assertEquals(403, assertThrows(ApiException.class, () -> jobs.updateBudgetPolicy("viewer.a", workspace, new JobService.BudgetPolicyRequest(5, 0L))).status);
        var updated = jobs.updateBudgetPolicy("owner.a", workspace, new JobService.BudgetPolicyRequest(2, 0L));
        assertEquals(2, updated.get("dailyLimit")); assertEquals(1L, updated.get("version"));
        @SuppressWarnings("unchecked") var credits = (java.util.Map<String,Object>) jobs.budget("owner.a", workspace).get("credits");
        assertEquals(2, credits.get("dailyLimit"));
        String first = create(), second = create();
        assertEquals(422, assertThrows(ApiException.class, () -> jobs.updateBudgetPolicy("owner.a", workspace, new JobService.BudgetPolicyRequest(1, 1L))).status);
        jobs.cancel("owner.a", workspace, first, UUID.randomUUID().toString(), new JobService.CancelJob(job(first).version()));
        assertEquals(1, jobs.updateBudgetPolicy("owner.a", workspace, new JobService.BudgetPolicyRequest(1, 1L)).get("dailyLimit"));
        assertEquals(429, assertThrows(ApiException.class, this::create).status);
        assertEquals(422, assertThrows(ApiException.class, () -> jobs.updateBudgetPolicy("owner.a", workspace, new JobService.BudgetPolicyRequest(101, 2L))).status);
        assertEquals(409, assertThrows(ApiException.class, () -> jobs.updateBudgetPolicy("owner.a", workspace, new JobService.BudgetPolicyRequest(3, 0L))).status);
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE operation='BUDGET_POLICY_UPDATED'", Integer.class) >= 2);
        assertEquals(1, jdbc.queryForObject("SELECT daily_limit FROM budget_account WHERE workspace_id=? AND accepted_date=?", Integer.class, workspace, java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))));
        assertNotNull(second);
    }

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

    @Test void reviewPurposeIsFrozenIdempotentScopedAndSingleCall() {
        provider.content=ReviewFixtures.json(provider.json);
        String key=UUID.randomUUID().toString();
        var request=new JobService.CreateJob(application,"需求评审",ReviewFixtures.INPUT,List.of(),"zh-CN",true,"REQUIREMENTS_REVIEW");
        String id=jobs.create("owner.a",workspace,key,request).jobId();
        assertEquals(id,jobs.create("owner.a",workspace,key,request).jobId());
        assertEquals(409,assertThrows(ApiException.class,()->jobs.create("owner.a",workspace,key,new JobService.CreateJob(application,"需求评审",ReviewFixtures.INPUT,List.of(),"zh-CN",true))).status);
        var message=ready(id);assertTrue(worker.execute(message));assertTrue(worker.execute(message));
        assertEquals("REQUIREMENTS_REVIEW",job(id).taskType());assertEquals("SUCCEEDED",job(id).status());assertEquals(1,provider.calls.get());assertEquals(1,sent(id));
        assertEquals(TaskType.REQUIREMENTS_REVIEW.schemaVersion,jdbc.queryForObject("SELECT schema_version FROM ai_job WHERE id=?",String.class,id));
        assertEquals(403,assertThrows(ApiException.class,()->jobs.result("viewer.a",workspace,id)).status);
        assertEquals(404,assertThrows(ApiException.class,()->jobs.result("owner.b",workspace,id)).status);
    }

    @Test void defaultAndExplicitExtractShareLegacyNormalizedIntent() {
        var ordinary=request();String key=UUID.randomUUID().toString();String id=jobs.create("owner.a",workspace,key,ordinary).jobId();
        var explicit=new JobService.CreateJob(ordinary.applicationId(),ordinary.title(),ordinary.inputText(),ordinary.destinationIds(),ordinary.outputLanguage(),true,"EXTRACT");
        assertEquals(id,jobs.create("owner.a",workspace,key,explicit).jobId());
        var normalized=ReflectionTestUtils.invokeMethod(jobs,"normalize",explicit);
        assertFalse(provider.json.readTree(provider.json.writeValueAsString(normalized)).has("taskType"));
        assertEquals(422,assertThrows(ApiException.class,()->jobs.modelProfile("owner.a",workspace,"UNKNOWN")).status);
        assertEquals(404,assertThrows(ApiException.class,()->jobs.modelProfile("owner.b",workspace,"UNKNOWN")).status);
    }

    @Test void invalidReviewFailsOnceAndExplicitRerunRetainsPurpose() {
        provider.content=ReviewFixtures.json(provider.json).replace("超时30秒","虚构引用");
        var request=new JobService.CreateJob(application,"评审失败",ReviewFixtures.INPUT,List.of(),"zh-CN",true,"REQUIREMENTS_REVIEW");
        String id=jobs.create("owner.a",workspace,UUID.randomUUID().toString(),request).jobId();
        assertTrue(worker.execute(ready(id)));assertEquals("FAILED",job(id).status());assertEquals("EVIDENCE_INVALID",job(id).errorCode());assertEquals(1,provider.calls.get());assertEquals("SPENT",credit(id));
        var retry=jobs.retry("owner.a",workspace,id,UUID.randomUUID().toString(),new JobService.RetryJob(job(id).version(),"重新核对引用",true));
        assertEquals("REQUIREMENTS_REVIEW",job(retry.jobId()).taskType());
        provider.content=ReviewFixtures.json(provider.json);assertTrue(worker.execute(ready(retry.jobId())));
        assertEquals("SUCCEEDED",job(retry.jobId()).status());assertEquals(2,provider.calls.get());assertEquals("SPENT",credit(id));
    }

    @Test void tracingCollectorFailureDoesNotChangeSuccessOrDuplicateCalls() throws Exception {
        var collector = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var received = new java.util.concurrent.atomic.AtomicInteger();
        collector.createContext("/", exchange -> {
            try (exchange) {
                String body = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                assertFalse(body.contains("必须持久化任务")); assertFalse(body.contains("test-key-not-real"));
                received.incrementAndGet(); exchange.sendResponseHeaders(503,-1);
            }
        });
        collector.start();
        var original = ReflectionTestUtils.getField(worker,"tracing");
        try (var tracing = new dev.relayops.observability.TaskTracing(true,"http://127.0.0.1:"+collector.getAddress().getPort(),"pk-lf-fixture","sk-lf-fixture","test")) {
            ReflectionTestUtils.setField(worker,"tracing",tracing);
            String id=create(); var message=ready(id);
            assertTrue(worker.execute(message)); assertTrue(worker.execute(message));
            tracing.flush();
            assertEquals("SUCCEEDED",job(id).status()); assertEquals("SPENT",credit(id));
            assertEquals(1,sent(id)); assertEquals(1,provider.calls.get()); assertTrue(received.get()>0);
            assertFalse(io.opentelemetry.api.trace.Span.current().getSpanContext().isValid());
        } finally { ReflectionTestUtils.setField(worker,"tracing",original); collector.stop(0); }
    }

    @Test void tracingOptionalLocalLangfuseAcceptance() throws Exception {
        // Normal CI still verifies this path against a real local HTTP collector.
        boolean localLangfuse="true".equals(System.getenv("RELAYOPS_LANGFUSE_VERIFY"));
        var collector=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        collector.createContext("/",exchange->{try(exchange){exchange.getRequestBody().readAllBytes();exchange.sendResponseHeaders(200,-1);}});
        collector.start();
        String base=localLangfuse?System.getenv("LANGFUSE_BASE_URL"):"http://127.0.0.1:"+collector.getAddress().getPort();
        String publicKey=localLangfuse?System.getenv("LANGFUSE_PUBLIC_KEY"):"pk-lf-fixture";
        String secretKey=localLangfuse?System.getenv("LANGFUSE_SECRET_KEY"):"sk-lf-fixture";
        var original=ReflectionTestUtils.getField(worker,"tracing");
        try(var tracing=new dev.relayops.observability.TaskTracing(true,base,publicKey,secretKey,"test")) {
            ReflectionTestUtils.setField(worker,"tracing",tracing);
            provider.raw=provider.json.writeValueAsString(java.util.Map.of("choices",List.of(java.util.Map.of("finish_reason","stop","message",java.util.Map.of("content",provider.validAnalysis()))),"usage",java.util.Map.of("prompt_tokens",23,"completion_tokens",11,"total_tokens",34)));
            String id=create(); var message=ready(id);
            assertTrue(worker.execute(message)); assertTrue(worker.execute(message)); tracing.flush();
            assertEquals("SUCCEEDED",job(id).status()); assertEquals(1,provider.calls.get());
            if(localLangfuse) System.out.println("LANGFUSE_VERIFY_JOB="+id);
        } finally { ReflectionTestUtils.setField(worker,"tracing",original); collector.stop(0); }
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

package dev.relayops.job;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import dev.relayops.common.ApiException;
import dev.relayops.messaging.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@EnabledIfEnvironmentVariable(named = "RELAYOPS_MESSAGING_TESTS", matches = "true")
@SpringBootTest(properties = {
        "spring.datasource.url=${RELAYOPS_MESSAGING_DB_URL:jdbc:mysql://127.0.0.1:3307/relayops_messaging_test?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true}",
        "spring.rabbitmq.virtual-host=relayops-test", "relayops.consumer-enabled=false",
        "relayops.relay-enabled=false", "relayops.recovery-enabled=false", "relayops.pending-limit=2"
})
class MessagingTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired JobService jobs;
    @Autowired JobWorker worker;
    @Autowired FakeModel model;
    @Autowired OutboxRelay relay;
    @Autowired AiConsumer consumer;
    @Autowired RabbitTemplate rabbit;
    @Autowired AmqpAdmin admin;
    @Autowired JsonMapper json;
    @Autowired TransactionTemplate tx;
    @Autowired CachingConnectionFactory connectionFactory;
    @MockitoSpyBean OutboxStore outbox;
    String workspaceId;
    String appId;

    @BeforeEach
    void isolatedFixtures() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertTrue(connection.getMetaData().getURL().contains("/relayops_messaging_test?"), "Refuse to clean a non-test database");
        }
        assertEquals("relayops-test", connectionFactory.getVirtualHost(), "Refuse to purge a non-test vhost");
        ((RabbitAdmin) admin).initialize();
        admin.purgeQueue(RabbitConfig.AI_QUEUE, false);
        admin.purgeQueue(RabbitConfig.QUARANTINE_QUEUE, false);
        // Only operational fixtures in the dedicated messaging schema are reset; demo data is untouched.
        jdbc.update("UPDATE ai_job SET parent_job_id = NULL");
        for (String table : List.of("delivery_outbox", "delivery_attempt", "delivery_run", "delivery", "job_destination", "destination", "quarantine_message", "outbox_event", "audit_event", "idempotency_record", "ai_result", "budget_entry", "ai_attempt", "ai_job", "budget_account")) jdbc.update("DELETE FROM " + table);
        workspaceId = jdbc.queryForObject("SELECT id FROM workspace WHERE code = 'a'", String.class);
        appId = jdbc.queryForObject("SELECT id FROM application WHERE workspace_id = ? LIMIT 1", String.class, workspaceId);
    }

    JobService.CreateJob request() { return new JobService.CreateJob(appId, "消息故障验证", "消息可重复，模型不可自动重复调用。", List.of(), "zh-CN"); }
    String create() { return jobs.create("owner.a", workspaceId, UUID.randomUUID().toString(), request()).jobId(); }
    String state(String id) { return jobs.get("owner.a", workspaceId, id).status(); }

    void send(byte[] body, String id) throws Exception {
        var properties = new MessageProperties();
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setMessageId(id);
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        var correlation = new CorrelationData(UUID.randomUUID().toString());
        rabbit.send(RabbitConfig.READY_EXCHANGE, RabbitConfig.AI_KEY, new Message(body, properties), correlation);
        assertTrue(correlation.getFuture().get(5, TimeUnit.SECONDS).ack());
        assertNull(correlation.getReturned());
    }

    GetResponse delivery(Channel channel) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            var response = channel.basicGet(RabbitConfig.AI_QUEUE, false);
            if (response != null) return response;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Expected an actual Broker delivery");
    }

    void consumeOne() {
        rabbit.execute(channel -> {
            var response = delivery(channel);
            var properties = new MessageProperties();
            properties.setDeliveryTag(response.getEnvelope().getDeliveryTag());
            properties.setMessageId(response.getProps().getMessageId());
            consumer.consume(new Message(response.getBody(), properties), channel);
            return null;
        });
    }

    @Test
    void rolledBackAdmissionLeavesNoJobBudgetOrOutbox() {
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
            create();
            throw new IllegalStateException("injected transaction failure");
        }));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_job", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM budget_entry", Integer.class));
    }

    @Test
    void tenDuplicateEnvelopesOnlyCallModelOnce() throws Exception {
        String id = create();
        var claimed = outbox.claim().getFirst();
        byte[] bytes = json.writeValueAsBytes(claimed.envelope());
        assertEquals(8, json.readTree(bytes).size());
        assertFalse(new String(bytes, StandardCharsets.UTF_8).contains(request().inputText()));
        long calls = model.callCount();
        for (int i = 0; i < 10; i++) send(bytes, claimed.envelope().messageId());
        for (int i = 0; i < 10; i++) consumeOne();
        assertEquals("SUCCEEDED", state(id));
        assertEquals(calls + 1, model.callCount());
        assertEquals(1, jobs.get("owner.a", workspaceId, id).modelCalls());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_result WHERE job_id = ?", Integer.class, id));
    }

    @Test
    void mandatoryReturnRetainsRetryResponsibility() {
        String id = create();
        long calls = model.callCount();
        var binding = BindingBuilder.bind(new Queue(RabbitConfig.AI_QUEUE)).to(new DirectExchange(RabbitConfig.READY_EXCHANGE)).with(RabbitConfig.AI_KEY);
        admin.removeBinding(binding);
        try {
            relay.publish(outbox.claim());
            assertEquals("NEW", jdbc.queryForObject("SELECT status FROM outbox_event WHERE aggregate_id = ?", String.class, id));
            assertEquals("UNROUTABLE", jdbc.queryForObject("SELECT last_error FROM outbox_event WHERE aggregate_id = ?", String.class, id));
            assertEquals(calls, model.callCount());
            assertEquals("QUEUED", state(id));
        } finally { admin.declareBinding(binding); }
        jdbc.update("UPDATE outbox_event SET next_publish_at = CURRENT_TIMESTAMP(3)");
        relay.publish(outbox.claim());
        consumeOne();
        assertEquals("SUCCEEDED", state(id));
    }

    @Test
    void confirmedPublishWithLostDbWritebackReusesMessageId() throws Exception {
        String id = create();
        var first = outbox.claim().getFirst();
        send(json.writeValueAsBytes(first.envelope()), first.envelope().messageId());
        consumeOne(); // Broker has received and processed it; intentionally omit store.published(first).
        long calls = model.callCount();
        jdbc.update("UPDATE outbox_event SET claim_until = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND)");
        var replay = outbox.claim().getFirst();
        assertEquals(first.envelope().messageId(), replay.envelope().messageId());
        assertNotEquals(first.claimToken(), replay.claimToken());
        assertEquals(0, outbox.published(first)); // expired publisher cannot overwrite the new claim.
        relay.publish(List.of(replay));
        consumeOne();
        assertEquals(calls, model.callCount());
        assertEquals("SUCCEEDED", state(id));
        assertEquals("SENT", jdbc.queryForObject("SELECT status FROM outbox_event WHERE aggregate_id = ?", String.class, id));
    }

    @Test
    void committedResultWithLostConsumerAckIsNotExecutedAgain() {
        String id = create();
        relay.publish(outbox.claim());
        rabbit.execute(channel -> {
            var response = delivery(channel);
            assertTrue(worker.execute(ReadyMessage.decode(response.getBody(), json)));
            channel.abort(); // result transaction committed; ACK deliberately lost.
            return null;
        });
        long calls = model.callCount();
        consumeOne();
        assertEquals(calls, model.callCount());
        assertEquals(1, jobs.get("owner.a", workspaceId, id).modelCalls());
    }

    @Test
    void preparedLeaseRecoveryRejectsOldGenerationAndLateWriter() {
        String id = create(), oldToken = UUID.randomUUID().toString();
        var row = jdbc.queryForMap("SELECT * FROM ai_job WHERE id = ?", id);
        var oldWork = new JobWorker.Work(id, workspaceId, ((java.sql.Date) row.get("accepted_date")).toLocalDate(), "旧执行器", (byte[]) row.get("input_cipher"), "zh-CN", oldToken, 1, "fake-extract-v1");
        jdbc.update("UPDATE ai_job SET status = 'RUNNING', lease_token = ?, lease_until = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND) WHERE id = ?", oldToken, id);
        jdbc.update("INSERT INTO ai_attempt(id,workspace_id,job_id,lease_token,status) VALUES (?,?,?,?,'PREPARED')", UUID.randomUUID().toString(), workspaceId, id, oldToken);
        worker.recover(); worker.recover();
        assertEquals(2, jdbc.queryForObject("SELECT dispatch_generation FROM ai_job WHERE id = ?", Integer.class, id));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ?", Integer.class, id));
        var events = outbox.claim().stream().sorted(java.util.Comparator.comparingLong(e -> e.envelope().dispatchGeneration())).toList();
        relay.publish(List.of(events.getFirst()));
        consumeOne();
        assertEquals("QUEUED", state(id));
        relay.publish(List.of(events.getLast()));
        consumeOne();
        byte[] result = jdbc.queryForObject("SELECT result_cipher FROM ai_result WHERE job_id = ?", byte[].class, id);
        ReflectionTestUtils.invokeMethod(worker, "settle", oldWork, "{\"corrupt\":true}", null, true);
        assertArrayEquals(result, jdbc.queryForObject("SELECT result_cipher FROM ai_result WHERE job_id = ?", byte[].class, id));
        assertEquals(1, jobs.get("owner.a", workspaceId, id).modelCalls());
    }

    @Test
    void lostReadyMessageCreatesExactlyOneNewGeneration() {
        String id = create();
        relay.publish(outbox.claim());
        admin.purgeQueue(RabbitConfig.AI_QUEUE, false); // simulate loss of Broker ready data.
        jdbc.update("UPDATE outbox_event SET published_at = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 121 SECOND)");
        worker.recover(); worker.recover();
        assertEquals(2, jdbc.queryForObject("SELECT dispatch_generation FROM ai_job WHERE id = ?", Integer.class, id));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class));
        relay.publish(outbox.claim()); consumeOne();
        assertEquals("SUCCEEDED", state(id));
    }

    @Test
    void sentLeaseRecoveryAndRedeliveryNeverCallModelAgain() {
        String id = create(), token = UUID.randomUUID().toString();
        long calls = model.callCount();
        tx.executeWithoutResult(status -> {
            jdbc.update("UPDATE ai_job SET status = 'RUNNING', lease_token = ?, lease_until = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND) WHERE id = ?", token, id);
            jdbc.update("INSERT INTO ai_attempt(id,workspace_id,job_id,lease_token,status,sent_at) VALUES (?,?,?,?,'SENT',CURRENT_TIMESTAMP(3))", UUID.randomUUID().toString(), workspaceId, id, token);
            jdbc.update("UPDATE budget_account SET reserved = reserved - 1, spent = spent + 1 WHERE workspace_id = ?", workspaceId);
            jdbc.update("UPDATE budget_entry SET state = 'SPENT' WHERE job_id = ?", id);
        });
        worker.recover(); worker.recover();
        relay.publish(outbox.claim()); consumeOne();
        assertEquals("UNKNOWN", state(id));
        assertEquals("UNCERTAIN", jdbc.queryForObject("SELECT state FROM budget_entry WHERE job_id = ?", String.class, id));
        assertEquals(calls, model.callCount());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ?", Integer.class, id));
    }

    @Test
    void invalidMessagesKeepOnlySanitizedDiagnostics() throws Exception {
        create();
        var e = outbox.claim().getFirst().envelope();
        List<byte[]> invalid = List.of(
                "BROKER_SECRET_MARKER not JSON".getBytes(StandardCharsets.UTF_8),
                json.writeValueAsBytes(new ReadyMessage(99, e.messageId(), e.kind(), e.workspaceId(), e.aggregateId(), 1, Instant.now(), e.traceId())),
                json.writeValueAsBytes(new ReadyMessage(1, e.messageId(), e.kind(), UUID.randomUUID().toString(), e.aggregateId(), 1, Instant.now(), e.traceId())),
                json.writeValueAsBytes(new ReadyMessage(1, UUID.randomUUID().toString(), e.kind(), e.workspaceId(), UUID.randomUUID().toString(), 1, Instant.now(), e.traceId())));
        for (byte[] bytes : invalid) { send(bytes, UUID.randomUUID().toString()); consumeOne(); }
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM quarantine_message", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_attempt", Integer.class));
        for (int i = 0; i < 4; i++) {
            Message notification = rabbit.receive(RabbitConfig.QUARANTINE_QUEUE, 3000);
            assertNotNull(notification);
            String body = new String(notification.getBody(), StandardCharsets.UTF_8);
            assertFalse(body.contains("BROKER_SECRET_MARKER"));
            assertEquals(64, json.readTree(body).get("bodyHash").asText().length());
        }
    }

    @Test
    void capacityRejectsNewIntentButAllowsReplay() {
        String key = UUID.randomUUID().toString();
        var first = jobs.create("owner.a", workspaceId, key, request());
        create();
        assertEquals(503, assertThrows(ApiException.class, this::create).status);
        assertEquals(first, jobs.create("owner.a", workspaceId, key, request()));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM ai_job", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM budget_entry", Integer.class));
    }

    @Test
    void databaseFailureDoesNotAckAndChannelCloseRequeues() {
        String id = create();
        relay.publish(outbox.claim());
        long calls = model.callCount();
        doThrow(new DataAccessResourceFailureException("injected unavailable database")).doCallRealMethod().when(outbox).verify(any());
        consumeOne();
        assertEquals("QUEUED", state(id));
        assertEquals(calls, model.callCount());
        consumeOne();
        assertEquals("SUCCEEDED", state(id));
    }

    @Test
    void cancelledQueuedJobIgnoresAlreadyPublishedEnvelope() {
        String id = create();
        relay.publish(outbox.claim());
        long calls = model.callCount();
        jobs.cancel("owner.a", workspaceId, id, UUID.randomUUID().toString(), new JobService.CancelJob(0L));
        consumeOne();
        assertEquals("CANCELLED", state(id));
        assertEquals(calls, model.callCount());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_attempt WHERE job_id = ?", Integer.class, id));
        assertEquals("RELEASED", jdbc.queryForObject("SELECT state FROM budget_entry WHERE job_id = ?", String.class, id));
    }

    @Test
    void cancellationAndClaimRaceHaveOneLegalOutcome() {
        String id = create();
        relay.publish(outbox.claim());
        rabbit.execute(channel -> {
            var delivered = delivery(channel);
            var envelope = ReadyMessage.decode(delivered.getBody(), json);
            var start = new java.util.concurrent.CountDownLatch(1);
            try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
                var execute = pool.submit(() -> { start.await(); return worker.execute(envelope); });
                var cancel = pool.submit(() -> {
                    start.await();
                    try { jobs.cancel("owner.a", workspaceId, id, UUID.randomUUID().toString(), new JobService.CancelJob(0L)); return 200; }
                    catch (ApiException ex) { return ex.status; }
                });
                start.countDown();
                assertTrue(execute.get(10, TimeUnit.SECONDS));
                int status = cancel.get(10, TimeUnit.SECONDS);
                if (status == 200) {
                    assertEquals("CANCELLED", state(id));
                    assertEquals(0, jobs.get("owner.a", workspaceId, id).modelCalls());
                } else {
                    assertEquals(409, status);
                    assertEquals("SUCCEEDED", state(id));
                    assertEquals(1, jobs.get("owner.a", workspaceId, id).modelCalls());
                }
                channel.basicAck(delivered.getEnvelope().getDeliveryTag(), false);
            }
            return null;
        });
    }

    @Test
    void backlogRejectsRerunWithoutCreatingChildOrReservation() {
        String source = create();
        jdbc.update("UPDATE ai_job SET queue_expires_at = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND) WHERE id = ?", source);
        worker.recover();
        create(); // two unpublished records reach this test context's pending limit.
        int reserved = jdbc.queryForObject("SELECT reserved FROM budget_account WHERE workspace_id = ?", Integer.class, workspaceId);
        var request = new JobService.RetryJob(jobs.get("owner.a", workspaceId, source).version(), "恢复后重跑", true);
        assertEquals(503, assertThrows(ApiException.class, () -> jobs.retry("owner.a", workspaceId, source, UUID.randomUUID().toString(), request)).status);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_job WHERE parent_job_id = ?", Integer.class, source));
        assertEquals(reserved, jdbc.queryForObject("SELECT reserved FROM budget_account WHERE workspace_id = ?", Integer.class, workspaceId));
        assertEquals("FAILED", state(source));
    }
}

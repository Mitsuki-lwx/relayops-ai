package dev.relayops.messaging;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class OutboxRelay {
    private static final Logger LOG = LoggerFactory.getLogger(OutboxRelay.class);
    private final OutboxStore store;
    private final RabbitTemplate rabbit;
    private final JsonMapper json;
    private final boolean enabled;

    public OutboxRelay(OutboxStore store, RabbitTemplate rabbit, JsonMapper json, @Value("${relayops.relay-enabled}") boolean enabled) {
        this.store = store; this.rabbit = rabbit; this.json = json; this.enabled = enabled;
    }

    @Scheduled(fixedDelay = 1000)
    public void tick() {
        if (!enabled) return;
        try { publish(store.claim()); }
        catch (Exception ex) { LOG.warn("operation=outbox_deferred exceptionType={}", ex.getClass().getSimpleName()); }
    }

    record Pending(OutboxStore.Claimed event, CorrelationData correlation) {}

    public void publish(List<OutboxStore.Claimed> events) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        List<Pending> pending = new ArrayList<>();
        boolean connectionFailed = false;
        for (var event : events) {
            if (connectionFailed || System.nanoTime() >= deadline) { store.failed(event, "PUBLISH_DEFERRED"); continue; }
            var correlation = new CorrelationData(event.envelope().messageId() + ":" + event.claimToken());
            try {
                var properties = new MessageProperties();
                properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
                properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                properties.setMessageId(event.envelope().messageId());
                rabbit.send(RabbitConfig.READY_EXCHANGE, RabbitConfig.AI_KEY,
                        new Message(json.writeValueAsBytes(event.envelope()), properties), correlation);
                pending.add(new Pending(event, correlation));
            } catch (Exception ex) {
                connectionFailed = true;
                store.failed(event, "PUBLISH_CONNECTION_FAILED");
            }
        }
        try {
            long remaining = Math.max(1, deadline - System.nanoTime());
            CompletableFuture.allOf(pending.stream().map(p -> p.correlation().getFuture()).toArray(CompletableFuture[]::new)).get(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        catch (Exception ex) { /* Unconfirmed entries below retain retry responsibility. */ }
        for (Pending item : pending) {
            var future = item.correlation().getFuture();
            boolean ack = future.isDone() && !future.isCompletedExceptionally() && future.join().ack();
            if (ack && item.correlation().getReturned() == null) store.published(item.event());
            else store.failed(item.event(), item.correlation().getReturned() != null ? "UNROUTABLE" : "PUBLISH_UNCONFIRMED");
        }
    }
}

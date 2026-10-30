package dev.relayops.messaging;

import com.rabbitmq.client.Channel;
import dev.relayops.delivery.DeliveryWorker;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class DeliveryConsumer {
    private static final Logger LOG = LoggerFactory.getLogger(DeliveryConsumer.class);
    private final JsonMapper json;
    private final OutboxStore outbox;
    private final QuarantineStore quarantine;
    private final DeliveryWorker worker;
    private final RabbitTemplate rabbit;

    public DeliveryConsumer(JsonMapper json, OutboxStore outbox, QuarantineStore quarantine, DeliveryWorker worker, RabbitTemplate rabbit) {
        this.json = json; this.outbox = outbox; this.quarantine = quarantine; this.worker = worker; this.rabbit = rabbit;
    }

    @RabbitListener(id = "delivery-executor", queues = RabbitConfig.DELIVERY_QUEUE, autoStartup = "${relayops.consumer-enabled}")
    public void consume(Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        try {
            try {
                ReadyMessage envelope = ReadyMessage.decode(message.getBody(), json);
                if (!"DELIVERY_SEND".equals(envelope.kind())) throw new ReadyMessage.Invalid("WRONG_ROUTE_KIND");
                outbox.verify(envelope);
                if (!worker.execute(envelope)) { channel.abort(); return; }
            } catch (ReadyMessage.Invalid ex) {
                var diagnostic = quarantine.save(message.getBody(), message.getMessageProperties().getMessageId(), ex.code);
                try {
                    var properties = new MessageProperties();
                    properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
                    // The DB diagnostic is authoritative; the queue is a best-effort, sanitized notification.
                    // Never dead-letter original untrusted bytes, which may contain secrets.
                    rabbit.send(RabbitConfig.QUARANTINE_EXCHANGE, "invalid.v1", new Message(json.writeValueAsBytes(diagnostic), properties));
                } catch (Exception ignored) { LOG.warn("operation=quarantine_notification_deferred"); }
                channel.basicReject(tag, false);
                return;
            }
            channel.basicAck(tag, false);
        } catch (Exception ex) {
            // Closing the channel returns unacked deliveries. The container waits 5s before recovery.
            // Never log exception messages: DB/network errors may include sensitive arguments.
            LOG.warn("operation=consumer_deferred exceptionType={}", ex.getClass().getSimpleName());
            channel.abort();
        }
    }
}

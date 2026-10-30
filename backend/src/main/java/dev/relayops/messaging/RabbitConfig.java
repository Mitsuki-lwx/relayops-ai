package dev.relayops.messaging;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {
    public static final String READY_EXCHANGE = "relay.ready.v1";
    public static final String AI_QUEUE = "relay.ai.ready.v1";
    public static final String AI_KEY = "ai.execute.v1";
    public static final String DELIVERY_QUEUE = "relay.delivery.ready.v1";
    public static final String DELIVERY_KEY = "delivery.send.v1";
    public static final String QUARANTINE_EXCHANGE = "relay.quarantine.v1";
    public static final String QUARANTINE_QUEUE = "relay.quarantine.v1";

    @Bean
    Declarables topology() {
        var ready = new DirectExchange(READY_EXCHANGE, true, false);
        var quarantine = new DirectExchange(QUARANTINE_EXCHANGE, true, false);
        var ai = QueueBuilder.durable(AI_QUEUE).withArgument("x-queue-type", "classic")
                .maxLength(10000).overflow(QueueBuilder.Overflow.rejectPublish).build();
        var invalid = QueueBuilder.durable(QUARANTINE_QUEUE).withArgument("x-queue-type", "classic")
                .maxLength(1000).overflow(QueueBuilder.Overflow.rejectPublish).build();
        var delivery = QueueBuilder.durable(DELIVERY_QUEUE).withArgument("x-queue-type", "classic")
                .maxLength(10000).overflow(QueueBuilder.Overflow.rejectPublish).build();
        return new Declarables(ready, quarantine, ai, delivery, invalid,
                BindingBuilder.bind(delivery).to(ready).with(DELIVERY_KEY),
                BindingBuilder.bind(ai).to(ready).with(AI_KEY), BindingBuilder.bind(invalid).to(quarantine).with("invalid.v1"));
    }

    @Bean
    RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        var template = new RabbitTemplate(connectionFactory);
        template.setMandatory(true);
        return template;
    }

    @Bean
    SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(ConnectionFactory connectionFactory) {
        var factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(1);
        factory.setPrefetchCount(1);
        factory.setDefaultRequeueRejected(false);
        factory.setRecoveryInterval(5000L);
        factory.setMissingQueuesFatal(false);
        return factory;
    }
}

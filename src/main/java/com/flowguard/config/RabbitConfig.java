package com.flowguard.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    // ---- names, all in one place so nothing typos out of sync ----
    public static final String EXCHANGE = "webhook.exchange";
    public static final String DELIVERY_QUEUE = "webhook.delivery.queue";
    public static final String DELIVERY_ROUTING_KEY = "webhook.deliver";

    public static final String RETRY_EXCHANGE = "webhook.retry.exchange";
    public static final String RETRY_QUEUE = "webhook.retry.queue";
    public static final String RETRY_ROUTING_KEY = "webhook.retry";

    public static final String DLQ = "webhook.dlq";
    public static final String DLQ_ROUTING_KEY = "webhook.dead";
    public static final String DLX_EXCHANGE = "webhook.dlx.exchange";
    // ===== Main path: event fires -> published here -> consumer tries delivery =====

    @Bean
    public DirectExchange webhookExchange() {
        return new DirectExchange(EXCHANGE);
    }

    @Bean
    public Queue deliveryQueue() {
        // If a message exhausts delivery here without being ack'd/handled, Spring's
        // retry logic (next file) sends it to the retry exchange manually — this
        // queue itself has no TTL/DLX, it's just the "try now" queue.
        return QueueBuilder.durable(DELIVERY_QUEUE).build();
    }

    @Bean
    public Binding deliveryBinding() {
        return BindingBuilder.bind(deliveryQueue()).to(webhookExchange()).with(DELIVERY_ROUTING_KEY);
    }

    // ===== Retry path: failed message parked here with a TTL. =====
    // ===== When the TTL expires, RabbitMQ auto-dead-letters it BACK into the =====
    // ===== delivery exchange — this IS our backoff, no polling, no cron. =====

    @Bean
    public DirectExchange retryExchange() {
        return new DirectExchange(RETRY_EXCHANGE);
    }

    @Bean
    public Queue retryQueue() {
        return QueueBuilder.durable(RETRY_QUEUE)
                // Per-message TTL is set at publish time (varies by attempt count),
                // so we don't fix one here — see WebhookPublisher next.
                .withArgument("x-dead-letter-exchange", EXCHANGE)
                .withArgument("x-dead-letter-routing-key", DELIVERY_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding retryBinding() {
        return BindingBuilder.bind(retryQueue()).to(retryExchange()).with(RETRY_ROUTING_KEY);
    }

    // ===== Dead-letter: final resting place after MAX_ATTEMPTS. Inspect/replay by hand. =====

    @Bean
    public DirectExchange dlxExchange() {
        return new DirectExchange(DLX_EXCHANGE);
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(DLQ).build();
    }

    @Bean
    public Binding dlqBinding() {
        return BindingBuilder.bind(deadLetterQueue()).to(dlxExchange()).with(DLQ_ROUTING_KEY);
    }

    // ===== Plumbing: JSON message conversion so we can publish/consume POJOs directly =====

    @Bean
    public Jackson2JsonMessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter());
        return template;
    }
}
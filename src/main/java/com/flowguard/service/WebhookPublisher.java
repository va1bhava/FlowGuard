package com.flowguard.service;

import com.flowguard.config.RabbitConfig;
import com.flowguard.dto.WebhookDeliveryMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookPublisher {

    private final RabbitTemplate rabbitTemplate;

    // Publishing is fire-and-forget from the caller's perspective — this returns
    // almost instantly (just a socket write), the actual HTTP delivery to the
    // customer's endpoint happens later, in the consumer, off this thread entirely.
    public void publish(WebhookDeliveryMessage message) {
        rabbitTemplate.convertAndSend(
                RabbitConfig.EXCHANGE,
                RabbitConfig.DELIVERY_ROUTING_KEY,
                message
        );
        log.info("Published webhook delivery {} for tenant {}", message.getDeliveryId(), message.getTenantId());
    }
}
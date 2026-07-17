package com.flowguard.service;

import com.flowguard.Entity.WebhookDelivery;
import com.flowguard.config.RabbitConfig;
import com.flowguard.dto.WebhookDeliveryMessage;
import com.flowguard.metrics.MetricsService;
import com.flowguard.repository.WebhookDeliveryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookConsumer {

    private static final String HMAC_ALGO = "HmacSHA256";
    private static final int MAX_ATTEMPTS = 6; // 5s,15s,45s,2.25m,6.75m,20m -> then DLQ

    private final WebhookDeliveryRepository webhookDeliveryRepository;
    private final RabbitTemplate rabbitTemplate;
    private final @Qualifier("webhookRestTemplate") RestTemplate restTemplate;
    private final MetricsService metricsService;

    // Rabbit hands us one message per invocation. Concurrency (how many of these
    // run in parallel) is controlled by listener container settings in application.yml,
    // NOT by a semaphore in code anymore — that's Rabbit's job now.
    @RabbitListener(queues = RabbitConfig.DELIVERY_QUEUE)
    public void handleDelivery(WebhookDeliveryMessage message) {
        try {
            String signature = sign(message.getPayload(), message.getSecret());

            HttpHeaders headers = new HttpHeaders();
            headers.set("Content-Type", "application/json");
            headers.set("X-FlowGuard-Event", message.getEventName());
            headers.set("X-FlowGuard-Signature", signature);

            restTemplate.postForEntity(message.getUrl(),
                    new HttpEntity<>(message.getPayload(), headers), String.class);

            markDelivered(message.getDeliveryId());
            metricsService.recordWebhookDeliveryOutcome(true);
            log.info("Webhook delivered for tenant {} (delivery {})", message.getTenantId(), message.getDeliveryId());

        } catch (Exception e) {
            handleFailure(message, e);
        }
        // No manual ack/nack needed — default auto-ack means: this method returning
        // normally (even after we've handled the failure ourselves below) removes
        // the message from webhook.delivery.queue. We're not relying on Rabbit's
        // own redelivery here; WE decide retry timing by republishing to the retry queue.
    }

    private void handleFailure(WebhookDeliveryMessage message, Exception e) {
        int attempts = message.getAttemptCount() + 1;
        message.setAttemptCount(attempts);

        if (attempts >= MAX_ATTEMPTS) {
            markDead(message.getDeliveryId(), e.getMessage()); // the ONLY intermediate write — happens once, ever
            metricsService.recordWebhookDeliveryOutcome(false); // final outcome only — retries in between aren't "failures" yet
            rabbitTemplate.convertAndSend(RabbitConfig.DLX_EXCHANGE, RabbitConfig.DLQ_ROUTING_KEY, message);
            log.error("Webhook delivery DEAD for tenant {} (delivery {}) after {} attempts: {}",
                    message.getTenantId(), message.getDeliveryId(), attempts, e.getMessage());
            return;
        }

        // No DB write here — attempt count lives in the message, Rabbit's TTL does the waiting.
        long backoffMillis = (long) (5000 * Math.pow(3, attempts - 1));

        MessagePostProcessor setTtl = msg -> {
            msg.getMessageProperties().setExpiration(String.valueOf(backoffMillis));
            return msg;
        };

        rabbitTemplate.convertAndSend(RabbitConfig.RETRY_EXCHANGE, RabbitConfig.RETRY_ROUTING_KEY, message, setTtl);

        log.warn("Webhook delivery failed (attempt {}/{}) for tenant {} (delivery {}), retrying in {}ms: {}",
                attempts, MAX_ATTEMPTS, message.getTenantId(), message.getDeliveryId(), backoffMillis, e.getMessage());
    }

    private void markDelivered(java.util.UUID deliveryId) {
        webhookDeliveryRepository.findById(deliveryId).ifPresent(d -> {
            d.setStatus("DELIVERED");
            webhookDeliveryRepository.save(d);
        });
    }

    private void markDead(java.util.UUID deliveryId, String error) {
        webhookDeliveryRepository.findById(deliveryId).ifPresent(d -> {
            d.setStatus("DEAD");
            d.setLastError(error);
            webhookDeliveryRepository.save(d);
        });
    }

    private String sign(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception ex) {
            throw new RuntimeException("Could not sign webhook payload", ex);
        }
    }
}
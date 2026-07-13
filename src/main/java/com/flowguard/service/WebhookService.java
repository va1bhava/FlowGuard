package com.flowguard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowguard.Entity.Tenant;
import com.flowguard.Entity.WebhookConfig;
import com.flowguard.Entity.WebhookDelivery;
import com.flowguard.dto.CreateWebhookRequest;
import com.flowguard.dto.WebhookEvent;
import com.flowguard.repository.TenantRepository;
import com.flowguard.repository.WebhookConfigRepository;
import com.flowguard.repository.WebhookDeliveryRepository;
import com.flowguard.util.WebhookUrlValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.Semaphore;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookService {

    private static final String HMAC_ALGO = "HmacSHA256";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    // Threads are cheap (virtual), outbound connections/network are not.
    // This caps how many webhook deliveries can be in flight at once,
    // regardless of how many dispatch() calls come in simultaneously.
    private static final int MAX_CONCURRENT_DELIVERIES = 20;
    private final Semaphore deliveryLimiter = new Semaphore(MAX_CONCURRENT_DELIVERIES);

    // ~5s, 15s, 45s, 2.25m, 6.75m, 20m — then the delivery is marked DEAD.
    private static final int MAX_ATTEMPTS = 6;

    private final WebhookConfigRepository webhookConfigRepository;
    private final WebhookDeliveryRepository webhookDeliveryRepository;
    private final TenantRepository tenantRepository;
    private final WebhookUrlValidator webhookUrlValidator;
    private final ObjectMapper objectMapper;

    // Dedicated, timeout-bounded client — NOT the shared RestTemplate used by
    // ProxyService, so a slow customer endpoint can't affect proxying.
    private final @Qualifier("webhookRestTemplate") RestTemplate restTemplate;

    public WebhookConfig create(UUID tenantId, CreateWebhookRequest request) {
        // Blocks localhost/private-IP/link-local targets (incl. cloud metadata) —
        // prevents a tenant from using this server as an SSRF proxy.
        webhookUrlValidator.validate(request.getUrl());

        Tenant tenantRef = tenantRepository.getReferenceById(tenantId);
        String[] eventNames = Arrays.stream(request.getEvents())
                .map(Enum::name)
                .toArray(String[]::new);

        WebhookConfig config = WebhookConfig.builder()
                .tenant(tenantRef)
                .url(request.getUrl())
                .secret(generateSecret())
                .events(eventNames)
                .isActive(true)
                .build();

        return webhookConfigRepository.save(config);
    }

    public List<WebhookConfig> list(UUID tenantId) {
        return webhookConfigRepository.findByTenantIdAndIsActiveTrue(tenantId);
    }

    public void delete(UUID tenantId, UUID webhookId) {
        // Scoped lookup — tenant A can never delete tenant B's webhook,
        // even if they guess/enumerate the webhook UUID.
        WebhookConfig config = webhookConfigRepository.findByIdAndTenantId(webhookId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Webhook not found for this tenant"));
        webhookConfigRepository.delete(config);
    }

    // Fire-and-forget entry point: a webhook delivery must never slow down or
    // fail the request path that triggered it (rate limiting, auth, circuit breaking).
    // Runs on the virtual-thread "webhookExecutor", not the caller's thread.
    @Async("webhookExecutor")
    public void dispatch(UUID tenantId, WebhookEvent event, Map<String, Object> data) {
        if (tenantId == null) {
            return; // unattributed traffic (raw-IP fallback) has no tenant to notify
        }

        List<WebhookConfig> configs = webhookConfigRepository.findByTenantIdAndIsActiveTrue(tenantId);
        if (configs.isEmpty()) {
            return;
        }

        String eventName = event.name();
        for (WebhookConfig config : configs) {
            if (config.getEvents() == null || !Arrays.asList(config.getEvents()).contains(eventName)) {
                continue;
            }
            enqueue(config, eventName, tenantId, data);
        }
    }

    // Builds the payload once, persists it as a delivery record, then makes the
    // first attempt. Persisting first means a delivery is never silently lost —
    // if this JVM dies mid-attempt, the retry scheduler will still pick it up.
    private void enqueue(WebhookConfig config, String eventName, UUID tenantId, Map<String, Object> data) {
        String body;
        try {
            Map<String, Object> payload = Map.of(
                    "event", eventName,
                    "tenantId", tenantId.toString(),
                    "timestamp", Instant.now().toString(),
                    "data", data
            );
            body = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.error("Failed to serialize webhook payload for tenant {}: {}", tenantId, e.getMessage());
            return;
        }

        WebhookDelivery delivery = WebhookDelivery.builder()
                .webhookConfig(config)
                .tenantId(tenantId)
                .eventName(eventName)
                .payload(body)
                .nextAttemptAt(Instant.now())
                .build();
        webhookDeliveryRepository.save(delivery);

        attemptDelivery(delivery);
    }

    // Called for the first attempt (from enqueue) AND by WebhookRetryScheduler
    // for every subsequent retry. Public so the scheduler can call it directly.
    public void attemptDelivery(WebhookDelivery delivery) {
        try {
            // Block (park the virtual thread — cheap) until a delivery slot
            // frees up, instead of letting unlimited concurrent HTTP calls fire.
            deliveryLimiter.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        try {
            String signature = sign(delivery.getPayload(), delivery.getWebhookConfig().getSecret());

            HttpHeaders headers = new HttpHeaders();
            headers.set("Content-Type", "application/json");
            headers.set("X-FlowGuard-Event", delivery.getEventName());
            headers.set("X-FlowGuard-Signature", signature);

            restTemplate.postForEntity(delivery.getWebhookConfig().getUrl(),
                    new HttpEntity<>(delivery.getPayload(), headers), String.class);

            delivery.setStatus("DELIVERED");
            webhookDeliveryRepository.save(delivery);

        } catch (Exception e) {
            int attempts = delivery.getAttemptCount() + 1;
            delivery.setAttemptCount(attempts);
            delivery.setLastError(e.getMessage());

            if (attempts >= MAX_ATTEMPTS) {
                // Dead-letter — surfaced later via a "failed deliveries" endpoint for the tenant.
                delivery.setStatus("DEAD");
                log.error("Webhook delivery DEAD for tenant {} url {} after {} attempts",
                        delivery.getTenantId(), delivery.getWebhookConfig().getUrl(), attempts);
            } else {
                long backoffSeconds = (long) (5 * Math.pow(3, attempts - 1));
                delivery.setNextAttemptAt(Instant.now().plusSeconds(backoffSeconds));
                log.warn("Webhook delivery failed (attempt {}/{}) for tenant {}, retrying in {}s: {}",
                        attempts, MAX_ATTEMPTS, delivery.getTenantId(), backoffSeconds, e.getMessage());
            }
            webhookDeliveryRepository.save(delivery);
        } finally {
            deliveryLimiter.release();
        }
    }

    private String sign(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Could not sign webhook payload", e);
        }
    }

    private String generateSecret() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
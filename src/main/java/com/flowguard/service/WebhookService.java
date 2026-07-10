package com.flowguard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowguard.Entity.Tenant;
import com.flowguard.Entity.WebhookConfig;
import com.flowguard.dto.CreateWebhookRequest;
import com.flowguard.dto.WebhookEvent;
import com.flowguard.repository.TenantRepository;
import com.flowguard.repository.WebhookConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
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

    private final WebhookConfigRepository webhookConfigRepository;
    private final TenantRepository tenantRepository;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public WebhookConfig create(UUID tenantId, CreateWebhookRequest request) {
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
        webhookConfigRepository.deleteById(webhookId);
    }

    // Fire-and-forget: a webhook delivery must never slow down or fail the
    // request path that triggered it (rate limiting, auth, circuit breaking).
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
            deliver(config, eventName, tenantId, data);
        }
    }

    private void deliver(WebhookConfig config, String eventName, UUID tenantId, Map<String, Object> data) {
        try {
            // Block (park the virtual thread — cheap) until a delivery slot
            // frees up, instead of letting unlimited concurrent HTTP calls fire.
            deliveryLimiter.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        try {
            Map<String, Object> payload = Map.of(
                    "event", eventName,
                    "tenantId", tenantId.toString(),
                    "timestamp", Instant.now().toString(),
                    "data", data
            );
            String body = objectMapper.writeValueAsString(payload);
            String signature = sign(body, config.getSecret());

            HttpHeaders headers = new HttpHeaders();
            headers.set("Content-Type", "application/json");
            headers.set("X-FlowGuard-Event", eventName);
            headers.set("X-FlowGuard-Signature", signature);

            restTemplate.postForEntity(config.getUrl(), new HttpEntity<>(body, headers), String.class);
        } catch (RestClientException e) {
            // TODO: no retry/backoff yet — a customer endpoint being briefly down
            // silently drops this delivery. Needs a retry queue before production.
            log.warn("Webhook delivery failed for tenant {} url {}: {}", tenantId, config.getUrl(), e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error dispatching webhook for tenant {}: {}", tenantId, e.getMessage(), e);
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
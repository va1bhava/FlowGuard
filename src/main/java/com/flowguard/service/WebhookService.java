package com.flowguard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowguard.Entity.Tenant;
import com.flowguard.Entity.WebhookConfig;
import com.flowguard.Entity.WebhookDelivery;
import com.flowguard.dto.CreateWebhookRequest;
import com.flowguard.dto.WebhookDeliveryMessage;
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

    // ~5s, 15s, 45s, 2.25m, 6.75m, 20m — then the delivery is marked DEAD.
    private static final int MAX_ATTEMPTS = 6;

    private final WebhookPublisher webhookPublisher;
    private final WebhookConfigRepository webhookConfigRepository;
    private final WebhookDeliveryRepository webhookDeliveryRepository;
    private final TenantRepository tenantRepository;
    private final WebhookUrlValidator webhookUrlValidator;
    private final ObjectMapper objectMapper;

    public WebhookConfig create(UUID tenantId, CreateWebhookRequest request) {
        // Blocks localhost/private-IP/link-local targets (incl. cloud metadata) —
        // prevents a tenant from using this server as an SSRF proxy.
        webhookUrlValidator.validate(request.getUrl());

        Tenant tenantRef = tenantRepository.getReferenceById(tenantId);

        // No events specified (frontend sent none, or client omitted the field
        // entirely) -> subscribe to every event type rather than NPE-ing or
        // silently creating a webhook that never fires.
        WebhookEvent[] events = (request.getEvents() == null || request.getEvents().length == 0)
                ? WebhookEvent.values()
                : request.getEvents();

        String[] eventNames = Arrays.stream(events)
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
    // enqueue() — only change is the last line:
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
        delivery = webhookDeliveryRepository.save(delivery); // need the generated id below

        webhookPublisher.publish(WebhookDeliveryMessage.builder()
                .deliveryId(delivery.getId())
                .tenantId(tenantId)
                .url(config.getUrl())
                .secret(config.getSecret())
                .eventName(eventName)
                .payload(body)
                .attemptCount(0)
                .build());
    }

    private String generateSecret() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
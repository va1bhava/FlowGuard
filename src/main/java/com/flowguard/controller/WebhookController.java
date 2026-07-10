package com.flowguard.controller;

import com.flowguard.Entity.WebhookConfig;
import com.flowguard.dto.CreateWebhookRequest;
import com.flowguard.dto.WebhookResponse;
import com.flowguard.service.WebhookService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

// NOTE: temporarily unauthenticated, same as IpRuleController/RequestLogController —
// will be scoped to the authenticated tenant once JWT auth lands.
@RestController
@RequestMapping("/api/tenants/{tenantId}/webhooks")
@RequiredArgsConstructor
public class WebhookController {

    private final WebhookService webhookService;

    @PostMapping
    public ResponseEntity<WebhookResponse> create(@PathVariable UUID tenantId,
                                                  @RequestBody CreateWebhookRequest request) {
        WebhookConfig config = webhookService.create(tenantId, request);
        return ResponseEntity.ok(WebhookResponse.fromWithSecret(config));
    }

    @GetMapping
    public ResponseEntity<List<WebhookResponse>> list(@PathVariable UUID tenantId) {
        List<WebhookResponse> configs = webhookService.list(tenantId).stream()
                .map(WebhookResponse::from).toList();
        return ResponseEntity.ok(configs);
    }

    @DeleteMapping("/{webhookId}")
    public ResponseEntity<Void> delete(@PathVariable UUID tenantId, @PathVariable UUID webhookId) {
        webhookService.delete(tenantId, webhookId);
        return ResponseEntity.noContent().build();
    }
}
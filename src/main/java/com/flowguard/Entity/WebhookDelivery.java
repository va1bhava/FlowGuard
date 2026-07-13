package com.flowguard.Entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Data @Entity @Builder
@NoArgsConstructor @AllArgsConstructor
@Table(name = "webhook_deliveries")
public class WebhookDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "webhook_config_id", nullable = false)
    private WebhookConfig webhookConfig;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String eventName;

    @Column(columnDefinition = "text", nullable = false)
    private String payload; // pre-serialized JSON body, so retries send byte-identical content

    @Column(nullable = false)
    @Builder.Default
    private int attemptCount = 0;

    @Column(nullable = false)
    private Instant nextAttemptAt;

    @Column(nullable = false)
    @Builder.Default
    private String status = "PENDING"; // PENDING | DELIVERED | DEAD

    private String lastError;
}
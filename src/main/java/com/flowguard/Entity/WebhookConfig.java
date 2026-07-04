package com.flowguard.Entity;

import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;

@Data @Entity @Builder
@NoArgsConstructor @AllArgsConstructor
@Table(name = "webhook_configs")
public class WebhookConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Column(nullable = false)
    private String url; // where we POST the alert

    @Column(nullable = false)
    private String secret; // used to sign payload with HMAC-SHA256

    // e.g. ["RATE_LIMIT_BREACH", "KEY_EXPIRED"]
    // stored as text array in postgres
    @Column(columnDefinition = "text[]")
    private String[] events;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean isActive = true;
}
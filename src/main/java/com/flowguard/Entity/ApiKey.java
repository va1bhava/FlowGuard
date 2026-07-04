package com.flowguard.Entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.Instant;
import java.util.UUID;

@Data @Entity @Builder
@NoArgsConstructor @AllArgsConstructor
@Table(name = "api_keys")
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    // SHA-256 hash of the actual key — never store plaintext
    @Column(name = "key_hash", nullable = false, unique = true)
    private String keyHash;

    // First 12 chars shown in dashboard e.g. "fg_live_abc1..."
    @Column(name = "key_prefix", nullable = false)
    private String keyPrefix;

    @Column(nullable = false)
    private String name; // e.g. "Production Key", "Test Key"

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean isActive = true;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}

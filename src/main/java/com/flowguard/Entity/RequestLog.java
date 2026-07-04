package com.flowguard.Entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.Instant;
import java.util.UUID;

@Data @Entity @Builder
@NoArgsConstructor @AllArgsConstructor
@Table(name = "request_logs",
        indexes = @Index(name = "idx_tenant_created",
                columnList = "tenant_id, created_at DESC"))
public class RequestLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "api_key_id")
    private UUID apiKeyId;

    @Column(name = "ip_address")
    private String ipAddress;

    @Column(nullable = false, length = 10)
    private String method; // GET, POST, etc.

    @Column(nullable = false)
    private String path;

    @Column(name = "status_code", nullable = false)
    private int statusCode;

    @Column(name = "response_time_ms")
    private long responseTimeMs;

    @Column(name = "was_rate_limited", nullable = false)
    private boolean wasRateLimited;

    @Column(name = "was_ip_blocked", nullable = false)
    private boolean wasIpBlocked;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}

package com.flowguard.Entity;

import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;

@Data @Entity @Builder
@NoArgsConstructor @AllArgsConstructor
@Table(name = "ip_rules")
public class IpRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    // PostgreSQL INET type supports subnets like 192.168.1.0/24
    @Column(name = "ip_address", nullable = false, length = 45)
    private String ipAddress;

    @Enumerated(EnumType.STRING)
    @Column(name = "rule_type", nullable = false)
    private RuleType ruleType;

    @Column
    private String reason; // optional note e.g. "known scraper"

    public enum RuleType {
        BLOCK, ALLOW
    }
}
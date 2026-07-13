// WebhookConfigRepository.java
package com.flowguard.repository;

import com.flowguard.Entity.WebhookConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WebhookConfigRepository extends JpaRepository<WebhookConfig, UUID> {
    List<WebhookConfig> findByTenantIdAndIsActiveTrue(UUID tenantId);
    Optional<WebhookConfig> findByIdAndTenantId(UUID id, UUID tenantId);
}
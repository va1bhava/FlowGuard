// RequestLogRepository.java
package com.flowguard.repository;

import com.flowguard.Entity.RequestLog;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface RequestLogRepository extends JpaRepository<RequestLog, Long> {
    List<RequestLog> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);
}
// IpRuleRepository.java
package com.flowguard.repository;

import com.flowguard.Entity.IpRule;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface IpRuleRepository extends JpaRepository<IpRule, UUID> {
    List<IpRule> findByTenantId(UUID tenantId);
}
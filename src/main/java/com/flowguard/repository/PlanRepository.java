package com.flowguard.repository;

import com.flowguard.Entity.Plans;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface PlanRepository extends JpaRepository<Plans, Long> {
    Optional<Plans> findByName(String name);
}

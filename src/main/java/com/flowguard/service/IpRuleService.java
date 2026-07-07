package com.flowguard.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowguard.Entity.IpRule;
import com.flowguard.Entity.Tenant;
import com.flowguard.dto.CreateIpRuleRequest;
import com.flowguard.repository.IpRuleRepository;
import com.flowguard.repository.TenantRepository;
import com.flowguard.util.IpMatcher;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class IpRuleService {

    private final IpRuleRepository ipRuleRepository;
    private final TenantRepository tenantRepository;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    private static final String CACHE_PREFIX = "iprules:tenant:";
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    public IpRule createRule(UUID tenantId, CreateIpRuleRequest request) {
        Tenant tenantRef = tenantRepository.getReferenceById(tenantId);
        IpRule rule = IpRule.builder()
                .tenant(tenantRef)
                .ipAddress(request.getIpAddress())
                .ruleType(request.getRuleType())
                .reason(request.getReason())
                .build();
        rule = ipRuleRepository.save(rule);
        invalidateCache(tenantId);
        return rule;
    }

    public List<IpRule> listRules(UUID tenantId) {
        return ipRuleRepository.findByTenantId(tenantId);
    }

    public void deleteRule(UUID tenantId, UUID ruleId) {
        ipRuleRepository.deleteById(ruleId);
        invalidateCache(tenantId);
    }

    // Hot-path check used by RateLimiterFilter on every request.
    public boolean isBlocked(UUID tenantId, String clientIp) {
        List<CachedRule> rules = getRulesCached(tenantId);
        if (rules.isEmpty()) {
            return false;
        }

        List<CachedRule> allowRules = rules.stream()
                .filter(r -> r.ruleType == IpRule.RuleType.ALLOW).toList();

        // Allowlist mode: presence of ANY allow rule means only those IPs may pass.
        if (!allowRules.isEmpty()) {
            boolean isAllowed = allowRules.stream().anyMatch(r -> IpMatcher.matches(clientIp, r.ipAddress));
            return !isAllowed;
        }

        return rules.stream()
                .filter(r -> r.ruleType == IpRule.RuleType.BLOCK)
                .anyMatch(r -> IpMatcher.matches(clientIp, r.ipAddress));
    }

    private List<CachedRule> getRulesCached(UUID tenantId) {
        String cacheKey = CACHE_PREFIX + tenantId;
        try {
            String cached = stringRedisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                return objectMapper.readValue(cached, new TypeReference<List<CachedRule>>() {});
            }
        } catch (Exception e) {
            log.warn("Failed to read cached IP rules for tenant {}: {}", tenantId, e.getMessage());
        }

        List<CachedRule> rules = ipRuleRepository.findByTenantId(tenantId).stream()
                .map(r -> new CachedRule(r.getIpAddress(), r.getRuleType()))
                .toList();

        try {
            stringRedisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(rules), CACHE_TTL);
        } catch (Exception e) {
            log.warn("Failed to cache IP rules for tenant {}: {}", tenantId, e.getMessage());
        }
        return rules;
    }

    private void invalidateCache(UUID tenantId) {
        boolean deleted = Boolean.TRUE.equals(stringRedisTemplate.delete(CACHE_PREFIX + tenantId));
        log.info("Cache invalidation for tenant {}: {}", tenantId, deleted ? "cleared" : "no cache existed");
    }

    // Deliberately NOT the IpRule entity — caching the entity would serialize
    // its eager Tenant relation (including passwordHash) into Redis.
    @Data
    @NoArgsConstructor
    static class CachedRule {
        private String ipAddress;
        private IpRule.RuleType ruleType;

        CachedRule(String ipAddress, IpRule.RuleType ruleType) {
            this.ipAddress = ipAddress;
            this.ruleType = ruleType;
        }
    }
}
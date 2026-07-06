package com.flowguard.dto;

import com.flowguard.Entity.IpRule;
import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
public class IpRuleResponse {
    private UUID id;
    private String ipAddress;
    private IpRule.RuleType ruleType;
    private String reason;

    public static IpRuleResponse from(IpRule rule) {
        return IpRuleResponse.builder()
                .id(rule.getId())
                .ipAddress(rule.getIpAddress())
                .ruleType(rule.getRuleType())
                .reason(rule.getReason())
                .build();
    }
}
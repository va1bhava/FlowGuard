package com.flowguard.dto;

import com.flowguard.Entity.IpRule;
import lombok.Data;

@Data
public class CreateIpRuleRequest {
    private String ipAddress;   // e.g. "1.2.3.4" or "1.2.3.0/24"
    private IpRule.RuleType ruleType; // BLOCK or ALLOW
    private String reason;
}
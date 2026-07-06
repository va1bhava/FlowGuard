package com.flowguard.controller;

import com.flowguard.Entity.IpRule;
import com.flowguard.dto.CreateIpRuleRequest;
import com.flowguard.dto.IpRuleResponse;
import com.flowguard.service.IpRuleService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

// NOTE: temporarily unauthenticated, same as RequestLogController —
// will be scoped to the authenticated tenant once JWT auth lands.
@RestController
@RequestMapping("/api/tenants/{tenantId}/ip-rules")
@RequiredArgsConstructor
public class IpRuleController {

    private final IpRuleService ipRuleService;

    @PostMapping
    public ResponseEntity<IpRuleResponse> create(@PathVariable UUID tenantId,
                                                 @RequestBody CreateIpRuleRequest request) {
        IpRule rule = ipRuleService.createRule(tenantId, request);
        return ResponseEntity.ok(IpRuleResponse.from(rule));
    }

    @GetMapping
    public ResponseEntity<List<IpRuleResponse>> list(@PathVariable UUID tenantId) {
        List<IpRuleResponse> rules = ipRuleService.listRules(tenantId).stream()
                .map(IpRuleResponse::from).toList();
        return ResponseEntity.ok(rules);
    }

    @DeleteMapping("/{ruleId}")
    public ResponseEntity<Void> delete(@PathVariable UUID tenantId, @PathVariable UUID ruleId) {
        ipRuleService.deleteRule(tenantId, ruleId);
        return ResponseEntity.noContent().build();
    }
}
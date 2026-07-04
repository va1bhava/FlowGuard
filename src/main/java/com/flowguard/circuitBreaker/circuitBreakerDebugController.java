package com.flowguard.circuitBreaker;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class circuitBreakerDebugController {

    @Autowired
    private circuitBreakerService circuitBreakerService;

    @GetMapping("/debug/circuit")
    public Map<String, String> getState(
            @RequestParam String tenantId,
            @RequestParam String backendHost) {
        String state = circuitBreakerService.getState(tenantId, backendHost);
        return Map.of("tenantId", tenantId, "backendHost", backendHost, "state", state);
    }
}
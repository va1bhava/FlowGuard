package com.flowguard.controller;

import com.flowguard.Entity.RequestLog;
import com.flowguard.repository.RequestLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

// NOTE: temporarily unauthenticated — will be locked down to "this tenant only"
// once JWT auth lands (tenantId will come from the token, not the path).
@RestController
@RequestMapping("/api/tenants")
@RequiredArgsConstructor
public class RequestLogController {

    private final RequestLogRepository requestLogRepository;

    @GetMapping("/{tenantId}/logs")
    public ResponseEntity<List<RequestLog>> getLogs(@PathVariable UUID tenantId) {
        List<RequestLog> logs = requestLogRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
        return ResponseEntity.ok(logs);
    }
}
package com.flowguard.service;

import com.flowguard.Entity.RequestLog;
import com.flowguard.repository.RequestLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RequestLogService {

    private final RequestLogRepository requestLogRepository;

    // Fire-and-forget: runs on a separate thread so a slow DB write never
    // adds latency to the actual proxied request.
    @Async
    public void log(UUID tenantId, UUID apiKeyId, String ipAddress, String method,
                    String path, int statusCode, long responseTimeMs,
                    boolean wasRateLimited, boolean wasIpBlocked) {
        try {
            RequestLog entry = RequestLog.builder()
                    .tenantId(tenantId)
                    .apiKeyId(apiKeyId)
                    .ipAddress(ipAddress)
                    .method(method)
                    .path(path)
                    .statusCode(statusCode)
                    .responseTimeMs(responseTimeMs)
                    .wasRateLimited(wasRateLimited)
                    .wasIpBlocked(wasIpBlocked)
                    .build();
            requestLogRepository.save(entry);
        } catch (Exception e) {
            // Logging must never break the gateway — swallow and just record the failure.
            log.error("Failed to persist request log for tenant {}: {}", tenantId, e.getMessage());
        }
    }
}
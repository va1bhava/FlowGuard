package com.flowguard.filter;
import com.flowguard.circuitBreaker.circuitBreakerService;
import com.flowguard.dto.ApiKeyResolutionResult;
import com.flowguard.exception.InvalidApiKeyException;
import com.flowguard.resolver.KeyResolver;
import com.flowguard.service.*;
import com.flowguard.strategy.RateLimiterStrategy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.redis.connection.ReactiveStreamCommands;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import com.flowguard.dto.WebhookEvent;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimiterFilter extends OncePerRequestFilter {

    private final circuitBreakerService circuitBreakerService;
    private final ProxyService proxyService;
    private final RateLimiterStrategy strategy;
    private final KeyResolver keyResolver;
    private final RequestLogService requestLogService;
    private final IpRuleService ipRuleService;
    private final AbuseDetectionService abuseDetectionService;
    private final WebhookService webhookService;
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        String requestPath = request.getRequestURI();
        if (requestPath.startsWith("/api/tenants") || requestPath.startsWith("/debug") || requestPath.startsWith("/flaky")
                || requestPath.equals("/ping") || requestPath.equals("/health")) {
            filterChain.doFilter(request, response);
            return;
        }
        long startTime = System.currentTimeMillis();
        String clientIp = resolveClientIp(request);
        log.info("Resolved client IP: {}", clientIp);
        String method = request.getMethod();
        String path = request.getRequestURI();

        ApiKeyResolutionResult resolved;
        try {
            resolved = keyResolver.resolve(request);
        } catch (InvalidApiKeyException e) {
            log.warn("Rejected request: {}", e.getMessage());
            response.setStatus(401);
            response.setContentType("application/json");
            response.getWriter().write("""
                    {
                      "error": "Unauthorized",
                      "message": "%s"
                    }
                    """.formatted(e.getMessage()));
            // No tenant identified — nothing to attribute this to in request_logs.
            return;
        }
        if (resolved.getTenantId() != null && ipRuleService.isBlocked(resolved.getTenantId(), clientIp)) {
            log.warn("Blocked IP {} for tenant {}", clientIp, resolved.getTenantId());
            response.setStatus(403);
            response.setContentType("application/json");
            response.getWriter().write("""
                {
                  "error": "Forbidden",
                  "message": "Your IP address is not permitted to access this resource."
                }
                """);
            logIfTenantKnown(resolved, clientIp, method, path, 403, startTime, false, true);
            return;
        }
        if (resolved.isUnlimited()) {
            filterChain.doFilter(request, response);
            logIfTenantKnown(resolved, clientIp, method, path, response.getStatus(), startTime, false, false);
            return;
        }

        String key = resolved.getRateLimitKey();
        int limit = resolved.getRequestsPerMinute();
        log.info("Rate limiting key: {}, limit: {}", key, limit);
        boolean allowed = strategy.isAllowed(key, limit);

        response.setHeader("X-RateLimit-Remaining",
                String.valueOf(strategy.remainingLimit(key, limit)));

        if (!allowed) {
            long retryAfter = strategy.retryAfterSeconds(key, limit);

            log.warn("Rate limit exceeded for key: {}", key);

            if (resolved.getTenantId() != null
                    && abuseDetectionService.recordRejectionAndCheckThreshold(resolved.getTenantId())) {
                webhookService.dispatch(resolved.getTenantId(), WebhookEvent.RATE_LIMIT_BREACH, Map.of(
                        "clientIp", clientIp,
                        "path", path
                ));
            }

            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(retryAfter));
            response.setContentType("application/json");
            response.getWriter().write("""
                    {
                      "error": "Too Many Requests",
                      "message": "Rate limit exceeded. Please slow down.",
                      "retryAfterSeconds": %d
                    }
                    """.formatted(retryAfter));
            logIfTenantKnown(resolved, clientIp, method, path, 429, startTime, true, false);
            return;
        }
        if (resolved.getUpstreamUrl() == null) {
            // No upstream configured — this is a raw IP-based/no-key request, let it hit our own controllers
            filterChain.doFilter(request, response);
            logIfTenantKnown(resolved, clientIp, method, path, response.getStatus(), startTime, false, false);
            return;
        }

        // So all this below code will hit the upstream URL of the tenant and return the response
        // it will never hit our controllers

        String tenantId = resolved.getTenantId().toString();
        String backendHost = resolved.getUpstreamUrl();

        if (!circuitBreakerService.allowRequests(tenantId, backendHost)) {
            log.warn("Circuit OPEN for tenant {} backend {} — short-circuiting", tenantId, backendHost);
            response.setStatus(503);
            response.setContentType("application/json");
            response.getWriter().write("""
                    {
                      "error": "Service Unavailable",
                      "message": "Backend is currently unavailable (circuit open)."
                    }
                    """);
            logIfTenantKnown(resolved, clientIp, method, path, 503, startTime, false, false);
            return;
        }

        String requestBody = request.getReader().lines()
                .collect(java.util.stream.Collectors.joining(System.lineSeparator()));

        ResponseEntity<byte[]> proxied = proxyService.forward(request, resolved.getUpstreamUrl(), requestBody);

        if (proxied.getStatusCode().is5xxServerError()) {
            circuitBreakerService.recordFailure(tenantId, backendHost);
        } else {
            circuitBreakerService.recordSucess(tenantId, backendHost);
        }

        response.setStatus(proxied.getStatusCode().value());
        if (proxied.getHeaders().getContentType() != null) {
            response.setContentType(proxied.getHeaders().getContentType().toString());
        }
        if (proxied.getBody() != null) {
            response.getOutputStream().write(proxied.getBody());
        }

        logIfTenantKnown(resolved, clientIp, method, path, proxied.getStatusCode().value(),
                startTime, false, false);
    }

    private void logIfTenantKnown(ApiKeyResolutionResult resolved, String clientIp, String method,
                                  String path, int statusCode, long startTime,
                                  boolean wasRateLimited, boolean wasIpBlocked) {
        // IP-based fallback requests have no tenant — request_logs.tenant_id is non-null,
        // so we simply don't persist those (they're not billable/attributable traffic).
        if (resolved.getTenantId() == null) {
            return;
        }
        long responseTimeMs = System.currentTimeMillis() - startTime;
        requestLogService.log(resolved.getTenantId(), resolved.getApiKeyId(), clientIp, method,
                path, statusCode, responseTimeMs, wasRateLimited, wasIpBlocked);
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        String ip = (forwardedFor != null && !forwardedFor.isBlank()) ? forwardedFor : request.getRemoteAddr();

        // Normalize IPv6 loopback to IPv4 loopback — same machine, same rule should apply.
        if ("0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip)) {
            return "127.0.0.1";
        }
        return ip;
    }
}
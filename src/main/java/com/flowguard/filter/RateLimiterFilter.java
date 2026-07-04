package com.flowguard.filter;

import com.flowguard.dto.ApiKeyResolutionResult;
import com.flowguard.exception.InvalidApiKeyException;
import com.flowguard.resolver.KeyResolver;
import com.flowguard.service.ProxyService;
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

@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimiterFilter extends OncePerRequestFilter {


    private final ProxyService proxyService;
    private final RateLimiterStrategy strategy;
    private final KeyResolver keyResolver;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

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
            return;
        }

        if (resolved.isUnlimited()) {
            filterChain.doFilter(request, response);
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
            return;
        }

        if (resolved.getUpstreamUrl() == null) {
            // No upstream configured — this is a raw IP-based/no-key request, let it hit our own controllers
            filterChain.doFilter(request, response);
            return;
        }

        // So all this below code will hit the upstream URL of the tenant and return the response
        // it will never hit our controllers

        String requestBody = request.getReader().lines()
                .collect(java.util.stream.Collectors.joining(System.lineSeparator()));

        ResponseEntity<byte[]> proxied = proxyService.forward(request, resolved.getUpstreamUrl(), requestBody);

        response.setStatus(proxied.getStatusCode().value());
        if (proxied.getHeaders().getContentType() != null) {
            response.setContentType(proxied.getHeaders().getContentType().toString());
        }
        if (proxied.getBody() != null) {
            response.getOutputStream().write(proxied.getBody());
        }
    }
}
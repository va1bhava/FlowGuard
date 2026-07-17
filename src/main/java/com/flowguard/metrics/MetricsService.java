package com.flowguard.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

/**
 * Central place for all custom FlowGuard metrics.
 *
 * Deliberately NOT tagging by tenantId — with N tenants and M backends,
 * a tenantId label multiplies every metric by N and creates unbounded
 * cardinality in Prometheus (new tenant = new time series forever).
 * We tag by bounded, low-cardinality dimensions instead: algorithm name,
 * allow/reject outcome, webhook event type, etc.
 */
@Service
public class MetricsService {

    private final MeterRegistry registry;

    public MetricsService(MeterRegistry registry) {
        this.registry = registry;
    }

    // ---- Rate limiting ----

    public void recordRateLimitDecision(String algorithm, boolean allowed) {
        Counter.builder("flowguard_ratelimit_requests_total")
                .description("Rate limiter decisions, tagged by algorithm and outcome")
                .tag("algorithm", algorithm)
                .tag("result", allowed ? "allowed" : "rejected")
                .register(registry)
                .increment();
    }

    // ---- Circuit breaker ----

    /**
     * Records the circuit breaker's state at the moment of a success/failure check.
     * NOTE: this fires on every recordSuccess/recordFailure call, not only when the
     * state actually changes (the underlying Lua script returns current state either
     * way). Use it as "time spent in each state" via rate(), not as a transition count.
     */
    public void recordCircuitBreakerState(String state) {
        Counter.builder("flowguard_circuit_breaker_state_total")
                .description("Circuit breaker state observed on each success/failure check")
                .tag("state", state) // OPEN, CLOSED, HALF_OPEN
                .register(registry)
                .increment();
    }

    public void recordCircuitBreakerOutcome(boolean allowed) {
        Counter.builder("flowguard_circuit_breaker_decisions_total")
                .description("Circuit breaker allow/short-circuit decisions")
                .tag("result", allowed ? "allowed" : "short_circuited")
                .register(registry)
                .increment();
    }

    // ---- IP rules ----

    public void recordIpBlocked() {
        Counter.builder("flowguard_ip_blocked_total")
                .description("Requests rejected by IP block/allow rules")
                .register(registry)
                .increment();
    }

    // ---- Webhooks ----

    public void recordWebhookDispatch(String eventType) {
        Counter.builder("flowguard_webhook_dispatched_total")
                .description("Webhook events dispatched, tagged by event type")
                .tag("event", eventType)
                .register(registry)
                .increment();
    }

    public void recordWebhookDeliveryOutcome(boolean success) {
        Counter.builder("flowguard_webhook_delivery_total")
                .description("Webhook delivery attempts, tagged by outcome")
                .tag("result", success ? "success" : "failure")
                .register(registry)
                .increment();
    }

    // ---- Proxy / upstream ----

    public void recordUpstreamStatus(int statusCode) {
        String bucket = (statusCode / 100) + "xx";
        Counter.builder("flowguard_upstream_responses_total")
                .description("Upstream backend responses, tagged by status class")
                .tag("status_class", bucket)
                .register(registry)
                .increment();
    }
}
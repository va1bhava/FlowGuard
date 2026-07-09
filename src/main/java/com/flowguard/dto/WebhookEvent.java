package com.flowguard.dto;

public enum WebhookEvent {
    RATE_LIMIT_BREACH,
    KEY_EXPIRED,
    CIRCUIT_OPEN,
    IP_BLOCKED
}
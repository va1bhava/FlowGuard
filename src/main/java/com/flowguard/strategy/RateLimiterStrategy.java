package com.flowguard.strategy;

public interface RateLimiterStrategy {

    // Returns true = allow, false = block
    boolean isAllowed(String key,int limitPerMinute);

    // How many requests remaining in current window
    long remainingLimit(String key,int limitPerMinute);

    // How many seconds until they can try again
    long retryAfterSeconds(String key,int limitPerMinute);
}

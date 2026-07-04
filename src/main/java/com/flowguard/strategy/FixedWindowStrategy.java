package com.flowguard.strategy;

import com.flowguard.properties.RateLimiterProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

@RequiredArgsConstructor
public class FixedWindowStrategy implements RateLimiterStrategy {

    private final StringRedisTemplate redisTemplate;
    private final RateLimiterProperties properties;

    private String redisKey(String key) {
        return "fw:" + key;
    }

    @Override
    public boolean isAllowed(String key,int limitPerMinute) {
        String rKey = redisKey(key);

        // Atomically increment counter
        long count = redisTemplate.opsForValue().increment(rKey);

        if (count == 1) {
            // First request in this window — set expiry
            redisTemplate.expire(rKey, Duration.ofSeconds(properties.getWindowSeconds()));
        }

        return count <= limitPerMinute;
    }

    @Override
    public long remainingLimit(String key,int limitPerMinute) {
        String val = redisTemplate.opsForValue().get(redisKey(key));
        if (val == null) return limitPerMinute;
        long used = Long.parseLong(val);
        return Math.max(0, limitPerMinute - used);
    }

    @Override
    public long retryAfterSeconds(String key,int limitPerMinute) {
        Long ttl = redisTemplate.getExpire(redisKey(key));
        return (ttl != null && ttl > 0) ? ttl : properties.getWindowSeconds();
    }
}

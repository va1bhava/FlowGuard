package com.flowguard.strategy;

import com.flowguard.properties.RateLimiterProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;

/**
 * Leaky Bucket Algorithm
 *
 * Analogy: water drips into a bucket, leaks out at fixed rate.
 * If bucket overflows (queue full) → reject request.
 *
 * Unlike Token Bucket, this does NOT allow bursts.
 * Output is always smooth at exactly leakRate requests/sec.
 *
 * Redis keys:
 *   lb:queue:{key}  → current number of requests in queue
 *   lb:last:{key}   → unix timestamp of last leak calculation
 */
@RequiredArgsConstructor
public class LeakyBucketStrategy implements RateLimiterStrategy {

    private final StringRedisTemplate redisTemplate;
    private final RateLimiterProperties properties;

    private String queueKey(String key)    { return "lb:queue:" + key; }
    private String lastLeakKey(String key) { return "lb:last:" + key; }

    @Override
    public boolean isAllowed(String key,int limitPerMinute) {
        String qKey = queueKey(key);
        String lKey = lastLeakKey(key);

        long bucketCapacity=limitPerMinute;
        double leakRate=bucketCapacity/60.0;
        long now = Instant.now().getEpochSecond();

        String rawQueue = redisTemplate.opsForValue().get(qKey);
        String rawLast  = redisTemplate.opsForValue().get(lKey);

        long queueSize;
        long lastLeak;

        if (rawQueue == null || rawLast == null) {
            // First request ever for this key
            // Queue starts at 1 (this request occupies one slot)
            redisTemplate.opsForValue().set(qKey, "1",
                    Duration.ofSeconds(properties.getWindowSeconds()));
            redisTemplate.opsForValue().set(lKey, String.valueOf(now),
                    Duration.ofSeconds(properties.getWindowSeconds()));
            return true;
        }

        queueSize = Long.parseLong(rawQueue);
        lastLeak  = Long.parseLong(rawLast);

        // Step 1: Calculate how many requests leaked since last check
        long secondsPassed = now - lastLeak;
        long leaked   = (long)(secondsPassed * leakRate);

        // Step 2: Drain the queue by leaked amount (can't go below 0)
        queueSize = Math.max(0, queueSize - leaked);

        // Step 3: Update last leak timestamp if anything actually leaked
        if (leaked > 0) {
            redisTemplate.opsForValue().set(lKey, String.valueOf(now),
                    Duration.ofSeconds(properties.getWindowSeconds()));
        }

        // Step 4: Queue full? Reject immediately
        if (queueSize >= bucketCapacity) {
            redisTemplate.opsForValue().set(qKey, String.valueOf(queueSize),
                    Duration.ofSeconds(properties.getWindowSeconds()));
            return false;
        }

        // Step 5: Add this request to the queue
        queueSize++;
        redisTemplate.opsForValue().set(qKey, String.valueOf(queueSize),
                Duration.ofSeconds(properties.getWindowSeconds()));
        return true;
    }

    @Override
    public long remainingLimit(String key,int limitPerMinute) {
        String raw = redisTemplate.opsForValue().get(queueKey(key));
        long bucketCapacity=limitPerMinute;
        if (raw == null) return bucketCapacity;
        long queueSize = Long.parseLong(raw);
        return Math.max(0, bucketCapacity- queueSize);
    }

    @Override
    public long retryAfterSeconds(String key,int limitPerMinute) {
        // Time for one slot to free up = 1 / leakRate seconds
        return (long) Math.ceil(1.0 / (limitPerMinute/60.0));
    }
}

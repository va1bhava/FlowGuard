package com.flowguard.strategy;

import com.flowguard.properties.RateLimiterProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@RequiredArgsConstructor
public class TokenBucketStrategy implements RateLimiterStrategy {

    private final StringRedisTemplate redisTemplate;
    private final RateLimiterProperties properties;

    private String tokenKey(String key)      { return "tb:tokens:" + key; }
    private String lastRefillKey(String key) { return "tb:refill:" + key; }
    private static final DefaultRedisScript<Long> TOKEN_BUCKET_SCRIPT;
    static {
        TOKEN_BUCKET_SCRIPT = new DefaultRedisScript<>();
        TOKEN_BUCKET_SCRIPT.setScriptText("""
                local tokenKey = KEYS[1]
                local refillKey = KEYS[2]
                local capacity = tonumber(ARGV[1])
                local refillRate = tonumber(ARGV[2])
                local now = tonumber(ARGV[3])
                local ttl = tonumber(ARGV[4])
                
                local tokens = tonumber(redis.call('GET', tokenKey))
                local lastRefill = tonumber(redis.call('GET', refillKey))
                
                if tokens == nil or lastRefill == nil then
                    redis.call('SET', tokenKey, capacity - 1, 'EX', ttl)
                    redis.call('SET', refillKey, now, 'EX', ttl)
                    return 1
                end
                
                local secondsPassed = now - lastRefill
                local tokensToAdd = secondsPassed * refillRate
                tokens = math.min(capacity, tokens + tokensToAdd)
                
                if tokensToAdd > 0 then
                    redis.call('SET', refillKey, now, 'EX', ttl)
                end
                
                if tokens <= 0 then
                    redis.call('SET', tokenKey, 0, 'EX', ttl)
                    return 0
                end
                
                tokens = tokens - 1
                redis.call('SET', tokenKey, tokens, 'EX', ttl)
                return 1
                """);
        TOKEN_BUCKET_SCRIPT.setResultType(Long.class);
    }

    @Override
    public boolean isAllowed(String key, int limitPerMinute) {
        Long result = redisTemplate.execute(TOKEN_BUCKET_SCRIPT,
                List.of(tokenKey(key), lastRefillKey(key)),
                String.valueOf(limitPerMinute),
                String.valueOf(limitPerMinute / 60.0),
                String.valueOf(Instant.now().getEpochSecond()),
                String.valueOf(properties.getWindowSeconds())
        );
        return Long.valueOf(1L).equals(result);
    }

    @Override
    public long remainingLimit(String key, int limitPerMinute) {
        String raw = redisTemplate.opsForValue().get(tokenKey(key));
        if (raw == null) return limitPerMinute;
        return Math.max(0, (long) Double.parseDouble(raw));  // ← change here
    }

    @Override
    public long retryAfterSeconds(String key, int limitPerMinute) {
        String raw = redisTemplate.opsForValue().get(tokenKey(key));
        if (raw == null || Double.parseDouble(raw) > 0) return 0;  // ← change here
        return (long) Math.ceil(1.0 / (limitPerMinute / 60.0));
    }
}

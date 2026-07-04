package com.flowguard.config;

import com.flowguard.properties.RateLimiterProperties;
import com.flowguard.strategy.FixedWindowStrategy;
import com.flowguard.strategy.LeakyBucketStrategy;
import com.flowguard.strategy.RateLimiterStrategy;
import com.flowguard.strategy.TokenBucketStrategy;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
@RequiredArgsConstructor
public class RateLimiterConfig {

    private final RateLimiterProperties properties;
    private final StringRedisTemplate redisTemplate;

    @Bean
    public RateLimiterStrategy rateLimiterStrategy() {
        return switch (properties.getAlgorithm()) {
            case FIXED_WINDOW  -> new FixedWindowStrategy(redisTemplate, properties);
            case TOKEN_BUCKET  -> new TokenBucketStrategy(redisTemplate, properties);
            case LEAKY_BUCKET  -> new LeakyBucketStrategy(redisTemplate, properties);
        };
    }
}

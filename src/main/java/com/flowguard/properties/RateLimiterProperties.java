package com.flowguard.properties;

import com.flowguard.config.Algorithm;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "rate-limiter")
public class RateLimiterProperties {

     private Algorithm algorithm = Algorithm.TOKEN_BUCKET;
    private long windowSeconds = 60;    // requests drained per second
}

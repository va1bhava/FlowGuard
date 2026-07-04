package com.flowguard.circuitBreaker;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;

@Service
public class circuitBreakerService {
    private static final int FAILURE_THRESHOLD = 5;
    private static final long COOLDOWN_MS = 30_000L;
    private static final int HALF_OPEN_MAX_TRIALS = 3;

    private final StringRedisTemplate redisTemplate;

    private final DefaultRedisScript<String> allowRequestScript;
    private final DefaultRedisScript<String> recordSuccessScript;
    private final DefaultRedisScript<String> recordFailureScript;

    @Autowired
    public circuitBreakerService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;

        this.allowRequestScript = new DefaultRedisScript<>();
        this.allowRequestScript.setLocation(new ClassPathResource("lua/allow_request.lua"));
        this.allowRequestScript.setResultType(String.class);

        this.recordSuccessScript = new DefaultRedisScript<>();
        this.recordSuccessScript.setLocation(new ClassPathResource("lua/record_success.lua"));
        this.recordSuccessScript.setResultType(String.class);

        this.recordFailureScript = new DefaultRedisScript<>();
        this.recordFailureScript.setLocation(new ClassPathResource("lua/record_failure.lua"));
        this.recordFailureScript.setResultType(String.class);
    }
      public String buildKey(String tenantId , String backendHost){
        return "circuit"+tenantId+":"+backendHost;
      }

      public boolean allowRequests(String tenantId, String backendHost){
        String key= buildKey(tenantId,backendHost);
        long now = System.currentTimeMillis();

        String result= redisTemplate.execute(
                allowRequestScript,
                Collections.singletonList(key)
                ,String.valueOf(now)
                ,String.valueOf(COOLDOWN_MS)
                ,String.valueOf(HALF_OPEN_MAX_TRIALS)
        );
        return "ALLOW".equals(result);
      }

      public void recordSucess(String tenantId, String backendHost) {
          String key = buildKey(tenantId, backendHost);

          redisTemplate.execute(recordSuccessScript,
                  Collections.singletonList(key)
                  , String.valueOf(HALF_OPEN_MAX_TRIALS)
          );
      }
    public void recordFailure(String tenantId, String backendHost) {
        String key = buildKey(tenantId, backendHost);
        long now = System.currentTimeMillis();
        redisTemplate.execute(
                recordFailureScript,
                Collections.singletonList(key),
                String.valueOf(now),
                String.valueOf(FAILURE_THRESHOLD)
        );
    }
    public String getState(String tenantId, String backendHost) {
        String key = buildKey(tenantId, backendHost);
        Object state = redisTemplate.opsForHash().get(key, "state");
        return state != null ? state.toString() : "CLOSED";
    }
}

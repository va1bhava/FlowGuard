package com.flowguard.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Service
public class AbuseDetectionService {

    private static final int WINDOW_SECONDS = 60;
    private static final int THRESHOLD = 100;      // 100 rejections in 60s = fire alert
    private static final int COOLDOWN_SECONDS = 300; // then stay quiet for 5 min

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<String> abuseScript;

    @Autowired
    public AbuseDetectionService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.abuseScript = new DefaultRedisScript<>();
        this.abuseScript.setLocation(new ClassPathResource("lua/rate_limit_abuse.lua"));
        this.abuseScript.setResultType(String.class);
    }

    // Returns true only when this call is the one that crossed the threshold —
    // false for every rejection before that, and false again for the whole
    // cooldown window after, even though the tenant is still being rejected.
    public boolean recordRejectionAndCheckThreshold(UUID tenantId) {
        String countKey = "abuse:429count:" + tenantId;
        String alertKey = "abuse:429alerted:" + tenantId;

        List<String> keys = List.of(countKey, alertKey);
        String result = redisTemplate.execute(
                abuseScript,
                keys,
                String.valueOf(WINDOW_SECONDS),
                String.valueOf(THRESHOLD),
                String.valueOf(COOLDOWN_SECONDS)
        );
        return "ALERT".equals(result);
    }
}
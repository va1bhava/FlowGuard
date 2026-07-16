package com.flowguard.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class AuthSessionService {

    private final StringRedisTemplate redisTemplate;

    private static final String SESSION_PREFIX = "auth:session:";
    private static final long SESSION_TTL_SECONDS = 60 * 60; // must be >= access token TTL

    private static final String REFRESH_PREFIX = "auth:refresh:";
    private static final String TENANT_REFRESH_PREFIX = "auth:tenant_refresh:";
    private static final long REFRESH_TTL_DAYS = 7;

    private static final String TENANT_SESSION_PREFIX = "auth:tenant_session:";

    // Enforces single-session-per-tenant: creating a new session kills whatever
    // session existed before it, so refresh cycles don't leave orphaned sessions
    // sitting in Redis until their TTL happens to expire.
    public void createSession(String sessionId, String tenantId) {
        String previousSessionId = redisTemplate.opsForValue().get(TENANT_SESSION_PREFIX + tenantId);
        if (previousSessionId != null) {
            redisTemplate.delete(SESSION_PREFIX + previousSessionId);
        }
        redisTemplate.opsForValue().set(SESSION_PREFIX + sessionId, tenantId, SESSION_TTL_SECONDS, TimeUnit.SECONDS);
        redisTemplate.opsForValue().set(TENANT_SESSION_PREFIX + tenantId, sessionId, SESSION_TTL_SECONDS, TimeUnit.SECONDS);
    }

    public boolean isSessionValid(String sessionId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(SESSION_PREFIX + sessionId));
    }

    public String getTenantIdFromSession(String sessionId) {
        return redisTemplate.opsForValue().get(SESSION_PREFIX + sessionId);
    }

    public void deleteSession(String sessionId) {
        String tenantId = redisTemplate.opsForValue().get(SESSION_PREFIX + sessionId);
        redisTemplate.delete(SESSION_PREFIX + sessionId);
        if (tenantId != null) {
            redisTemplate.delete(TENANT_SESSION_PREFIX + tenantId);
        }
    }

    // value stored as "tenantId:email" — same lookup key doubles as both an
    // existence check and a way to recover both fields on refresh.
    public void storeRefreshToken(String refreshToken, String tenantId, String email) {
        redisTemplate.opsForValue()
                .set(REFRESH_PREFIX + refreshToken, tenantId + ":" + email, REFRESH_TTL_DAYS, TimeUnit.DAYS);
        // Reverse mapping: tenantId -> refreshToken. This is what lets /refresh and
        // /logout work even when the browser never sends the refreshToken cookie
        // back (blocked third-party cookies, cross-site SameSite issues, etc) —
        // we recover the refresh token via the tenantId embedded in the (still
        // decodable, even if expired) access token instead of relying on the cookie.
        redisTemplate.opsForValue()
                .set(TENANT_REFRESH_PREFIX + tenantId, refreshToken, REFRESH_TTL_DAYS, TimeUnit.DAYS);
    }

    public String getTenantAndEmailFromRefreshToken(String refreshToken) {
        return redisTemplate.opsForValue().get(REFRESH_PREFIX + refreshToken);
    }

    public String getRefreshTokenByTenantId(String tenantId) {
        return redisTemplate.opsForValue().get(TENANT_REFRESH_PREFIX + tenantId);
    }

    public void deleteRefreshToken(String refreshToken) {
        redisTemplate.delete(REFRESH_PREFIX + refreshToken);
    }

    public void deleteRefreshTokenByTenantId(String tenantId) {
        String refreshToken = redisTemplate.opsForValue().get(TENANT_REFRESH_PREFIX + tenantId);
        if (refreshToken != null) {
            redisTemplate.delete(REFRESH_PREFIX + refreshToken);
        }
        redisTemplate.delete(TENANT_REFRESH_PREFIX + tenantId);
    }
}
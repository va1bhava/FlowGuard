package com.flowguard.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;
import java.util.UUID;

@Component
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secretKey;

    // Short-lived access token — the whole point is that it's cheap to reissue
    // via /refresh, so it doesn't need a long life.
    private static final long ACCESS_TOKEN_TTL_MS = 1000 * 60 * 15; // 15 minutes

    private Key key;

    @PostConstruct
    public void init() {
        this.key = new SecretKeySpec(
                secretKey.getBytes(StandardCharsets.UTF_8),
                0,
                secretKey.getBytes(StandardCharsets.UTF_8).length,
                "HmacSHA256"
        );
    }

    public String generateToken(String email, String tenantId, String sessionId) {
        return Jwts.builder()
                .subject(email)
                .claim("tenantId", tenantId)
                .claim("sessionId", sessionId)
                .issuedAt(new Date(System.currentTimeMillis()))
                .expiration(new Date(System.currentTimeMillis() + ACCESS_TOKEN_TTL_MS))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    public Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith((SecretKey) key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public String extractUsername(String token) {
        return extractAllClaims(token).getSubject();
    }

    public String extractTenantId(String token) {
        return extractAllClaims(token).get("tenantId", String.class);
    }

    public String extractSessionId(String token) {
        return extractAllClaims(token).get("sessionId", String.class);
    }

    // Used by /refresh when the access token has already expired — we still need
    // its sessionId to look up the userId->refreshToken reverse mapping, so we
    // parse the claims while explicitly ignoring the expiration check.
    public String extractSessionIdIgnoreExpiry(String token) {
        try {
            return Jwts.parser()
                    .verifyWith((SecretKey) key)
                    .clockSkewSeconds(Long.MAX_VALUE / 1000)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload()
                    .get("sessionId", String.class);
        } catch (Exception e) {
            return null;
        }
    }

    public boolean isTokenExpired(String token) {
        return extractAllClaims(token).getExpiration().before(new Date());
    }

    public String generateSessionId() {
        return UUID.randomUUID().toString();
    }

    public String generateRefreshToken() {
        return UUID.randomUUID().toString();
    }
}
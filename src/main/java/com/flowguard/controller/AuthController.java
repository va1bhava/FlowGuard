package com.flowguard.controller;

import com.flowguard.Entity.Tenant;
import com.flowguard.dto.LoginRequest;
import com.flowguard.dto.LoginResponse;
import com.flowguard.jwt.JwtUtil;
import com.flowguard.repository.TenantRepository;
import com.flowguard.service.AuthSessionService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tenants/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final TenantRepository tenantRepository;
    private final JwtUtil jwtUtil;
    private final AuthSessionService authSessionService;

    // Access tokens are 15 min (see JwtUtil), reported here so clients know when to refresh.
    private static final long ACCESS_TOKEN_TTL_SECONDS = 15 * 60;
    private static final int REFRESH_TOKEN_TTL_SECONDS = 7 * 24 * 60 * 60;
    private static final String REFRESH_COOKIE_NAME = "refreshToken";

    @Value("${cookie.secure:false}")
    private boolean cookieSecure;

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request, HttpServletResponse response) {
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword())
            );
        } catch (BadCredentialsException e) {
            throw new BadCredentialsException("Invalid email or password");
        }

        Tenant tenant = tenantRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));

        String tenantId = tenant.getId().toString();
        String sessionId = jwtUtil.generateSessionId();
        String accessToken = jwtUtil.generateToken(tenant.getEmail(), tenantId, sessionId);
        String refreshToken = jwtUtil.generateRefreshToken();

        authSessionService.createSession(sessionId, tenantId);
        authSessionService.storeRefreshToken(refreshToken, tenantId, tenant.getEmail());

        addRefreshCookie(response, refreshToken);

        return ResponseEntity.ok(LoginResponse.builder()
                .accessToken(accessToken)
                .tenantId(tenantId)
                .email(tenant.getEmail())
                .expiresIn(ACCESS_TOKEN_TTL_SECONDS)
                .build());
    }

    // Rotates the refresh token on every use (single-session enforcement): the old
    // one is invalidated and a new one issued, so a stolen refresh token only works once.
    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refresh(@CookieValue(name = REFRESH_COOKIE_NAME, required = false) String refreshToken,
                                                 HttpServletResponse response) {
        if (refreshToken == null) {
            return ResponseEntity.status(401).build();
        }

        String tenantAndEmail = authSessionService.getTenantAndEmailFromRefreshToken(refreshToken);
        if (tenantAndEmail == null) {
            return ResponseEntity.status(401).build();
        }

        String[] parts = tenantAndEmail.split(":", 2);
        String tenantId = parts[0];
        String email = parts[1];

        // Old refresh token is single-use — burn it before issuing a new one.
        authSessionService.deleteRefreshToken(refreshToken);

        String sessionId = jwtUtil.generateSessionId();
        String newAccessToken = jwtUtil.generateToken(email, tenantId, sessionId);
        String newRefreshToken = jwtUtil.generateRefreshToken();

        authSessionService.createSession(sessionId, tenantId);
        authSessionService.storeRefreshToken(newRefreshToken, tenantId, email);

        addRefreshCookie(response, newRefreshToken);

        return ResponseEntity.ok(LoginResponse.builder()
                .accessToken(newAccessToken)
                .tenantId(tenantId)
                .email(email)
                .expiresIn(ACCESS_TOKEN_TTL_SECONDS)
                .build());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                       @CookieValue(name = REFRESH_COOKIE_NAME, required = false) String refreshToken,
                                       HttpServletResponse response) {
        String tenantIdFromToken = null;

        // Access token (if present) lets us kill the exact session; sessionId survives
        // even in an expired token since we only need to read the claim, not validate it.
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            String sessionId = jwtUtil.extractSessionIdIgnoreExpiry(token);
            if (sessionId != null) {
                authSessionService.deleteSession(sessionId);
            }
            // Extract tenantId even from expired tokens — needed as fallback
            // when the refresh cookie isn't sent (cross-origin SameSite=Lax).
            tenantIdFromToken = jwtUtil.extractTenantIdIgnoreExpiry(token);
        }

        if (refreshToken != null) {
            String tenantAndEmail = authSessionService.getTenantAndEmailFromRefreshToken(refreshToken);
            if (tenantAndEmail != null) {
                String tenantId = tenantAndEmail.split(":", 2)[0];
                authSessionService.deleteRefreshTokenByTenantId(tenantId);
            } else {
                authSessionService.deleteRefreshToken(refreshToken);
            }
        } else if (tenantIdFromToken != null) {
            // Cookie was not sent (cross-origin POST with SameSite=Lax) —
            // fall back to the reverse mapping via tenantId from the JWT.
            authSessionService.deleteRefreshTokenByTenantId(tenantIdFromToken);
        }

        clearRefreshCookie(response);
        return ResponseEntity.ok().build();
    }

    private void addRefreshCookie(HttpServletResponse response, String refreshToken) {
        Cookie cookie = new Cookie(REFRESH_COOKIE_NAME, refreshToken);
        cookie.setHttpOnly(true);
        cookie.setSecure(cookieSecure);
        cookie.setPath("/api/tenants/auth");
        cookie.setMaxAge(REFRESH_TOKEN_TTL_SECONDS);
        cookie.setAttribute("SameSite", cookieSecure ? "None" : "Lax");
        response.addCookie(cookie);
    }

    private void clearRefreshCookie(HttpServletResponse response) {
        Cookie cookie = new Cookie(REFRESH_COOKIE_NAME, "");
        cookie.setHttpOnly(true);
        cookie.setSecure(cookieSecure);
        cookie.setPath("/api/tenants/auth");
        cookie.setMaxAge(0);
        response.addCookie(cookie);
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<String> handleBadCredentials(BadCredentialsException e) {
        return ResponseEntity.status(401).body("{\"error\": \"" + e.getMessage() + "\"}");
    }
}
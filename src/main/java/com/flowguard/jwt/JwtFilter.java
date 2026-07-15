package com.flowguard.jwt;

import com.flowguard.service.AuthSessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

// Only ever added to the filter chain for /api/tenants/** (management routes), never
// for proxied tenant traffic — that traffic authenticates via X-API-Key in
// RateLimiterFilter instead. See SecurityConfig for exactly which paths this guards.
@Component
@RequiredArgsConstructor
public class JwtFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final AuthSessionService authSessionService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getServletPath();

        // This filter is registered globally by Spring Boot (any @Component Filter
        // is auto-added to the chain for every request), but JWT auth only applies
        // to tenant-management routes. Proxied tenant traffic authenticates via
        // X-API-Key in RateLimiterFilter, and /ping, /debug, /flaky etc. have no
        // auth of their own — none of that should require a bearer token here.
        if (!path.startsWith("/api/tenants")) {
            filterChain.doFilter(request, response);
            return;
        }

        if (path.startsWith("/api/tenants/signup")
                || path.startsWith("/api/tenants/auth")) {
            filterChain.doFilter(request, response);
            return;
        }

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            unauthorized(response, "No token provided");
            return;
        }

        String token = authHeader.substring(7);
        try {
            String sessionId = jwtUtil.extractSessionId(token);
            if (!authSessionService.isSessionValid(sessionId)) {
                unauthorized(response, "Session expired");
                return;
            }
            if (jwtUtil.isTokenExpired(token)) {
                unauthorized(response, "Token expired");
                return;
            }

            String email = jwtUtil.extractUsername(token);
            String tenantId = jwtUtil.extractTenantId(token);

            var principal = new org.springframework.security.core.userdetails.User(email, "", List.of());
            var authToken = new UsernamePasswordAuthenticationToken(principal, null, List.of());
            authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authToken);

            // Stashed for controllers that need it without re-parsing the token.
            request.setAttribute("tenantId", tenantId);
        } catch (Exception e) {
            unauthorized(response, "Invalid token");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void unauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\": \"" + message + "\"}");
    }
}
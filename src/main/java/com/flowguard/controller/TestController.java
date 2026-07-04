package com.flowguard.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
public class TestController {

    // Test endpoint — hit this repeatedly to see rate limiting in action
    @GetMapping("/ping")
    public Map<String, Object> ping() {
        return Map.of(
                "status", "ok",
                "message", "You are within rate limit!",
                "timestamp", Instant.now().toString()
        );
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }
}

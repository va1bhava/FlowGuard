package com.flowguard.circuitBreaker;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
public class flakyController {

    private final AtomicBoolean forceFail = new AtomicBoolean(false);

    @GetMapping("/flaky/todos/1")
    public Map<String, Object> flaky() {
        boolean shouldFail = forceFail.get() || ThreadLocalRandom.current().nextInt(100) < 70;

        if (shouldFail) {
            throw new RuntimeException("Simulated downstream failure");
        }

        return Map.of(
                "userId", 1,
                "id", 1,
                "title", "delectus aut autem",
                "completed", false
        );
    }

    @GetMapping("/flaky/toggle")
    public Map<String, Object> toggle() {
        boolean newValue = !forceFail.get();
        forceFail.set(newValue);
        return Map.of("forceFail", newValue);
    }
}

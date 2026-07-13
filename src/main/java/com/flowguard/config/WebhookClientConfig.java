package com.flowguard.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
public class WebhookClientConfig {

    // Strict timeouts so one slow/hanging customer endpoint can't pin a
    // webhook-delivery semaphore permit forever and starve other tenants.
    @Bean
    public RestTemplate webhookRestTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(3))
                .setReadTimeout(Duration.ofSeconds(5))
                .build();
    }
}
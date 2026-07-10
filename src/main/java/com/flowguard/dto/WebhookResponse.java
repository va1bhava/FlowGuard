package com.flowguard.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.flowguard.Entity.WebhookConfig;
import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WebhookResponse {
    private UUID id;
    private String url;
    private String[] events;
    private boolean isActive;
    private String secret; // only populated on create()

    public static WebhookResponse from(WebhookConfig config) {
        return WebhookResponse.builder()
                .id(config.getId())
                .url(config.getUrl())
                .events(config.getEvents())
                .isActive(config.isActive())
                .build();
    }

    public static WebhookResponse fromWithSecret(WebhookConfig config) {
        WebhookResponse response = from(config);
        response.setSecret(config.getSecret());
        return response;
    }
}
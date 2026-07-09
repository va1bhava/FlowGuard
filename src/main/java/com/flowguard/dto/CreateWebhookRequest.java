package com.flowguard.dto;

import lombok.Data;

@Data
public class CreateWebhookRequest {
    private String url;
    private WebhookEvent[] events;
}
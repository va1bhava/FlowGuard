package com.flowguard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

// This is the entire "job" that travels through RabbitMQ. It carries everything
// the consumer needs to actually make the HTTP call — the consumer should never
// need to hit the DB just to deliver a message (DB lookups belong at publish time).
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebhookDeliveryMessage {

    private UUID deliveryId;      // maps to WebhookDelivery.id — used to update status after attempt
    private UUID tenantId;
    private String url;           // where to POST
    private String secret;        // used to HMAC-sign the payload
    private String eventName;
    private String payload;       // pre-serialized JSON body — sent byte-identical on every retry
    private int attemptCount;     // how many times this has already been tried
}
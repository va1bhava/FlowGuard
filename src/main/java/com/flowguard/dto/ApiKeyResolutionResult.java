package com.flowguard.dto;

import lombok.Builder;
import lombok.Data;
import java.util.UUID;

@Data
@Builder
public class ApiKeyResolutionResult {
    private UUID tenantId;
    private UUID apiKeyId;
    private String rateLimitKey;
    private int requestsPerMinute;
    private boolean unlimited;
    private String upstreamUrl;
}
// SignupResponse.java
package com.flowguard.dto;

import lombok.Builder;
import lombok.Data;
import java.util.UUID;

@Data
@Builder
public class SignUpResponse {
    private UUID tenantId;
    private String email;
    private String plan;
    private String apiKey;      // shown ONCE, never stored
    private String keyPrefix;   // shown in dashboard later e.g. "fg_live_abc1..."
    private String message;
}
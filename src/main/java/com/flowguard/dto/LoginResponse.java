package com.flowguard.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LoginResponse {
    private String accessToken;
    private String tenantId;
    private String email;
    private long expiresIn; // seconds
}
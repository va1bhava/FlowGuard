package com.flowguard.dto;

import lombok.Data;

@Data
public class SignUpRequest {
    private String name;
    private String email;
    private String password;
    private String keyName;
    private String upstreamUrl;
}
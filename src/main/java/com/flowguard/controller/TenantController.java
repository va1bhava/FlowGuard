package com.flowguard.controller;

import com.flowguard.dto.SignUpRequest;
import com.flowguard.dto.SignUpResponse;
import com.flowguard.service.TenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tenants")
@RequiredArgsConstructor
public class TenantController {

    private final TenantService tenantService;

    @PostMapping("/signup")
    public ResponseEntity<SignUpResponse> signup(@RequestBody SignUpRequest request) {
        SignUpResponse response = tenantService.SignUp(request);
        return ResponseEntity.ok(response);
    }
}
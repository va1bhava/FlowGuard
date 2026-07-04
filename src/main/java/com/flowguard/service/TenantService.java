package com.flowguard.service;

import com.flowguard.Entity.ApiKey;
import com.flowguard.Entity.Plans;
import com.flowguard.Entity.Tenant;
import com.flowguard.dto.SignUpRequest;
import com.flowguard.dto.SignUpResponse;
import com.flowguard.repository.*;
import io.netty.handler.codec.base64.Base64Encoder;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;

@Slf4j
@Service
@RequiredArgsConstructor
public class TenantService {
    private final TenantRepository tenantRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final PlanRepository planRepository;
    private final StringRedisTemplate redisTemplate;
    private final BCryptPasswordEncoder passwordEncoder;

    @Transactional
    public SignUpResponse SignUp(SignUpRequest request){
        if(tenantRepository.existsByEmail(request.getEmail())){
            throw new RuntimeException("Email already Registered");
        }
        Plans freePlan = planRepository.findByName("free")
                .orElseThrow(() -> new RuntimeException("Free plan not found — did you run the seed?"));

        Tenant tenant = Tenant.builder().name(request.getName()).email(request.getEmail())
                .plan(freePlan).passwordHash(passwordEncoder.encode(request.getPassword()))
                .upstreamUrl(request.getUpstreamUrl())
                .isActive(true).build();
        tenant = tenantRepository.save(tenant);
        String plainKey=generateApiKey();
        String hashKey=sha256(plainKey);
        String prefixKey=plainKey.substring(0,16)+"...";
        ApiKey api= ApiKey.builder().tenant(tenant)
                .name(request.getName()!=null?request.getName():"Default Key").isActive(true)
                .keyHash(hashKey).keyPrefix(prefixKey).build();
        apiKeyRepository.save(api);
        log.info("New tenant signed up: {} ({}), plan: free", tenant.getName(), tenant.getEmail());
        return SignUpResponse.builder()
                .tenantId(tenant.getId())
                .email(tenant.getEmail())
                .plan("free")
                .apiKey(plainKey)   // shown once, never retrievable again
                .keyPrefix(prefixKey)
                .message("Save your API key — it won't be shown again.")
                .build();
    }
    public String generateApiKey(){
        byte [] bytes= new byte[24];
        new SecureRandom().nextBytes(bytes);
        return "fg_live"+ Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    public String sha256(String input)  {
        try{
            MessageDigest digest= MessageDigest.getInstance("SHA-256");
            byte [] hash= digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        }
        catch (NoSuchAlgorithmException e){
            throw new RuntimeException("SHA-256 not available", e);
        }

    }
}
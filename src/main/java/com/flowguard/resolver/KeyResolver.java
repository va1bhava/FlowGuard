package com.flowguard.resolver;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowguard.Entity.ApiKey;
import com.flowguard.dto.ApiKeyResolutionResult;
import com.flowguard.exception.InvalidApiKeyException;
import com.flowguard.repository.ApiKeyRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.bridge.Message;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.objenesis.ObjenesisException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

@Slf4j
@RequiredArgsConstructor
@Component
public class KeyResolver {

    private final ObjectMapper objectMapper;

    private final String CACHE_PREFIX="apikey";

    private final Duration CACHE_TTL= Duration.ofMinutes(5);

    private final StringRedisTemplate stringRedisTemplate;

    private final ApiKeyRepository apiKeyRepository;

    public ApiKeyResolutionResult resolve(HttpServletRequest request) {

        String apiKey=request.getHeader("X-API-Key");
        if(apiKey==null){
            return resolveByIP(request);
        }
        String keyHash=sha256(apiKey);
        String cacheKey=CACHE_PREFIX+keyHash;

        String cached=  stringRedisTemplate.opsForValue().get(cacheKey);

        if(cached!=null){
            //cache hit
            log.debug("Cache HIT for key: {}", apiKey.substring(0, 12));
            return deserializer(cached);
        }

        //cache miss
        log.debug("Cache Miss for key: {}", apiKey.substring(0, 12));
        //fetch DB
        ApiKey foundKey=apiKeyRepository.findByKeyHash(keyHash)
                .orElseThrow(()-> new InvalidApiKeyException("Api key invalid"));

        if(!foundKey.isActive()){
            throw new InvalidApiKeyException("Api key is inactive");
        }
        if(foundKey.getExpiresAt()!=null&&foundKey.getExpiresAt().isBefore(Instant.now())){
            throw new InvalidApiKeyException("Api key expired");
        }

        ApiKeyResolutionResult result=buildResult(foundKey);

        stringRedisTemplate.opsForValue().set(cacheKey,serializer(result),CACHE_TTL);
        return result;
    }
    public ApiKeyResolutionResult buildResult(ApiKey foundedKey){
        boolean unlimited = foundedKey.getTenant().getPlan().isUnlimited();
        return ApiKeyResolutionResult.builder().tenantId(foundedKey.getTenant().getId())
                .rateLimitKey("rl:tenant:" + foundedKey.getTenant().getId()).unlimited(unlimited)
                .requestsPerMinute(foundedKey.getTenant().getPlan().getRequestsPerMinute())
                .upstreamUrl(foundedKey.getTenant().getUpstreamUrl()).build();
    }
    public ApiKeyResolutionResult resolveByIP(HttpServletRequest request){
        String forwardedFor = request.getHeader("X-Forwarded-For");
        String ip = (forwardedFor != null && !forwardedFor.isBlank()) ?
                forwardedFor : request.getRemoteAddr();

        return ApiKeyResolutionResult.builder().tenantId(null).unlimited(false)
                .rateLimitKey("rl:ip:" + ip).requestsPerMinute(10).build();
    }
    public String sha256(String input){
        try {
            MessageDigest digest= MessageDigest.getInstance("SHA-256");
            byte [] hash= digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        }
        catch (Exception e){
            throw new RuntimeException();
        }
    }
    public String serializer(ApiKeyResolutionResult result){
        try {
            return objectMapper.writeValueAsString(result);
        }
        catch (Exception e){
            throw new RuntimeException("Could not Serialize");
        }
    }
    public ApiKeyResolutionResult deserializer(String json){
        try{
            return objectMapper.readValue(json,ApiKeyResolutionResult.class);
        }
        catch (Exception e){
            throw new RuntimeException("Could not deserialze");
        }
    }
}
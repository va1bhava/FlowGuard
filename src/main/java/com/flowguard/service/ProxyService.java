package com.flowguard.service;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProxyService {

    private final RestTemplate restTemplate;

    private static final List<String> EXCLUDED_HEADERS = List.of(
            "host", "x-api-key", "content-length"
    );

    public ResponseEntity<byte[]> forward(HttpServletRequest request,
                                          String upstreamUrl,
                                          String requestBody) {

        String path = request.getRequestURI();
        String query = request.getQueryString();
        String targetUrl = upstreamUrl + path + (query != null ? "?" + query : "");

        log.info("Proxying {} {} → {}", request.getMethod(), path, targetUrl);

        HttpHeaders headers = new HttpHeaders();

        // Here the request.getNames returns the Enumeration , and this enumeration is old type
        // We cannot run the for-each loop on the enumeration so , the collection.list converts its
        // to the normal list !!

        Collections.list(request.getHeaderNames()).forEach(headerName->{
            if(!EXCLUDED_HEADERS.contains(headerName))
                headers.set(headerName,request.getHeader(headerName));
        });
        HttpMethod method = HttpMethod.valueOf(request.getMethod());
        HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);

        try {
            return restTemplate.exchange(targetUrl, method, entity, byte[].class);
        } catch (HttpStatusCodeException e) {
            return ResponseEntity.status(e.getStatusCode())
                    .headers(e.getResponseHeaders())
                    .body(e.getResponseBodyAsByteArray());
        } catch (Exception e) {
            log.error("Proxy error: {}", e.getMessage());
            return ResponseEntity.status(502)
                    .body(("{\"error\":\"Bad Gateway\",\"message\":\"" + e.getMessage() + "\"}").getBytes());
        }
    }
}
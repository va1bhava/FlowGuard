package com.flowguard.util;

import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

@Component
public class WebhookUrlValidator {

    // Blocks a tenant from pointing their webhook at internal infra:
    // localhost, private LAN ranges, link-local (includes cloud metadata 169.254.169.254).
    public void validate(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed webhook URL");
        }

        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("Webhook URL must use http or https");
        }
        if (uri.getHost() == null) {
            throw new IllegalArgumentException("Webhook URL must have a host");
        }

        // Resolve DNS ourselves and check the actual IP — resolving at call time in
        // WebhookService too would help against DNS-rebinding, but this stops the
        // common case (localhost, private IPs, metadata endpoint) at creation time.
        InetAddress addr;
        try {
            addr = InetAddress.getByName(uri.getHost());
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Webhook host could not be resolved");
        }

        if (addr.isLoopbackAddress() || addr.isLinkLocalAddress()
                || addr.isSiteLocalAddress() || addr.isAnyLocalAddress()
                || addr.isMulticastAddress()) {
            throw new IllegalArgumentException("Webhook URL points to a disallowed internal address");
        }
    }
}
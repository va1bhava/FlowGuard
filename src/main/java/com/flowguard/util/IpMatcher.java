package com.flowguard.util;

import java.net.InetAddress;
import java.net.UnknownHostException;

// Supports exact IP match ("1.2.3.4") and CIDR subnet match ("1.2.3.0/24").
// IPv4-focused — mismatched address families (e.g. comparing an IPv6 client
// IP against an IPv4 rule) safely return false rather than throwing.
public final class IpMatcher {

    private IpMatcher() {}

    public static boolean matches(String clientIp, String rule) {
        try {
            if (rule.contains("/")) {
                return matchesCidr(clientIp, rule);
            }
            return clientIp.equals(rule);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean matchesCidr(String ip, String cidr) throws UnknownHostException {
        String[] parts = cidr.split("/");
        String subnetAddr = parts[0];
        int prefixLength = Integer.parseInt(parts[1]);

        byte[] ipBytes = InetAddress.getByName(ip).getAddress();
        byte[] subnetBytes = InetAddress.getByName(subnetAddr).getAddress();

        if (ipBytes.length != subnetBytes.length) {
            return false; // one's IPv4, the other's IPv6 — not comparable
        }

        int fullBytes = prefixLength / 8;
        int remainingBits = prefixLength % 8;

        for (int i = 0; i < fullBytes; i++) {
            if (ipBytes[i] != subnetBytes[i]) {
                return false;
            }
        }
        if (remainingBits > 0) {
            int mask = 0xFF << (8 - remainingBits);
            if ((ipBytes[fullBytes] & mask) != (subnetBytes[fullBytes] & mask)) {
                return false;
            }
        }
        return true;
    }
}
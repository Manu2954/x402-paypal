package io.x402.paypal.config;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "x402")
public record X402Properties(
        String network,
        String asset,
        String payTo,
        String masterKey,
        String vaultKey,
        Duration challengeTtl,
        Map<String, Long> routes
) {
}

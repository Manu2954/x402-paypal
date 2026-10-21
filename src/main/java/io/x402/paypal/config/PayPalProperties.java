package io.x402.paypal.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "paypal")
public record PayPalProperties(
        String env,
        String clientId,
        String clientSecret,
        String webhookId,
        Duration connectTimeout,
        Duration readTimeout
) {

    public String baseUrl() {
        return "live".equalsIgnoreCase(env)
                ? "https://api-m.paypal.com"
                : "https://api-m.sandbox.paypal.com";
    }

    public boolean isConfigured() {
        return clientId != null && !clientId.isBlank()
                && clientSecret != null && !clientSecret.isBlank();
    }
}

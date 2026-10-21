package io.x402.paypal.paypal;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.x402.paypal.config.PayPalProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

@Component
public class PayPalAuthClient {

    private static final long EXPIRY_SKEW_SECONDS = 60;

    private final RestClient restClient;
    private final PayPalProperties properties;
    private final Object refreshLock = new Object();

    private volatile CachedToken cachedToken;

    public PayPalAuthClient(RestClient payPalRestClient, PayPalProperties properties) {
        this.restClient = payPalRestClient;
        this.properties = properties;
    }

    public String accessToken() {
        CachedToken current = cachedToken;
        if (current != null && current.expiresAt().isAfter(Instant.now().plusSeconds(EXPIRY_SKEW_SECONDS))) {
            return current.value();
        }

        synchronized (refreshLock) {
            current = cachedToken;
            if (current != null && current.expiresAt().isAfter(Instant.now().plusSeconds(EXPIRY_SKEW_SECONDS))) {
                return current.value();
            }

            CachedToken refreshed = fetchToken();
            cachedToken = refreshed;
            return refreshed.value();
        }
    }

    public Map<String, Object> tokenStatus() {
        String token = accessToken();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("configured", properties.isConfigured());
        status.put("environment", properties.env());
        status.put("baseUrl", properties.baseUrl());
        status.put("tokenValid", token != null && !token.isBlank());
        status.put("expiresAt", cachedToken == null ? null : cachedToken.expiresAt().toString());
        return status;
    }

    private CachedToken fetchToken() {
        if (!properties.isConfigured()) {
            throw new IllegalStateException("PayPal client credentials are not configured");
        }

        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");

        TokenResponse response = restClient.post()
                .uri("/v1/oauth2/token")
                .headers(headers -> headers.setBasicAuth(properties.clientId(), properties.clientSecret()))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(TokenResponse.class);

        if (response == null || response.accessToken() == null || response.accessToken().isBlank()) {
            throw new IllegalStateException("PayPal returned an empty access token");
        }

        long lifetimeSeconds = Math.max(1, response.expiresIn() - EXPIRY_SKEW_SECONDS);
        return new CachedToken(response.accessToken(), Instant.now().plusSeconds(lifetimeSeconds));
    }

    private record CachedToken(String value, Instant expiresAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") long expiresIn
    ) {
    }
}

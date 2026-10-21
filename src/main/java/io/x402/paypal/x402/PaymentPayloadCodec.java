package io.x402.paypal.x402;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class PaymentPayloadCodec {

    private final ObjectMapper objectMapper;

    public PaymentPayloadCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public PaymentPayload decode(String headerValue) {
        try {
            byte[] json = decodeBase64(headerValue);
            return objectMapper.readValue(json, PaymentPayload.class);
        } catch (Exception exception) {
            throw new IllegalArgumentException("PAYMENT-SIGNATURE is not valid base64 JSON", exception);
        }
    }

    public String encode(Object value) {
        try {
            return Base64.getEncoder().encodeToString(objectMapper.writeValueAsBytes(value));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not encode payload", exception);
        }
    }

    public String canonical(String challengeId, String method, String resource, String amount,
                            String asset, String nonce, long timestamp) {
        return String.join("\n",
                challengeId,
                method,
                resource,
                amount,
                asset,
                nonce,
                Long.toString(timestamp)
        );
    }

    private byte[] decodeBase64(String value) {
        String normalized = value.trim();
        try {
            return Base64.getDecoder().decode(normalized);
        } catch (IllegalArgumentException exception) {
            return Base64.getUrlDecoder().decode(normalized);
        }
    }

    public record PaymentPayload(int x402Version, JsonNode accepted, Payload payload) {
    }

    public record Payload(String agentId, String challengeId, String nonce, long timestamp, String signature) {
    }
}

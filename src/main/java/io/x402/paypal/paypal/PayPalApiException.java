package io.x402.paypal.paypal;

import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.client.RestClientResponseException;

public class PayPalApiException extends RuntimeException {

    private static final Set<String> PAYER_ACTION_RELS = Set.of(
            "payer-action", "confirm-payment-source", "approve", "redirect"
    );

    private final int statusCode;
    private final String errorName;
    private final String debugId;
    private final JsonNode details;
    private final JsonNode links;

    public PayPalApiException(int statusCode, String errorName, String message, String debugId,
                              JsonNode details, JsonNode links) {
        super(message);
        this.statusCode = statusCode;
        this.errorName = errorName;
        this.debugId = debugId;
        this.details = details;
        this.links = links;
    }

    public static PayPalApiException from(RestClientResponseException exception, ObjectMapper objectMapper) {
        int status = exception.getStatusCode().value();
        String name = "PAYPAL_ERROR";
        String message = exception.getStatusText();
        String debugId = null;
        JsonNode details = null;
        JsonNode links = null;

        try {
            JsonNode root = objectMapper.readTree(exception.getResponseBodyAsString());
            name = text(root, "name", name);
            message = text(root, "message", message);
            debugId = text(root, "debug_id", null);
            details = root.get("details");
            links = root.get("links");
        } catch (Exception ignored) {
            // Keep the HTTP status text when PayPal returns a non-JSON body.
        }

        return new PayPalApiException(status, name, message, debugId, details, links);
    }

    public int statusCode() {
        return statusCode;
    }

    public String errorName() {
        return errorName;
    }

    public String debugId() {
        return debugId;
    }

    public JsonNode details() {
        return details;
    }

    public JsonNode links() {
        return links;
    }

    public boolean isPayerActionRequired() {
        return statusCode == 422 && ("PAYER_ACTION_REQUIRED".equals(errorName) || payerActionUrl() != null);
    }

    public String payerActionUrl() {
        if (links == null || !links.isArray()) {
            return null;
        }
        for (JsonNode link : links) {
            String rel = link.path("rel").asText();
            if (PAYER_ACTION_RELS.contains(rel)) {
                return link.path("href").asText(null);
            }
        }
        return null;
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? fallback : value.asText();
    }
}

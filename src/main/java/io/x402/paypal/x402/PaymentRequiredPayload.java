package io.x402.paypal.x402;

import java.util.List;
import java.util.Map;

public record PaymentRequiredPayload(
        int x402Version,
        String error,
        Resource resource,
        List<Accept> accepts
) {

    public record Resource(String url, String description, String mimeType) {
    }

    public record Accept(
            String scheme,
            String network,
            String amount,
            String asset,
            String payTo,
            int maxTimeoutSeconds,
            Map<String, Object> extra
    ) {
    }
}

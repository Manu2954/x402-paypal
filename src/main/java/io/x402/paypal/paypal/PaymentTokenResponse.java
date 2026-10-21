package io.x402.paypal.paypal;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentTokenResponse(
        String id,
        String status,
        List<Link> links,
        @JsonProperty("payment_source") PaymentSource paymentSource
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PaymentSource(Card card, PayPal paypal) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Card(String brand, @JsonProperty("last_digits") String last4) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PayPal(@JsonProperty("email_address") String emailAddress,
                         @JsonProperty("account_id") String accountId) {
    }
}

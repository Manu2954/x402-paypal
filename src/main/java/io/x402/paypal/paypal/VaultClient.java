package io.x402.paypal.paypal;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

@Component
public class VaultClient {

    private final PayPalApi payPalApi;

    public VaultClient(PayPalApi payPalApi) {
        this.payPalApi = payPalApi;
    }

    public SetupTokenResponse createWalletSetupToken(String customerId, String description,
                                                     String brandName, String returnUrl, String cancelUrl) {
        Map<String, Object> body = Map.of(
                "payment_source", Map.of("paypal", Map.of(
                        "description", description,
                        "usage_pattern", "UNSCHEDULED_PREPAID",
                        "usage_type", "MERCHANT",
                        "experience_context", Map.of(
                                "brand_name", brandName,
                                "locale", "en-US",
                                "return_url", returnUrl,
                                "cancel_url", cancelUrl
                        )
                )),
                "customer", Map.of("id", customerId)
        );
        return payPalApi.post("/v3/vault/setup-tokens", body, SetupTokenResponse.class, UUID.randomUUID().toString());
    }

    public SetupTokenResponse createCardSetupToken(String customerId, String brandName,
                                                    String returnUrl, String cancelUrl) {
        Map<String, Object> body = Map.of(
                "payment_source", Map.of("card", Map.of(
                        "verification_method", "SCA_WHEN_REQUIRED",
                        "experience_context", Map.of(
                                "brand_name", brandName,
                                "locale", "en-US",
                                "return_url", returnUrl,
                                "cancel_url", cancelUrl
                        )
                )),
                "customer", Map.of("id", customerId)
        );
        return payPalApi.post("/v3/vault/setup-tokens", body, SetupTokenResponse.class, UUID.randomUUID().toString());
    }

    public SetupTokenResponse getSetupToken(String setupTokenId) {
        return payPalApi.get("/v3/vault/setup-tokens/" + setupTokenId, SetupTokenResponse.class);
    }

    public PaymentTokenResponse exchangeSetupToken(String setupTokenId) {
        Map<String, Object> body = Map.of(
                "payment_source", Map.of("token", Map.of(
                        "id", setupTokenId,
                        "type", "SETUP_TOKEN"
                ))
        );
        return payPalApi.post("/v3/vault/payment-tokens", body, PaymentTokenResponse.class, UUID.randomUUID().toString());
    }

    public PaymentTokenResponse getPaymentToken(String paymentTokenId) {
        return payPalApi.get("/v3/vault/payment-tokens/" + paymentTokenId, PaymentTokenResponse.class);
    }

    public void deletePaymentToken(String paymentTokenId) {
        payPalApi.delete("/v3/vault/payment-tokens/" + paymentTokenId);
    }
}

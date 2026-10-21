package io.x402.paypal.paypal;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.stereotype.Component;

@Component
public class OrdersClient {

    private final PayPalApi payPalApi;

    public OrdersClient(PayPalApi payPalApi) {
        this.payPalApi = payPalApi;
    }

    public OrderResponse createOrder(String sourceType, String vaultToken, long amountCents,
                                     String attemptId, String description) {
        Map<String, Object> amount = Map.of(
                "currency_code", "USD",
                "value", BigDecimal.valueOf(amountCents, 2).toPlainString()
        );

        Map<String, Object> purchaseUnit = Map.of(
                "reference_id", attemptId,
                "custom_id", attemptId,
                "description", description,
                "amount", amount
        );

        Map<String, Object> source;
        if ("CARD".equals(sourceType)) {
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("vault_id", vaultToken);
            card.put("stored_credential", Map.of(
                    "payment_initiator", "MERCHANT",
                    "payment_type", "UNSCHEDULED",
                    "usage", "SUBSEQUENT"
            ));
            source = Map.of("card", card);
        } else {
            source = Map.of("paypal", Map.of("vault_id", vaultToken));
        }

        Map<String, Object> body = Map.of(
                "intent", "CAPTURE",
                "purchase_units", List.of(purchaseUnit),
                "payment_source", source
        );

        return payPalApi.post("/v2/checkout/orders", body, OrderResponse.class, attemptId + ":create");
    }

    public OrderResponse getOrder(String orderId) {
        return payPalApi.get("/v2/checkout/orders/" + orderId, OrderResponse.class);
    }

    public CaptureOrderResponse captureOrder(String orderId, String attemptId) {
        return payPalApi.post("/v2/checkout/orders/" + orderId + "/capture",
                Map.of(), CaptureOrderResponse.class, attemptId + ":capture");
    }

    public CaptureOrderResponse asCapture(OrderResponse order) {
        return new CaptureOrderResponse(order.id(), order.status(), order.purchaseUnits());
    }

    public CaptureSummary summary(CaptureOrderResponse response) {
        if (response == null || response.purchaseUnits() == null || response.purchaseUnits().isEmpty()) {
            return null;
        }
        CaptureOrderResponse.PurchaseUnit unit = response.purchaseUnits().getFirst();
        if (unit.payments() == null || unit.payments().captures() == null || unit.payments().captures().isEmpty()) {
            return null;
        }
        CaptureOrderResponse.Capture capture = unit.payments().captures().getFirst();
        CaptureOrderResponse.Breakdown breakdown = capture.sellerReceivableBreakdown();
        return new CaptureSummary(
                capture.id(),
                capture.status(),
                amount(breakdown == null ? capture.amount() : breakdown.grossAmount()),
                amount(breakdown == null ? null : breakdown.paypalFee()),
                amount(breakdown == null ? null : breakdown.netAmount())
        );
    }

    private String amount(CaptureOrderResponse.Amount amount) {
        return amount == null ? null : amount.value();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OrderResponse(String id, String status, List<Link> links,
                                @JsonProperty("purchase_units") List<CaptureOrderResponse.PurchaseUnit> purchaseUnits) {

        public String payerActionUrl() {
            if (links == null) {
                return null;
            }
            return links.stream()
                    .filter(link -> "payer-action".equals(link.rel()) || "approve".equals(link.rel()))
                    .map(Link::href)
                    .findFirst()
                    .orElse(null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CaptureOrderResponse(String id, String status,
                                       @JsonProperty("purchase_units") List<PurchaseUnit> purchaseUnits) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record PurchaseUnit(Payments payments) {
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Payments(List<Capture> captures) {
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Capture(String id, String status, Amount amount,
                              @JsonProperty("seller_receivable_breakdown") Breakdown sellerReceivableBreakdown,
                              List<Link> links) {
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Amount(@JsonProperty("currency_code") String currencyCode, String value) {
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Breakdown(
                @JsonProperty("gross_amount") Amount grossAmount,
                @JsonProperty("paypal_fee") Amount paypalFee,
                @JsonProperty("net_amount") Amount netAmount
        ) {
        }
    }

    public record CaptureSummary(String captureId, String status, String gross, String fee, String net) {
    }
}

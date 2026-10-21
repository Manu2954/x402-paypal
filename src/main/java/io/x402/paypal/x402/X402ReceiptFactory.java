package io.x402.paypal.x402;

import java.util.LinkedHashMap;
import java.util.Map;

import io.x402.paypal.config.X402Properties;
import io.x402.paypal.payments.PaymentResult;
import org.springframework.stereotype.Component;

@Component
public class X402ReceiptFactory {

    private final PaymentPayloadCodec codec;
    private final X402Properties properties;

    public X402ReceiptFactory(PaymentPayloadCodec codec, X402Properties properties) {
        this.codec = codec;
        this.properties = properties;
    }

    public String header(PaymentResult result, String principalId) {
        Map<String, Object> extra = new LinkedHashMap<>();
        if (result.orderId() != null) {
            extra.put("orderId", result.orderId());
        }
        if (result.captureId() != null) {
            extra.put("captureId", result.captureId());
        }
        if (result.gross() != null) {
            extra.put("gross", result.gross());
        }
        if (result.fee() != null) {
            extra.put("fee", result.fee());
        }
        if (result.net() != null) {
            extra.put("net", result.net());
        }
        extra.put("status", result.captureStatus() == null ? "COMPLETED" : result.captureStatus());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("transaction", result.captureId());
        body.put("network", properties.network());
        body.put("payer", principalId);
        body.put("extra", extra);
        return codec.encode(body);
    }
}

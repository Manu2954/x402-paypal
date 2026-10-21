package io.x402.paypal.payments;

public record PaymentResult(
        String attemptId,
        String orderId,
        String captureId,
        String captureStatus,
        String gross,
        String fee,
        String net,
        String contentJson,
        String contentType,
        boolean replay
) {
}

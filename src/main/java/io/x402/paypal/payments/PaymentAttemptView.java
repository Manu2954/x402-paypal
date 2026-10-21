package io.x402.paypal.payments;

import java.time.LocalDateTime;

public record PaymentAttemptView(
        String attemptId,
        String status,
        long amountCents,
        String orderId,
        String captureId,
        String errorCode,
        String payerActionUrl,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}

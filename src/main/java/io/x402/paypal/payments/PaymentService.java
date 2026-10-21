package io.x402.paypal.payments;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.x402.paypal.agent.PrincipalRepository;
import io.x402.paypal.enrollment.EnrollmentEntity;
import io.x402.paypal.enrollment.EnrollmentRepository;
import io.x402.paypal.paypal.OrdersClient;
import io.x402.paypal.paypal.PayPalApiException;
import io.x402.paypal.payment.ChallengeEntity;
import io.x402.paypal.payment.PaymentAttemptEntity;
import io.x402.paypal.payment.PaymentAttemptRepository;
import io.x402.paypal.premium.PremiumContent;
import io.x402.paypal.security.CryptoService;
import io.x402.paypal.security.Ids;
import io.x402.paypal.x402.ChallengeService;
import io.x402.paypal.x402.X402Exception;
import org.springframework.stereotype.Service;

@Service
public class PaymentService {

    private final PaymentAttemptRepository attemptRepository;
    private final PrincipalRepository principalRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final OrdersClient ordersClient;
    private final CryptoService cryptoService;
    private final ChallengeService challengeService;
    private final ObjectMapper objectMapper;

    public PaymentService(PaymentAttemptRepository attemptRepository, PrincipalRepository principalRepository,
                          EnrollmentRepository enrollmentRepository, OrdersClient ordersClient,
                          CryptoService cryptoService, ChallengeService challengeService, ObjectMapper objectMapper) {
        this.attemptRepository = attemptRepository;
        this.principalRepository = principalRepository;
        this.enrollmentRepository = enrollmentRepository;
        this.ordersClient = ordersClient;
        this.cryptoService = cryptoService;
        this.challengeService = challengeService;
        this.objectMapper = objectMapper;
    }

    public PaymentResult settle(String principalId, ChallengeEntity challenge, EnrollmentEntity enrollment,
                                String description, PremiumContent content) {
        if (enrollment == null || !"ACTIVE".equals(enrollment.getStatus())
                || enrollment.getVaultTokenEnc() == null) {
            throw new X402Exception("ENROLLMENT_REQUIRED", "No active payment enrollment", null, null, null);
        }

        if (!challengeService.consumeIfUnused(challenge)) {
            return replay(principalId, challenge.getId()).orElseThrow(() -> new X402Exception(
                    "REPLAY_DETECTED", "Challenge was already used", null, null, null));
        }

        long amountCents = challenge.getAmountCents();
        principalRepository.resetPeriod(principalId, LocalDate.now(java.time.ZoneOffset.UTC).withDayOfMonth(1));
        if (principalRepository.reserveBudget(principalId, amountCents) == 0) {
            throw new X402Exception("BUDGET_EXCEEDED", "Agent budget does not cover this request",
                    null, null, null);
        }

        PaymentAttemptEntity attempt = new PaymentAttemptEntity(
                Ids.id("pay_"),
                challenge.getId(),
                principalId,
                enrollment.getId(),
                amountCents,
                "PROCESSING",
                LocalDateTime.now(Clock.systemUTC())
        );
        attempt.setContent(content.json(), content.contentType());
        attemptRepository.save(attempt);

        String vaultToken;
        try {
            vaultToken = cryptoService.decryptVaultToken(enrollment.getVaultTokenEnc());
        } catch (RuntimeException exception) {
            return fail(attempt, principalId, amountCents,
                    "VAULT_TOKEN_INVALID", "Stored payment token could not be read");
        }

        OrdersClient.OrderResponse order;
        try {
            order = ordersClient.createOrder(
                    enrollment.getSourceType(), vaultToken, amountCents, attempt.getId(), description);
        } catch (PayPalApiException exception) {
            return payPalFailure(attempt, principalId, enrollment, amountCents, exception, false);
        }

        attempt.setOrderId(order.id());
        attemptRepository.save(attempt);

        if (!"COMPLETED".equals(order.status()) && order.payerActionUrl() != null) {
            attempt.setStatus("REQUIRES_ACTION");
            attempt.setPayerActionUrl(order.payerActionUrl());
            attemptRepository.save(attempt);
            throw new X402Exception("PAYER_ACTION_REQUIRED", "Additional payer action is required",
                    null, order.payerActionUrl(), attempt.getId());
        }

        if ("COMPLETED".equals(order.status()) && ordersClient.summary(ordersClient.asCapture(order)) != null) {
            return succeed(attempt, ordersClient.asCapture(order));
        }

        try {
            OrdersClient.CaptureOrderResponse capture = ordersClient.captureOrder(order.id(), attempt.getId());
            return succeed(attempt, capture);
        } catch (PayPalApiException exception) {
            return payPalFailure(attempt, principalId, enrollment, amountCents, exception, true);
        }
    }

    public PaymentResult resume(String principalId, String attemptId) {
        PaymentAttemptEntity attempt = attemptRepository.findByIdAndPrincipalId(attemptId, principalId)
                .orElseThrow(() -> new IllegalArgumentException("Payment attempt not found"));
        if (!"REQUIRES_ACTION".equals(attempt.getStatus())) {
            throw new IllegalStateException("Payment attempt is not waiting for payer action");
        }
        if (attempt.getOrderId() == null) {
            throw new IllegalStateException("Payment attempt has no PayPal order");
        }

        try {
            OrdersClient.OrderResponse order = ordersClient.getOrder(attempt.getOrderId());
            OrdersClient.CaptureOrderResponse existing = ordersClient.asCapture(order);
            if ("COMPLETED".equals(order.status()) && ordersClient.summary(existing) != null) {
                return succeed(attempt, existing);
            }
            OrdersClient.CaptureOrderResponse capture = ordersClient.captureOrder(
                    attempt.getOrderId(), attempt.getId());
            return succeed(attempt, capture);
        } catch (PayPalApiException exception) {
            EnrollmentEntity enrollment = enrollmentRepository.findById(attempt.getEnrollmentId()).orElse(null);
            return payPalFailure(attempt, principalId, enrollment, attempt.getAmountCents(), exception, true);
        }
    }

    public Optional<PaymentResult> replay(String principalId, String challengeId) {
        if (challengeId == null) {
            return Optional.empty();
        }
        return attemptRepository.findByChallengeId(challengeId)
                .filter(attempt -> attempt.getPrincipalId().equals(principalId))
                .filter(attempt -> "SETTLED".equals(attempt.getStatus()))
                .map(attempt -> new PaymentResult(
                        attempt.getId(),
                        attempt.getOrderId(),
                        attempt.getCaptureId(),
                        "COMPLETED",
                        null,
                        null,
                        null,
                        attempt.getContentJson(),
                        attempt.getContentType(),
                        true
                ));
    }

    public PaymentAttemptView view(String principalId, String attemptId) {
        PaymentAttemptEntity attempt = attemptRepository.findByIdAndPrincipalId(attemptId, principalId)
                .orElseThrow(() -> new IllegalArgumentException("Payment attempt not found"));
        return new PaymentAttemptView(
                attempt.getId(),
                attempt.getStatus(),
                attempt.getAmountCents(),
                attempt.getOrderId(),
                attempt.getCaptureId(),
                attempt.getErrorCode(),
                attempt.getPayerActionUrl(),
                attempt.getCreatedAt(),
                attempt.getUpdatedAt()
        );
    }

    public PaymentResult succeed(PaymentAttemptEntity attempt, OrdersClient.CaptureOrderResponse capture) {
        OrdersClient.CaptureSummary summary = ordersClient.summary(capture);
        if (summary == null || !"COMPLETED".equals(summary.status())) {
            attempt.setStatus("FAILED");
            attempt.setErrorCode("CAPTURE_NOT_COMPLETED");
            attemptRepository.save(attempt);
            principalRepository.releaseBudget(attempt.getPrincipalId(), attempt.getAmountCents());
            throw new X402Exception("PAYMENT_FAILED", "PayPal did not complete the capture",
                    null, null, attempt.getId());
        }

        attempt.setStatus("SETTLED");
        attempt.setCaptureId(summary.captureId());
        attempt.setResponseJson(writeJson(capture));
        attemptRepository.save(attempt);

        return new PaymentResult(
                attempt.getId(),
                attempt.getOrderId(),
                summary.captureId(),
                summary.status(),
                summary.gross(),
                summary.fee(),
                summary.net(),
                attempt.getContentJson(),
                attempt.getContentType(),
                false
        );
    }

    private PaymentResult payPalFailure(PaymentAttemptEntity attempt, String principalId,
                                        EnrollmentEntity enrollment, long amountCents,
                                        PayPalApiException exception, boolean capturePhase) {
        if (exception.isPayerActionRequired()) {
            String payerActionUrl = exception.payerActionUrl();
            attempt.setStatus("REQUIRES_ACTION");
            attempt.setPayerActionUrl(payerActionUrl);
            attempt.setErrorCode("PAYER_ACTION_REQUIRED");
            attemptRepository.save(attempt);
            throw new X402Exception("PAYER_ACTION_REQUIRED", "Additional payer action is required",
                    null, payerActionUrl, attempt.getId());
        }

        boolean tokenGone = exception.statusCode() == 404 && !capturePhase;
        if (tokenGone && enrollment != null) {
            enrollment.disable();
            enrollmentRepository.save(enrollment);
        }

        String errorCode = tokenGone ? "ENROLLMENT_REQUIRED"
                : "INSTRUMENT_DECLINED".equals(exception.errorName()) ? "INSTRUMENT_DECLINED"
                : "PAYMENT_FAILED";

        return fail(attempt, principalId, amountCents, errorCode,
                exception.getMessage());
    }

    private PaymentResult fail(PaymentAttemptEntity attempt, String principalId,
                               long amountCents, String errorCode, String message) {
        attempt.setStatus("FAILED");
        attempt.setErrorCode(errorCode);
        attemptRepository.save(attempt);
        principalRepository.releaseBudget(principalId, amountCents);

        String responseCode = "ENROLLMENT_REQUIRED".equals(errorCode)
                ? "ENROLLMENT_REQUIRED"
                : "INSTRUMENT_DECLINED".equals(errorCode) ? "INSTRUMENT_DECLINED" : "PAYMENT_FAILED";
        throw new X402Exception(responseCode, message == null ? "Payment failed" : message,
                null, null, attempt.getId());
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            return null;
        }
    }
}

package io.x402.paypal.premium;

import java.util.Map;
import java.util.Optional;

import io.x402.paypal.agent.AgentAuthFilter;
import io.x402.paypal.config.X402Properties;
import io.x402.paypal.enrollment.EnrollmentEntity;
import io.x402.paypal.enrollment.EnrollmentService;
import io.x402.paypal.payment.ChallengeEntity;
import io.x402.paypal.payments.PaymentResult;
import io.x402.paypal.payments.PaymentService;
import io.x402.paypal.x402.ChallengeService;
import io.x402.paypal.x402.PaymentPayloadCodec;
import io.x402.paypal.x402.PaymentRequiredFactory;
import io.x402.paypal.x402.X402Exception;
import io.x402.paypal.x402.X402ReceiptFactory;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/premium")
public class PremiumController {

    public static final String PAYMENT_SIGNATURE_HEADER = "PAYMENT-SIGNATURE";
    public static final String PAYMENT_REQUIRED_HEADER = "PAYMENT-REQUIRED";
    public static final String PAYMENT_RESPONSE_HEADER = "PAYMENT-RESPONSE";

    private final PaymentRequiredFactory paymentRequiredFactory;
    private final PaymentPayloadCodec codec;
    private final ChallengeService challengeService;
    private final EnrollmentService enrollmentService;
    private final PaymentService paymentService;
    private final PremiumContentService contentService;
    private final X402ReceiptFactory receiptFactory;
    private final X402Properties properties;

    public PremiumController(PaymentRequiredFactory paymentRequiredFactory, PaymentPayloadCodec codec,
                             ChallengeService challengeService, EnrollmentService enrollmentService,
                             PaymentService paymentService, PremiumContentService contentService,
                             X402ReceiptFactory receiptFactory, X402Properties properties) {
        this.paymentRequiredFactory = paymentRequiredFactory;
        this.codec = codec;
        this.challengeService = challengeService;
        this.enrollmentService = enrollmentService;
        this.paymentService = paymentService;
        this.contentService = contentService;
        this.receiptFactory = receiptFactory;
        this.properties = properties;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok", "service", "premium-content");
    }

    @GetMapping("/quote")
    public ResponseEntity<String> quote(HttpServletRequest request) {
        return paid(request, "/v1/premium/quote", "Premium market quote", contentService::quote);
    }

    @GetMapping("/report")
    public ResponseEntity<String> report(HttpServletRequest request) {
        return paid(request, "/v1/premium/report", "Premium market report", contentService::report);
    }

    private ResponseEntity<String> paid(HttpServletRequest request, String path, String description,
                                        java.util.function.Supplier<PremiumContent> contentSupplier) {
        String principalId = (String) request.getAttribute(AgentAuthFilter.PRINCIPAL_ID_ATTRIBUTE);
        String resource = request.getRequestURL().toString();
        long priceCents = price("GET", path);
        String asset = properties.asset();
        EnrollmentEntity enrollment = enrollmentService.activeEnrollment(principalId);

        String signature = request.getHeader(PAYMENT_SIGNATURE_HEADER);
        if (signature == null || signature.isBlank()) {
            throw paymentRequiredFactory.requirePayment(
                    principalId, request, "GET", priceCents, asset, description, "Payment required", enrollment);
        }

        PaymentPayloadCodec.PaymentPayload payload;
        try {
            payload = codec.decode(signature);
        } catch (IllegalArgumentException exception) {
            throw withRequirement(new X402Exception("INVALID_PAYLOAD", exception.getMessage(),
                    null, null, null), principalId, request, priceCents, asset, description, enrollment);
        }

        if (payload.payload() != null) {
            Optional<PaymentResult> replay = paymentService.replay(principalId, payload.payload().challengeId());
            if (replay.isPresent()) {
                return response(replay.get(), principalId, true);
            }
        }

        ChallengeEntity challenge;
        try {
            challenge = challengeService.verify(payload, principalId, resource, "GET", priceCents, asset);
        } catch (X402Exception exception) {
            throw withRequirement(exception, principalId, request, priceCents, asset, description, enrollment);
        }

        PremiumContent content = contentSupplier.get();
        PaymentResult result;
        try {
            result = paymentService.settle(principalId, challenge, enrollment, description, content);
        } catch (X402Exception exception) {
            if ("PAYER_ACTION_REQUIRED".equals(exception.code())) {
                throw exception;
            }
            throw withRequirement(exception, principalId, request, priceCents, asset, description, enrollment);
        }

        return response(result, principalId, false);
    }

    private ResponseEntity<String> response(PaymentResult result, String principalId, boolean replay) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .header(PAYMENT_RESPONSE_HEADER, receiptFactory.header(result, principalId));
        if (replay) {
            builder.header("X-X402-Replay", "true");
        }
        MediaType contentType = result.contentType() == null
                ? MediaType.APPLICATION_JSON
                : MediaType.parseMediaType(result.contentType());
        return builder.contentType(contentType).body(result.contentJson());
    }

    private X402Exception withRequirement(X402Exception exception, String principalId,
                                          HttpServletRequest request, long priceCents, String asset,
                                          String description, EnrollmentEntity enrollment) {
        if (exception.paymentRequiredHeader() != null || "PAYER_ACTION_REQUIRED".equals(exception.code())) {
            return exception;
        }
        var requirement = paymentRequiredFactory.build(
                principalId, request, "GET", priceCents, asset, description, exception.getMessage(), enrollment);
        return new X402Exception(exception.code(), exception.getMessage(),
                paymentRequiredFactory.encode(requirement), exception.payerActionUrl(), exception.attemptId());
    }

    private long price(String method, String path) {
        String key = method + " " + path;
        Long value = properties.routes().get(key);
        if (value == null) {
            value = properties.routes().entrySet().stream()
                    .filter(entry -> entry.getKey()
                            .replace("[", "")
                            .replace("]", "")
                            .trim()
                            .equals(key))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);
        }
        if (value == null) {
            throw new IllegalStateException("No x402 price configured for " + key);
        }
        return value;
    }
}

package io.x402.paypal.webhook;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.x402.paypal.agent.PrincipalRepository;
import io.x402.paypal.config.PayPalProperties;
import io.x402.paypal.enrollment.EnrollmentEntity;
import io.x402.paypal.enrollment.EnrollmentRepository;
import io.x402.paypal.paypal.PayPalApi;
import io.x402.paypal.payment.PaymentAttemptEntity;
import io.x402.paypal.payment.PaymentAttemptRepository;
import io.x402.paypal.security.CryptoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    private final PayPalApi payPalApi;
    private final PayPalProperties payPalProperties;
    private final WebhookEventRepository eventRepository;
    private final PaymentAttemptRepository attemptRepository;
    private final PrincipalRepository principalRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final CryptoService cryptoService;
    private final ObjectMapper objectMapper;

    public WebhookService(PayPalApi payPalApi, PayPalProperties payPalProperties,
                          WebhookEventRepository eventRepository, PaymentAttemptRepository attemptRepository,
                          PrincipalRepository principalRepository, EnrollmentRepository enrollmentRepository,
                          CryptoService cryptoService, ObjectMapper objectMapper) {
        this.payPalApi = payPalApi;
        this.payPalProperties = payPalProperties;
        this.eventRepository = eventRepository;
        this.attemptRepository = attemptRepository;
        this.principalRepository = principalRepository;
        this.enrollmentRepository = enrollmentRepository;
        this.cryptoService = cryptoService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void process(String body, WebhookHeaders headers) {
        JsonNode event;
        try {
            event = objectMapper.readTree(body);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Webhook body is not valid JSON", exception);
        }

        String eventId = event.path("id").asText(null);
        String type = event.path("event_type").asText("UNKNOWN");
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("Webhook event id is missing");
        }

        if (eventRepository.existsById(eventId)) {
            return;
        }

        verify(headers, event);

        WebhookEventEntity record = new WebhookEventEntity(
                eventId, type, body, LocalDateTime.now(Clock.systemUTC()));
        eventRepository.save(record);

        try {
            handle(type, event);
            record.processed();
            eventRepository.save(record);
        } catch (RuntimeException exception) {
            log.error("Webhook {} ({}) failed", eventId, type, exception);
            throw exception;
        }
    }

    private void verify(WebhookHeaders headers, JsonNode event) {
        if (payPalProperties.webhookId() == null || payPalProperties.webhookId().isBlank()) {
            log.warn("PAYPAL_WEBHOOK_ID is empty; webhook signature verification is skipped");
            return;
        }
        if (headers.transmissionId() == null || headers.transmissionSig() == null
                || headers.transmissionTime() == null || headers.certUrl() == null
                || headers.authAlgo() == null) {
            throw new WebhookVerificationException("Webhook signature headers are missing");
        }

        Map<String, Object> body = Map.of(
                "auth_algo", headers.authAlgo(),
                "cert_url", headers.certUrl(),
                "transmission_id", headers.transmissionId(),
                "transmission_sig", headers.transmissionSig(),
                "transmission_time", headers.transmissionTime(),
                "webhook_id", payPalProperties.webhookId(),
                "webhook_event", event
        );

        JsonNode response = payPalApi.post("/v1/notifications/verify-webhook-signature",
                body, JsonNode.class, null);
        if (!"SUCCESS".equals(response.path("verification_status").asText())) {
            throw new WebhookVerificationException("Webhook signature verification failed");
        }
    }

    private void handle(String type, JsonNode event) {
        JsonNode resource = event.path("resource");
        String captureId = resource.path("id").asText(null);
        String orderId = resource.path("supplementary_data").path("related_ids")
                .path("order_id").asText(null);
        String vaultTokenId = resource.path("id").asText(null);

        switch (type) {
            case "PAYMENT.CAPTURE.COMPLETED" -> captureCompleted(captureId, orderId);
            case "PAYMENT.CAPTURE.DENIED" -> captureDenied(captureId, orderId);
            case "PAYMENT.CAPTURE.REFUNDED" -> captureRefunded(captureId, orderId);
            case "VAULT.PAYMENT-TOKEN.DELETED" -> vaultDeleted(vaultTokenId);
            case "VAULT.PAYMENT-TOKEN.CREATED" -> log.info("Vault token created: {}", vaultTokenId);
            default -> log.info("Unhandled webhook type {}", type);
        }
    }

    private void captureCompleted(String captureId, String orderId) {
        PaymentAttemptEntity attempt = findAttempt(captureId, orderId);
        if (attempt == null || "SETTLED".equals(attempt.getStatus())) {
            return;
        }
        attempt.setCaptureId(captureId);
        attempt.setStatus("SETTLED");
        attemptRepository.save(attempt);
    }

    private void captureDenied(String captureId, String orderId) {
        PaymentAttemptEntity attempt = findAttempt(captureId, orderId);
        if (attempt == null || "FAILED".equals(attempt.getStatus()) || "SETTLED".equals(attempt.getStatus())) {
            return;
        }
        attempt.setStatus("FAILED");
        attempt.setErrorCode("CAPTURE_DENIED");
        attemptRepository.save(attempt);
        principalRepository.releaseBudget(attempt.getPrincipalId(), attempt.getAmountCents());
    }

    private void captureRefunded(String captureId, String orderId) {
        PaymentAttemptEntity attempt = findAttempt(captureId, orderId);
        if (attempt == null) {
            return;
        }
        attempt.setStatus("REFUNDED");
        attemptRepository.save(attempt);
    }

    private void vaultDeleted(String vaultTokenId) {
        if (vaultTokenId == null) {
            return;
        }
        List<EnrollmentEntity> active = enrollmentRepository.findByStatus("ACTIVE");
        for (EnrollmentEntity enrollment : active) {
            if (enrollment.getVaultTokenEnc() == null) {
                continue;
            }
            try {
                if (vaultTokenId.equals(cryptoService.decryptVaultToken(enrollment.getVaultTokenEnc()))) {
                    enrollment.disable();
                    enrollmentRepository.save(enrollment);
                    log.info("Enrollment {} disabled after vault token deletion", enrollment.getId());
                }
            } catch (RuntimeException exception) {
                log.warn("Could not inspect vault token for enrollment {}", enrollment.getId());
            }
        }
    }

    private PaymentAttemptEntity findAttempt(String captureId, String orderId) {
        if (captureId != null) {
            var byCapture = attemptRepository.findByCaptureId(captureId);
            if (byCapture.isPresent()) {
                return byCapture.get();
            }
        }
        if (orderId != null) {
            return attemptRepository.findByOrderId(orderId).orElse(null);
        }
        return null;
    }

    public record WebhookHeaders(String authAlgo, String certUrl, String transmissionId,
                                 String transmissionSig, String transmissionTime) {
    }
}

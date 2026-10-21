package io.x402.paypal.enrollment;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import io.x402.paypal.config.AppProperties;
import io.x402.paypal.config.PayPalProperties;
import io.x402.paypal.paypal.PayPalApiException;
import io.x402.paypal.paypal.PaymentTokenResponse;
import io.x402.paypal.paypal.SetupTokenResponse;
import io.x402.paypal.paypal.VaultClient;
import io.x402.paypal.security.CryptoService;
import io.x402.paypal.security.Ids;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EnrollmentService {

    private final EnrollmentRepository enrollmentRepository;
    private final VaultClient vaultClient;
    private final CryptoService cryptoService;
    private final AppProperties appProperties;
    private final PayPalProperties payPalProperties;

    public EnrollmentService(EnrollmentRepository enrollmentRepository, VaultClient vaultClient,
                             CryptoService cryptoService, AppProperties appProperties,
                             PayPalProperties payPalProperties) {
        this.enrollmentRepository = enrollmentRepository;
        this.vaultClient = vaultClient;
        this.cryptoService = cryptoService;
        this.appProperties = appProperties;
        this.payPalProperties = payPalProperties;
    }

    @Transactional
    public EnrollmentCreated create(String principalId, String sourceType) {
        String normalizedSource = normalizeSource(sourceType);
        String enrollmentId = Ids.id("enr_");
        String customerId = Ids.payPalCustomerId();
        LocalDateTime now = LocalDateTime.now(Clock.systemUTC());
        String linkToken = cryptoService.issueEnrollmentToken(enrollmentId, Instant.now().plus(1, ChronoUnit.HOURS));
        String enrollmentUrl = appProperties.baseUrl() + "/enroll/" + linkToken;
        String returnUrl = enrollmentUrl + "/return";
        String cancelUrl = enrollmentUrl + "/cancel";

        if ("CARD".equals(normalizedSource)) {
            SetupTokenResponse setup = vaultClient.createCardSetupToken(
                    customerId, appProperties.brandName(), returnUrl, cancelUrl);

            EnrollmentEntity enrollment = new EnrollmentEntity(
                    enrollmentId, principalId, customerId, normalizedSource, "PENDING",
                    null, setup.id(), null, now);
            enrollmentRepository.save(enrollment);

            return new EnrollmentCreated(enrollmentId, "PENDING", null, enrollmentUrl);
        }

        SetupTokenResponse setup = vaultClient.createWalletSetupToken(
                customerId, "Pay per request for " + appProperties.brandName(),
                appProperties.brandName(), returnUrl, cancelUrl);
        String approveUrl = setup.link("approve") != null ? setup.link("approve") : setup.link("payer-action");

        EnrollmentEntity enrollment = new EnrollmentEntity(
                enrollmentId, principalId, customerId, normalizedSource, "PENDING",
                null, setup.id(), approveUrl, now);
        enrollmentRepository.save(enrollment);

        return new EnrollmentCreated(enrollmentId, "PENDING", approveUrl, enrollmentUrl);
    }

    @Transactional
    public EnrollmentView exchange(String principalId, String enrollmentId, String vaultSetupToken) {
        EnrollmentEntity enrollment = requireOwned(principalId, enrollmentId);
        return exchangeInternal(enrollment, vaultSetupToken);
    }

    @Transactional
    public EnrollmentView exchangeByLink(String linkToken, String vaultSetupToken) {
        String enrollmentId = cryptoService.verifyEnrollmentToken(linkToken);
        EnrollmentEntity enrollment = enrollmentRepository.findById(enrollmentId)
                .orElseThrow(() -> new IllegalArgumentException("Enrollment not found"));
        return exchangeInternal(enrollment, vaultSetupToken);
    }

    @Transactional
    public EnrollmentView exchangeByLinkIfApproved(String linkToken) {
        String enrollmentId = cryptoService.verifyEnrollmentToken(linkToken);
        EnrollmentEntity enrollment = enrollmentRepository.findById(enrollmentId)
                .orElseThrow(() -> new IllegalArgumentException("Enrollment not found"));
        if ("ACTIVE".equals(enrollment.getStatus())) {
            return view(enrollment);
        }
        SetupTokenResponse setup = vaultClient.getSetupToken(enrollment.getSetupTokenId());
        if (!isSetupApproved(setup.status())) {
            throw new EnrollmentPendingException("Enrollment is not approved yet", enrollment.getApproveUrl());
        }
        return exchangeInternal(enrollment, null);
    }

    @Transactional(readOnly = true)
    public List<EnrollmentView> list(String principalId) {
        return enrollmentRepository.findByPrincipalIdOrderByCreatedAtDesc(principalId).stream()
                .map(this::view)
                .toList();
    }

    @Transactional(readOnly = true)
    public EnrollmentView get(String principalId, String enrollmentId) {
        return view(requireOwned(principalId, enrollmentId));
    }

    @Transactional(readOnly = true)
    public EnrollmentEntity activeEnrollment(String principalId) {
        return enrollmentRepository
                .findFirstByPrincipalIdAndStatusOrderByCreatedAtDesc(principalId, "ACTIVE")
                .orElse(null);
    }

    @Transactional
    public void revoke(String principalId, String enrollmentId) {
        EnrollmentEntity enrollment = requireOwned(principalId, enrollmentId);
        if ("REVOKED".equals(enrollment.getStatus())) {
            return;
        }
        if (enrollment.getVaultTokenEnc() != null) {
            try {
                vaultClient.deletePaymentToken(cryptoService.decryptVaultToken(enrollment.getVaultTokenEnc()));
            } catch (PayPalApiException exception) {
                if (exception.statusCode() != 404) {
                    throw exception;
                }
            }
        }
        enrollment.revoke();
        enrollmentRepository.save(enrollment);
    }

    @Transactional(readOnly = true)
    public CardSetupConfig cardSetupConfig(String linkToken) {
        String enrollmentId = cryptoService.verifyEnrollmentToken(linkToken);
        EnrollmentEntity enrollment = enrollmentRepository.findById(enrollmentId)
                .orElseThrow(() -> new IllegalArgumentException("Enrollment not found"));
        if (!"CARD".equals(enrollment.getSourceType()) || enrollment.getSetupTokenId() == null) {
            throw new IllegalArgumentException("This enrollment is not a pending card setup");
        }
        return new CardSetupConfig(
                enrollmentId,
                enrollment.getSetupTokenId(),
                payPalProperties.clientId(),
                payPalProperties.env(),
                appProperties.brandName()
        );
    }

    private EnrollmentView exchangeInternal(EnrollmentEntity enrollment, String vaultSetupToken) {
        if ("ACTIVE".equals(enrollment.getStatus())) {
            return view(enrollment);
        }
        if ("REVOKED".equals(enrollment.getStatus())) {
            throw new IllegalStateException("Enrollment is revoked");
        }

        String setupTokenId = vaultSetupToken == null || vaultSetupToken.isBlank()
                ? enrollment.getSetupTokenId()
                : vaultSetupToken;
        if (setupTokenId == null || setupTokenId.isBlank()) {
            throw new IllegalStateException("No setup token is available for this enrollment");
        }

        SetupTokenResponse setup = vaultClient.getSetupToken(setupTokenId);
        if (!isSetupApproved(setup.status())) {
            throw new EnrollmentPendingException("Enrollment is not approved yet", enrollment.getApproveUrl());
        }

        PaymentTokenResponse paymentToken = vaultClient.exchangeSetupToken(setupTokenId);
        if (paymentToken == null || paymentToken.id() == null || paymentToken.id().isBlank()) {
            throw new IllegalStateException("PayPal did not return a payment token");
        }

        String brand = null;
        String last4 = null;
        if (paymentToken.paymentSource() != null && paymentToken.paymentSource().card() != null) {
            brand = paymentToken.paymentSource().card().brand();
            last4 = paymentToken.paymentSource().card().last4();
        } else if (paymentToken.paymentSource() != null && paymentToken.paymentSource().paypal() != null) {
            brand = "PAYPAL";
            last4 = "";
        }

        enrollment.activate(cryptoService.encryptVaultToken(paymentToken.id()), brand, last4);
        enrollmentRepository.save(enrollment);
        return view(enrollment);
    }

    private static boolean isSetupApproved(String status) {
        return "APPROVED".equals(status) || "VAULTED".equals(status);
    }

    private EnrollmentEntity requireOwned(String principalId, String enrollmentId) {
        return enrollmentRepository.findByIdAndPrincipalId(enrollmentId, principalId)
                .orElseThrow(() -> new IllegalArgumentException("Enrollment not found"));
    }

    private EnrollmentView view(EnrollmentEntity enrollment) {
        return new EnrollmentView(
                enrollment.getId(),
                enrollment.getSourceType(),
                enrollment.getStatus(),
                enrollment.getBrand(),
                enrollment.getLast4(),
                enrollment.getApproveUrl(),
                enrollment.getCreatedAt().toString()
        );
    }

    private String normalizeSource(String sourceType) {
        if (sourceType == null) {
            throw new IllegalArgumentException("sourceType is required");
        }
        String value = sourceType.trim().toUpperCase();
        if (!value.equals("CARD") && !value.equals("PAYPAL_WALLET")) {
            throw new IllegalArgumentException("sourceType must be CARD or PAYPAL_WALLET");
        }
        return value;
    }

    public record EnrollmentCreated(String enrollmentId, String status, String approveUrl, String enrollmentUrl) {
    }

    public record EnrollmentView(String enrollmentId, String sourceType, String status, String brand,
                                 String last4, String approveUrl, String createdAt) {
    }

    public record CardSetupConfig(String enrollmentId, String setupTokenId, String clientId,
                                  String environment, String brandName) {
    }
}

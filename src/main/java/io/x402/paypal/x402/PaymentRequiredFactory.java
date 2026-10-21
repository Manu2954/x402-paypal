package io.x402.paypal.x402;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.x402.paypal.config.AppProperties;
import io.x402.paypal.config.X402Properties;
import io.x402.paypal.enrollment.EnrollmentEntity;
import io.x402.paypal.payment.ChallengeEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

@Component
public class PaymentRequiredFactory {

    private final ChallengeService challengeService;
    private final PaymentPayloadCodec codec;
    private final X402Properties properties;
    private final AppProperties appProperties;

    public PaymentRequiredFactory(ChallengeService challengeService, PaymentPayloadCodec codec,
                                  X402Properties properties, AppProperties appProperties) {
        this.challengeService = challengeService;
        this.codec = codec;
        this.properties = properties;
        this.appProperties = appProperties;
    }

    public X402Exception requirePayment(String principalId, HttpServletRequest request,
                                        String method, long amountCents, String asset,
                                        String description, String error,
                                        EnrollmentEntity enrollment) {
        PaymentRequiredPayload payload = build(principalId, request, method, amountCents, asset,
                description, error, enrollment);
        return X402Exception.paymentRequired("PAYMENT_SIGNATURE_REQUIRED", "Payment required", codec.encode(payload));
    }

    public String encode(PaymentRequiredPayload payload) {
        return codec.encode(payload);
    }

    public PaymentRequiredPayload build(String principalId, HttpServletRequest request,
                                        String method, long amountCents, String asset,
                                        String description, String error,
                                        EnrollmentEntity enrollment) {
        int timeoutSeconds = (int) (properties.challengeTtl() == null
                ? Duration.ofSeconds(120).toSeconds()
                : properties.challengeTtl().toSeconds());

        PaymentRequiredPayload.Resource resource = new PaymentRequiredPayload.Resource(
                request.getRequestURL().toString(),
                description,
                "application/json"
        );

        List<PaymentRequiredPayload.Accept> accepts = new ArrayList<>();
        if (enrollment != null && "ACTIVE".equals(enrollment.getStatus())) {
            ChallengeEntity challenge = challengeService.issue(
                    principalId, resource.url(), method, amountCents, asset);

            accepts.add(accept(
                    amountCents, asset, timeoutSeconds,
                    extra(amountCents, challenge.getId(), challenge.getNonce(),
                            enrollment.getSourceType(), true, null)
            ));
        } else {
            accepts.add(accept(amountCents, asset, timeoutSeconds,
                    extra(amountCents, null, null, "PAYPAL_WALLET", false,
                            appProperties.baseUrl() + "/v1/enrollments")));
            accepts.add(accept(amountCents, asset, timeoutSeconds,
                    extra(amountCents, null, null, "CARD", false,
                            appProperties.baseUrl() + "/v1/enrollments")));
        }

        return new PaymentRequiredPayload(2, error, resource, accepts);
    }

    private PaymentRequiredPayload.Accept accept(long amountCents, String asset, int timeoutSeconds,
                                                 Map<String, Object> extra) {
        return new PaymentRequiredPayload.Accept(
                "exact",
                properties.network(),
                Long.toString(amountCents),
                asset,
                properties.payTo(),
                timeoutSeconds,
                extra
        );
    }

    private Map<String, Object> extra(long amountCents, String challengeId, String nonce,
                                      String sourceType, boolean enrolled, String enrollmentUrl) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("paymentFlow", "upfront");
        extra.put("amountFormatted", BigDecimal.valueOf(amountCents, 2).toPlainString());
        if (challengeId != null) {
            extra.put("challengeId", challengeId);
        }
        if (nonce != null) {
            extra.put("nonce", nonce);
        }
        extra.put("sourceType", sourceType);
        extra.put("enrolled", enrolled);
        if (enrollmentUrl != null) {
            extra.put("enrollmentRequired", true);
            extra.put("enrollmentUrl", enrollmentUrl);
        }
        return extra;
    }
}

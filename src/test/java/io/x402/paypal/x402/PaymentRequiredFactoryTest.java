package io.x402.paypal.x402;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.x402.paypal.config.AppProperties;
import io.x402.paypal.config.X402Properties;
import io.x402.paypal.enrollment.EnrollmentEntity;
import io.x402.paypal.payment.ChallengeEntity;
import io.x402.paypal.payment.ChallengeRepository;
import io.x402.paypal.security.CryptoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

@ExtendWith(MockitoExtension.class)
class PaymentRequiredFactoryTest {

    @Mock
    private ChallengeRepository challengeRepository;

    private PaymentRequiredFactory factory;

    @BeforeEach
    void setUp() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        X402Properties properties = new X402Properties(
                "paypal:sandbox", "USD", "merchant", key, key, Duration.ofSeconds(120), Map.of());
        CryptoService cryptoService = new CryptoService(properties);
        PaymentPayloadCodec codec = new PaymentPayloadCodec(new ObjectMapper());
        ChallengeService challengeService = new ChallengeService(
                challengeRepository, cryptoService, codec, properties);
        factory = new PaymentRequiredFactory(
                challengeService, codec, properties,
                new AppProperties("http://localhost:8080", "Premium API"));
    }

    @Test
    void unenrolledAgentGetsBothSources() {
        var payload = factory.build("agt_1", request(), "GET", 100, "USD",
                "Premium quote", "Payment required", null);

        assertThat(payload.accepts()).hasSize(2);
        assertThat(payload.accepts()).allSatisfy(accept -> {
            assertThat(accept.extra().get("enrolled")).isEqualTo(false);
            assertThat(accept.extra()).doesNotContainKey("challengeId");
        });
        assertThat(payload.accepts()).extracting(accept -> accept.extra().get("sourceType"))
                .containsExactly("PAYPAL_WALLET", "CARD");
    }

    @Test
    void activeEnrollmentIncludesChallengeAndNonce() {
        when(challengeRepository.save(any(ChallengeEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var payload = factory.build("agt_1", request(), "GET", 200, "USD",
                "Premium report", "Payment required", enrollment());

        assertThat(payload.accepts()).hasSize(1);
        Map<String, Object> extra = payload.accepts().getFirst().extra();
        assertThat(extra.get("enrolled")).isEqualTo(true);
        assertThat(extra.get("challengeId")).isNotNull();
        assertThat(extra.get("nonce")).isNotNull();
        assertThat(extra.get("sourceType")).isEqualTo("CARD");
    }

    private MockHttpServletRequest request() {
        return new MockHttpServletRequest("GET", "/v1/premium/quote");
    }

    private EnrollmentEntity enrollment() {
        EnrollmentEntity enrollment = new EnrollmentEntity(
                "enr_1", "agt_1", "cust_1", "CARD", "PENDING", null, null, null,
                LocalDateTime.now(Clock.systemUTC()));
        enrollment.activate("encrypted", "VISA", "1111");
        return enrollment;
    }
}

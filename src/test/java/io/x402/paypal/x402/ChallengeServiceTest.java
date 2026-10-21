package io.x402.paypal.x402;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.x402.paypal.config.X402Properties;
import io.x402.paypal.payment.ChallengeEntity;
import io.x402.paypal.payment.ChallengeRepository;
import io.x402.paypal.security.CryptoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChallengeServiceTest {

    private static final String AGENT_ID = "agt_test";
    private static final String CHALLENGE_ID = "ch_test";
    private static final String NONCE = "nonce-test";
    private static final String RESOURCE = "http://localhost:8080/v1/premium/quote";
    private static final long AMOUNT = 100;

    @Mock
    private ChallengeRepository challengeRepository;

    private CryptoService cryptoService;
    private PaymentPayloadCodec codec;
    private ChallengeService challengeService;

    @BeforeEach
    void setUp() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        X402Properties properties = new X402Properties(
                "paypal:sandbox", "USD", "merchant", key, key, Duration.ofSeconds(120), Map.of());
        cryptoService = new CryptoService(properties);
        codec = new PaymentPayloadCodec(new ObjectMapper());
        challengeService = new ChallengeService(challengeRepository, cryptoService, codec, properties);
    }

    @Test
    void acceptsValidSignature() {
        ChallengeEntity challenge = challenge(LocalDateTime.now(java.time.Clock.systemUTC()).plusMinutes(1), null);
        when(challengeRepository.findById(CHALLENGE_ID)).thenReturn(Optional.of(challenge));

        long timestamp = java.time.Instant.now().getEpochSecond();
        ChallengeEntity verified = challengeService.verify(
                payload(NONCE, timestamp, signature(NONCE, timestamp)),
                AGENT_ID, RESOURCE, "GET", AMOUNT, "USD");

        assertThat(verified).isSameAs(challenge);
    }

    @Test
    void rejectsWrongSignature() {
        ChallengeEntity challenge = challenge(LocalDateTime.now(java.time.Clock.systemUTC()).plusMinutes(1), null);
        when(challengeRepository.findById(CHALLENGE_ID)).thenReturn(Optional.of(challenge));

        long timestamp = java.time.Instant.now().getEpochSecond();
        assertThatThrownBy(() -> challengeService.verify(
                payload(NONCE, timestamp, "wrong"),
                AGENT_ID, RESOURCE, "GET", AMOUNT, "USD"))
                .isInstanceOf(X402Exception.class)
                .extracting("code")
                .isEqualTo("INVALID_SIGNATURE");
    }

    @Test
    void rejectsExpiredChallenge() {
        ChallengeEntity challenge = challenge(LocalDateTime.now(java.time.Clock.systemUTC()).minusSeconds(1), null);
        when(challengeRepository.findById(CHALLENGE_ID)).thenReturn(Optional.of(challenge));

        long timestamp = java.time.Instant.now().getEpochSecond();
        assertThatThrownBy(() -> challengeService.verify(
                payload(NONCE, timestamp, signature(NONCE, timestamp)),
                AGENT_ID, RESOURCE, "GET", AMOUNT, "USD"))
                .isInstanceOf(X402Exception.class)
                .extracting("code")
                .isEqualTo("CHALLENGE_EXPIRED");
    }

    @Test
    void rejectsConsumedChallenge() {
        ChallengeEntity challenge = challenge(LocalDateTime.now(java.time.Clock.systemUTC()).plusMinutes(1), LocalDateTime.now(java.time.Clock.systemUTC()));
        when(challengeRepository.findById(CHALLENGE_ID)).thenReturn(Optional.of(challenge));

        long timestamp = java.time.Instant.now().getEpochSecond();
        assertThatThrownBy(() -> challengeService.verify(
                payload(NONCE, timestamp, signature(NONCE, timestamp)),
                AGENT_ID, RESOURCE, "GET", AMOUNT, "USD"))
                .isInstanceOf(X402Exception.class)
                .extracting("code")
                .isEqualTo("REPLAY_DETECTED");
    }

    @Test
    void rejectsResourceMismatch() {
        ChallengeEntity challenge = challenge(LocalDateTime.now(java.time.Clock.systemUTC()).plusMinutes(1), null);
        when(challengeRepository.findById(CHALLENGE_ID)).thenReturn(Optional.of(challenge));

        long timestamp = java.time.Instant.now().getEpochSecond();
        assertThatThrownBy(() -> challengeService.verify(
                payload(NONCE, timestamp, signature(NONCE, timestamp)),
                AGENT_ID, RESOURCE, "POST", AMOUNT, "USD"))
                .isInstanceOf(X402Exception.class)
                .extracting("code")
                .isEqualTo("PAYMENT_MISMATCH");
    }

    private ChallengeEntity challenge(LocalDateTime expiresAt, LocalDateTime consumedAt) {
        ChallengeEntity challenge = new ChallengeEntity(
                CHALLENGE_ID, AGENT_ID, RESOURCE, "GET", AMOUNT, "USD", NONCE, expiresAt);
        if (consumedAt != null) {
            challenge.consume();
        }
        return challenge;
    }

    private PaymentPayloadCodec.PaymentPayload payload(String nonce, long timestamp, String signature) {
        return new PaymentPayloadCodec.PaymentPayload(2, null,
                new PaymentPayloadCodec.Payload(AGENT_ID, CHALLENGE_ID, nonce, timestamp, signature));
    }

    private String signature(String nonce, long timestamp) {
        String canonical = codec.canonical(
                CHALLENGE_ID, "GET", RESOURCE, Long.toString(AMOUNT), "USD", nonce, timestamp);
        return cryptoService.sign(canonical, cryptoService.agentHmacSecret(AGENT_ID));
    }
}

package io.x402.paypal.x402;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

import io.x402.paypal.config.X402Properties;
import io.x402.paypal.payment.ChallengeEntity;
import io.x402.paypal.payment.ChallengeRepository;
import io.x402.paypal.security.CryptoService;
import io.x402.paypal.security.Ids;
import org.springframework.stereotype.Service;

@Service
public class ChallengeService {

    private static final long MAX_CLOCK_SKEW_SECONDS = 120;

    private final ChallengeRepository challengeRepository;
    private final CryptoService cryptoService;
    private final PaymentPayloadCodec codec;
    private final X402Properties properties;

    public ChallengeService(ChallengeRepository challengeRepository, CryptoService cryptoService,
                            PaymentPayloadCodec codec, X402Properties properties) {
        this.challengeRepository = challengeRepository;
        this.cryptoService = cryptoService;
        this.codec = codec;
        this.properties = properties;
    }

    public ChallengeEntity issue(String principalId, String resource, String method,
                                 long amountCents, String asset) {
        Duration ttl = properties.challengeTtl() == null ? Duration.ofSeconds(120) : properties.challengeTtl();
        ChallengeEntity challenge = new ChallengeEntity(
                Ids.id("ch_"),
                principalId,
                resource,
                method,
                amountCents,
                asset,
                UUID.randomUUID().toString(),
                LocalDateTime.now(Clock.systemUTC()).plus(ttl)
        );
        return challengeRepository.save(challenge);
    }

    public ChallengeEntity verify(PaymentPayloadCodec.PaymentPayload payload, String principalId,
                                  String expectedResource, String expectedMethod,
                                  long expectedAmountCents, String expectedAsset) {
        if (payload == null || payload.payload() == null || payload.x402Version() != 2) {
            throw new X402Exception("INVALID_PAYLOAD", "Payment payload is invalid", null, null, null);
        }
        PaymentPayloadCodec.Payload values = payload.payload();
        if (values.challengeId() == null || values.nonce() == null || values.signature() == null) {
            throw new X402Exception("INVALID_PAYLOAD", "Payment payload is incomplete", null, null, null);
        }

        ChallengeEntity challenge = challengeRepository.findById(values.challengeId())
                .orElseThrow(() -> new X402Exception("INVALID_CHALLENGE", "Challenge not found", null, null, null));

        if (!challenge.getPrincipalId().equals(principalId)) {
            throw new X402Exception("INVALID_CHALLENGE", "Challenge belongs to another agent", null, null, null);
        }
        if (challenge.getConsumedAt() != null) {
            throw new X402Exception("REPLAY_DETECTED", "Challenge was already used", null, null, null);
        }
        if (LocalDateTime.now(Clock.systemUTC()).isAfter(challenge.getExpiresAt())) {
            throw new X402Exception("CHALLENGE_EXPIRED", "Challenge expired", null, null, null);
        }
        if (!challenge.getResource().equals(expectedResource)
                || !challenge.getMethod().equals(expectedMethod)
                || challenge.getAmountCents() != expectedAmountCents
                || !challenge.getAsset().equals(expectedAsset)) {
            throw new X402Exception("PAYMENT_MISMATCH", "Challenge does not match this request", null, null, null);
        }
        if (!challenge.getNonce().equals(values.nonce())) {
            throw new X402Exception("REPLAY_DETECTED", "Nonce does not match the challenge", null, null, null);
        }

        long now = java.time.Instant.now().getEpochSecond();
        if (Math.abs(now - values.timestamp()) > MAX_CLOCK_SKEW_SECONDS) {
            throw new X402Exception("INVALID_SIGNATURE", "Timestamp is outside the allowed window", null, null, null);
        }

        String canonical = codec.canonical(
                challenge.getId(),
                challenge.getMethod(),
                challenge.getResource(),
                Long.toString(challenge.getAmountCents()),
                challenge.getAsset(),
                challenge.getNonce(),
                values.timestamp()
        );
        String agentSecret = cryptoService.agentHmacSecret(principalId);
        if (!cryptoService.verifySignature(canonical, values.signature(), agentSecret)) {
            throw new X402Exception("INVALID_SIGNATURE", "Signature does not match", null, null, null);
        }

        return challenge;
    }

    public boolean consumeIfUnused(ChallengeEntity challenge) {
        return challengeRepository.consumeIfUnused(
                challenge.getId(), LocalDateTime.now(Clock.systemUTC())) == 1;
    }
}

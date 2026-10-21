package io.x402.paypal.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Base64;

import io.x402.paypal.config.X402Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CryptoServiceTest {

    private CryptoService cryptoService;

    @BeforeEach
    void setUp() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        cryptoService = new CryptoService(new X402Properties(
                "paypal:sandbox", "USD", "merchant", key, key, null, null));
    }

    @Test
    void signsAndVerifiesCanonicalValue() {
        String secret = cryptoService.agentHmacSecret("agt_1");
        String signature = cryptoService.sign("challenge\nGET\nurl\n100\nUSD\nnonce\n1", secret);

        assertThat(cryptoService.verifySignature(
                "challenge\nGET\nurl\n100\nUSD\nnonce\n1", signature, secret)).isTrue();
        assertThat(cryptoService.verifySignature(
                "challenge\nGET\nurl\n200\nUSD\nnonce\n1", signature, secret)).isFalse();
    }

    @Test
    void encryptsVaultTokenWithRandomIv() {
        String first = cryptoService.encryptVaultToken("vault-token");
        String second = cryptoService.encryptVaultToken("vault-token");

        assertThat(first).isNotEqualTo(second);
        assertThat(cryptoService.decryptVaultToken(first)).isEqualTo("vault-token");
        assertThat(cryptoService.decryptVaultToken(second)).isEqualTo("vault-token");
    }

    @Test
    void enrollmentTokenRoundTripAndExpiry() {
        String token = cryptoService.issueEnrollmentToken("enr_1", Instant.now().plusSeconds(60));
        assertThat(cryptoService.verifyEnrollmentToken(token)).isEqualTo("enr_1");

        String expired = cryptoService.issueEnrollmentToken("enr_1", Instant.now().minusSeconds(1));
        assertThatThrownBy(() -> cryptoService.verifyEnrollmentToken(expired))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expired");
    }
}

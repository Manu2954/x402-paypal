package io.x402.paypal.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import io.x402.paypal.config.X402Properties;
import org.springframework.stereotype.Service;

@Service
public class CryptoService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String AES_ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_BYTES = 12;

    private final byte[] masterKey;
    private final byte[] vaultKey;
    private final SecureRandom random = new SecureRandom();

    public CryptoService(X402Properties properties) {
        this.masterKey = decodeKey(properties.masterKey(), "x402.master-key");
        this.vaultKey = decodeKey(properties.vaultKey(), "x402.vault-key");
    }

    public String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    public String agentHmacSecret(String agentId) {
        return hmacSha256Base64(masterKey, "agent:" + agentId);
    }

    public String sign(String canonicalValue, String agentHmacSecret) {
        return hmacSha256Base64(agentHmacSecret.getBytes(StandardCharsets.UTF_8), canonicalValue);
    }

    public boolean verifySignature(String canonicalValue, String signature, String agentHmacSecret) {
        if (signature == null || agentHmacSecret == null) {
            return false;
        }
        String expected = sign(canonicalValue, agentHmacSecret);
        return constantTimeEquals(expected, signature);
    }

    public String encryptVaultToken(String plaintext) {
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(vaultKey, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] output = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, output, 0, iv.length);
            System.arraycopy(encrypted, 0, output, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(output);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not encrypt vault token", exception);
        }
    }

    public String decryptVaultToken(String ciphertext) {
        try {
            byte[] input = Base64.getDecoder().decode(ciphertext);
            byte[] iv = new byte[GCM_IV_BYTES];
            byte[] encrypted = new byte[input.length - GCM_IV_BYTES];
            System.arraycopy(input, 0, iv, 0, iv.length);
            System.arraycopy(input, iv.length, encrypted, 0, encrypted.length);

            Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(vaultKey, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not decrypt vault token", exception);
        }
    }

    public String issueEnrollmentToken(String enrollmentId, Instant expiresAt) {
        String payload = enrollmentId + "|" + expiresAt.getEpochSecond();
        String signature = hmacSha256Base64(masterKey, payload);
        String token = payload + "|" + signature;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token.getBytes(StandardCharsets.UTF_8));
    }

    public String verifyEnrollmentToken(String token) {
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|");
            if (parts.length != 3) {
                throw new IllegalArgumentException("Malformed enrollment token");
            }

            String payload = parts[0] + "|" + parts[1];
            String expected = hmacSha256Base64(masterKey, payload);
            if (!constantTimeEquals(expected, parts[2])) {
                throw new IllegalArgumentException("Invalid enrollment token");
            }

            long expiresAt = Long.parseLong(parts[1]);
            if (Instant.now().getEpochSecond() > expiresAt) {
                throw new IllegalArgumentException("Enrollment link expired");
            }
            return parts[0];
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid enrollment token", exception);
        }
    }

    private String hmacSha256Base64(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            byte[] signature = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        } catch (Exception exception) {
            throw new IllegalStateException("HMAC-SHA256 is not available", exception);
        }
    }

    private boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8)
        );
    }

    private byte[] decodeKey(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(propertyName + " is not configured. Generate one with: openssl rand -base64 32");
        }

        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(value.trim());
        } catch (IllegalArgumentException exception) {
            decoded = Base64.getUrlDecoder().decode(value.trim());
        }

        if (decoded.length != 32) {
            throw new IllegalStateException(propertyName + " must decode to 32 bytes");
        }
        return decoded;
    }
}

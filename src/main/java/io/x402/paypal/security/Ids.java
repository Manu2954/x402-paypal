package io.x402.paypal.security;

import java.security.SecureRandom;
import java.util.Base64;

public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    public static String randomToken(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    public static String id(String prefix) {
        return prefix + randomToken(18);
    }

    public static String apiKey() {
        return "x402_" + randomToken(32);
    }

    public static String payPalCustomerId() {
        return randomToken(16);
    }
}

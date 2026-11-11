package io.x402.paypal.demo;

public class DemoRateLimitException extends RuntimeException {

    private final long retryAfterSeconds;

    public DemoRateLimitException(long retryAfterSeconds) {
        super("Too many demo sessions from this address. Try again later.");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}

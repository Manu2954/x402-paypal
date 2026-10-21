package io.x402.paypal.x402;

public class X402Exception extends RuntimeException {

    private final String code;
    private final String paymentRequiredHeader;
    private final String payerActionUrl;
    private final String attemptId;

    public X402Exception(String code, String message, String paymentRequiredHeader,
                         String payerActionUrl, String attemptId) {
        super(message);
        this.code = code;
        this.paymentRequiredHeader = paymentRequiredHeader;
        this.payerActionUrl = payerActionUrl;
        this.attemptId = attemptId;
    }

    public static X402Exception paymentRequired(String code, String message, String header) {
        return new X402Exception(code, message, header, null, null);
    }

    public String code() {
        return code;
    }

    public String paymentRequiredHeader() {
        return paymentRequiredHeader;
    }

    public String payerActionUrl() {
        return payerActionUrl;
    }

    public String attemptId() {
        return attemptId;
    }
}

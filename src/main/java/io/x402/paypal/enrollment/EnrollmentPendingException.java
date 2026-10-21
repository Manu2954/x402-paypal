package io.x402.paypal.enrollment;

public class EnrollmentPendingException extends RuntimeException {

    private final String approveUrl;

    public EnrollmentPendingException(String message, String approveUrl) {
        super(message);
        this.approveUrl = approveUrl;
    }

    public String approveUrl() {
        return approveUrl;
    }
}

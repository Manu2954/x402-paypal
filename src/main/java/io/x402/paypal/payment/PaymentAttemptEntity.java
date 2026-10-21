package io.x402.paypal.payment;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "payment_attempt")
public class PaymentAttemptEntity {

    @Id
    @Column(length = 40)
    private String id;

    @Column(name = "challenge_id", length = 40)
    private String challengeId;

    @Column(name = "principal_id", nullable = false, length = 40)
    private String principalId;

    @Column(name = "enrollment_id", nullable = false, length = 40)
    private String enrollmentId;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @Column(nullable = false, length = 30)
    private String status;

    @Column(name = "order_id", length = 64)
    private String orderId;

    @Column(name = "capture_id", length = 64)
    private String captureId;

    @Column(name = "response_json")
    private String responseJson;

    @Column(name = "content_json")
    private String contentJson;

    @Column(name = "content_type", length = 120)
    private String contentType;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "payer_action_url", length = 2048)
    private String payerActionUrl;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected PaymentAttemptEntity() {
    }

    public PaymentAttemptEntity(String id, String challengeId, String principalId, String enrollmentId,
                                long amountCents, String status, LocalDateTime createdAt) {
        this.id = id;
        this.challengeId = challengeId;
        this.principalId = principalId;
        this.enrollmentId = enrollmentId;
        this.amountCents = amountCents;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getChallengeId() {
        return challengeId;
    }

    public String getPrincipalId() {
        return principalId;
    }

    public String getEnrollmentId() {
        return enrollmentId;
    }

    public long getAmountCents() {
        return amountCents;
    }

    public String getStatus() {
        return status;
    }

    public String getOrderId() {
        return orderId;
    }

    public String getCaptureId() {
        return captureId;
    }

    public String getResponseJson() {
        return responseJson;
    }

    public String getContentJson() {
        return contentJson;
    }

    public String getContentType() {
        return contentType;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getPayerActionUrl() {
        return payerActionUrl;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setStatus(String status) {
        this.status = status;
        this.updatedAt = LocalDateTime.now(java.time.Clock.systemUTC());
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
        this.updatedAt = LocalDateTime.now(java.time.Clock.systemUTC());
    }

    public void setCaptureId(String captureId) {
        this.captureId = captureId;
        this.updatedAt = LocalDateTime.now(java.time.Clock.systemUTC());
    }

    public void setResponseJson(String responseJson) {
        this.responseJson = responseJson;
        this.updatedAt = LocalDateTime.now(java.time.Clock.systemUTC());
    }

    public void setContent(String contentJson, String contentType) {
        this.contentJson = contentJson;
        this.contentType = contentType;
        this.updatedAt = LocalDateTime.now(java.time.Clock.systemUTC());
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
        this.updatedAt = LocalDateTime.now(java.time.Clock.systemUTC());
    }

    public void setPayerActionUrl(String payerActionUrl) {
        this.payerActionUrl = payerActionUrl;
        this.updatedAt = LocalDateTime.now(java.time.Clock.systemUTC());
    }
}

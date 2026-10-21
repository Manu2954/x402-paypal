package io.x402.paypal.enrollment;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "enrollment")
public class EnrollmentEntity {

    @Id
    @Column(length = 40)
    private String id;

    @Column(name = "principal_id", nullable = false, length = 40)
    private String principalId;

    @Column(name = "paypal_customer_id", nullable = false, length = 22)
    private String paypalCustomerId;

    @Column(name = "source_type", nullable = false, length = 20)
    private String sourceType;

    @Column(name = "vault_token_enc", length = 2048)
    private String vaultTokenEnc;

    @Column(length = 40)
    private String brand;

    @Column(length = 4)
    private String last4;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "consent_max_cents")
    private Long consentMaxCents;

    @Column(name = "setup_token_id", length = 64)
    private String setupTokenId;

    @Column(name = "approve_url", length = 2048)
    private String approveUrl;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    protected EnrollmentEntity() {
    }

    public EnrollmentEntity(String id, String principalId, String paypalCustomerId, String sourceType,
                            String status, Long consentMaxCents, String setupTokenId, String approveUrl,
                            LocalDateTime createdAt) {
        this.id = id;
        this.principalId = principalId;
        this.paypalCustomerId = paypalCustomerId;
        this.sourceType = sourceType;
        this.status = status;
        this.consentMaxCents = consentMaxCents;
        this.setupTokenId = setupTokenId;
        this.approveUrl = approveUrl;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getPrincipalId() {
        return principalId;
    }

    public String getPaypalCustomerId() {
        return paypalCustomerId;
    }

    public String getSourceType() {
        return sourceType;
    }

    public String getVaultTokenEnc() {
        return vaultTokenEnc;
    }

    public String getBrand() {
        return brand;
    }

    public String getLast4() {
        return last4;
    }

    public String getStatus() {
        return status;
    }

    public Long getConsentMaxCents() {
        return consentMaxCents;
    }

    public String getSetupTokenId() {
        return setupTokenId;
    }

    public String getApproveUrl() {
        return approveUrl;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getRevokedAt() {
        return revokedAt;
    }

    public void activate(String vaultTokenEnc, String brand, String last4) {
        this.vaultTokenEnc = vaultTokenEnc;
        this.brand = brand;
        this.last4 = last4;
        this.status = "ACTIVE";
        this.setupTokenId = null;
    }

    public void revoke() {
        this.status = "REVOKED";
        this.revokedAt = LocalDateTime.now(java.time.Clock.systemUTC());
    }

    public void disable() {
        this.status = "DISABLED";
        this.revokedAt = LocalDateTime.now(java.time.Clock.systemUTC());
    }
}

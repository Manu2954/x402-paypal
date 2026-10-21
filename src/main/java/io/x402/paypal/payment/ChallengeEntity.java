package io.x402.paypal.payment;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "challenge")
public class ChallengeEntity {

    @Id
    @Column(length = 40)
    private String id;

    @Column(name = "principal_id", nullable = false, length = 40)
    private String principalId;

    @Column(nullable = false, length = 512)
    private String resource;

    @Column(nullable = false, length = 10)
    private String method;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @Column(nullable = false, length = 10)
    private String asset;

    @Column(nullable = false, length = 64)
    private String nonce;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;

    protected ChallengeEntity() {
    }

    public ChallengeEntity(String id, String principalId, String resource, String method, long amountCents,
                           String asset, String nonce, LocalDateTime expiresAt) {
        this.id = id;
        this.principalId = principalId;
        this.resource = resource;
        this.method = method;
        this.amountCents = amountCents;
        this.asset = asset;
        this.nonce = nonce;
        this.expiresAt = expiresAt;
    }

    public String getId() {
        return id;
    }

    public String getPrincipalId() {
        return principalId;
    }

    public String getResource() {
        return resource;
    }

    public String getMethod() {
        return method;
    }

    public long getAmountCents() {
        return amountCents;
    }

    public String getAsset() {
        return asset;
    }

    public String getNonce() {
        return nonce;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public LocalDateTime getConsumedAt() {
        return consumedAt;
    }

    public void consume() {
        this.consumedAt = LocalDateTime.now(java.time.Clock.systemUTC());
    }
}

package io.x402.paypal.agent;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "principal")
public class PrincipalEntity {

    @Id
    @Column(length = 40)
    private String id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(name = "api_key_hash", nullable = false, length = 255)
    private String apiKeyHash;

    @Column(name = "budget_cents", nullable = false)
    private long budgetCents;

    @Column(name = "spent_cents", nullable = false)
    private long spentCents;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected PrincipalEntity() {
    }

    public PrincipalEntity(String id, String name, String apiKeyHash, long budgetCents, long spentCents,
                           LocalDate periodStart, String status, LocalDateTime createdAt) {
        this.id = id;
        this.name = name;
        this.apiKeyHash = apiKeyHash;
        this.budgetCents = budgetCents;
        this.spentCents = spentCents;
        this.periodStart = periodStart;
        this.status = status;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getApiKeyHash() {
        return apiKeyHash;
    }

    public long getBudgetCents() {
        return budgetCents;
    }

    public long getSpentCents() {
        return spentCents;
    }

    public LocalDate getPeriodStart() {
        return periodStart;
    }

    public String getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setSpentCents(long spentCents) {
        this.spentCents = spentCents;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}

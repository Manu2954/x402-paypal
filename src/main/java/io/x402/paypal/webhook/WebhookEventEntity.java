package io.x402.paypal.webhook;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "webhook_event")
public class WebhookEventEntity {

    @Id
    @Column(name = "event_id", length = 128)
    private String eventId;

    @Column(nullable = false, length = 120)
    private String type;

    @Column(nullable = false, columnDefinition = "CLOB")
    private String payload;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    protected WebhookEventEntity() {
    }

    public WebhookEventEntity(String eventId, String type, String payload, LocalDateTime receivedAt) {
        this.eventId = eventId;
        this.type = type;
        this.payload = payload;
        this.receivedAt = receivedAt;
    }

    public String getEventId() {
        return eventId;
    }

    public String getType() {
        return type;
    }

    public String getPayload() {
        return payload;
    }

    public LocalDateTime getReceivedAt() {
        return receivedAt;
    }

    public LocalDateTime getProcessedAt() {
        return processedAt;
    }

    public void processed() {
        this.processedAt = LocalDateTime.now(java.time.Clock.systemUTC());
    }
}

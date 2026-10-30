package io.github.fullstacknick.stockreservation.reservation.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "reservation")
public class Reservation {
    public enum Status { ACTIVE, RELEASED }

    @Id private UUID id;
    @Column(name = "external_request_id", nullable = false, length = 64, updatable = false)
    private String externalRequestId;
    @Column(nullable = false, length = 64, updatable = false) private String sku;
    @Column(nullable = false, updatable = false) private int quantity;
    @Column(name = "required_on", nullable = false, updatable = false) private LocalDate requiredOn;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8) private Status status;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "released_at") private Instant releasedAt;

    protected Reservation() {}

    public UUID getId() { return id; }
    public String getExternalRequestId() { return externalRequestId; }
    public String getSku() { return sku; }
    public int getQuantity() { return quantity; }
    public LocalDate getRequiredOn() { return requiredOn; }
    public Status getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getReleasedAt() { return releasedAt; }

    public boolean matches(String requestedSku, int requestedQuantity, LocalDate requestedOn) {
        return sku.equals(requestedSku) && quantity == requestedQuantity && requiredOn.equals(requestedOn);
    }

    public void release(Instant now) {
        if (status != Status.ACTIVE) throw new IllegalStateException("Reservation is not active");
        status = Status.RELEASED;
        releasedAt = now;
    }
}

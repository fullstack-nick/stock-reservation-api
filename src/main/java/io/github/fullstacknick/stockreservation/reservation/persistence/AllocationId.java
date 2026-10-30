package io.github.fullstacknick.stockreservation.reservation.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class AllocationId implements Serializable {
    @Column(name = "reservation_id") private UUID reservationId;
    @Column(name = "batch_id") private Long batchId;

    protected AllocationId() {}
    public AllocationId(UUID reservationId, Long batchId) {
        this.reservationId = reservationId;
        this.batchId = batchId;
    }

    @Override public boolean equals(Object other) {
        return this == other || other instanceof AllocationId id
                && Objects.equals(reservationId, id.reservationId) && Objects.equals(batchId, id.batchId);
    }
    @Override public int hashCode() { return Objects.hash(reservationId, batchId); }
}

package io.github.fullstacknick.stockreservation.reservation.persistence;

import io.github.fullstacknick.stockreservation.stock.persistence.StockBatch;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

@Entity
@Table(name = "reservation_allocation")
public class ReservationAllocation {
    @EmbeddedId private AllocationId id;
    @MapsId("reservationId") @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id") private Reservation reservation;
    @MapsId("batchId") @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "batch_id") private StockBatch batch;
    @Column(nullable = false, updatable = false) private int quantity;

    protected ReservationAllocation() {}
    public ReservationAllocation(Reservation reservation, StockBatch batch, int quantity) {
        this.id = new AllocationId(reservation.getId(), batch.getId());
        this.reservation = reservation;
        this.batch = batch;
        this.quantity = quantity;
    }

    public StockBatch getBatch() { return batch; }
    public int getQuantity() { return quantity; }
}

package io.github.fullstacknick.stockreservation.stock.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

@Entity
@Table(name = "stock_batch")
public class StockBatch {
    @Id private Long id;
    @Column(nullable = false, length = 64, updatable = false) private String sku;
    @Column(name = "last_usable_on", nullable = false, updatable = false) private LocalDate lastUsableOn;
    @Column(name = "on_hand", nullable = false, updatable = false) private int onHand;
    @Column(nullable = false) private int reserved;

    protected StockBatch() {}

    public Long getId() { return id; }
    public String getSku() { return sku; }
    public LocalDate getLastUsableOn() { return lastUsableOn; }
    public int getOnHand() { return onHand; }
    public int getReserved() { return reserved; }

    public void reserve(int quantity) {
        if (quantity <= 0 || quantity > onHand - reserved) {
            throw new IllegalStateException("Allocation exceeds locked availability");
        }
        reserved += quantity;
    }

    public void release(int quantity) {
        if (quantity <= 0 || quantity > reserved) {
            throw new IllegalStateException("Stored allocation exceeds reserved stock");
        }
        reserved -= quantity;
    }
}

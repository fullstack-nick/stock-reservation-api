package io.github.fullstacknick.stockreservation.stock.persistence;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockBatchRepository extends JpaRepository<StockBatch, Long> {
    // No availability predicate: wait for releases and read the current locked counters.
    @Query(value = """
            SELECT b.* FROM stock_batch b
            WHERE b.sku = :sku AND b.last_usable_on >= :requiredOn
            ORDER BY b.last_usable_on, b.id FOR UPDATE OF b
            """, nativeQuery = true)
    List<StockBatch> lockEligible(@Param("sku") String sku, @Param("requiredOn") LocalDate requiredOn);

    @Query(value = """
            SELECT b.* FROM stock_batch b
            JOIN reservation_allocation a ON a.batch_id = b.id
            WHERE a.reservation_id = :reservationId
            ORDER BY b.last_usable_on, b.id FOR UPDATE OF b
            """, nativeQuery = true)
    List<StockBatch> lockAllocated(@Param("reservationId") UUID reservationId);
}

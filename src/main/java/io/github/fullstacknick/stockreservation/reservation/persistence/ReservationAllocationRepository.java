package io.github.fullstacknick.stockreservation.reservation.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationAllocationRepository extends JpaRepository<ReservationAllocation, AllocationId> {
    interface QuantityByBatch {
        Long getBatchId();
        int getQuantity();
    }

    // This projection must not load batch entities before their FOR UPDATE query.
    @Query("select a.id.batchId as batchId, a.quantity as quantity from ReservationAllocation a where a.id.reservationId = :id")
    List<QuantityByBatch> quantities(@Param("id") UUID id);

    @Query("""
            select a from ReservationAllocation a join fetch a.batch b
            where a.id.reservationId = :id order by b.lastUsableOn, b.id
            """)
    List<ReservationAllocation> findWithBatches(@Param("id") UUID id);
}

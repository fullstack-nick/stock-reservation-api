package io.github.fullstacknick.stockreservation.reservation.api;

import io.github.fullstacknick.stockreservation.reservation.persistence.Reservation;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ReservationResponse(UUID id, String externalRequestId, String sku, int quantity,
        LocalDate requiredOn, Reservation.Status status, Instant createdAt, Instant releasedAt,
        List<AllocationResponse> allocations) {
    public ReservationResponse { allocations = List.copyOf(allocations); }
    public record AllocationResponse(long batchId, LocalDate lastUsableOn, int quantity) {}
}

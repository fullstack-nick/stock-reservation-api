package io.github.fullstacknick.stockreservation.reservation.persistence;

import io.github.fullstacknick.stockreservation.reservation.api.CreateReservationRequest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReservationClaimRepository {
    private final JdbcTemplate jdbc;
    public ReservationClaimRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean claim(UUID id, CreateReservationRequest request, Instant createdAt) {
        // JdbcTemplate joins the service's JPA transaction through the shared datasource.
        return !jdbc.query("""
                INSERT INTO reservation (id, external_request_id, sku, quantity, required_on, status, created_at)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?)
                ON CONFLICT (external_request_id) DO NOTHING RETURNING id
                """, (row, index) -> row.getObject("id", UUID.class), id, request.externalRequestId(),
                request.sku(), request.quantity(), request.requiredOn(), Timestamp.from(createdAt)).isEmpty();
    }
}

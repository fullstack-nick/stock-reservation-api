package io.github.fullstacknick.stockreservation.reservation.application;

import io.github.fullstacknick.stockreservation.reservation.api.CreateReservationRequest;
import io.github.fullstacknick.stockreservation.reservation.api.ReservationResponse;
import io.github.fullstacknick.stockreservation.reservation.api.ReservationResponse.AllocationResponse;
import io.github.fullstacknick.stockreservation.reservation.domain.AllocationPlanner;
import io.github.fullstacknick.stockreservation.reservation.domain.ReservationException;
import io.github.fullstacknick.stockreservation.reservation.domain.ReservationException.Reason;
import io.github.fullstacknick.stockreservation.reservation.persistence.Reservation;
import io.github.fullstacknick.stockreservation.reservation.persistence.ReservationAllocation;
import io.github.fullstacknick.stockreservation.reservation.persistence.ReservationAllocationRepository;
import io.github.fullstacknick.stockreservation.reservation.persistence.ReservationClaimRepository;
import io.github.fullstacknick.stockreservation.reservation.persistence.ReservationRepository;
import io.github.fullstacknick.stockreservation.reservation.persistence.TransactionSettings;
import io.github.fullstacknick.stockreservation.stock.persistence.StockBatch;
import io.github.fullstacknick.stockreservation.stock.persistence.StockBatchRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReservationService {
    public record Creation(ReservationResponse reservation, boolean created) {}

    private final ReservationRepository reservations;
    private final ReservationAllocationRepository allocations;
    private final StockBatchRepository batches;
    private final ReservationClaimRepository claims;
    private final TransactionSettings transactionSettings;
    private final AllocationPlanner planner;
    private final Clock clock;

    public ReservationService(ReservationRepository reservations, ReservationAllocationRepository allocations,
            StockBatchRepository batches, ReservationClaimRepository claims, TransactionSettings transactionSettings,
            AllocationPlanner planner, Clock clock) {
        this.reservations = reservations;
        this.allocations = allocations;
        this.batches = batches;
        this.claims = claims;
        this.transactionSettings = transactionSettings;
        this.planner = planner;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeoutString = "${reservation.transaction-timeout-seconds}")
    public Creation create(CreateReservationRequest request) {
        Instant now = now();
        LocalDate today = LocalDate.ofInstant(now, clock.getZone());
        transactionSettings.apply();
        UUID id = UUID.randomUUID();
        if (!claims.claim(id, request, now)) {
            // Separate SQL statement: a competing commit must become visible before replay/conflict handling.
            Reservation existing = reservations.findByExternalRequestId(request.externalRequestId())
                    .orElseThrow(() -> new IllegalStateException("Committed request claim disappeared"));
            if (!existing.matches(request.sku(), request.quantity(), request.requiredOn())) {
                throw new ReservationException(Reason.REQUEST_ID_CONFLICT,
                        "This external request ID already belongs to different reservation input.");
            }
            return new Creation(response(existing), false);
        }
        if (request.requiredOn().isBefore(today)) {
            throw new ReservationException(Reason.VALIDATION_FAILED,
                    "requiredOn must be today or later in the warehouse time zone.", "requiredOn");
        }
        Reservation reservation = reservations.findById(id).orElseThrow();
        var locked = batches.lockEligible(request.sku(), request.requiredOn());
        var candidates = locked.stream().map(batch -> new AllocationPlanner.Candidate(
                batch.getId(), batch.getLastUsableOn(), batch.getOnHand(), batch.getReserved())).toList();
        var plan = planner.plan(candidates, request.requiredOn(), request.quantity());
        var byId = locked.stream().collect(Collectors.toMap(StockBatch::getId, Function.identity()));
        var rows = plan.stream().map(allocation -> {
            StockBatch batch = byId.get(allocation.batchId());
            batch.reserve(allocation.quantity());
            return new ReservationAllocation(reservation, batch, allocation.quantity());
        }).toList();
        allocations.saveAll(rows);
        allocations.flush();
        return new Creation(response(reservation), true);
    }

    @Transactional(readOnly = true)
    public ReservationResponse get(UUID id) {
        return response(reservations.findById(id).orElseThrow(ReservationService::notFound));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeoutString = "${reservation.transaction-timeout-seconds}")
    public ReservationResponse release(UUID id) {
        transactionSettings.apply();
        Reservation reservation = reservations.lockById(id).orElseThrow(ReservationService::notFound);
        if (reservation.getStatus() == Reservation.Status.RELEASED) return response(reservation);
        var amounts = allocations.quantities(id).stream().collect(Collectors.toMap(
                ReservationAllocationRepository.QuantityByBatch::getBatchId,
                ReservationAllocationRepository.QuantityByBatch::getQuantity));
        var locked = batches.lockAllocated(id);
        for (StockBatch batch : locked) batch.release(amounts.get(batch.getId()));
        reservation.release(now());
        allocations.flush();
        return response(reservation);
    }

    private ReservationResponse response(Reservation reservation) {
        var details = allocations.findWithBatches(reservation.getId()).stream().map(allocation ->
                new AllocationResponse(allocation.getBatch().getId(), allocation.getBatch().getLastUsableOn(),
                        allocation.getQuantity())).toList();
        return new ReservationResponse(reservation.getId(), reservation.getExternalRequestId(), reservation.getSku(),
                reservation.getQuantity(), reservation.getRequiredOn(), reservation.getStatus(),
                reservation.getCreatedAt(), reservation.getReleasedAt(), details);
    }

    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
    private static ReservationException notFound() {
        return new ReservationException(Reason.RESERVATION_NOT_FOUND, "Reservation does not exist.");
    }
}

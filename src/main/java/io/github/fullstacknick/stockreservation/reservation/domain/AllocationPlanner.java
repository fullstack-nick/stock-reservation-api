package io.github.fullstacknick.stockreservation.reservation.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Pure, deterministic FEFO calculation. Persistence callers must supply locked snapshots. */
public class AllocationPlanner {
    public record Candidate(long batchId, LocalDate lastUsableOn, int onHand, int reserved) {
        public int available() { return onHand - reserved; }
    }

    public record Allocation(long batchId, int quantity) {}

    public List<Allocation> plan(List<Candidate> candidates, LocalDate requiredOn, int quantity) {
        if (quantity <= 0) throw new IllegalArgumentException("Quantity must be positive");
        var eligible = candidates.stream()
                .filter(batch -> !batch.lastUsableOn().isBefore(requiredOn))
                .sorted(Comparator.comparing(Candidate::lastUsableOn).thenComparingLong(Candidate::batchId))
                .toList();
        long available = eligible.stream().mapToLong(Candidate::available).sum();
        if (available < quantity) throw ReservationException.insufficient(quantity, available);

        var allocations = new ArrayList<Allocation>();
        int remaining = quantity;
        for (Candidate batch : eligible) {
            int taken = Math.min(remaining, batch.available());
            if (taken > 0) allocations.add(new Allocation(batch.batchId(), taken));
            remaining -= taken;
            if (remaining == 0) break;
        }
        return List.copyOf(allocations);
    }
}

package io.github.fullstacknick.stockreservation.reservation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import io.github.fullstacknick.stockreservation.reservation.domain.AllocationPlanner.Allocation;
import io.github.fullstacknick.stockreservation.reservation.domain.AllocationPlanner.Candidate;

class AllocationPlannerTest {
    private final AllocationPlanner planner = new AllocationPlanner();
    private final LocalDate required = LocalDate.of(2026, 11, 2);

    @Test void allocatesFromOneBatch() {
        assertThat(planner.plan(List.of(batch(1, 3, 10, 2)), required, 4))
                .containsExactly(new Allocation(1, 4));
    }

    @Test void allocatesFefoAcrossBatchesWithoutMutatingCandidates() {
        var candidates = List.of(batch(2, 6, 10, 0), batch(1, 3, 6, 0));
        assertThat(planner.plan(candidates, required, 9)).containsExactly(new Allocation(1, 6), new Allocation(2, 3));
        assertThat(candidates).extracting(Candidate::reserved).containsExactly(0, 0);
    }

    @Test void includesEqualDateAndBreaksTiesById() {
        assertThat(planner.plan(List.of(batch(2, 2, 3, 0), batch(1, 2, 4, 0)), required, 5))
                .containsExactly(new Allocation(1, 4), new Allocation(2, 1));
    }

    @Test void ignoresExpiredAndFullyReservedStock() {
        assertThat(planner.plan(List.of(batch(1, 1, 100, 0), batch(2, 2, 6, 6), batch(3, 3, 5, 0)), required, 4))
                .containsExactly(new Allocation(3, 4));
    }

    @Test void failsWholePlanWhenOnlyEightCasesAreAvailable() {
        assertThatThrownBy(() -> planner.plan(List.of(batch(1, 2, 6, 0), batch(2, 3, 4, 2)), required, 9))
                .isInstanceOf(ReservationException.class).hasMessageContaining("8 eligible cases");
    }

    @Test void emptyInventoryReportsZero() {
        assertThatThrownBy(() -> planner.plan(List.of(), required, 1))
                .isInstanceOf(ReservationException.class).hasMessageContaining("0 eligible cases");
    }

    @Test void sumsAvailabilityBeyondIntegerMaximumWithoutOverflow() {
        assertThat(planner.plan(List.of(batch(1, 2, Integer.MAX_VALUE, 1), batch(2, 3, Integer.MAX_VALUE, 0)),
                required, Integer.MAX_VALUE))
                .containsExactly(new Allocation(1, Integer.MAX_VALUE - 1), new Allocation(2, 1));
    }

    @Test void rejectsNonPositiveQuantities() {
        assertThatThrownBy(() -> planner.plan(List.of(), required, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> planner.plan(List.of(), required, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    private Candidate batch(long id, int day, int onHand, int reserved) {
        return new Candidate(id, LocalDate.of(2026, 11, day), onHand, reserved);
    }
}

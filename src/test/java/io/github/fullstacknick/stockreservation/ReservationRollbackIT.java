package io.github.fullstacknick.stockreservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

import io.github.fullstacknick.stockreservation.reservation.persistence.ReservationAllocationRepository;
import io.github.fullstacknick.stockreservation.support.IntegrationTestSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

class ReservationRollbackIT extends IntegrationTestSupport {
    @MockitoSpyBean ReservationAllocationRepository allocations;
    @PersistenceContext EntityManager entityManager;

    @Test void creationRollsBackClaimCountersAndAllocationsAfterRealFlush() throws Exception {
        var flushed = new AtomicBoolean();
        doAnswer(invocation -> {
            entityManager.flush();
            flushed.set(count("reservation_allocation") == 2 && reserved(101) == 6 && reserved(102) == 3);
            throw new IllegalStateException("Test-only failure after creation flush");
        }).when(allocations).flush();
        var input = request("FLUSH-FAIL", "YOGURT-CASE", 9);
        var failed = send("POST", "/reservations", input);
        assertThat(failed.status()).isEqualTo(500);
        assertThat(failed.body().path("code").asString()).isEqualTo("INTERNAL_ERROR");
        assertThat(failed.body().toString()).doesNotContain("Test-only", "SQLException", "stackTrace");
        assertThat(flushed).isTrue();
        assertThat(count("reservation")).isZero();
        assertThat(count("reservation_allocation")).isZero();
        assertThat(reserved(101)).isZero();
        assertThat(reserved(102)).isZero();
        assertInvariants();
        doAnswer(invocation -> { entityManager.flush(); return null; }).when(allocations).flush();
        assertThat(send("POST", "/reservations", input).status()).isEqualTo(201);
    }

    @Test void releaseRollsBackStatusTimestampAndCountersAfterRealFlush() throws Exception {
        var created = service.create(request("RELEASE-FAIL", "YOGURT-CASE", 9)).reservation();
        var flushed = new AtomicBoolean();
        doAnswer(invocation -> {
            entityManager.flush();
            flushed.set(reserved(101) == 0 && reserved(102) == 0 && "RELEASED".equals(jdbc.queryForObject(
                    "select status from reservation where id = ?", String.class, created.id())));
            throw new IllegalStateException("Test-only failure after release flush");
        }).when(allocations).flush();
        assertThat(send("POST", "/reservations/" + created.id() + "/release", null).status()).isEqualTo(500);
        assertThat(flushed).isTrue();
        assertThat(service.get(created.id())).isEqualTo(created);
        assertThat(reserved(101)).isEqualTo(6);
        assertThat(reserved(102)).isEqualTo(3);
    }

    @Test void corruptedCounterFailsWithoutClampingOrChangingOtherRows() throws Exception {
        var created = service.create(request("CORRUPTED", "YOGURT-CASE", 9)).reservation();
        jdbc.update("update stock_batch set reserved = 5 where id = 101");
        try {
            assertThat(send("POST", "/reservations/" + created.id() + "/release", null).status()).isEqualTo(500);
            assertThat(service.get(created.id())).isEqualTo(created);
            assertThat(reserved(101)).isEqualTo(5);
            assertThat(reserved(102)).isEqualTo(3);
        } finally {
            jdbc.update("update stock_batch set reserved = 6 where id = 101");
        }
    }
}

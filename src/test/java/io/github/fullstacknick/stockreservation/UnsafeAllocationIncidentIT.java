package io.github.fullstacknick.stockreservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import io.github.fullstacknick.stockreservation.stock.persistence.StockBatch;
import io.github.fullstacknick.stockreservation.stock.persistence.StockBatchRepository;
import io.github.fullstacknick.stockreservation.support.IntegrationTestSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** An intentionally unsafe repository replacement, confined to this disposable-database exercise. */
class UnsafeAllocationIncidentIT extends IntegrationTestSupport {
    @MockitoSpyBean StockBatchRepository batches;
    @PersistenceContext EntityManager entityManager;

    @Test void reproducesTwoSuccessfulOrdersAndLostCounterUpdate() throws Exception {
        var bothRead = new CountDownLatch(2);
        doAnswer(invocation -> {
            List<StockBatch> snapshots = entityManager.createNativeQuery("""
                    select b.* from stock_batch b where b.sku = :sku and b.last_usable_on >= :requiredOn
                    order by b.last_usable_on, b.id
                    """, StockBatch.class)
                    .setParameter("sku", invocation.getArgument(0))
                    .setParameter("requiredOn", invocation.getArgument(1)).getResultList();
            bothRead.countDown();
            if (!bothRead.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Unsafe readers did not overlap");
            return snapshots;
        }).when(batches).lockEligible(anyString(), any());
        var workers = Executors.newFixedThreadPool(2);
        try {
            var a = workers.submit(() -> send("POST", "/reservations", request("INCIDENT-A", "APPLE-CASE", 4)));
            var b = workers.submit(() -> send("POST", "/reservations", request("INCIDENT-B", "APPLE-CASE", 4)));
            var first = a.get(15, TimeUnit.SECONDS);
            var second = b.get(15, TimeUnit.SECONDS);
            assertThat(first.status()).isEqualTo(201);
            assertThat(second.status()).isEqualTo(201);
            assertThat(count("reservation")).isEqualTo(2);
            assertThat(reserved(301)).isEqualTo(4);
            assertThat(jdbc.queryForObject("select sum(quantity) from reservation_allocation where batch_id=301", Long.class))
                    .isEqualTo(8L);
            var violations = jdbc.queryForList(Files.readString(Path.of("docs/sql/check-invariants.sql")));
            assertThat(violations).hasSize(1);
            assertThat(violations.getFirst()).containsEntry("violation", "batch_reserved")
                    .containsEntry("expected", 8L).containsEntry("actual", 4L);
            LoggerFactory.getLogger(UnsafeAllocationIncidentIT.class).info(
                    "INCIDENT EVIDENCE: HTTP statuses={},{}; batch=301; onHand=5; reserved=4; activeAllocations=8; violations={}",
                    first.status(), second.status(), violations);
        } finally {
            workers.shutdownNow();
            assertThat(workers.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
            // Restore only this test's disposable database before the inherited accounting check.
            jdbc.execute("TRUNCATE reservation_allocation, reservation");
            jdbc.update("update stock_batch set reserved = 0");
        }
    }
}

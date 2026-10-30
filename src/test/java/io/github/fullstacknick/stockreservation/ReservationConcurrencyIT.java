package io.github.fullstacknick.stockreservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.github.fullstacknick.stockreservation.reservation.api.CreateReservationRequest;
import io.github.fullstacknick.stockreservation.support.IntegrationTestSupport;
import java.sql.Connection;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class ReservationConcurrencyIT extends IntegrationTestSupport {
    @Autowired PlatformTransactionManager transactionManager;

    @Test void competingRequestsCannotReserveEightOfFiveCases() throws Exception {
        var results = blockedPair("select id from stock_batch where id = 301 for update",
                () -> send("POST", "/reservations", request("ORDER-A", "APPLE-CASE", 4)),
                () -> send("POST", "/reservations", request("ORDER-B", "APPLE-CASE", 4)));
        assertThat(results).extracting(Result::status).containsExactlyInAnyOrder(201, 409);
        assertThat(count("reservation")).isEqualTo(1);
        assertThat(count("reservation_allocation")).isEqualTo(1);
        assertThat(reserved(301)).isEqualTo(4);
    }

    @Test void simultaneousCopiesHaveOneIdentityAndOneStockEffect() throws Exception {
        var input = request("DUPLICATE", "YOGURT-CASE", 9);
        var results = blockedPair("select id from stock_batch where id = 101 for update",
                () -> send("POST", "/reservations", input), () -> send("POST", "/reservations", input));
        assertThat(results).extracting(Result::status).containsExactly(201, 200);
        assertThat(results.get(0).id()).isEqualTo(results.get(1).id());
        assertThat(results.get(0).body()).isEqualTo(results.get(1).body());
        assertThat(count("reservation")).isEqualTo(1);
        assertThat(count("reservation_allocation")).isEqualTo(2);
        assertThat(reserved(101)).isEqualTo(6);
        assertThat(reserved(102)).isEqualTo(3);
    }

    @Test void sameIdentityAcrossSkusConflictsOnlyAfterWinnerCommits() throws Exception {
        var results = blockedPair("select id from stock_batch where id = 101 for update",
                () -> send("POST", "/reservations", request("GLOBAL-ID", "YOGURT-CASE", 4)),
                () -> send("POST", "/reservations", request("GLOBAL-ID", "MILK-CASE", 4)));
        assertThat(results).extracting(Result::status).containsExactly(201, 409);
        assertThat(results.get(1).body().path("code").asString()).isEqualTo("REQUEST_ID_CONFLICT");
        assertThat(reserved(101)).isEqualTo(4);
        assertThat(reserved(201)).isZero();
    }

    @Test void concurrentReleasesSubtractExactlyOnce() throws Exception {
        var created = service.create(request("RELEASE-ME", "YOGURT-CASE", 9)).reservation();
        String route = "/reservations/" + created.id() + "/release";
        var results = blockedPair("select id from reservation where id = '" + created.id() + "' for update",
                () -> send("POST", route, null), () -> send("POST", route, null));
        assertThat(results).extracting(Result::status).containsExactly(200, 200);
        assertThat(results.get(0).body()).isEqualTo(results.get(1).body());
        assertThat(reserved(101)).isZero();
        assertThat(reserved(102)).isZero();
    }

    @Test void waitingCreationReconsidersFullyReservedBatchAfterRelease() throws Exception {
        var held = service.create(request("HOLD-ALL", "APPLE-CASE", 5)).reservation();
        var results = blockedPair("select id from stock_batch where id = 301 for update",
                () -> send("POST", "/reservations/" + held.id() + "/release", null),
                () -> send("POST", "/reservations", request("AFTER-RELEASE", "APPLE-CASE", 4)));
        assertThat(results).extracting(Result::status).containsExactly(200, 201);
        assertThat(reserved(301)).isEqualTo(4);
    }

    @Test void overlappingEligibilitySetsKeepAccountingAndLockOrder() throws Exception {
        var late = new CreateReservationRequest("LATE", "YOGURT-CASE", 8, LocalDate.of(2026, 11, 4));
        var results = blockedPair("select id from stock_batch where id in (101,102) order by last_usable_on,id for update",
                () -> send("POST", "/reservations", request("EARLY", "YOGURT-CASE", 10)),
                () -> send("POST", "/reservations", late));
        assertThat(results).extracting(Result::status).containsExactlyInAnyOrder(201, 409);
        assertThat(count("reservation")).isEqualTo(1);
    }

    @Test void independentSkuCompletesWhileAnotherSkuIsLocked() throws Exception {
        var workers = Executors.newFixedThreadPool(1);
        try (Connection blocker = locked("select id from stock_batch where id = 301 for update")) {
            var waiting = workers.submit(() -> send("POST", "/reservations", request("WAIT-APPLE", "APPLE-CASE", 4)));
            awaitBlocked(1);
            assertThat(send("POST", "/reservations", request("FREE-MILK", "MILK-CASE", 1)).status()).isEqualTo(201);
            assertThat(waiting.isDone()).isFalse();
            blocker.commit();
            assertThat(waiting.get(20, TimeUnit.SECONDS).status()).isEqualTo(201);
        } finally {
            workers.shutdownNow();
        }
    }

    @Test void waitingDuplicateCanOwnClaimAfterFirstTransactionRollsBackRealWrites() throws Exception {
        var workers = Executors.newFixedThreadPool(2);
        var flushed = new CountDownLatch(1);
        var failOwner = new CountDownLatch(1);
        var input = request("ROLLBACK-OWNER", "APPLE-CASE", 4);
        try {
            var owner = workers.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                service.create(input); // The actual service flushes its JDBC + JPA writes into this transaction.
                flushed.countDown();
                try {
                    if (!failOwner.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test coordination timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                throw new IllegalStateException("Deliberate owner failure after flush");
            }));
            assertThat(flushed.await(5, TimeUnit.SECONDS)).isTrue();
            var duplicate = workers.submit(() -> send("POST", "/reservations", input));
            awaitBlocked(1);
            failOwner.countDown();
            assertThatThrownBy(() -> owner.get(10, TimeUnit.SECONDS))
                    .hasRootCauseMessage("Deliberate owner failure after flush");
            assertThat(duplicate.get(20, TimeUnit.SECONDS).status()).isEqualTo(201);
            assertThat(count("reservation")).isEqualTo(1);
            assertThat(count("reservation_allocation")).isEqualTo(1);
            assertThat(reserved(301)).isEqualTo(4);
        } finally {
            failOwner.countDown();
            workers.shutdownNow();
        }
    }

    private List<Result> blockedPair(String lockSql, Callable<Result> first, Callable<Result> second) throws Exception {
        var workers = Executors.newFixedThreadPool(2);
        try (Connection blocker = locked(lockSql)) {
            var a = workers.submit(first);
            awaitBlocked(1);
            var b = workers.submit(second);
            awaitBlocked(2);
            blocker.commit();
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        } finally {
            workers.shutdownNow();
        }
    }

    private Connection locked(String sql) throws Exception {
        Connection connection = dataSource.getConnection();
        try {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) { statement.executeQuery(sql).close(); }
            return connection;
        } catch (Exception exception) {
            connection.close();
            throw exception;
        }
    }

    private void awaitBlocked(int count) {
        await().atMost(Duration.ofSeconds(3)).pollInterval(Duration.ofMillis(20)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("""
                        select count(*) from pg_stat_activity
                        where datname = current_database() and cardinality(pg_blocking_pids(pid)) > 0
                        """, Integer.class)).isGreaterThanOrEqualTo(count));
    }
}

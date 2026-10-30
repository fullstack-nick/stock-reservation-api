package io.github.fullstacknick.stockreservation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.fullstacknick.stockreservation.support.IntegrationTestSupport;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "reservation.lock-timeout=150ms")
class ReservationTimeoutIT extends IntegrationTestSupport {
    @Test void heldLockReturnsRetryableProblemAndRollsBackIdentity() throws Exception {
        var input = request("TIMED-OUT", "APPLE-CASE", 4);
        try (var blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.createStatement()) {
                statement.executeQuery("select id from stock_batch where id = 301 for update").close();
            }
            Instant started = Instant.now();
            var result = send("POST", "/reservations", input);
            assertThat(result.status()).isEqualTo(503);
            assertThat(result.body().path("code").asString()).isEqualTo("TEMPORARILY_UNAVAILABLE");
            assertThat(result.headers().firstValue("Retry-After")).contains("1");
            assertThat(Duration.between(started, Instant.now())).isLessThan(Duration.ofSeconds(5));
            assertThat(count("reservation")).isZero();
            assertThat(count("reservation_allocation")).isZero();
            blocker.rollback();
        }
        assertThat(send("POST", "/reservations", input).status()).isEqualTo(201);
    }
}

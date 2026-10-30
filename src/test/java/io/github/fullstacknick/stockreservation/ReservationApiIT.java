package io.github.fullstacknick.stockreservation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.fullstacknick.stockreservation.support.IntegrationTestSupport;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import io.github.fullstacknick.stockreservation.reservation.api.CreateReservationRequest;
import org.junit.jupiter.api.Test;

class ReservationApiIT extends IntegrationTestSupport {
    @Test void inclusiveBoundaryAndTieOrderArePersisted() throws Exception {
        var created = send("POST", "/reservations", request("MILK-1", "MILK-CASE", 5));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().path("allocations").get(0).path("batchId").asLong()).isEqualTo(201);
        assertThat(created.body().path("allocations").get(1).path("batchId").asLong()).isEqualTo(202);
        assertThat(reserved(201)).isEqualTo(4);
        assertThat(reserved(202)).isEqualTo(1);
    }

    @Test void insufficientAndUnknownSkuLeaveNoDurableClaim() throws Exception {
        for (String sku : new String[]{"YOGURT-CASE", "UNKNOWN"}) {
            var result = send("POST", "/reservations", request("FAIL-" + sku, sku, 17));
            assertThat(result.status()).isEqualTo(409);
            assertThat(result.body().path("code").asString()).isEqualTo("INSUFFICIENT_STOCK");
        }
        assertThat(count("reservation")).isZero();
        assertThat(count("reservation_allocation")).isZero();
        assertThat(reserved(101)).isZero();
    }

    @Test void conflictsNeverChangeOriginalInput() throws Exception {
        var original = send("POST", "/reservations", request("SAME-ID", "YOGURT-CASE", 4));
        var changed = new CreateReservationRequest[]{request("SAME-ID", "MILK-CASE", 4),
                request("SAME-ID", "YOGURT-CASE", 5),
                new CreateReservationRequest("SAME-ID", "YOGURT-CASE", 4, LocalDate.of(2026, 11, 3))};
        for (var input : changed) {
            var conflict = send("POST", "/reservations", input);
            assertThat(conflict.status()).isEqualTo(409);
            assertThat(conflict.body().path("code").asString()).isEqualTo("REQUEST_ID_CONFLICT");
        }
        assertThat(send("GET", "/reservations/" + original.id(), null).body()).isEqualTo(original.body());
        assertThat(count("reservation")).isEqualTo(1);
    }

    @Test void failedIdentityCanSucceedAfterReleaseAndBeCorrectedAfterValidationFailure() throws Exception {
        var holder = send("POST", "/reservations", request("HOLDER", "APPLE-CASE", 5));
        var waiting = request("WAITING", "APPLE-CASE", 4);
        assertThat(send("POST", "/reservations", waiting).status()).isEqualTo(409);
        send("POST", "/reservations/" + holder.id() + "/release", null);
        assertThat(send("POST", "/reservations", waiting).status()).isEqualTo(201);
        var past = new CreateReservationRequest("CORRECTABLE", "MILK-CASE", 1, LocalDate.of(2026, 10, 31));
        assertThat(send("POST", "/reservations", past).status()).isEqualTo(400);
        assertThat(send("POST", "/reservations", request("CORRECTABLE", "MILK-CASE", 1)).status()).isEqualTo(201);
    }

    @Test void discardedResponseAndLaterDateReplayHaveOneEffect() throws Exception {
        var input = request("LOST-RESPONSE", "YOGURT-CASE", 2);
        send("POST", "/reservations", input);
        clock.set(Instant.parse("2026-12-01T00:00:00Z"));
        var retried = send("POST", "/reservations", input);
        assertThat(retried.status()).isEqualTo(200);
        assertThat(retried.body().path("status").asString()).isEqualTo("ACTIVE");
        assertThat(count("reservation")).isEqualTo(1);
        assertThat(reserved(101)).isEqualTo(2);
    }

    @Test void unknownReservationAndStandardHttpErrorsHaveStableProblems() throws Exception {
        String path = "/reservations/" + UUID.randomUUID();
        for (String operation : new String[]{"GET", "POST"}) {
            var result = send(operation, path + (operation.equals("POST") ? "/release" : ""), null);
            assertThat(result.status()).isEqualTo(404);
            assertThat(result.body().path("code").asString()).isEqualTo("RESERVATION_NOT_FOUND");
        }
        assertThat(send("DELETE", path, null).status()).isEqualTo(405);
        assertThat(send("GET", "/actuator/health", null).body().path("status").asString()).isEqualTo("UP");
        assertThat(send("GET", "/actuator/env", null).status()).isEqualTo(404);
    }

    @Test void warehouseMidnightAndDstUseConfiguredZone() throws Exception {
        clock.set(Instant.parse("2026-10-31T23:30:00Z")); // November 1 in Berlin.
        assertThat(send("POST", "/reservations", new CreateReservationRequest("MIDNIGHT", "YOGURT-CASE", 1,
                LocalDate.of(2026, 10, 31))).status()).isEqualTo(400);
        clock.set(Instant.parse("2026-03-29T22:30:00Z")); // March 30 after DST transition.
        assertThat(send("POST", "/reservations", new CreateReservationRequest("DST", "YOGURT-CASE", 1,
                LocalDate.of(2026, 3, 29))).status()).isEqualTo(400);
        assertThat(send("POST", "/reservations", new CreateReservationRequest("DST", "YOGURT-CASE", 1,
                LocalDate.of(2026, 3, 30))).status()).isEqualTo(201);
    }

    @Test void timestampsRoundTripAtMicrosecondPrecision() throws Exception {
        clock.set(Instant.parse("2026-11-01T09:00:00.123456789Z"));
        var input = request("PRECISE", "MILK-CASE", 1);
        var created = send("POST", "/reservations", input);
        assertThat(created.body().path("createdAt").asString()).isEqualTo("2026-11-01T09:00:00.123456Z");
        assertThat(send("POST", "/reservations", input).body()).isEqualTo(created.body());
    }
    @Test void reservesReplaysReadsAndReleasesAcrossTwoBatches() throws Exception {
        var input = request("ORDER-1042", "YOGURT-CASE", 9);
        var created = send("POST", "/reservations", input);
        assertThat(created.status()).isEqualTo(201);
        String path = "/reservations/" + created.id();
        assertThat(created.headers().firstValue("Location")).contains(path);
        assertThat(created.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(created.body().path("releasedAt").isNull()).isTrue();
        assertThat(created.body().path("allocations").get(0).path("batchId").asLong()).isEqualTo(101);
        assertThat(created.body().path("allocations").get(0).path("quantity").asInt()).isEqualTo(6);
        assertThat(created.body().path("allocations").get(1).path("quantity").asInt()).isEqualTo(3);
        assertThat(reserved(101)).isEqualTo(6);
        assertThat(reserved(102)).isEqualTo(3);
        assertThat(reserved(103)).isZero();
        assertThat(send("GET", path, null).body()).isEqualTo(created.body());
        var replay = send("POST", "/reservations", input);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body()).isEqualTo(created.body());
        assertThat(count("reservation")).isEqualTo(1);
        clock.set(Instant.parse("2026-11-01T10:00:00Z"));
        var released = send("POST", path + "/release", null);
        assertThat(released.status()).isEqualTo(200);
        assertThat(released.body().path("status").asString()).isEqualTo("RELEASED");
        assertThat(reserved(101)).isZero();
        assertThat(reserved(102)).isZero();
        assertThat(count("reservation_allocation")).isEqualTo(2);
        clock.set(Instant.parse("2026-11-04T10:00:00Z"));
        assertThat(send("POST", path + "/release", null).body()).isEqualTo(released.body());
        assertThat(send("POST", "/reservations", input).body()).isEqualTo(released.body());
    }
}

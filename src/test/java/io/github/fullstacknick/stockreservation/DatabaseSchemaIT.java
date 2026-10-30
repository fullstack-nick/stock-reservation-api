package io.github.fullstacknick.stockreservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.fullstacknick.stockreservation.support.IntegrationTestSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;

class DatabaseSchemaIT extends IntegrationTestSupport {
    @ParameterizedTest
    @ValueSource(strings = {
            "update stock_batch set reserved = -1 where id = 101",
            "update stock_batch set reserved = 7 where id = 101",
            "update stock_batch set on_hand = -1 where id = 101",
            "update stock_batch set sku = 'bad sku' where id = 101",
            "update stock_batch set last_usable_on = date '10000-01-01' where id = 101",
            "insert into stock_batch values (-1, 'VALID', '2026-11-02', 1, 0)",
            "update reservation set quantity = 0",
            "update reservation set status = 'INVALID'",
            "update reservation set status = 'RELEASED'",
            "update reservation set status = 'RELEASED', released_at = created_at - interval '1 second'",
            "update reservation set released_at = created_at",
            "update reservation set external_request_id = ' space'",
            "update reservation set required_on = date '10000-01-01'",
            "insert into reservation select gen_random_uuid(), external_request_id, sku, quantity, required_on, status, created_at, released_at from reservation",
            "insert into reservation_allocation select reservation_id, batch_id, quantity from reservation_allocation",
            "update reservation_allocation set quantity = 0",
            "update reservation_allocation set batch_id = 99999",
            "update reservation_allocation set reservation_id = gen_random_uuid()",
            "delete from stock_batch where id = 101",
            "delete from reservation"
    })
    void databaseRejectsInvalidStateEvenWithoutApplicationValidation(String sql) {
        service.create(request("CONSTRAINT", "YOGURT-CASE", 1));
        assertThatThrownBy(() -> jdbc.update(sql)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void migrationsAndSupportingIndexesExist() {
        assertThat(jdbc.queryForList("select tablename from pg_tables where schemaname = 'public'", String.class))
                .containsExactlyInAnyOrder("stock_batch", "reservation", "reservation_allocation", "flyway_schema_history");
        assertThat(jdbc.queryForList("select indexname from pg_indexes where schemaname = 'public'", String.class))
                .contains("ix_batch_fefo", "ix_allocation_batch", "uq_reservation_external_request", "pk_reservation_allocation");
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success", Integer.class)).isEqualTo(1);
    }

    @Test void diagnosticIncludesBatchWithNoActiveAllocations() throws Exception {
        jdbc.update("update stock_batch set reserved = 1 where id = 101");
        try {
            var violations = jdbc.queryForList(Files.readString(Path.of("docs/sql/check-invariants.sql")));
            assertThat(violations).hasSize(1);
            assertThat(violations.getFirst()).containsEntry("violation", "batch_reserved")
                    .containsEntry("entity_id", "101").containsEntry("expected", 0L).containsEntry("actual", 1L);
        } finally {
            jdbc.update("update stock_batch set reserved = 0 where id = 101");
        }
    }
}

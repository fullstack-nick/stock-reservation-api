package io.github.fullstacknick.stockreservation.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;

class ApiExceptionHandlerTest {
    @ParameterizedTest
    @ValueSource(strings = {"55P03", "57014", "40P01", "40001", "08001", "08006"})
    void classifiesNestedSqlStatesRatherThanDriverMessages(String state) {
        assertThat(ApiExceptionHandler.temporarilyUnavailable(
                new RuntimeException("wrapper", new SQLException("Any localized driver message", state)))).isTrue();
    }

    @Test void classifiesPoolAndTransactionFailuresAsTemporary() {
        assertThat(ApiExceptionHandler.temporarilyUnavailable(new CannotCreateTransactionException("pool"))).isTrue();
        assertThat(ApiExceptionHandler.temporarilyUnavailable(new TransactionTimedOutException("deadline"))).isTrue();
    }

    @Test void neverDisguisesConstraintOrProgrammingFailuresAsRetries() {
        assertThat(ApiExceptionHandler.temporarilyUnavailable(new SQLException("constraint", "23514"))).isFalse();
        assertThat(ApiExceptionHandler.temporarilyUnavailable(new IllegalStateException("broken invariant"))).isFalse();
        assertThat(ApiExceptionHandler.temporarilyUnavailable(new SQLException("missing SQLSTATE"))).isFalse();
    }
}

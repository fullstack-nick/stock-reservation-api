package io.github.fullstacknick.stockreservation.reservation.persistence;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class TransactionSettings {
    private final JdbcTemplate jdbc;
    private final String lockTimeout;
    private final String statementTimeout;

    public TransactionSettings(JdbcTemplate jdbc,
            @Value("${reservation.lock-timeout}") Duration lockTimeout,
            @Value("${reservation.statement-timeout}") Duration statementTimeout) {
        this.jdbc = jdbc;
        this.lockTimeout = milliseconds(lockTimeout);
        this.statementTimeout = milliseconds(statementTimeout);
    }

    private static String milliseconds(Duration duration) {
        if (duration.toMillis() < 1) throw new IllegalArgumentException("Timeout must be positive");
        return duration.toMillis() + "ms";
    }

    public void apply() {
        jdbc.queryForObject("select set_config('lock_timeout', ?, true)", String.class, lockTimeout);
        jdbc.queryForObject("select set_config('statement_timeout', ?, true)", String.class, statementTimeout);
    }
}

package io.github.fullstacknick.stockreservation.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestInfrastructure {
    // PostgreSQL 18.6-bookworm. Digest-only syntax is intentional for Testcontainers 2.
    public static final String POSTGRES_IMAGE =
            "postgres@sha256:5c4783a8cc3f0074912164f94dfa1be4e043438f0f707cf968d7af9a0d31a2c8";

    @Bean @ServiceConnection
    PostgreSQLContainer postgres() { return new PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE)); }

    @Bean @Primary
    MutableClock testClock() { return new MutableClock(); }
}

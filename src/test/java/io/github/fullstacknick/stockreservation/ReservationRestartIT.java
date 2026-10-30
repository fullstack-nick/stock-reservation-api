package io.github.fullstacknick.stockreservation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.fullstacknick.stockreservation.support.TestInfrastructure;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class ReservationRestartIT {
    // The database belongs to JUnit, not either Spring context being restarted.
    @Container static final PostgreSQLContainer DATABASE = new PostgreSQLContainer(TestInfrastructure.POSTGRES_IMAGE);
    private final HttpClient client = HttpClient.newHttpClient();
    private static final String REQUEST = """
            {"externalRequestId":"PERSISTED","sku":"YOGURT-CASE","quantity":9,"requiredOn":"2026-11-02"}
            """;

    @Test void restartPreservesIdentityAllocationsAndDemoStockWithoutReseeding() throws Exception {
        JsonNode original;
        try (var first = startApplication()) {
            original = post(first, "/reservations", REQUEST, 201);
        }
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(
                DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()));
        assertThat(jdbc.queryForObject("select reserved from stock_batch where id = 101", Integer.class)).isEqualTo(6);
        try (var second = startApplication()) {
            var replay = post(second, "/reservations", REQUEST, 200);
            assertThat(replay).isEqualTo(original);
            int port = second.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
            var read = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                    + "/reservations/" + original.path("id").asString())).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(read.statusCode()).isEqualTo(200);
            assertThat(second.getBean(JsonMapper.class).readTree(read.body())).isEqualTo(original);
            assertThat(jdbc.queryForObject("select count(*) from stock_batch", Integer.class)).isEqualTo(8);
            assertThat(jdbc.queryForObject("select count(*) from reservation", Integer.class)).isEqualTo(1);
            var released = post(second, "/reservations/" + original.path("id").asString() + "/release", null, 200);
            assertThat(post(second, "/reservations", REQUEST, 200)).isEqualTo(released);
        }
        assertThat(jdbc.queryForList(Files.readString(Path.of("docs/sql/check-invariants.sql")))).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from reservation_allocation", Integer.class)).isEqualTo(2);
    }

    private ConfigurableApplicationContext startApplication() {
        return SpringApplication.run(StockReservationApplication.class,
                "--spring.profiles.active=demo", "--server.port=0", "--spring.main.banner-mode=off",
                "--spring.datasource.url=" + DATABASE.getJdbcUrl(),
                "--spring.datasource.username=" + DATABASE.getUsername(),
                "--spring.datasource.password=" + DATABASE.getPassword());
    }

    private JsonNode post(ConfigurableApplicationContext context, String path, String body, int expectedStatus) throws Exception {
        int port = context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
        var publisher = body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body);
        var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json").POST(publisher).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(expectedStatus);
        return context.getBean(JsonMapper.class).readTree(response.body());
    }
}

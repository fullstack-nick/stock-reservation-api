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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class ReservationRestartIT {
    // The database belongs to JUnit, not either Spring context being restarted.
    @Container static final PostgreSQLContainer DATABASE = new PostgreSQLContainer(TestInfrastructure.POSTGRES_IMAGE);
    private final HttpClient client = HttpClient.newHttpClient();
    private static final String REQUEST = """
            {"externalRequestId":"PERSISTED","sku":"YOGURT-CASE","quantity":9,"requiredOn":"2026-11-02"}
            """;

    @Test void restartPreservesIdentityAllocationsAndDemoStockWithoutReseeding(CapturedOutput output) throws Exception {
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
        // Exercise the documented optional formatter, not just a mock logging appender.
        var logMapper = JsonMapper.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
        var completions = output.getAll().lines().filter(line -> line.startsWith("{"))
                .map(logMapper::readTree).filter(event -> "HTTP request completed".equals(event.path("message").asString()))
                .toList();
        assertThat(completions).hasSize(5);
        assertThat(completions).extracting(event -> event.path("status").asInt()).containsExactly(201, 200, 200, 200, 200);
        assertThat(completions.getFirst().path("outcome").asString()).isEqualTo("CREATED");
        for (var event : completions) {
            assertThat(event.path("externalRequestId").asString()).isEqualTo("PERSISTED");
            assertThat(event.path("reservationId").asString()).isEqualTo(original.path("id").asString());
            assertThat(java.util.UUID.fromString(event.path("correlationId").asString())).isNotNull();
        }
        assertThat(output.getAll()).doesNotContain("failed to append", "Duplicate nested pairs");
    }

    private ConfigurableApplicationContext startApplication() {
        // Other integration classes cache contexts in this JVM. A real process restart has
        // a fresh logging system; reset its initialization flag before testing that lifecycle.
        org.springframework.boot.logging.LoggingSystem.get(StockReservationApplication.class.getClassLoader()).cleanUp();
        return SpringApplication.run(StockReservationApplication.class,
                "--spring.profiles.active=demo", "--server.port=0", "--spring.main.banner-mode=off",
                "--logging.structured.format.console=ecs",
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

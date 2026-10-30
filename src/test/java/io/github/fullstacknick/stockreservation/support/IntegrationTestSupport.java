package io.github.fullstacknick.stockreservation.support;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.fullstacknick.stockreservation.reservation.api.CreateReservationRequest;
import io.github.fullstacknick.stockreservation.reservation.application.ReservationService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "reservation.lock-timeout=8s", "reservation.statement-timeout=15s", "reservation.transaction-timeout-seconds=25"
})
@Import(TestInfrastructure.class)
public abstract class IntegrationTestSupport {
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected DataSource dataSource;
    @Autowired protected ReservationService service;
    @Autowired protected MutableClock clock;
    @Autowired protected JsonMapper mapper;
    @Value("${local.server.port}") protected int port;
    protected final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private Map<Long, Integer> originalOnHand;

    @BeforeEach
    void resetDatabase() {
        jdbc.execute("TRUNCATE reservation_allocation, reservation, stock_batch");
        new ResourceDatabasePopulator(new ClassPathResource("fixtures/inventory.sql")).execute(dataSource);
        originalOnHand = jdbc.query("select id, on_hand from stock_batch", rows -> {
            var quantities = new java.util.HashMap<Long, Integer>();
            while (rows.next()) quantities.put(rows.getLong(1), rows.getInt(2));
            return quantities;
        });
        clock.set(MutableClock.INITIAL);
    }

    @AfterEach
    void verifyAccounting() throws Exception {
        assertInvariants();
        originalOnHand.forEach((id, quantity) -> assertThat(jdbc.queryForObject(
                "select on_hand from stock_batch where id = ?", Integer.class, id)).isEqualTo(quantity));
    }

    protected void assertInvariants() throws Exception {
        assertThat(jdbc.queryForList(Files.readString(Path.of("docs/sql/check-invariants.sql")))).isEmpty();
    }

    protected CreateReservationRequest request(String key, String sku, int quantity) {
        return new CreateReservationRequest(key, sku, quantity, LocalDate.of(2026, 11, 2));
    }

    protected Result send(String method, String path, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30)).header("Accept", "application/json");
        var publisher = body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body instanceof String raw ? raw : mapper.writeValueAsString(body));
        if (body != null) builder.header("Content-Type", "application/json");
        var response = http.send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
        return new Result(response.statusCode(), mapper.readTree(response.body()), response.headers());
    }

    protected long count(String table) { return jdbc.queryForObject("select count(*) from " + table, Long.class); }
    protected int reserved(long batchId) {
        return jdbc.queryForObject("select reserved from stock_batch where id = ?", Integer.class, batchId);
    }

    protected record Result(int status, JsonNode body, java.net.http.HttpHeaders headers) {
        public String id() { return body.path("id").asString(); }
    }
}

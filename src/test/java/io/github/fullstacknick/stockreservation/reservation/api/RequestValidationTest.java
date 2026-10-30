package io.github.fullstacknick.stockreservation.reservation.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.fullstacknick.stockreservation.config.ApplicationConfiguration;
import io.github.fullstacknick.stockreservation.reservation.application.ReservationService;
import io.github.fullstacknick.stockreservation.reservation.persistence.Reservation;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ReservationController.class)
@Import(ApplicationConfiguration.class)
class RequestValidationTest {
    private static final String VALID = """
            {"externalRequestId":"ORDER-1","sku":"YOGURT-CASE","quantity":9,"requiredOn":"2026-11-02"}
            """;
    @Autowired MockMvc mvc;
    @MockitoBean ReservationService service;

    static Stream<Arguments> invalidRequests() {
        return Stream.of(
                Arguments.of("{}", "VALIDATION_FAILED"),
                Arguments.of(VALID.replace("9", "0"), "VALIDATION_FAILED"),
                Arguments.of(VALID.replace("9", "-1"), "VALIDATION_FAILED"),
                Arguments.of(VALID.replace("9", "null"), "VALIDATION_FAILED"),
                Arguments.of(VALID.replace("9", "1.0"), "MALFORMED_REQUEST"),
                Arguments.of(VALID.replace("9", "1e2"), "MALFORMED_REQUEST"),
                Arguments.of(VALID.replace("9", "2147483648"), "MALFORMED_REQUEST"),
                Arguments.of(VALID.replace("9", "\"9\""), "MALFORMED_REQUEST"),
                Arguments.of(VALID.replace("9", "true"), "MALFORMED_REQUEST"),
                Arguments.of(VALID.replace("ORDER-1", " ORDER-1"), "VALIDATION_FAILED"),
                Arguments.of(VALID.replace("ORDER-1", "A".repeat(65)), "VALIDATION_FAILED"),
                Arguments.of(VALID.replace("\"ORDER-1\"", "42"), "MALFORMED_REQUEST"),
                Arguments.of(VALID.replace("ORDER-1", ""), "VALIDATION_FAILED"),
                Arguments.of(VALID.replace("YOGURT-CASE", "yogurt-case"), "VALIDATION_FAILED"),
                Arguments.of(VALID.replace("YOGURT-CASE", "YOGURT-CASE "), "VALIDATION_FAILED"),
                Arguments.of(VALID.replace("YOGURT-CASE", "A".repeat(65)), "VALIDATION_FAILED"),
                Arguments.of(VALID.replace("2026-11-02", "2026-02-30"), "MALFORMED_REQUEST"),
                Arguments.of(VALID.replace("2026-11-02", "2026-1-2"), "MALFORMED_REQUEST"),
                Arguments.of(VALID.replace("2026-11-02", "0000-01-01"), "MALFORMED_REQUEST"),
                Arguments.of(VALID.replace("2026-11-02", "+10000-01-01"), "MALFORMED_REQUEST"),
                Arguments.of(VALID.replace("\"2026-11-02\"", "20261102"), "MALFORMED_REQUEST"),
                Arguments.of(VALID.replace("\"2026-11-02\"", "null"), "VALIDATION_FAILED"),
                Arguments.of(VALID.replace("}", ",\"unknown\":true}"), "MALFORMED_REQUEST"),
                Arguments.of(VALID.replace("}", ",\"quantity\":1}"), "MALFORMED_REQUEST"),
                Arguments.of(VALID + "{}", "MALFORMED_REQUEST"),
                Arguments.of("{", "MALFORMED_REQUEST"));
    }

    @ParameterizedTest(name = "Invalid request {index}: {1}")
    @MethodSource("invalidRequests")
    void rejectsInvalidInputBeforeCallingService(String json, String code) throws Exception {
        mvc.perform(post("/reservations").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(code))
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(header().exists("X-Correlation-ID"));
        verifyNoInteractions(service);
    }

    @Test void rejectsInvalidUuidAndUnexpectedReleaseBody() throws Exception {
        mvc.perform(get("/reservations/1-1-1-1-1")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("id"));
        mvc.perform(post("/reservations/" + UUID.randomUUID() + "/release")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("body"));
        verifyNoInteractions(service);
    }

    @Test void acceptsOldDatesStructurallySoServiceCanRecognizeReplay() throws Exception {
        UUID id = UUID.randomUUID();
        var body = new ReservationResponse(id, "ORDER-1", "YOGURT-CASE", 9, LocalDate.of(2000, 1, 1),
                Reservation.Status.RELEASED, Instant.EPOCH, Instant.EPOCH, List.of());
        when(service.create(any())).thenReturn(new ReservationService.Creation(body, false));
        mvc.perform(post("/reservations").contentType(MediaType.APPLICATION_JSON)
                .content(VALID.replace("2026-11-02", "2000-01-01")))
                .andExpect(status().isOk()).andExpect(header().string("Location", "/reservations/" + id));
    }

    @Test void normalizesCorrelationAndKeepsProblemsFreeOfRejectedValues() throws Exception {
        String correlation = "A28F271E-D789-4E3A-91F6-F6EE11642C95";
        mvc.perform(post("/reservations").header("X-Correlation-ID", correlation)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("X-Correlation-ID", correlation.toLowerCase()))
                .andExpect(jsonPath("$.correlationId").value(correlation.toLowerCase()))
                .andExpect(jsonPath("$.errors[0].field").value("externalRequestId"))
                .andExpect(jsonPath("$.errors[0].rejectedValue").doesNotExist());
    }

    @Test void rejectsUnsupportedHttpContractsBeforeCallingService() throws Exception {
        mvc.perform(post("/reservations").contentType(MediaType.TEXT_PLAIN).content(VALID))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        mvc.perform(post("/reservations").contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_XML).content(VALID))
                .andExpect(status().isNotAcceptable())
                .andExpect(jsonPath("$.code").value("NOT_ACCEPTABLE"));
        verifyNoInteractions(service);
    }

    @Test void replacesInvalidCorrelationAndClearsThreadContextAfterEveryAttempt() throws Exception {
        var result = mvc.perform(post("/reservations").header("X-Correlation-ID", "untrusted-value")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andReturn();
        String selected = result.getResponse().getHeader("X-Correlation-ID");
        org.assertj.core.api.Assertions.assertThat(UUID.fromString(selected).toString()).isEqualTo(selected);
        org.assertj.core.api.Assertions.assertThat(org.slf4j.MDC.getCopyOfContextMap()).isNullOrEmpty();
        var next = mvc.perform(post("/reservations").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn();
        org.assertj.core.api.Assertions.assertThat(next.getResponse().getHeader("X-Correlation-ID"))
                .isNotEqualTo(selected);
        org.assertj.core.api.Assertions.assertThat(org.slf4j.MDC.getCopyOfContextMap()).isNullOrEmpty();
    }
}

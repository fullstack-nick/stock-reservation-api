package io.github.fullstacknick.stockreservation.reservation.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import tools.jackson.databind.annotation.JsonDeserialize;

public record CreateReservationRequest(
        @NotNull @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]{0,63}") String externalRequestId,
        @NotNull @Pattern(regexp = "[A-Z0-9][A-Z0-9_-]{0,63}") String sku,
        @NotNull @Positive Integer quantity,
        @NotNull @JsonDeserialize(using = StrictLocalDateDeserializer.class) LocalDate requiredOn) {}

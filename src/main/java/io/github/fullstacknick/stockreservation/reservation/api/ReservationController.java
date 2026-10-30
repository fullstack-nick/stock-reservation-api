package io.github.fullstacknick.stockreservation.reservation.api;

import io.github.fullstacknick.stockreservation.reservation.application.ReservationService;
import io.github.fullstacknick.stockreservation.reservation.domain.ReservationException;
import io.github.fullstacknick.stockreservation.reservation.domain.ReservationException.Reason;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/reservations", produces = MediaType.APPLICATION_JSON_VALUE)
public class ReservationController {
    private final ReservationService service;
    public ReservationController(ReservationService service) { this.service = service; }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ReservationResponse> create(@Valid @RequestBody CreateReservationRequest request) {
        MDC.put("externalRequestId", request.externalRequestId());
        MDC.put("sku", request.sku());
        MDC.put("quantity", request.quantity().toString());
        var result = service.create(request);
        audit(result.reservation(), result.created() ? "CREATED" : "REPLAYED");
        return ResponseEntity.status(result.created() ? 201 : 200)
                .location(URI.create("/reservations/" + result.reservation().id())).body(result.reservation());
    }

    @GetMapping("/{id}")
    public ReservationResponse get(@PathVariable String id) {
        var response = service.get(parseId(id));
        audit(response, "READ");
        return response;
    }

    @PostMapping("/{id}/release")
    public ReservationResponse release(@PathVariable String id, @RequestBody(required = false) byte[] body) {
        UUID parsed = parseId(id);
        if (body != null && body.length > 0) {
            throw new ReservationException(Reason.VALIDATION_FAILED, "Release requires an empty request body.", "body");
        }
        var response = service.release(parsed);
        audit(response, "RELEASED");
        return response;
    }

    private static UUID parseId(String id) {
        if (!id.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw new ReservationException(Reason.VALIDATION_FAILED, "id must be a canonical UUID.", "id");
        }
        MDC.put("reservationId", id);
        return UUID.fromString(id);
    }

    private static void audit(ReservationResponse response, String outcome) {
        MDC.put("reservationId", response.id().toString());
        MDC.put("externalRequestId", response.externalRequestId());
        MDC.put("sku", response.sku());
        MDC.put("quantity", Integer.toString(response.quantity()));
        MDC.put("outcome", outcome);
    }
}

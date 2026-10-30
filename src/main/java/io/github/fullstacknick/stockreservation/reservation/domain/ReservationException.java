package io.github.fullstacknick.stockreservation.reservation.domain;

public class ReservationException extends RuntimeException {
    public enum Reason { INSUFFICIENT_STOCK, REQUEST_ID_CONFLICT, RESERVATION_NOT_FOUND, VALIDATION_FAILED }

    private final Reason reason;
    private final String field;

    public ReservationException(Reason reason, String message) {
        this(reason, message, null);
    }

    public ReservationException(Reason reason, String message, String field) {
        super(message);
        this.reason = reason;
        this.field = field;
    }

    public Reason reason() { return reason; }
    public String field() { return field; }

    public static ReservationException insufficient(int requested, long available) {
        return new ReservationException(Reason.INSUFFICIENT_STOCK,
                "Requested " + requested + " cases; " + available + " eligible cases are available.");
    }
}

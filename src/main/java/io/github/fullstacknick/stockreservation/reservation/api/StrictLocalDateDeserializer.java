package io.github.fullstacknick.stockreservation.reservation.api;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

public class StrictLocalDateDeserializer extends ValueDeserializer<LocalDate> {
    @Override
    public LocalDate deserialize(JsonParser parser, DeserializationContext context) {
        if (!parser.hasToken(JsonToken.VALUE_STRING)) {
            return (LocalDate) context.handleUnexpectedToken(LocalDate.class, parser);
        }
        String value = parser.getString();
        if (value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
            try {
                LocalDate date = LocalDate.parse(value);
                if (date.getYear() >= 1) return date;
            } catch (DateTimeParseException ignored) {
                // The response handler returns a safe generic format error, without echoing input.
            }
        }
        return (LocalDate) context.handleWeirdStringValue(LocalDate.class, value,
                "Expected a valid YYYY-MM-DD date in years 0001-9999");
    }
}

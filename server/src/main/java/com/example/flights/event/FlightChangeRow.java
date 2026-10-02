package com.example.flights.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A raw row of {@code app_internal.flight_change_event}.
 *
 * @param stations stations the change is relevant to (old and new route)
 */
public record FlightChangeRow(
        long eventId,
        String operation,
        UUID flightId,
        String oldRow,
        String newRow,
        Instant occurredAt,
        List<String> stations) {

    public FlightChangeRow {
        stations = stations == null ? List.of() : List.copyOf(stations);
    }
}

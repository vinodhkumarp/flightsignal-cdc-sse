package com.example.flights.event;

import java.time.Instant;
import java.util.List;

import com.example.flights.flight.Flight;

/**
 * Business-level flight notification sent to browsers.
 *
 * @param stations stations this notification is delivered to
 */
public record FlightEvent(
        String eventId,
        Instant occurredAt,
        String operation,
        Flight flight,
        Flight previousFlight,
        List<FieldChange> changes,
        String type,
        String severity,
        String message,
        DelayDetails delay,
        List<String> stations) {

    public FlightEvent {
        stations = stations == null ? List.of() : List.copyOf(stations);
    }
}

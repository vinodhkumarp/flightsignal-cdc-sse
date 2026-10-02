package com.example.flights.event;

import java.time.Instant;
import java.util.List;

import com.example.flights.flight.Flight;

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
        DelayDetails delay) {
}


package com.example.flights.event;

import java.time.Instant;
import java.util.UUID;

public record FlightChangeRow(
        long eventId,
        String operation,
        UUID flightId,
        String oldRow,
        String newRow,
        Instant occurredAt) {
}


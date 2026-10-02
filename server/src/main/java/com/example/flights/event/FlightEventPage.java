package com.example.flights.event;

import java.util.List;

public record FlightEventPage(
        List<FlightEvent> events,
        String nextCursor,
        boolean hasMore) {
}

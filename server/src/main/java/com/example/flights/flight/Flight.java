package com.example.flights.flight;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record Flight(
        UUID flightId,
        String carrierCode,
        String flightNumber,
        LocalDate serviceDate,
        String originAirport,
        String destinationAirport,
        List<String> routeStations,
        String originTimezone,
        String destinationTimezone,
        Instant scheduledDepartureUtc,
        Instant estimatedDepartureUtc,
        Instant actualDepartureUtc,
        Instant scheduledArrivalUtc,
        Instant estimatedArrivalUtc,
        Instant actualArrivalUtc,
        String status,
        String gate,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public Flight {
        routeStations = routeStations == null || routeStations.isEmpty()
                ? List.of(originAirport, destinationAirport)
                : List.copyOf(routeStations);
    }

    public String label() {
        return carrierCode + flightNumber;
    }
}


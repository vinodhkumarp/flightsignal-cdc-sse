package com.example.flights.flight;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record UpdateFlightRequest(
        String carrierCode,
        String flightNumber,
        LocalDate serviceDate,
        String originAirport,
        String destinationAirport,
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
        List<String> routeStations,
        Long version) {
}


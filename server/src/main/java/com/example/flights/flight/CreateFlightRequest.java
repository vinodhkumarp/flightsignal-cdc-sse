package com.example.flights.flight;

import java.time.Instant;
import java.time.LocalDate;

public record CreateFlightRequest(
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
        String gate) {
}


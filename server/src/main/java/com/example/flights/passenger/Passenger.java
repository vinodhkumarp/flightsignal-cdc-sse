package com.example.flights.passenger;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record Passenger(
        UUID passengerId,
        UUID flightId,
        String carrierCode,
        String flightNumber,
        LocalDate serviceDate,
        String originAirport,
        String destinationAirport,
        int manifestSequence,
        String bookingReference,
        String givenName,
        String familyName,
        String email,
        String phoneNumber,
        String preferredContactMethod,
        String seatNumber,
        String cabinClass,
        String loyaltyTier,
        boolean specialAssistance,
        String contactStatus,
        Instant createdAt,
        Instant updatedAt) {

    public String fullName() {
        return givenName + " " + familyName;
    }
}

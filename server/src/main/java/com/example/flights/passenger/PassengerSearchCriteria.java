package com.example.flights.passenger;

import java.time.LocalDate;

public record PassengerSearchCriteria(
        String flightNumber,
        LocalDate travelDate,
        String originAirport,
        String destinationAirport,
        String passengerName,
        String bookingReference) {
}

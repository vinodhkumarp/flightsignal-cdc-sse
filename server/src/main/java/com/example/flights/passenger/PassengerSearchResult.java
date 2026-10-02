package com.example.flights.passenger;

import java.util.List;

public record PassengerSearchResult(
        List<Passenger> passengers,
        int count,
        PassengerSearchCriteria criteria) {
}

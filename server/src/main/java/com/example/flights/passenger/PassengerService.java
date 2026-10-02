package com.example.flights.passenger;

import java.time.LocalDate;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.example.flights.api.ApiException;
import com.example.flights.security.StationScope;

@Service
public class PassengerService {

    private static final Pattern FLIGHT_CODE = Pattern.compile("[A-Z0-9]{3,11}");
    private static final Pattern AIRPORT = Pattern.compile("[A-Z]{3}");

    private final PassengerRepository repository;

    public PassengerService(PassengerRepository repository) {
        this.repository = repository;
    }

    public PassengerSearchResult search(
            String flightNumber,
            LocalDate travelDate,
            String originAirport,
            String destinationAirport,
            String passengerName,
            String bookingReference,
            StationScope scope) {
        var normalizedFlight = upperRequired(flightNumber, "flightNumber");
        require(FLIGHT_CODE.matcher(normalizedFlight).matches(),
                "flightNumber must contain three to eleven letters or digits.");
        require(travelDate != null, "travelDate is required.");

        var criteria = new PassengerSearchCriteria(
                normalizedFlight,
                travelDate,
                normalizeAirport(originAirport, "originAirport"),
                normalizeAirport(destinationAirport, "destinationAirport"),
                upperToNull(passengerName),
                upperToNull(bookingReference));
        var passengers = repository.search(criteria, scope);
        return new PassengerSearchResult(
                passengers,
                passengers.size(),
                criteria);
    }

    private static String normalizeAirport(String value, String field) {
        var normalized = upperToNull(value);
        if (normalized == null) {
            return null;
        }
        require(AIRPORT.matcher(normalized).matches(),
                field + " must be a three-letter airport code.");
        return normalized;
    }

    private static String upperRequired(String value, String field) {
        var normalized = upperToNull(value);
        require(normalized != null, field + " is required.");
        return normalized;
    }

    private static String upperToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new ApiException(HttpStatus.BAD_REQUEST, message);
        }
    }
}

package com.example.flights.flight;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;

import com.example.flights.api.ApiException;

/**
 * Normalisation and validation rules shared by flight creation and update,
 * mirroring the database CHECK constraints so that callers get a clear 400
 * instead of a generic constraint violation.
 */
final class FlightRules {

    static final Set<String> STATUSES = Set.of(
            "SCHEDULED",
            "DELAYED",
            "BOARDING",
            "DEPARTED",
            "ARRIVED",
            "CANCELLED");

    private static final Pattern CARRIER = Pattern.compile("[A-Z0-9]{2,3}");
    private static final Pattern FLIGHT_NUMBER = Pattern.compile("[A-Z0-9]{1,8}");
    private static final Pattern AIRPORT = Pattern.compile("[A-Z]{3}");
    private static final int GATE_MAX_LENGTH = 12;
    private static final int MAX_ROUTE_STATIONS = 10;

    private FlightRules() {
    }

    static String carrier(String value) {
        var normalized = upper(value, "carrierCode");
        require(CARRIER.matcher(normalized).matches(),
                "carrierCode must contain two or three letters or digits.");
        return normalized;
    }

    static String flightNumber(String value) {
        var normalized = upper(value, "flightNumber");
        require(FLIGHT_NUMBER.matcher(normalized).matches(),
                "flightNumber must contain one to eight letters or digits.");
        return normalized;
    }

    static UnaryOperator<String> airport(String field) {
        return value -> {
            var normalized = upper(value, field);
            require(AIRPORT.matcher(normalized).matches(),
                    field + " must be a three-letter airport code.");
            return normalized;
        };
    }

    static UnaryOperator<String> timezone(String field) {
        return value -> {
            require(value != null && !value.isBlank(), field + " is required.");
            var trimmed = value.trim();
            try {
                ZoneId.of(trimmed);
            } catch (DateTimeException exception) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        field + " must be a valid IANA timezone.");
            }
            return trimmed;
        };
    }

    static String status(String value) {
        var normalized = upper(value, "status");
        require(STATUSES.contains(normalized), "status is not supported.");
        return normalized;
    }

    static String gate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        var trimmed = value.trim();
        require(trimmed.length() <= GATE_MAX_LENGTH,
                "gate must be at most " + GATE_MAX_LENGTH + " characters.");
        return trimmed;
    }

    /**
     * Normalises a journey's stations (e.g. {@code [syd, SIN, lhr]} to
     * {@code [SYD, SIN, LHR]}), removing duplicates but keeping order.
     *
     * @return {@code null} when no route was supplied
     */
    static List<String> routeStations(List<String> value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        var stations = new LinkedHashSet<String>();
        for (var station : value) {
            stations.add(airport("routeStations").apply(station));
        }
        require(stations.size() >= 2 && stations.size() <= MAX_ROUTE_STATIONS,
                "routeStations must contain between 2 and " + MAX_ROUTE_STATIONS + " stations.");
        return new ArrayList<>(stations);
    }

    /** Applies {@code rule} only when a value was supplied (PATCH semantics). */
    static String optional(String value, UnaryOperator<String> rule) {
        return value == null ? null : rule.apply(value);
    }

    static void require(boolean condition, String message) {
        if (!condition) {
            throw new ApiException(HttpStatus.BAD_REQUEST, message);
        }
    }

    private static String upper(String value, String field) {
        require(value != null && !value.isBlank(), field + " is required.");
        return value.trim().toUpperCase(Locale.ROOT);
    }
}

package com.example.flights.flight;

import static com.example.flights.flight.FlightRules.optional;
import static com.example.flights.flight.FlightRules.require;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.example.flights.api.ApiException;

@Service
public class FlightService {

    private final FlightRepository repository;

    public FlightService(FlightRepository repository) {
        this.repository = repository;
    }

    public List<Flight> findAll() {
        return repository.findAll();
    }

    public Flight create(CreateFlightRequest request) {
        require(request != null, "Flight details are required.");

        var origin = FlightRules.airport("originAirport").apply(request.originAirport());
        var destination = FlightRules.airport("destinationAirport")
                .apply(request.destinationAirport());
        require(!origin.equals(destination),
                "originAirport and destinationAirport must be different.");
        require(request.serviceDate() != null, "serviceDate is required.");
        require(request.scheduledDepartureUtc() != null,
                "scheduledDepartureUtc is required.");
        require(request.scheduledArrivalUtc() != null,
                "scheduledArrivalUtc is required.");
        require(request.scheduledArrivalUtc().isAfter(request.scheduledDepartureUtc()),
                "scheduledArrivalUtc must be after scheduledDepartureUtc.");

        return repository.insert(new CreateFlightRequest(
                FlightRules.carrier(request.carrierCode()),
                FlightRules.flightNumber(request.flightNumber()),
                request.serviceDate(),
                origin,
                destination,
                FlightRules.timezone("originTimezone").apply(request.originTimezone()),
                FlightRules.timezone("destinationTimezone")
                        .apply(request.destinationTimezone()),
                request.scheduledDepartureUtc(),
                request.estimatedDepartureUtc() == null
                        ? request.scheduledDepartureUtc()
                        : request.estimatedDepartureUtc(),
                request.actualDepartureUtc(),
                request.scheduledArrivalUtc(),
                request.estimatedArrivalUtc() == null
                        ? request.scheduledArrivalUtc()
                        : request.estimatedArrivalUtc(),
                request.actualArrivalUtc(),
                request.status() == null ? "SCHEDULED" : FlightRules.status(request.status()),
                FlightRules.gate(request.gate())));
    }

    public Flight update(UUID flightId, UpdateFlightRequest request) {
        require(request != null, "Flight update is required.");
        require(request.version() != null && request.version() > 0,
                "version must be a positive integer.");

        var origin = optional(request.originAirport(), FlightRules.airport("originAirport"));
        var destination = optional(
                request.destinationAirport(),
                FlightRules.airport("destinationAirport"));
        require(origin == null || !origin.equals(destination),
                "originAirport and destinationAirport must be different.");
        require(request.scheduledDepartureUtc() == null
                        || request.scheduledArrivalUtc() == null
                        || request.scheduledArrivalUtc().isAfter(request.scheduledDepartureUtc()),
                "scheduledArrivalUtc must be after scheduledDepartureUtc.");

        var changes = new LinkedHashMap<String, Object>();
        put(changes, "carrierCode", optional(request.carrierCode(), FlightRules::carrier));
        put(changes, "flightNumber", optional(request.flightNumber(), FlightRules::flightNumber));
        put(changes, "serviceDate", request.serviceDate());
        put(changes, "originAirport", origin);
        put(changes, "destinationAirport", destination);
        put(changes, "originTimezone", optional(
                request.originTimezone(), FlightRules.timezone("originTimezone")));
        put(changes, "destinationTimezone", optional(
                request.destinationTimezone(), FlightRules.timezone("destinationTimezone")));
        put(changes, "scheduledDepartureUtc", request.scheduledDepartureUtc());
        put(changes, "estimatedDepartureUtc", request.estimatedDepartureUtc());
        put(changes, "actualDepartureUtc", request.actualDepartureUtc());
        put(changes, "scheduledArrivalUtc", request.scheduledArrivalUtc());
        put(changes, "estimatedArrivalUtc", request.estimatedArrivalUtc());
        put(changes, "actualArrivalUtc", request.actualArrivalUtc());
        put(changes, "status", optional(request.status(), FlightRules::status));
        put(changes, "gate", FlightRules.gate(request.gate()));

        require(!changes.isEmpty(), "At least one flight field must be supplied.");

        return repository.update(flightId, request.version(), changes)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.CONFLICT,
                        "Flight was not found or was changed by another user. "
                                + "Refresh and retry."));
    }

    public Flight delete(UUID flightId) {
        return repository.delete(flightId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "Flight was not found."));
    }

    private static void put(
            LinkedHashMap<String, Object> changes,
            String key,
            Object value) {
        if (value != null) {
            changes.put(key, value);
        }
    }
}

package com.example.flights.flight;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.flights.security.StationScope;

@RestController
@RequestMapping("/api/flights")
public class FlightController {

    private final FlightService service;

    public FlightController(FlightService service) {
        this.service = service;
    }

    @GetMapping
    Map<String, List<Flight>> findAll(StationScope scope) {
        return Map.of("flights", service.findAll(scope));
    }

    @PostMapping
    ResponseEntity<Map<String, Flight>> create(
            @RequestBody CreateFlightRequest request,
            StationScope scope) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(Map.of("flight", service.create(request, scope)));
    }

    @PatchMapping("/{flightId}")
    Map<String, Flight> update(
            @PathVariable UUID flightId,
            @RequestBody UpdateFlightRequest request,
            StationScope scope) {
        return Map.of("flight", service.update(flightId, request, scope));
    }

    @DeleteMapping("/{flightId}")
    Map<String, Flight> delete(@PathVariable UUID flightId, StationScope scope) {
        return Map.of("flight", service.delete(flightId, scope));
    }
}


package com.example.flights.passenger;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/passengers")
public class PassengerController {

    private final PassengerService service;

    public PassengerController(PassengerService service) {
        this.service = service;
    }

    @GetMapping
    PassengerSearchResult search(
            @RequestParam(required = false) String flightNumber,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate travelDate,
            @RequestParam(required = false) String originAirport,
            @RequestParam(required = false) String destinationAirport,
            @RequestParam(required = false) String passengerName,
            @RequestParam(required = false) String bookingReference) {
        return service.search(
                flightNumber,
                travelDate,
                originAirport,
                destinationAirport,
                passengerName,
                bookingReference);
    }
}

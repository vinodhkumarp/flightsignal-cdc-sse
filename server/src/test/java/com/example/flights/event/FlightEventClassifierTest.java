package com.example.flights.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class FlightEventClassifierTest {

    private FlightEventClassifier classifier;

    @BeforeEach
    void setUp() {
        var jsonMapper = JsonMapper.builder().findAndAddModules().build();
        classifier = new FlightEventClassifier(new FlightJsonMapper(jsonMapper));
    }

    @Test
    void classifiesAnInsertedFlight() {
        var event = classifier.classify(event("INSERT", null, flightJson(
                "2026-10-02T01:00:00Z", "SCHEDULED", "12", 1)));

        assertThat(event.type()).isEqualTo("flight.added");
        assertThat(event.message()).contains("AA112");
        assertThat(event.flight().originAirport()).isEqualTo("SYD");
    }

    @Test
    void classifiesADepartureDelayAndCalculatesMinutes() {
        var event = classifier.classify(event(
                "UPDATE",
                flightJson("2026-10-02T01:00:00Z", "SCHEDULED", "12", 1),
                flightJson("2026-10-02T01:25:00Z", "DELAYED", "12", 2)));

        assertThat(event.type()).isEqualTo("flight.departure.delayed");
        assertThat(event.delay().additionalDelayMinutes()).isEqualTo(25);
        assertThat(event.delay().totalDelayMinutes()).isEqualTo(25);
        assertThat(event.severity()).isEqualTo("warning");
    }

    @Test
    void cancellationTakesPriorityOverOtherChanges() {
        var event = classifier.classify(event(
                "UPDATE",
                flightJson("2026-10-02T01:00:00Z", "SCHEDULED", "12", 1),
                flightJson("2026-10-02T01:30:00Z", "CANCELLED", "12", 2)));

        assertThat(event.type()).isEqualTo("flight.cancelled");
        assertThat(event.severity()).isEqualTo("critical");
    }

    @Test
    void classifiesAGateChange() {
        var event = classifier.classify(event(
                "UPDATE",
                flightJson("2026-10-02T01:00:00Z", "SCHEDULED", "12", 1),
                flightJson("2026-10-02T01:00:00Z", "SCHEDULED", "18", 2)));

        assertThat(event.type()).isEqualTo("flight.gate.changed");
        assertThat(event.message()).contains("12 to 18");
    }

    @Test
    void mentionsAGateChangeThatAccompaniesADelay() {
        var event = classifier.classify(event(
                "UPDATE",
                flightJson("2026-10-02T01:00:00Z", "SCHEDULED", "12", 1),
                flightJson("2026-10-02T01:20:00Z", "DELAYED", "31", 2)));

        assertThat(event.type()).isEqualTo("flight.departure.delayed");
        assertThat(event.message())
                .startsWith("Departure for AA112 has been delayed by 20 minutes.")
                .endsWith("Gate changed from 12 to 31.");
        assertThat(event.changes())
                .extracting(FieldChange::field)
                .contains("status", "estimatedDepartureUtc", "gate");
    }

    @Test
    void describesDepartureInNaturalLanguage() {
        var event = classifier.classify(event(
                "UPDATE",
                flightJson("2026-10-02T01:00:00Z", "BOARDING", "12", 1),
                flightJson("2026-10-02T01:00:00Z", "DEPARTED", "12", 2)));

        assertThat(event.type()).isEqualTo("flight.status.changed");
        assertThat(event.message()).isEqualTo("Flight AA112 has departed.");
    }

    @Test
    void classifiesAReinstatedFlight() {
        var event = classifier.classify(event(
                "UPDATE",
                flightJson("2026-10-02T01:00:00Z", "CANCELLED", "12", 1),
                flightJson("2026-10-02T01:00:00Z", "SCHEDULED", "12", 2)));

        assertThat(event.type()).isEqualTo("flight.reinstated");
        assertThat(event.message()).contains("reinstated", "scheduled");
    }

    @Test
    void classifiesAnEarlierDeparture() {
        var event = classifier.classify(event(
                "UPDATE",
                flightJson("2026-10-02T01:00:00Z", "SCHEDULED", "12", 1),
                flightJson("2026-10-02T00:45:00Z", "SCHEDULED", "12", 2)));

        assertThat(event.type()).isEqualTo("flight.departure.revised");
        assertThat(event.message()).contains("15 minutes earlier");
        assertThat(event.delay()).isNull();
    }

    @Test
    void classifiesARemovedFlightUsingTheOldRow() {
        var event = classifier.classify(event(
                "DELETE",
                flightJson("2026-10-02T01:00:00Z", "SCHEDULED", "12", 1),
                null));

        assertThat(event.type()).isEqualTo("flight.removed");
        assertThat(event.flight().flightId()).isEqualTo(
                UUID.fromString("2eec1981-08c8-4bab-ad6d-8801e607a777"));
    }

    private static FlightChangeRow event(
            String operation,
            String oldRow,
            String newRow) {
        return new FlightChangeRow(
                42,
                operation,
                UUID.fromString("2eec1981-08c8-4bab-ad6d-8801e607a777"),
                oldRow,
                newRow,
                Instant.parse("2026-10-01T02:00:00Z"));
    }

    private static String flightJson(
            String estimatedDeparture,
            String status,
            String gate,
            long version) {
        return """
                {
                  "flight_id": "2eec1981-08c8-4bab-ad6d-8801e607a777",
                  "carrier_code": "AA",
                  "flight_number": "112",
                  "service_date": "2026-10-02",
                  "origin_airport": "SYD",
                  "destination_airport": "LAX",
                  "origin_timezone": "Australia/Sydney",
                  "destination_timezone": "America/Los_Angeles",
                  "scheduled_departure_utc": "2026-10-02T01:00:00Z",
                  "estimated_departure_utc": "%s",
                  "actual_departure_utc": null,
                  "scheduled_arrival_utc": "2026-10-02T15:00:00Z",
                  "estimated_arrival_utc": "2026-10-02T15:00:00Z",
                  "actual_arrival_utc": null,
                  "status": "%s",
                  "gate": "%s",
                  "version": %d,
                  "created_at": "2026-10-01T01:00:00Z",
                  "updated_at": "2026-10-01T01:00:00Z"
                }
                """.formatted(estimatedDeparture, status, gate, version);
    }
}


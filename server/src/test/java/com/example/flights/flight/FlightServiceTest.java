package com.example.flights.flight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.example.flights.api.ApiException;

class FlightServiceTest {

    private final AtomicReference<CreateFlightRequest> inserted = new AtomicReference<>();
    private final AtomicReference<LinkedHashMap<String, Object>> updated = new AtomicReference<>();

    private final FlightService service = new FlightService(new FlightRepository(null) {
        @Override
        public Flight insert(CreateFlightRequest flight) {
            inserted.set(flight);
            return null;
        }

        @Override
        public Optional<Flight> update(
                UUID flightId,
                long expectedVersion,
                LinkedHashMap<String, Object> changes) {
            updated.set(changes);
            return Optional.empty();
        }
    });

    @Test
    void normalisesAndDefaultsANewFlight() {
        service.create(request(" qf ", "11", "syd", "mel", " Australia/Sydney ", " "));

        var flight = inserted.get();
        assertThat(flight.carrierCode()).isEqualTo("QF");
        assertThat(flight.originAirport()).isEqualTo("SYD");
        assertThat(flight.originTimezone()).isEqualTo("Australia/Sydney");
        assertThat(flight.status()).isEqualTo("SCHEDULED");
        assertThat(flight.gate()).isNull();
        assertThat(flight.estimatedDepartureUtc()).isEqualTo(flight.scheduledDepartureUtc());
    }

    @Test
    void rejectsAnInvalidTimezoneWithA400() {
        assertThatThrownBy(() -> service.create(
                request("QF", "11", "SYD", "MEL", "Not/A Zone!", "")))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getMessage()).contains("originTimezone");
                });
    }

    @Test
    void rejectsIdenticalOriginAndDestination() {
        assertThatThrownBy(() -> service.create(
                request("QF", "11", "SYD", "syd", "Australia/Sydney", "")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("must be different");
    }

    @Test
    void updatesOnlySuppliedFieldsAndReportsVersionConflicts() {
        var request = new UpdateFlightRequest(
                null, null, null, null, null, null, null,
                null, null, null, null, null, null,
                "boarding", " 22 ", 3L);

        assertThatThrownBy(() -> service.update(UUID.randomUUID(), request))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(updated.get())
                .containsOnlyKeys("status", "gate")
                .containsEntry("status", "BOARDING")
                .containsEntry("gate", "22");
    }

    @Test
    void rejectsAnEmptyUpdate() {
        var request = new UpdateFlightRequest(
                null, null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, 1L);

        assertThatThrownBy(() -> service.update(UUID.randomUUID(), request))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("At least one");
    }

    private static CreateFlightRequest request(
            String carrier,
            String number,
            String origin,
            String destination,
            String originTimezone,
            String gate) {
        return new CreateFlightRequest(
                carrier,
                number,
                LocalDate.parse("2026-10-05"),
                origin,
                destination,
                originTimezone,
                "Australia/Melbourne",
                Instant.parse("2026-10-05T01:00:00Z"),
                null,
                null,
                Instant.parse("2026-10-05T02:30:00Z"),
                null,
                null,
                null,
                gate);
    }
}

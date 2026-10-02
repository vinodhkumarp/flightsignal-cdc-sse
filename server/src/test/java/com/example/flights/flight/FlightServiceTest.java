package com.example.flights.flight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.example.flights.api.ApiException;
import com.example.flights.security.StationScope;

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
        public Optional<Flight> findById(UUID flightId) {
            return Optional.of(existingFlight());
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

    private static final StationScope ALL = StationScope.allStations();

    @Test
    void normalisesAndDefaultsANewFlight() {
        service.create(request(" qf ", "11", "syd", "mel", " Australia/Sydney ", " ", null), ALL);

        var flight = inserted.get();
        assertThat(flight.carrierCode()).isEqualTo("QF");
        assertThat(flight.originAirport()).isEqualTo("SYD");
        assertThat(flight.originTimezone()).isEqualTo("Australia/Sydney");
        assertThat(flight.status()).isEqualTo("SCHEDULED");
        assertThat(flight.gate()).isNull();
        assertThat(flight.estimatedDepartureUtc()).isEqualTo(flight.scheduledDepartureUtc());
        assertThat(flight.routeStations()).isNull();
    }

    @Test
    void normalisesAMultiStopRoute() {
        service.create(
                request("QF", "1", "SYD", "LHR", "Australia/Sydney", "", List.of("syd", "SIN", "lhr", "SIN")),
                StationScope.of(List.of("SIN")));

        assertThat(inserted.get().routeStations()).containsExactly("SYD", "SIN", "LHR");
    }

    @Test
    void rejectsARouteThatOmitsTheOriginOrDestination() {
        assertThatThrownBy(() -> service.create(
                request("QF", "1", "SYD", "LHR", "Australia/Sydney", "", List.of("SYD", "SIN")), ALL))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("must include originAirport and destinationAirport");
    }

    @Test
    void forbidsCreatingAFlightForAnotherStation() {
        assertThatThrownBy(() -> service.create(
                request("QF", "11", "SYD", "MEL", "Australia/Sydney", "", null),
                StationScope.of(List.of("AKL"))))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.status()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void forbidsUpdatingAFlightOutsideTheCallersStations() {
        var request = new UpdateFlightRequest(
                null, null, null, null, null, null, null,
                null, null, null, null, null, null,
                "BOARDING", null, null, 3L);

        assertThatThrownBy(() -> service.update(
                UUID.randomUUID(), request, StationScope.of(List.of("AKL"))))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.status()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(updated.get()).isNull();
    }

    @Test
    void rejectsAnInvalidTimezoneWithA400() {
        assertThatThrownBy(() -> service.create(
                request("QF", "11", "SYD", "MEL", "Not/A Zone!", "", null), ALL))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getMessage()).contains("originTimezone");
                });
    }

    @Test
    void rejectsIdenticalOriginAndDestination() {
        assertThatThrownBy(() -> service.create(
                request("QF", "11", "SYD", "syd", "Australia/Sydney", "", null), ALL))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("must be different");
    }

    @Test
    void updatesOnlySuppliedFieldsAndReportsVersionConflicts() {
        var request = new UpdateFlightRequest(
                null, null, null, null, null, null, null,
                null, null, null, null, null, null,
                "boarding", " 22 ", null, 3L);

        assertThatThrownBy(() -> service.update(UUID.randomUUID(), request, ALL))
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
                null, null, null, 1L);

        assertThatThrownBy(() -> service.update(UUID.randomUUID(), request, ALL))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("At least one");
    }

    private static CreateFlightRequest request(
            String carrier,
            String number,
            String origin,
            String destination,
            String originTimezone,
            String gate,
            List<String> routeStations) {
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
                gate,
                routeStations);
    }

    private static Flight existingFlight() {
        return new Flight(
                UUID.randomUUID(), "QF", "11", LocalDate.parse("2026-10-05"),
                "SYD", "MEL", List.of("SYD", "MEL"),
                "Australia/Sydney", "Australia/Melbourne",
                Instant.parse("2026-10-05T01:00:00Z"), null, null,
                Instant.parse("2026-10-05T02:30:00Z"), null, null,
                "SCHEDULED", "12", 3, Instant.now(), Instant.now());
    }
}

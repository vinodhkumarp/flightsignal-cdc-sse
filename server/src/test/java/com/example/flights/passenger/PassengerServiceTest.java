package com.example.flights.passenger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.example.flights.api.ApiException;
import com.example.flights.security.StationScope;

class PassengerServiceTest {

    @Test
    void normalizesFlightAndPassengerFiltersBeforeSearching() {
        var captured = new AtomicReference<PassengerSearchCriteria>();
        var repository = new PassengerRepository(null) {
            @Override
            public List<Passenger> search(PassengerSearchCriteria criteria, StationScope scope) {
                captured.set(criteria);
                return List.of();
            }
        };
        var service = new PassengerService(repository);

        var result = service.search(
                " aa112 ",
                LocalDate.parse("2026-10-02"),
                "syd",
                "lax",
                "amelia nguyen",
                "ab12cd",
                StationScope.allStations());

        assertThat(result.count()).isZero();
        assertThat(captured.get()).isEqualTo(new PassengerSearchCriteria(
                "AA112",
                LocalDate.parse("2026-10-02"),
                "SYD",
                "LAX",
                "AMELIA NGUYEN",
                "AB12CD"));
    }

    @Test
    void requiresAFlightNumberAndTravelDate() {
        var service = new PassengerService(new PassengerRepository(null));

        assertThatThrownBy(() -> service.search(
                " ",
                null,
                null,
                null,
                null,
                null,
                StationScope.allStations()))
                .isInstanceOf(ApiException.class)
                .hasMessage("flightNumber is required.");
    }
}

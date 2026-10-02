package com.example.flights.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.example.flights.api.ApiException;

class StationScopeTest {

    private final StationScope sydneyAndSingapore = StationScope.of(List.of("SYD", "SIN"));

    @Test
    void permitsEventsThatTouchAnyOfItsStations() {
        assertThat(sydneyAndSingapore.permits(List.of("LHR", "SIN"))).isTrue();
        assertThat(sydneyAndSingapore.permits(List.of("AKL", "MEL"))).isFalse();
        assertThat(sydneyAndSingapore.permits(List.of())).isFalse();
        assertThat(StationScope.allStations().permits(List.of("AKL"))).isTrue();
        assertThat(StationScope.none().permits(List.of("SYD"))).isFalse();
    }

    @Test
    void narrowsToARequestedSubset() {
        var narrowed = sydneyAndSingapore.narrowTo(" sin ");

        assertThat(narrowed.all()).isFalse();
        assertThat(narrowed.stations()).containsExactly("SIN");
        assertThat(sydneyAndSingapore.narrowTo(null)).isSameAs(sydneyAndSingapore);
        assertThat(StationScope.allStations().narrowTo("AKL").stations()).containsExactly("AKL");
    }

    @Test
    void refusesToNarrowToAStationOutsideTheScope() {
        assertThatThrownBy(() -> sydneyAndSingapore.narrowTo("SIN,LHR"))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(exception.getMessage()).contains("LHR").doesNotContain("SIN");
                });
    }

    @Test
    void rejectsMalformedStationCodes() {
        assertThatThrownBy(() -> sydneyAndSingapore.narrowTo("SYDNEY"))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void producesASortedSqlList() {
        assertThat(sydneyAndSingapore.sqlList()).isEqualTo("SIN,SYD");
    }
}

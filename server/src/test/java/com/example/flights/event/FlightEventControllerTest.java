package com.example.flights.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.OptionalLong;

import org.junit.jupiter.api.Test;

class FlightEventControllerTest {

    @Test
    void hasNoCursorWithoutHeaderOrQuery() {
        assertThat(FlightEventController.cursor(null, null)).isEmpty();
        assertThat(FlightEventController.cursor(" ", null)).isEmpty();
    }

    @Test
    void usesTheLargerOfHeaderAndQuery() {
        assertThat(FlightEventController.cursor("42", 40L)).isEqualTo(OptionalLong.of(42));
        assertThat(FlightEventController.cursor("42", 50L)).isEqualTo(OptionalLong.of(50));
    }

    @Test
    void acceptsZeroAsAnExplicitCursor() {
        assertThat(FlightEventController.cursor(null, 0L)).isEqualTo(OptionalLong.of(0));
    }

    @Test
    void ignoresInvalidOrNegativeValues() {
        assertThat(FlightEventController.cursor("abc", null)).isEmpty();
        assertThat(FlightEventController.cursor("-5", -1L)).isEmpty();
        assertThat(FlightEventController.cursor("abc", 7L)).isEqualTo(OptionalLong.of(7));
    }
}

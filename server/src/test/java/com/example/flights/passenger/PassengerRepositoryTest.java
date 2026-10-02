package com.example.flights.passenger;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PassengerRepositoryTest {

    @Test
    void splitsAFlightCodeIntoTwoAndThreeCharacterCarrierCandidates() {
        assertThat(PassengerRepository.flightKeys("QF11"))
                .containsExactly(
                        new Object[] {"QF", "11"},
                        new Object[] {"QF1", "1"});
    }

    @Test
    void usesOnlyTheTwoCharacterSplitForShortCodes() {
        assertThat(PassengerRepository.flightKeys("QF1"))
                .containsExactly(new Object[] {"QF", "1"});
    }
}

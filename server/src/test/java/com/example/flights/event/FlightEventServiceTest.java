package com.example.flights.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Test;

import com.example.flights.config.SseProperties;

class FlightEventServiceTest {

    private static final FlightEventClassifier CLASSIFIER = new FlightEventClassifier(null) {
        @Override
        public FlightEvent classify(FlightChangeRow row) {
            return event(row);
        }
    };

    @Test
    void returnsAKeysetPageAndUsesTheLastReturnedEventAsCursor() {
        var repository = new FlightEventRepository(null) {
            @Override
            public List<FlightChangeRow> findPage(Long before, int limit) {
                return List.of(row(30), row(29), row(28));
            }
        };
        var service = new FlightEventService(repository, CLASSIFIER, properties(500, 20));

        var page = service.findPage(null, 2);

        assertThat(page.events())
                .extracting(FlightEvent::eventId)
                .containsExactly("30", "29");
        assertThat(page.nextCursor()).isEqualTo("29");
        assertThat(page.hasMore()).isTrue();
    }

    @Test
    void replaysNewerEventsPlusASafetyWindowBelowTheCursor() {
        var repository = new RangeRepository(1, 60);
        var service = new FlightEventService(repository, CLASSIFIER, properties(500, 5));

        var replay = service.replayAfter(50);

        assertThat(repository.lastAfter).isEqualTo(45);
        assertThat(replay.resetRequired()).isFalse();
        assertThat(replay.events())
                .extracting(FlightEvent::eventId)
                .containsExactly("46", "47", "48", "49", "50",
                        "51", "52", "53", "54", "55", "56", "57", "58", "59", "60");
    }

    @Test
    void asksTheClientToResetWhenTooManyEventsWereMissed() {
        var service = new FlightEventService(
                new RangeRepository(1, 100),
                CLASSIFIER,
                properties(10, 5));

        var replay = service.replayAfter(50);

        assertThat(replay.resetRequired()).isTrue();
        assertThat(replay.events()).isEmpty();
    }

    @Test
    void replaysExactlyTheLimitWithoutReset() {
        var service = new FlightEventService(
                new RangeRepository(1, 60),
                CLASSIFIER,
                properties(10, 0));

        var replay = service.replayAfter(50);

        assertThat(replay.resetRequired()).isFalse();
        assertThat(replay.events()).hasSize(10);
    }

    private static SseProperties properties(int replayLimit, int safetyWindow) {
        return new SseProperties(
                Duration.ofSeconds(20),
                Duration.ofMinutes(30),
                Duration.ofSeconds(3),
                500,
                256,
                replayLimit,
                safetyWindow);
    }

    /** Repository holding events with IDs {@code first..last}. */
    private static final class RangeRepository extends FlightEventRepository {

        private final long first;
        private final long last;
        private long lastAfter = -1;

        RangeRepository(long first, long last) {
            super(null);
            this.first = first;
            this.last = last;
        }

        @Override
        public List<FlightChangeRow> findAfter(long eventId, int limit) {
            lastAfter = eventId;
            return LongStream.rangeClosed(Math.max(first, eventId + 1), last)
                    .limit(limit)
                    .mapToObj(FlightEventServiceTest::row)
                    .toList();
        }
    }

    private static FlightChangeRow row(long eventId) {
        return new FlightChangeRow(
                eventId,
                "UPDATE",
                UUID.randomUUID(),
                "{}",
                "{}",
                Instant.parse("2026-10-02T01:00:00Z"));
    }

    private static FlightEvent event(FlightChangeRow row) {
        return new FlightEvent(
                Long.toString(row.eventId()),
                row.occurredAt(),
                row.operation(),
                null,
                null,
                List.of(),
                "flight.updated",
                "info",
                "Updated",
                null);
    }
}

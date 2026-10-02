package com.example.flights.event;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.example.flights.config.SseProperties;
import com.example.flights.security.StationScope;

@Service
public class FlightEventService {

    private final FlightEventRepository repository;
    private final FlightEventClassifier classifier;
    private final SseProperties sseProperties;

    public FlightEventService(
            FlightEventRepository repository,
            FlightEventClassifier classifier,
            SseProperties sseProperties) {
        this.repository = repository;
        this.classifier = classifier;
        this.sseProperties = sseProperties;
    }

    public Optional<FlightEvent> findById(long eventId) {
        return repository.findById(eventId).map(classifier::classify);
    }

    public List<FlightEvent> findAfter(long eventId, int limit) {
        return repository.findAfter(eventId, limit)
                .stream()
                .map(classifier::classify)
                .toList();
    }

    public long latestEventId() {
        return repository.findLatestId();
    }

    public List<Long> eventIdsAfter(long eventId, int limit) {
        return repository.findIdsAfter(eventId, limit);
    }

    public FlightEventPage findPage(Long before, int limit, StationScope scope) {
        var rows = repository.findPage(before, limit + 1, scope);
        var hasMore = rows.size() > limit;
        var pageRows = hasMore ? rows.subList(0, limit) : rows;
        var events = pageRows.stream()
                .map(classifier::classify)
                .toList();
        var nextCursor = hasMore && !pageRows.isEmpty()
                ? Long.toString(pageRows.getLast().eventId())
                : null;

        return new FlightEventPage(events, nextCursor, hasMore);
    }

    /**
     * Builds the replay for a client whose last received event is
     * {@code cursor}.
     *
     * <p>Event IDs come from an identity column, so a transaction that took a
     * lower ID can commit after one with a higher ID. To avoid losing such an
     * event across a reconnect, the replay also re-sends a small safety window
     * of IDs at or below the cursor; clients de-duplicate by event ID.
     *
     * <p>If more than {@code replayLimit} events are newer than the cursor the
     * client is asked to reset (reload its history) rather than receiving an
     * unbounded burst.
     *
     * <p>Only events visible to {@code scope} are considered.
     */
    public EventReplay replayAfter(long cursor, StationScope scope) {
        var window = Math.max(0, sseProperties.replaySafetyWindow());
        var limit = Math.max(1, sseProperties.replayLimit());
        var from = Math.max(0, cursor - window);
        var rows = repository.findAfter(from, window + limit + 1, scope);
        var newer = rows.stream()
                .filter(row -> row.eventId() > cursor)
                .count();

        if (newer > limit) {
            return EventReplay.reset();
        }

        return new EventReplay(
                rows.stream().map(classifier::classify).toList(),
                false);
    }
}

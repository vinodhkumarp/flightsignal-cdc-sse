package com.example.flights.event;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FlightEventRepository {

    private static final String SELECT_EVENT = """
            SELECT event_id, operation, flight_id, old_row::text, new_row::text,
                   occurred_at
            FROM app_internal.flight_change_event
            """;

    private static final RowMapper<FlightChangeRow> EVENT_MAPPER =
            FlightEventRepository::mapEvent;

    private final NamedParameterJdbcTemplate jdbc;

    public FlightEventRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<FlightChangeRow> findById(long eventId) {
        return jdbc.query(
                        SELECT_EVENT + " WHERE event_id = :eventId",
                        Map.of("eventId", eventId),
                        EVENT_MAPPER)
                .stream()
                .findFirst();
    }

    public List<FlightChangeRow> findAfter(long eventId, int limit) {
        return jdbc.query(
                SELECT_EVENT
                        + " WHERE event_id > :eventId"
                        + " ORDER BY event_id ASC LIMIT :limit",
                Map.of("eventId", eventId, "limit", limit),
                EVENT_MAPPER);
    }

    public List<Long> findIdsAfter(long eventId, int limit) {
        return jdbc.queryForList(
                "SELECT event_id FROM app_internal.flight_change_event"
                        + " WHERE event_id > :eventId ORDER BY event_id ASC LIMIT :limit",
                Map.of("eventId", eventId, "limit", limit),
                Long.class);
    }

    public long findLatestId() {
        var latest = jdbc.getJdbcTemplate().queryForObject(
                "SELECT max(event_id) FROM app_internal.flight_change_event",
                Long.class);
        return latest == null ? 0 : latest;
    }

    /**
     * Deletes one batch of events older than {@code retention}.
     *
     * @return number of rows deleted
     */
    public long purgeOlderThan(Duration retention, int batchSize) {
        var deleted = jdbc.queryForObject(
                "SELECT app_internal.purge_flight_change_events("
                        + "make_interval(secs => :seconds), :batchSize)",
                Map.of(
                        "seconds", retention.toSeconds(),
                        "batchSize", batchSize),
                Long.class);
        return deleted == null ? 0 : deleted;
    }

    public List<FlightChangeRow> findRecent(int limit) {
        return jdbc.query(
                SELECT_EVENT + " ORDER BY event_id DESC LIMIT :limit",
                Map.of("limit", limit),
                EVENT_MAPPER);
    }

    public List<FlightChangeRow> findPage(Long before, int limit) {
        if (before == null) {
            return findRecent(limit);
        }

        return jdbc.query(
                SELECT_EVENT
                        + " WHERE event_id < :before"
                        + " ORDER BY event_id DESC LIMIT :limit",
                Map.of("before", before, "limit", limit),
                EVENT_MAPPER);
    }

    private static FlightChangeRow mapEvent(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new FlightChangeRow(
                resultSet.getLong("event_id"),
                resultSet.getString("operation"),
                resultSet.getObject("flight_id", UUID.class),
                resultSet.getString("old_row"),
                resultSet.getString("new_row"),
                resultSet.getObject("occurred_at", OffsetDateTime.class)
                        .toInstant());
    }
}

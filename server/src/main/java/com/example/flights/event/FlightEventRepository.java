package com.example.flights.event;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.flights.security.StationScope;

/**
 * Reads the durable change log.
 *
 * <p>Methods taking a {@link StationScope} return only events whose
 * {@code stations} overlap the caller's stations (GIN-indexed {@code &&}).
 * Unscoped methods are for the server-side listener, which must see every
 * event before deciding who receives it.
 */
@Repository
public class FlightEventRepository {

    private static final String SELECT_EVENT = """
            SELECT event_id, operation, flight_id, old_row::text, new_row::text,
                   occurred_at, stations
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

    /** Every event after {@code eventId}, ascending (listener catch-up). */
    public List<FlightChangeRow> findAfter(long eventId, int limit) {
        return findAfter(eventId, limit, StationScope.allStations());
    }

    /** Events visible to {@code scope} after {@code eventId}, ascending (SSE replay). */
    public List<FlightChangeRow> findAfter(long eventId, int limit, StationScope scope) {
        var conditions = new ArrayList<String>();
        var parameters = new MapSqlParameterSource()
                .addValue("eventId", eventId)
                .addValue("limit", limit);
        conditions.add("event_id > :eventId");
        addScope(scope, conditions, parameters);

        return jdbc.query(
                SELECT_EVENT + where(conditions) + " ORDER BY event_id ASC LIMIT :limit",
                parameters,
                EVENT_MAPPER);
    }

    /** Newest-first keyset page of events visible to {@code scope}. */
    public List<FlightChangeRow> findPage(Long before, int limit, StationScope scope) {
        var conditions = new ArrayList<String>();
        var parameters = new MapSqlParameterSource().addValue("limit", limit);
        if (before != null) {
            conditions.add("event_id < :before");
            parameters.addValue("before", before);
        }
        addScope(scope, conditions, parameters);

        return jdbc.query(
                SELECT_EVENT + where(conditions) + " ORDER BY event_id DESC LIMIT :limit",
                parameters,
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

    private static void addScope(
            StationScope scope,
            List<String> conditions,
            MapSqlParameterSource parameters) {
        if (scope.all()) {
            return;
        }
        if (scope.isEmpty()) {
            conditions.add("false");
            return;
        }
        conditions.add("stations && string_to_array(:scopeStations, ',')");
        parameters.addValue("scopeStations", scope.sqlList());
    }

    private static String where(List<String> conditions) {
        return conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
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
                        .toInstant(),
                textArray(resultSet.getArray("stations")));
    }

    static List<String> textArray(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        try {
            return Arrays.asList((String[]) array.getArray());
        } finally {
            array.free();
        }
    }
}

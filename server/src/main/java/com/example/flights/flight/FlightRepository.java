package com.example.flights.flight;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.flights.security.StationScope;

@Repository
public class FlightRepository {

    private static final String SELECT_COLUMNS = """
            SELECT flight_id, carrier_code, flight_number, service_date,
                   origin_airport, destination_airport, route_stations,
                   origin_timezone, destination_timezone,
                   scheduled_departure_utc, estimated_departure_utc,
                   actual_departure_utc, scheduled_arrival_utc,
                   estimated_arrival_utc, actual_arrival_utc,
                   status, gate, version, created_at, updated_at
            FROM public.flight_instance
            """;

    private static final RowMapper<Flight> FLIGHT_MAPPER =
            FlightRepository::mapFlight;

    private static final Map<String, String> UPDATE_COLUMNS = Map.ofEntries(
            Map.entry("carrierCode", "carrier_code"),
            Map.entry("flightNumber", "flight_number"),
            Map.entry("serviceDate", "service_date"),
            Map.entry("originAirport", "origin_airport"),
            Map.entry("destinationAirport", "destination_airport"),
            Map.entry("originTimezone", "origin_timezone"),
            Map.entry("destinationTimezone", "destination_timezone"),
            Map.entry("scheduledDepartureUtc", "scheduled_departure_utc"),
            Map.entry("estimatedDepartureUtc", "estimated_departure_utc"),
            Map.entry("actualDepartureUtc", "actual_departure_utc"),
            Map.entry("scheduledArrivalUtc", "scheduled_arrival_utc"),
            Map.entry("estimatedArrivalUtc", "estimated_arrival_utc"),
            Map.entry("actualArrivalUtc", "actual_arrival_utc"),
            Map.entry("status", "status"),
            Map.entry("gate", "gate"),
            Map.entry("routeStations", "route_stations"));

    private final NamedParameterJdbcTemplate jdbc;

    public FlightRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Flights touching at least one of the caller's stations. */
    public List<Flight> findAll(StationScope scope) {
        var parameters = new MapSqlParameterSource();
        var where = "";
        if (!scope.all()) {
            where = " WHERE route_stations && string_to_array(:scopeStations, ',')";
            parameters.addValue("scopeStations", scope.sqlList());
        }
        return jdbc.query(
                SELECT_COLUMNS + where
                        + " ORDER BY scheduled_departure_utc ASC, carrier_code, flight_number",
                parameters,
                FLIGHT_MAPPER);
    }

    public Optional<Flight> findById(UUID flightId) {
        return jdbc.query(
                        SELECT_COLUMNS + " WHERE flight_id = :flightId",
                        Map.of("flightId", flightId),
                        FLIGHT_MAPPER)
                .stream()
                .findFirst();
    }

    public Flight insert(CreateFlightRequest flight) {
        var sql = """
                INSERT INTO public.flight_instance (
                    carrier_code, flight_number, service_date,
                    origin_airport, destination_airport, route_stations,
                    origin_timezone, destination_timezone,
                    scheduled_departure_utc, estimated_departure_utc,
                    actual_departure_utc, scheduled_arrival_utc,
                    estimated_arrival_utc, actual_arrival_utc,
                    status, gate
                ) VALUES (
                    :carrierCode, :flightNumber, :serviceDate,
                    :originAirport, :destinationAirport,
                    string_to_array(:routeStations, ','),
                    :originTimezone, :destinationTimezone,
                    :scheduledDepartureUtc, :estimatedDepartureUtc,
                    :actualDepartureUtc, :scheduledArrivalUtc,
                    :estimatedArrivalUtc, :actualArrivalUtc,
                    :status, :gate
                )
                RETURNING *
                """;

        var parameters = new MapSqlParameterSource()
                .addValue("carrierCode", flight.carrierCode())
                .addValue("flightNumber", flight.flightNumber())
                .addValue("serviceDate", flight.serviceDate())
                .addValue("originAirport", flight.originAirport())
                .addValue("destinationAirport", flight.destinationAirport())
                .addValue("routeStations", joinStations(flight.routeStations()))
                .addValue("originTimezone", flight.originTimezone())
                .addValue("destinationTimezone", flight.destinationTimezone())
                .addValue("scheduledDepartureUtc", jdbcValue(
                        flight.scheduledDepartureUtc()))
                .addValue("estimatedDepartureUtc", jdbcValue(
                        flight.estimatedDepartureUtc()))
                .addValue("actualDepartureUtc", jdbcValue(
                        flight.actualDepartureUtc()))
                .addValue("scheduledArrivalUtc", jdbcValue(
                        flight.scheduledArrivalUtc()))
                .addValue("estimatedArrivalUtc", jdbcValue(
                        flight.estimatedArrivalUtc()))
                .addValue("actualArrivalUtc", jdbcValue(
                        flight.actualArrivalUtc()))
                .addValue("status", flight.status())
                .addValue("gate", flight.gate());

        return jdbc.queryForObject(sql, parameters, FLIGHT_MAPPER);
    }

    public Optional<Flight> update(
            UUID flightId,
            long expectedVersion,
            LinkedHashMap<String, Object> changes) {
        var assignments = new ArrayList<String>();
        var parameters = new MapSqlParameterSource()
                .addValue("flightId", flightId)
                .addValue("expectedVersion", expectedVersion);

        changes.forEach((property, value) -> {
            var column = UPDATE_COLUMNS.get(property);
            if ("routeStations".equals(property)) {
                assignments.add(column + " = string_to_array(:routeStations, ',')");
                parameters.addValue(property, joinStations(asStations(value)));
            } else {
                assignments.add(column + " = :" + property);
                parameters.addValue(property, jdbcValue(value));
            }
        });

        var sql = """
                UPDATE public.flight_instance
                SET %s
                WHERE flight_id = :flightId
                  AND version = :expectedVersion
                RETURNING *
                """.formatted(String.join(", ", assignments));

        return jdbc.query(sql, parameters, FLIGHT_MAPPER)
                .stream()
                .findFirst();
    }

    public Optional<Flight> delete(UUID flightId) {
        return jdbc.query(
                        "DELETE FROM public.flight_instance "
                                + "WHERE flight_id = :flightId RETURNING *",
                        Map.of("flightId", flightId),
                        FLIGHT_MAPPER)
                .stream()
                .findFirst();
    }

    private static Flight mapFlight(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new Flight(
                resultSet.getObject("flight_id", UUID.class),
                resultSet.getString("carrier_code"),
                resultSet.getString("flight_number"),
                resultSet.getObject("service_date", java.time.LocalDate.class),
                resultSet.getString("origin_airport").trim(),
                resultSet.getString("destination_airport").trim(),
                textArray(resultSet.getArray("route_stations")),
                resultSet.getString("origin_timezone"),
                resultSet.getString("destination_timezone"),
                instant(resultSet, "scheduled_departure_utc"),
                instant(resultSet, "estimated_departure_utc"),
                instant(resultSet, "actual_departure_utc"),
                instant(resultSet, "scheduled_arrival_utc"),
                instant(resultSet, "estimated_arrival_utc"),
                instant(resultSet, "actual_arrival_utc"),
                resultSet.getString("status"),
                resultSet.getString("gate"),
                resultSet.getLong("version"),
                instant(resultSet, "created_at"),
                instant(resultSet, "updated_at"));
    }

    private static java.time.Instant instant(ResultSet resultSet, String column)
            throws SQLException {
        var value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    /** Station codes are validated [A-Z]{3}, so a comma join is safe. */
    private static String joinStations(List<String> stations) {
        return stations == null || stations.isEmpty() ? null : String.join(",", stations);
    }

    private static List<String> asStations(Object value) {
        var stations = new ArrayList<String>();
        if (value instanceof List<?> list) {
            list.forEach(item -> stations.add(String.valueOf(item)));
        }
        return stations;
    }

    private static List<String> textArray(java.sql.Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        try {
            return List.of((String[]) array.getArray());
        } finally {
            array.free();
        }
    }

    private static Object jdbcValue(Object value) {
        if (value instanceof java.time.Instant instant) {
            return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
        }
        return value;
    }
}

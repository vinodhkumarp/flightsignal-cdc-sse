package com.example.flights.passenger;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PassengerRepository {

    private static final String SELECT_PASSENGERS = """
            SELECT passenger_id, flight_id, carrier_code, flight_number,
                   service_date, origin_airport, destination_airport,
                   manifest_sequence, booking_reference,
                   given_name, family_name, email, phone_number,
                   preferred_contact_method, seat_number, cabin_class,
                   loyalty_tier, special_assistance, contact_status,
                   created_at, updated_at
            FROM public.flight_passenger
            """;

    private static final RowMapper<Passenger> PASSENGER_MAPPER =
            PassengerRepository::mapPassenger;

    private final NamedParameterJdbcTemplate jdbc;

    public PassengerRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Passenger> search(PassengerSearchCriteria criteria) {
        // Compare (carrier_code, flight_number) pairs instead of a concatenated
        // expression so the flight search index can be used.
        var sql = new StringBuilder(SELECT_PASSENGERS)
                .append(" WHERE (carrier_code, flight_number) IN (:flightKeys)")
                .append(" AND service_date = :travelDate");
        var parameters = new MapSqlParameterSource()
                .addValue("flightKeys", flightKeys(criteria.flightNumber()))
                .addValue("travelDate", criteria.travelDate());

        if (criteria.originAirport() != null) {
            sql.append(" AND origin_airport = :originAirport");
            parameters.addValue("originAirport", criteria.originAirport());
        }
        if (criteria.destinationAirport() != null) {
            sql.append(" AND destination_airport = :destinationAirport");
            parameters.addValue(
                    "destinationAirport",
                    criteria.destinationAirport());
        }
        if (criteria.passengerName() != null) {
            sql.append(" AND position(:passengerName in upper(")
                    .append("given_name || ' ' || family_name)) > 0");
            parameters.addValue("passengerName", criteria.passengerName());
        }
        if (criteria.bookingReference() != null) {
            sql.append(" AND position(:bookingReference in ")
                    .append("upper(booking_reference)) > 0");
            parameters.addValue(
                    "bookingReference",
                    criteria.bookingReference());
        }

        sql.append(" ORDER BY family_name, given_name, manifest_sequence LIMIT 200");
        return jdbc.query(sql.toString(), parameters, PASSENGER_MAPPER);
    }

    /**
     * Splits a flight code such as {@code QF11} into the possible
     * (carrier, number) pairs: carriers are two characters (IATA) or three
     * (ICAO), and the remainder is the flight number.
     */
    static List<Object[]> flightKeys(String flightCode) {
        var keys = new ArrayList<Object[]>(2);
        for (var carrierLength = 2; carrierLength <= 3; carrierLength++) {
            if (flightCode.length() > carrierLength) {
                keys.add(new Object[] {
                    flightCode.substring(0, carrierLength),
                    flightCode.substring(carrierLength)
                });
            }
        }
        return keys;
    }

    private static Passenger mapPassenger(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new Passenger(
                resultSet.getObject("passenger_id", java.util.UUID.class),
                resultSet.getObject("flight_id", java.util.UUID.class),
                resultSet.getString("carrier_code"),
                resultSet.getString("flight_number"),
                resultSet.getObject("service_date", java.time.LocalDate.class),
                resultSet.getString("origin_airport").trim(),
                resultSet.getString("destination_airport").trim(),
                resultSet.getInt("manifest_sequence"),
                resultSet.getString("booking_reference"),
                resultSet.getString("given_name"),
                resultSet.getString("family_name"),
                resultSet.getString("email"),
                resultSet.getString("phone_number"),
                resultSet.getString("preferred_contact_method"),
                resultSet.getString("seat_number"),
                resultSet.getString("cabin_class"),
                resultSet.getString("loyalty_tier"),
                resultSet.getBoolean("special_assistance"),
                resultSet.getString("contact_status"),
                instant(resultSet, "created_at"),
                instant(resultSet, "updated_at"));
    }

    private static java.time.Instant instant(ResultSet resultSet, String column)
            throws SQLException {
        var value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}

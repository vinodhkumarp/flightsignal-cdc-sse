package com.example.flights.event;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.example.flights.flight.Flight;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class FlightJsonMapper {

    private final JsonMapper jsonMapper;

    public FlightJsonMapper(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public Flight readFlight(String json) {
        if (json == null) {
            return null;
        }

        var node = jsonMapper.readTree(json);
        return new Flight(
                UUID.fromString(text(node, "flight_id")),
                text(node, "carrier_code"),
                text(node, "flight_number"),
                LocalDate.parse(text(node, "service_date")),
                text(node, "origin_airport").trim(),
                text(node, "destination_airport").trim(),
                stations(node, "route_stations"),
                text(node, "origin_timezone"),
                text(node, "destination_timezone"),
                instant(node, "scheduled_departure_utc"),
                instant(node, "estimated_departure_utc"),
                instant(node, "actual_departure_utc"),
                instant(node, "scheduled_arrival_utc"),
                instant(node, "estimated_arrival_utc"),
                instant(node, "actual_arrival_utc"),
                text(node, "status"),
                text(node, "gate"),
                node.path("version").asLong(),
                instant(node, "created_at"),
                instant(node, "updated_at"));
    }

    private static String text(JsonNode node, String field) {
        var value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asString();
    }

    private static List<String> stations(JsonNode node, String field) {
        var value = node.path(field);
        if (!value.isArray()) {
            return List.of();
        }
        var stations = new ArrayList<String>();
        for (var item : value) {
            if (!item.isNull()) {
                stations.add(item.asString().trim());
            }
        }
        return stations;
    }

    private static Instant instant(JsonNode node, String field) {
        var value = text(node, field);
        return value == null ? null : Instant.parse(value);
    }
}

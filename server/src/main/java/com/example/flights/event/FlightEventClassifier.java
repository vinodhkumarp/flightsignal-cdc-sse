package com.example.flights.event;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.example.flights.flight.Flight;

@Component
public class FlightEventClassifier {

    private final FlightJsonMapper flightJsonMapper;

    public FlightEventClassifier(FlightJsonMapper flightJsonMapper) {
        this.flightJsonMapper = flightJsonMapper;
    }

    public FlightEvent classify(FlightChangeRow databaseEvent) {
        var previous = flightJsonMapper.readFlight(databaseEvent.oldRow());
        var current = flightJsonMapper.readFlight(databaseEvent.newRow());
        var flight = current == null ? previous : current;
        var label = flight.label();

        if ("INSERT".equals(databaseEvent.operation())) {
            return event(
                    databaseEvent,
                    flight,
                    null,
                    List.of(),
                    "flight.added",
                    "info",
                    "Flight " + label + " from " + flight.originAirport()
                            + " to " + flight.destinationAirport()
                            + via(flight) + " has been added.",
                    null);
        }

        if ("DELETE".equals(databaseEvent.operation())) {
            return event(
                    databaseEvent,
                    flight,
                    previous,
                    List.of(),
                    "flight.removed",
                    "warning",
                    "Flight " + label + " was removed from the schedule.",
                    null);
        }

        var changes = describeChanges(previous, current);
        var primary = classifyUpdate(databaseEvent, previous, current, changes, label);
        return withSecondaryChanges(primary, previous, current);
    }

    private FlightEvent classifyUpdate(
            FlightChangeRow databaseEvent,
            Flight previous,
            Flight current,
            List<FieldChange> changes,
            String label) {
        if ("CANCELLED".equals(previous.status())
                && !"CANCELLED".equals(current.status())) {
            return event(
                    databaseEvent,
                    current,
                    previous,
                    changes,
                    "flight.reinstated",
                    "warning",
                    "Flight " + label + " has been reinstated and is now "
                            + statusText(current.status()) + ".",
                    null);
        }

        if (!"CANCELLED".equals(previous.status())
                && "CANCELLED".equals(current.status())) {
            return event(
                    databaseEvent,
                    current,
                    previous,
                    changes,
                    "flight.cancelled",
                    "critical",
                    "Flight " + label + " has been cancelled.",
                    null);
        }

        var previousEffectiveDeparture = effectiveDeparture(previous);
        var currentEffectiveDeparture = effectiveDeparture(current);
        var departureMovedBy = minutesBetween(
                previousEffectiveDeparture,
                currentEffectiveDeparture);
        var totalDelayMinutes = Math.max(
                0,
                minutesBetween(
                        current.scheduledDepartureUtc(),
                        currentEffectiveDeparture));
        var estimateChanged = !Objects.equals(
                previous.estimatedDepartureUtc(),
                current.estimatedDepartureUtc());

        if (estimateChanged && departureMovedBy > 0) {
            return event(
                    databaseEvent,
                    current,
                    previous,
                    changes,
                    "flight.departure.delayed",
                    "warning",
                    "Departure for " + label + " has been delayed by "
                            + departureMovedBy + " minutes.",
                    new DelayDetails(
                            departureMovedBy,
                            totalDelayMinutes,
                            previousEffectiveDeparture,
                            currentEffectiveDeparture));
        }

        if (estimateChanged && departureMovedBy < 0) {
            return event(
                    databaseEvent,
                    current,
                    previous,
                    changes,
                    "flight.departure.revised",
                    "info",
                    "Departure time for " + label + " has moved "
                            + Math.abs(departureMovedBy) + " minutes earlier.",
                    null);
        }

        if (!Objects.equals(
                previous.scheduledDepartureUtc(),
                current.scheduledDepartureUtc())) {
            return event(
                    databaseEvent,
                    current,
                    previous,
                    changes,
                    "flight.schedule.changed",
                    "warning",
                    "Scheduled departure for " + label + " has changed.",
                    null);
        }

        if (!Objects.equals(previous.routeStations(), current.routeStations())) {
            return event(
                    databaseEvent,
                    current,
                    previous,
                    changes,
                    "flight.route.changed",
                    "warning",
                    "Route for " + label + " changed to " + route(current)
                            + " (was " + route(previous) + ").",
                    null);
        }

        if (!Objects.equals(previous.gate(), current.gate())) {
            return event(
                    databaseEvent,
                    current,
                    previous,
                    changes,
                    "flight.gate.changed",
                    "info",
                    "Gate for " + label + " changed from "
                            + display(previous.gate()) + " to "
                            + display(current.gate()) + ".",
                    null);
        }

        if (!Objects.equals(previous.status(), current.status())) {
            return event(
                    databaseEvent,
                    current,
                    previous,
                    changes,
                    "flight.status.changed",
                    "info",
                    statusSentence(label, current.status()),
                    null);
        }

        return event(
                databaseEvent,
                current,
                previous,
                changes,
                "flight.updated",
                "info",
                "Flight details for " + label + " were updated.",
                null);
    }

    /**
     * One database update can change several things at once (for example a
     * delay together with a gate change). The event keeps its primary type,
     * and the message mentions the other operator-relevant changes so none
     * is silently lost.
     */
    private static FlightEvent withSecondaryChanges(
            FlightEvent primary,
            Flight previous,
            Flight current) {
        var notes = new ArrayList<String>();

        if (!"flight.gate.changed".equals(primary.type())
                && !Objects.equals(previous.gate(), current.gate())) {
            notes.add("Gate changed from " + display(previous.gate())
                    + " to " + display(current.gate()) + ".");
        }

        var statusIsPrimary = switch (primary.type()) {
            case "flight.status.changed", "flight.cancelled", "flight.reinstated" -> true;
            default -> false;
        };
        if (!statusIsPrimary
                && !Objects.equals(previous.status(), current.status())
                && !"DELAYED".equals(current.status())) {
            notes.add("Status is now " + statusText(current.status()) + ".");
        }

        var onward = onwardStations(primary.stations(), previous, current);
        if (!onward.isEmpty()) {
            notes.add("Onward connections at " + String.join(", ", onward) + " may be affected.");
        }

        if (notes.isEmpty()) {
            return primary;
        }

        return new FlightEvent(
                primary.eventId(),
                primary.occurredAt(),
                primary.operation(),
                primary.flight(),
                primary.previousFlight(),
                primary.changes(),
                primary.type(),
                primary.severity(),
                primary.message() + " " + String.join(" ", notes),
                primary.delay(),
                primary.stations());
    }

    /**
     * Stations the database added to the event beyond this leg's own route,
     * i.e. later legs of the same flight alerted because of a delay or
     * cancellation.
     */
    private static List<String> onwardStations(
            List<String> eventStations,
            Flight previous,
            Flight current) {
        var own = new HashSet<String>(previous.routeStations());
        own.addAll(current.routeStations());
        return eventStations.stream()
                .filter(station -> !own.contains(station))
                .sorted()
                .toList();
    }

    private static String route(Flight flight) {
        return String.join(" → ", flight.routeStations());
    }

    /** " via SIN" for multi-stop journeys, otherwise empty. */
    private static String via(Flight flight) {
        var route = flight.routeStations();
        if (route == null || route.size() <= 2) {
            return "";
        }
        return " via " + String.join(", ", route.subList(1, route.size() - 1));
    }

    private static String statusSentence(String label, String status) {
        return switch (status == null ? "" : status) {
            case "DEPARTED" -> "Flight " + label + " has departed.";
            case "ARRIVED" -> "Flight " + label + " has arrived.";
            case "SCHEDULED" -> "Flight " + label + " is back on schedule.";
            default -> "Flight " + label + " is now " + statusText(status) + ".";
        };
    }

    private static String statusText(String status) {
        return status == null ? "unknown" : status.toLowerCase(Locale.ROOT);
    }

    private static List<FieldChange> describeChanges(
            Flight previous,
            Flight current) {
        var changes = new ArrayList<FieldChange>();

        addChange(changes, "status", previous.status(), current.status());
        addChange(
                changes,
                "estimatedDepartureUtc",
                previous.estimatedDepartureUtc(),
                current.estimatedDepartureUtc());
        addChange(
                changes,
                "scheduledDepartureUtc",
                previous.scheduledDepartureUtc(),
                current.scheduledDepartureUtc());
        addChange(changes, "gate", previous.gate(), current.gate());
        addChange(changes, "routeStations", previous.routeStations(), current.routeStations());
        addChange(
                changes,
                "estimatedArrivalUtc",
                previous.estimatedArrivalUtc(),
                current.estimatedArrivalUtc());
        addChange(
                changes,
                "scheduledArrivalUtc",
                previous.scheduledArrivalUtc(),
                current.scheduledArrivalUtc());
        addChange(
                changes,
                "actualDepartureUtc",
                previous.actualDepartureUtc(),
                current.actualDepartureUtc());
        addChange(
                changes,
                "actualArrivalUtc",
                previous.actualArrivalUtc(),
                current.actualArrivalUtc());

        return List.copyOf(changes);
    }

    private static void addChange(
            List<FieldChange> changes,
            String field,
            Object previousValue,
            Object newValue) {
        if (!Objects.equals(previousValue, newValue)) {
            changes.add(new FieldChange(field, previousValue, newValue));
        }
    }

    private static FlightEvent event(
            FlightChangeRow row,
            Flight flight,
            Flight previous,
            List<FieldChange> changes,
            String type,
            String severity,
            String message,
            DelayDetails delay) {
        return new FlightEvent(
                Long.toString(row.eventId()),
                row.occurredAt(),
                row.operation(),
                flight,
                previous,
                changes,
                type,
                severity,
                message,
                delay,
                row.stations());
    }

    private static Instant effectiveDeparture(Flight flight) {
        return flight.estimatedDepartureUtc() == null
                ? flight.scheduledDepartureUtc()
                : flight.estimatedDepartureUtc();
    }

    private static long minutesBetween(Instant earlier, Instant later) {
        return Duration.between(earlier, later).toMinutes();
    }

    private static String display(String value) {
        return value == null || value.isBlank() ? "unassigned" : value;
    }
}


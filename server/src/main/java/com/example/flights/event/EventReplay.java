package com.example.flights.event;

import java.util.List;

/**
 * Events to send to a (re)connecting SSE client before live delivery starts.
 *
 * @param events        events to replay, oldest first
 * @param resetRequired {@code true} when the client missed more events than
 *                      the replay limit and must reload its history instead
 */
public record EventReplay(List<FlightEvent> events, boolean resetRequired) {

    public EventReplay {
        events = List.copyOf(events);
    }

    public static EventReplay none() {
        return new EventReplay(List.of(), false);
    }

    public static EventReplay reset() {
        return new EventReplay(List.of(), true);
    }
}

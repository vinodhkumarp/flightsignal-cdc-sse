package com.example.flights.ops;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import com.example.flights.event.PostgresNotificationListener;
import com.example.flights.event.SseHub;

/**
 * Reports whether real-time delivery works: DOWN while the PostgreSQL
 * LISTEN connection is reconnecting. Exposed as {@code flightListener} and
 * included in the readiness group (not liveness: restarting the process would
 * not fix an unreachable database).
 */
@Component("flightListener")
public class FlightListenerHealthIndicator implements HealthIndicator {

    private final PostgresNotificationListener listener;
    private final SseHub hub;

    public FlightListenerHealthIndicator(
            PostgresNotificationListener listener,
            SseHub hub) {
        this.listener = listener;
        this.hub = hub;
    }

    @Override
    public Health health() {
        var builder = listener.isConnected() ? Health.up() : Health.down();
        var lastHealthyAt = listener.lastHealthyAt();
        if (lastHealthyAt != null) {
            builder.withDetail("lastHealthyAt", lastHealthyAt.toString());
        }
        return builder
                .withDetail("channel", "flight_changes")
                .withDetail("sseClients", hub.size())
                .build();
    }
}

package com.example.flights.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.flights.config.SseProperties;
import com.example.flights.security.StationScope;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class SseHubTest {

    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
    }

    @Test
    void rejectsConnectionsBeyondTheLimit() {
        var hub = startedHub(2, 16);

        assertThat(hub.register(StationScope.allStations(), null)).isPresent();
        assertThat(hub.register(StationScope.allStations(), null)).isPresent();
        assertThat(hub.register(StationScope.allStations(), null)).isEmpty();
        assertThat(registry.counter("flightsignal.sse.clients.rejected").count()).isEqualTo(1);
    }

    @Test
    void rejectsConnectionsWhenNotRunning() {
        var hub = new SseHub(properties(10, 16), registry);

        assertThat(hub.register(StationScope.allStations(), null)).isEmpty();
    }

    @Test
    void disconnectsASlowConsumerWithoutAffectingOthers() {
        var hub = startedHub(10, 1);
        var slow = hub.register(StationScope.allStations(), null).orElseThrow();
        var healthy = hub.register(StationScope.allStations(), null).orElseThrow();
        // Clients are not started, so nothing drains their one-slot queues.
        // The first broadcast fills both; the healthy client then leaves, and
        // the second broadcast overflows only the slow client.

        hub.broadcast(SseClientTest.event("1"));
        healthy.close(SseClient.CloseReason.COMPLETED);
        hub.broadcast(SseClientTest.event("2"));

        assertThat(slow.isClosed()).isTrue();
        assertThat(hub.size()).isZero();
        assertThat(registry.counter("flightsignal.events.broadcast").count()).isEqualTo(2);
        assertThat(registry.get("flightsignal.sse.clients.closed")
                .tag("reason", "slow_consumer").counter().count()).isEqualTo(1);
    }

    @Test
    void queuesAnEventOnlyForClientsWhoseStationsOverlap() {
        var hub = startedHub(10, 1);
        var singapore = hub.register(StationScope.of(List.of("SIN")), null).orElseThrow();
        var auckland = hub.register(StationScope.of(List.of("AKL")), null).orElseThrow();

        // Queue capacity is one: a client that receives two events overflows.
        hub.broadcast(event("1", "LHR", "SIN", "SYD"));
        hub.broadcast(event("2", "LHR", "SIN", "SYD"));

        assertThat(singapore.isClosed()).as("received both QF1 events").isTrue();
        assertThat(auckland.isClosed()).as("received nothing").isFalse();
    }

    @Test
    void closesStreamsWhoseTokenHasExpired() {
        var hub = startedHub(10, 16);
        var expired = hub.register(StationScope.allStations(), Instant.now().minusSeconds(1)).orElseThrow();
        var valid = hub.register(StationScope.allStations(), Instant.now().plusSeconds(600)).orElseThrow();

        hub.heartbeat();

        assertThat(expired.isClosed()).isTrue();
        assertThat(valid.isClosed()).isFalse();
        assertThat(registry.get("flightsignal.sse.clients.closed")
                .tag("reason", "token_expired").counter().count()).isEqualTo(1);
    }

    @Test
    void stopClosesEveryClient() {
        var hub = startedHub(10, 16);
        var client = hub.register(StationScope.allStations(), null).orElseThrow();

        hub.stop();

        assertThat(client.isClosed()).isTrue();
        assertThat(hub.size()).isZero();
        assertThat(hub.isRunning()).isFalse();
    }

    private static FlightEvent event(String id, String... stations) {
        return new FlightEvent(
                id, Instant.now(), "UPDATE", null, null, List.of(),
                "flight.gate.changed", "info", "Gate changed", null, List.of(stations));
    }

    private SseHub startedHub(int maxClients, int queueCapacity) {
        var hub = new SseHub(properties(maxClients, queueCapacity), registry);
        hub.start();
        return hub;
    }

    private static SseProperties properties(int maxClients, int queueCapacity) {
        return new SseProperties(
                Duration.ofSeconds(20),
                Duration.ofMinutes(30),
                Duration.ofSeconds(3),
                maxClients,
                queueCapacity,
                500,
                20);
    }
}

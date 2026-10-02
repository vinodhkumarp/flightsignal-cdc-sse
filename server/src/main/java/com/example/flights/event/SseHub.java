package com.example.flights.event;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.example.flights.config.SseProperties;
import com.example.flights.event.SseClient.CloseReason;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Registry of connected SSE clients and fan-out point for flight events.
 *
 * <p>Broadcasting only enqueues work; each {@link SseClient} writes to its own
 * socket on its own virtual thread.
 */
@Component
public class SseHub implements SmartLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(SseHub.class);

    private final Set<SseClient> clients = ConcurrentHashMap.newKeySet();
    private final AtomicLong clientIds = new AtomicLong();
    private final SseProperties properties;
    private final MeterRegistry meterRegistry;
    private final Counter broadcastCounter;
    private final Counter rejectedCounter;
    private final Timer deliveryLag;

    private volatile boolean running;

    public SseHub(SseProperties properties, MeterRegistry meterRegistry) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;

        Gauge.builder("flightsignal.sse.clients", clients, Set::size)
                .description("Currently connected SSE clients")
                .register(meterRegistry);
        this.broadcastCounter = Counter.builder("flightsignal.events.broadcast")
                .description("Flight events fanned out to SSE clients")
                .register(meterRegistry);
        this.rejectedCounter = Counter.builder("flightsignal.sse.clients.rejected")
                .description("SSE connections refused because the limit was reached")
                .register(meterRegistry);
        this.deliveryLag = Timer.builder("flightsignal.events.delivery.lag")
                .description("Time from database commit to SSE fan-out")
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    /**
     * Registers a new client, or returns empty when this instance is at its
     * connection limit or shutting down.
     *
     * <p>The client starts buffering live events immediately; call
     * {@link #start(SseClient, EventReplay)} once the replay is known.
     */
    public Optional<SseClient> register() {
        if (!running || clients.size() >= properties.maxClients()) {
            rejectedCounter.increment();
            return Optional.empty();
        }

        var emitter = new SseEmitter(properties.timeout().toMillis());
        var client = new SseClient(
                clientIds.incrementAndGet(),
                emitter,
                properties.clientQueueCapacity(),
                this::onClosed);
        clients.add(client);

        emitter.onCompletion(() -> client.close(CloseReason.COMPLETED));
        emitter.onTimeout(() -> client.close(CloseReason.TIMEOUT));
        emitter.onError(error -> client.close(CloseReason.CLIENT_ERROR));
        return Optional.of(client);
    }

    public void start(SseClient client, EventReplay replay) {
        client.start(replay, properties.reconnectDelay());
    }

    public void broadcast(FlightEvent event) {
        broadcastCounter.increment();
        if (event.occurredAt() != null) {
            var lag = Duration.between(event.occurredAt(), Instant.now());
            if (!lag.isNegative()) {
                deliveryLag.record(lag);
            }
        }

        var message = new SseClient.EventMessage(event);
        for (var client : clients) {
            if (!client.offer(message)) {
                client.close(CloseReason.SLOW_CONSUMER);
            }
        }
    }

    @Scheduled(fixedDelayString = "${app.sse.heartbeat:20s}")
    void heartbeat() {
        for (var client : clients) {
            if (!client.offer(SseClient.Heartbeat.INSTANCE)) {
                client.close(CloseReason.SLOW_CONSUMER);
            }
        }
    }

    public int size() {
        return clients.size();
    }

    private void onClosed(SseClient client, CloseReason reason) {
        clients.remove(client);
        Counter.builder("flightsignal.sse.clients.closed")
                .description("SSE connections closed, by reason")
                .tag("reason", reason.name().toLowerCase(java.util.Locale.ROOT))
                .register(meterRegistry)
                .increment();

        if (reason == CloseReason.SLOW_CONSUMER) {
            LOGGER.warn("Disconnected slow SSE client {}; it will reconnect and replay",
                    client.id());
        }
    }

    @Override
    public void start() {
        running = true;
    }

    /** Completes every connection before the web server stops. */
    @Override
    public void stop() {
        running = false;
        for (var client : clients) {
            client.close(CloseReason.SHUTDOWN);
        }
        clients.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * Stops before the embedded web server's graceful shutdown phase so that
     * long-lived SSE requests do not hold shutdown open until the grace
     * period expires.
     */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}

package com.example.flights.event;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.SequencedSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.postgresql.PGConnection;
import org.postgresql.PGProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import com.example.flights.config.ListenerProperties;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Listens for {@code flight_changes} notifications and broadcasts the
 * classified events.
 *
 * <p>Design notes:
 * <ul>
 *   <li>Uses a dedicated JDBC connection outside the Hikari pool, so a
 *       long-lived LISTEN session never occupies a pool slot and is never
 *       handed to another caller with LISTEN still active.</li>
 *   <li>NOTIFY is only a wake-up signal. The durable
 *       {@code flight_change_event} table is the source of truth: after any
 *       reconnect the listener pages through every event above its
 *       high-water mark (minus a small window for out-of-order commits).</li>
 *   <li>The connection is probed with {@code SELECT 1} and has a socket
 *       timeout, so a half-open TCP connection (failover, NAT timeout,
 *       sleeping laptop) is detected instead of silently waiting forever.</li>
 *   <li>An event ID is marked as delivered only after it was broadcast, so a
 *       failure in between is retried by the catch-up pass.</li>
 * </ul>
 */
@Component
public class PostgresNotificationListener implements SmartLifecycle {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(PostgresNotificationListener.class);
    private static final String CHANNEL = "flight_changes";
    private static final int DELIVERED_ID_LIMIT = 10_000;

    private final JdbcConnectionDetails connectionDetails;
    private final FlightEventService eventService;
    private final SseHub hub;
    private final ListenerProperties properties;
    private final AtomicBoolean connected = new AtomicBoolean();
    private final Counter reconnects;
    private final Counter notifications;

    // Accessed only by the listener thread.
    private final SequencedSet<Long> deliveredIds = new LinkedHashSet<>();
    private long highWaterMark = -1;

    private volatile boolean running;
    private volatile Thread listenerThread;
    private volatile Connection activeConnection;
    private volatile Instant lastHealthyAt;

    public PostgresNotificationListener(
            JdbcConnectionDetails connectionDetails,
            FlightEventService eventService,
            SseHub hub,
            ListenerProperties properties,
            MeterRegistry meterRegistry) {
        this.connectionDetails = connectionDetails;
        this.eventService = eventService;
        this.hub = hub;
        this.properties = properties;

        Gauge.builder("flightsignal.listener.connected", connected, c -> c.get() ? 1 : 0)
                .description("1 while the PostgreSQL LISTEN connection is healthy")
                .register(meterRegistry);
        this.reconnects = Counter.builder("flightsignal.listener.reconnects")
                .description("PostgreSQL listener reconnect attempts after a failure")
                .register(meterRegistry);
        this.notifications = Counter.builder("flightsignal.listener.notifications")
                .description("NOTIFY messages received on the flight_changes channel")
                .register(meterRegistry);
    }

    @Override
    public void start() {
        running = true;
        listenerThread = Thread.ofVirtual()
                .name("postgres-flight-notifications")
                .start(this::listenLoop);
    }

    @Override
    public void stop() {
        running = false;
        closeQuietly(activeConnection);

        var thread = listenerThread;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(TimeUnit.SECONDS.toMillis(5));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    public boolean isConnected() {
        return connected.get();
    }

    public Instant lastHealthyAt() {
        return lastHealthyAt;
    }

    private void listenLoop() {
        var backoff = properties.initialBackoff();

        while (running) {
            try (var connection = openConnection();
                    var statement = connection.createStatement()) {
                activeConnection = connection;
                statement.execute("LISTEN " + CHANNEL);
                var postgresConnection = connection.unwrap(PGConnection.class);

                catchUp();
                markHealthy();
                backoff = properties.initialBackoff();
                LOGGER.info("Listening for PostgreSQL {} notifications", CHANNEL);

                receive(connection, postgresConnection);
            } catch (Exception exception) {
                if (running) {
                    reconnects.increment();
                    LOGGER.warn(
                            "PostgreSQL listener disconnected ({}); retrying in {}",
                            exception.toString(),
                            backoff);
                    LOGGER.debug("Listener failure", exception);
                }
            } finally {
                activeConnection = null;
                connected.set(false);
            }

            if (running) {
                sleep(backoff);
                backoff = min(backoff.multipliedBy(2), properties.maxBackoff());
            }
        }
    }

    private void receive(Connection connection, PGConnection postgresConnection)
            throws SQLException {
        var pollMillis = (int) Math.max(1, properties.pollTimeout().toMillis());
        var probeNanos = properties.healthCheckInterval().toNanos();
        var lastProbe = System.nanoTime();

        while (running) {
            var received = postgresConnection.getNotifications(pollMillis);
            if (received != null) {
                for (var notification : received) {
                    notifications.increment();
                    publish(notification.getParameter());
                }
                markHealthy();
            }

            if (System.nanoTime() - lastProbe >= probeNanos) {
                try (var probe = connection.createStatement()) {
                    probe.execute("SELECT 1");
                }
                lastProbe = System.nanoTime();
                markHealthy();
            }
        }
    }

    private Connection openConnection() throws SQLException {
        var info = new Properties();
        if (connectionDetails.getUsername() != null) {
            info.setProperty("user", connectionDetails.getUsername());
        }
        if (connectionDetails.getPassword() != null) {
            info.setProperty("password", connectionDetails.getPassword());
        }
        info.setProperty(PGProperty.APPLICATION_NAME.getName(), "flightsignal-listener");
        info.setProperty(PGProperty.TCP_KEEP_ALIVE.getName(), "true");
        info.setProperty(
                PGProperty.SOCKET_TIMEOUT.getName(),
                Long.toString(Math.max(1, properties.socketTimeout().toSeconds())));
        info.setProperty(PGProperty.CONNECT_TIMEOUT.getName(), "10");

        var connection = DriverManager.getConnection(connectionDetails.getJdbcUrl(), info);
        connection.setAutoCommit(true);
        return connection;
    }

    /**
     * On the first connection, starts from the newest event: connected
     * browsers replay their own history, so nothing old is re-broadcast.
     * After a reconnect, broadcasts everything committed while disconnected.
     */
    private void catchUp() {
        if (highWaterMark < 0) {
            highWaterMark = eventService.latestEventId();
            // Treat the existing tail as delivered so a later reconnect does
            // not re-broadcast events that predate this process.
            var window = properties.reconcileWindow();
            for (var eventId : eventService.eventIdsAfter(
                    Math.max(0, highWaterMark - window), Math.max(1, window))) {
                rememberDelivered(eventId);
            }
            return;
        }

        var after = Math.max(0, highWaterMark - properties.reconcileWindow());
        var pageSize = Math.max(1, properties.reconcilePageSize());
        var recovered = 0;

        while (running) {
            var page = eventService.findAfter(after, pageSize);
            for (var event : page) {
                if (deliver(event)) {
                    recovered++;
                }
                after = Long.parseLong(event.eventId());
            }
            if (page.size() < pageSize) {
                break;
            }
        }

        if (recovered > 0) {
            LOGGER.info("Recovered {} flight events missed while disconnected", recovered);
        }
    }

    private void publish(String payload) {
        long eventId;
        try {
            eventId = Long.parseLong(payload);
        } catch (NumberFormatException exception) {
            LOGGER.warn("Ignoring invalid flight event ID '{}'", payload);
            return;
        }

        if (deliveredIds.contains(eventId)) {
            return;
        }
        eventService.findById(eventId).ifPresent(this::deliver);
    }

    private boolean deliver(FlightEvent event) {
        var eventId = Long.parseLong(event.eventId());
        if (!deliveredIds.add(eventId)) {
            return false;
        }

        try {
            hub.broadcast(event);
        } catch (RuntimeException exception) {
            deliveredIds.remove(eventId);
            throw exception;
        }

        trimDelivered();
        highWaterMark = Math.max(highWaterMark, eventId);
        return true;
    }

    private void rememberDelivered(long eventId) {
        deliveredIds.add(eventId);
        trimDelivered();
    }

    private void trimDelivered() {
        while (deliveredIds.size() > DELIVERED_ID_LIMIT) {
            deliveredIds.removeFirst();
        }
    }

    private void markHealthy() {
        connected.set(true);
        lastHealthyAt = Instant.now();
    }

    private static Duration min(Duration left, Duration right) {
        return left.compareTo(right) <= 0 ? left : right;
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException exception) {
            LOGGER.debug("Error closing PostgreSQL listener connection", exception);
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}

package com.example.flights.event;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.example.flights.security.StationScope;

/**
 * One connected browser.
 *
 * <p>Producers (the PostgreSQL listener and the heartbeat scheduler) only
 * {@link #offer(Message) offer} messages to a bounded queue and never block.
 * A dedicated virtual thread per client drains the queue and performs the
 * potentially slow socket writes, so one slow browser cannot delay delivery
 * to the others. A client whose queue overflows is disconnected; its browser
 * reconnects and catches up through replay.
 *
 * <p>Each client carries the {@link StationScope} from its JWT and only
 * receives events for those stations. The connection is closed when the JWT
 * expires so the browser reconnects with a fresh token.
 */
public final class SseClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(SseClient.class);

    /** Messages a client can be asked to send. */
    sealed interface Message permits EventMessage, Heartbeat {
    }

    record EventMessage(FlightEvent event) implements Message {
    }

    enum Heartbeat implements Message {
        INSTANCE
    }

    /** Why a client connection ended; used for metrics and logging. */
    public enum CloseReason {
        COMPLETED,
        TIMEOUT,
        CLIENT_ERROR,
        SLOW_CONSUMER,
        TOKEN_EXPIRED,
        SHUTDOWN
    }

    private final long id;
    private final SseEmitter emitter;
    private final BlockingQueue<Message> queue;
    private final StationScope scope;
    private final Instant expiresAt;
    private final BiConsumer<SseClient, CloseReason> onClosed;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    private volatile Thread sender;

    SseClient(
            long id,
            SseEmitter emitter,
            int queueCapacity,
            StationScope scope,
            Instant expiresAt,
            BiConsumer<SseClient, CloseReason> onClosed) {
        this.id = id;
        this.emitter = emitter;
        this.queue = new ArrayBlockingQueue<>(Math.max(1, queueCapacity));
        this.scope = scope;
        this.expiresAt = expiresAt;
        this.onClosed = onClosed;
    }

    public StationScope scope() {
        return scope;
    }

    /** {@code true} when this client may receive {@code event}. */
    boolean wants(FlightEvent event) {
        return scope.permits(event.stations());
    }

    /** {@code true} once the client's JWT has expired. */
    boolean isExpiredAt(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    public long id() {
        return id;
    }

    public SseEmitter emitter() {
        return emitter;
    }

    public boolean isClosed() {
        return closed.get();
    }

    /**
     * Queues a message without blocking.
     *
     * @return {@code false} when the queue is full (slow consumer)
     */
    boolean offer(Message message) {
        if (closed.get()) {
            return true;
        }
        return queue.offer(message);
    }

    /**
     * Starts the sender thread. Live messages offered before this call stay
     * queued and are sent after the replay; events present in both are sent
     * only once.
     */
    void start(EventReplay replay, Duration reconnectDelay) {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("SSE client already started");
        }
        sender = Thread.ofVirtual()
                .name("sse-client-" + id)
                .start(() -> run(replay, reconnectDelay));
    }

    void close(CloseReason reason) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        onClosed.accept(this, reason);
        queue.clear();

        try {
            emitter.complete();
        } catch (RuntimeException exception) {
            LOGGER.debug("Ignoring error completing SSE client {}", id, exception);
        }

        var thread = sender;
        if (thread != null && thread != Thread.currentThread()) {
            thread.interrupt();
        }
    }

    private void run(EventReplay replay, Duration reconnectDelay) {
        try {
            var replayedIds = sendReplay(replay, reconnectDelay);

            while (!closed.get()) {
                var message = queue.take();
                switch (message) {
                    case EventMessage(var event) -> {
                        if (replayedIds.isEmpty() || !replayedIds.remove(event.eventId())) {
                            sendEvent(event);
                        }
                    }
                    case Heartbeat heartbeat -> emitter.send(
                            SseEmitter.event().comment("keepalive"));
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            close(CloseReason.SHUTDOWN);
        } catch (IOException | RuntimeException exception) {
            // Typically a disconnected browser; the emitter is unusable now.
            LOGGER.debug("SSE client {} disconnected: {}", id, exception.toString());
            close(CloseReason.CLIENT_ERROR);
        }
    }

    private Set<String> sendReplay(EventReplay replay, Duration reconnectDelay)
            throws IOException {
        var replayedIds = new HashSet<String>();

        if (replay.resetRequired()) {
            emitter.send(SseEmitter.event()
                    .name("reset")
                    .reconnectTime(reconnectDelay.toMillis())
                    .data(Map.of("reason", "replay-limit-exceeded")));
        } else {
            for (var event : replay.events()) {
                sendEvent(event);
                replayedIds.add(event.eventId());
            }
        }

        emitter.send(SseEmitter.event()
                .name("ready")
                .reconnectTime(reconnectDelay.toMillis())
                .data(Map.of("connected", true)));
        return replayedIds;
    }

    private void sendEvent(FlightEvent event) throws IOException {
        emitter.send(SseEmitter.event()
                .id(event.eventId())
                .name("flight-change")
                .data(event));
    }
}

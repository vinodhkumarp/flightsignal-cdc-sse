package com.example.flights.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.example.flights.event.SseClient.CloseReason;

class SseClientTest {

    @Test
    void sendsReplayThenReadyThenLiveEventsWithoutDuplicates() {
        var emitter = new CapturingEmitter();
        var client = new SseClient(1, emitter, 16, (c, reason) -> { });

        // Live events that arrive while the replay query runs are buffered.
        client.offer(new SseClient.EventMessage(event("2")));
        client.offer(new SseClient.EventMessage(event("3")));

        client.start(new EventReplay(List.of(event("1"), event("2")), false), Duration.ofSeconds(3));

        await().atMost(Duration.ofSeconds(5)).until(() -> emitter.frames.size() >= 4);
        assertThat(emitter.frames).hasSize(4);
        assertThat(emitter.frames.get(0)).contains("id:1", "event:flight-change");
        assertThat(emitter.frames.get(1)).contains("id:2");
        assertThat(emitter.frames.get(2)).contains("event:ready", "retry:3000");
        assertThat(emitter.frames.get(3)).contains("id:3");

        client.close(CloseReason.COMPLETED);
    }

    @Test
    void sendsResetInsteadOfReplayWhenTheClientIsTooFarBehind() {
        var emitter = new CapturingEmitter();
        var client = new SseClient(2, emitter, 16, (c, reason) -> { });

        client.start(EventReplay.reset(), Duration.ofSeconds(3));

        await().atMost(Duration.ofSeconds(5)).until(() -> emitter.frames.size() >= 2);
        assertThat(emitter.frames.get(0)).contains("event:reset");
        assertThat(emitter.frames.get(1)).contains("event:ready");

        client.close(CloseReason.COMPLETED);
    }

    @Test
    void reportsAFullQueueAndClosesOnlyOnce() {
        var reasons = new CopyOnWriteArrayList<CloseReason>();
        var client = new SseClient(3, new CapturingEmitter(), 1, (c, reason) -> reasons.add(reason));

        assertThat(client.offer(SseClient.Heartbeat.INSTANCE)).isTrue();
        assertThat(client.offer(SseClient.Heartbeat.INSTANCE)).isFalse();

        client.close(CloseReason.SLOW_CONSUMER);
        client.close(CloseReason.COMPLETED);

        assertThat(client.isClosed()).isTrue();
        assertThat(reasons).containsExactly(CloseReason.SLOW_CONSUMER);
    }

    @Test
    void closesWhenTheBrowserHasGone() {
        var reason = new AtomicReference<CloseReason>();
        var emitter = new CapturingEmitter();
        emitter.failWith = new IOException("Broken pipe");
        var client = new SseClient(4, emitter, 16, (c, r) -> reason.set(r));

        client.start(EventReplay.none(), Duration.ofSeconds(3));

        await().atMost(Duration.ofSeconds(5)).until(client::isClosed);
        assertThat(reason.get()).isEqualTo(CloseReason.CLIENT_ERROR);
    }

    static FlightEvent event(String id) {
        return new FlightEvent(
                id,
                Instant.now(),
                "UPDATE",
                null,
                null,
                List.of(),
                "flight.updated",
                "info",
                "Updated " + id,
                null);
    }

    /** Records each SSE frame as text instead of writing to a response. */
    static final class CapturingEmitter extends SseEmitter {

        final List<String> frames = new CopyOnWriteArrayList<>();
        volatile IOException failWith;

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            if (failWith != null) {
                throw failWith;
            }
            var frame = new StringBuilder();
            for (var part : builder.build()) {
                frame.append(part.getData());
            }
            frames.add(frame.toString());
        }

        @Override
        public synchronized void complete() {
            // No response to complete in a unit test.
        }
    }
}

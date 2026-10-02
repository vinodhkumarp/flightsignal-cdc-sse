package com.example.flights.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Server-Sent Events delivery settings ({@code app.sse.*}).
 *
 * @param heartbeat           interval between keep-alive comments
 * @param timeout             maximum lifetime of one SSE connection; the
 *                            browser reconnects transparently afterwards
 * @param reconnectDelay      reconnect delay advertised to browsers
 *                            through the SSE {@code retry:} field
 * @param maxClients          concurrent connections accepted by this
 *                            instance before new ones get HTTP 503
 * @param clientQueueCapacity messages buffered per client; a client that
 *                            falls this far behind is disconnected
 * @param replayLimit         maximum missed events replayed on reconnect;
 *                            beyond this the client is told to reset
 * @param replaySafetyWindow  event IDs at or below the client's cursor that
 *                            are replayed again, because identity values can
 *                            commit out of order (the client de-duplicates)
 */
@ConfigurationProperties("app.sse")
public record SseProperties(
        @DefaultValue("20s") Duration heartbeat,
        @DefaultValue("30m") Duration timeout,
        @DefaultValue("3s") Duration reconnectDelay,
        @DefaultValue("500") int maxClients,
        @DefaultValue("256") int clientQueueCapacity,
        @DefaultValue("500") int replayLimit,
        @DefaultValue("20") int replaySafetyWindow) {
}

package com.example.flights.event;

import java.util.OptionalLong;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.example.flights.api.ApiException;
import com.example.flights.config.SseProperties;
import com.example.flights.event.SseClient.CloseReason;
import com.example.flights.security.StationScope;

@RestController
@RequestMapping("/api/events")
public class FlightEventController {

    private final FlightEventService eventService;
    private final SseHub hub;
    private final SseProperties sseProperties;

    public FlightEventController(
            FlightEventService eventService,
            SseHub hub,
            SseProperties sseProperties) {
        this.eventService = eventService;
        this.hub = hub;
        this.sseProperties = sseProperties;
    }

    /**
     * Notification history visible to the caller.
     *
     * @param stations optional comma separated subset of the caller's
     *                 stations (403 if it names a station outside their scope)
     */
    @GetMapping
    FlightEventPage recent(
            @RequestParam(defaultValue = "30") int limit,
            @RequestParam(required = false) Long before,
            @RequestParam(required = false) String stations,
            StationScope scope) {
        return eventService.findPage(
                before,
                Math.clamp(limit, 1, 100),
                scope.narrowTo(stations));
    }

    /**
     * Opens the live event stream.
     *
     * <p>The replay cursor comes from the browser's automatic
     * {@code Last-Event-ID} header on reconnect, or from {@code ?after=} on
     * the first connection (the newest event the page already loaded). The
     * larger one wins. Without a cursor nothing is replayed: a client that
     * has not loaded history must not receive the oldest events as if they
     * were live.
     *
     * <p>Only events for the caller's stations (from the JWT, optionally
     * narrowed with {@code ?stations=}) are replayed and streamed. The stream
     * closes when the JWT expires; the client reconnects with a fresh token.
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    ResponseEntity<SseEmitter> stream(
            @RequestHeader(name = "Last-Event-ID", required = false)
            String lastEventId,
            @RequestParam(required = false) Long after,
            @RequestParam(required = false) String stations,
            StationScope scope,
            @AuthenticationPrincipal Jwt jwt) {
        var effectiveScope = scope.narrowTo(stations);
        if (effectiveScope.isEmpty()) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "Your account has no stations assigned, so there are no notifications to show.");
        }
        var registered = hub.register(effectiveScope, jwt == null ? null : jwt.getExpiresAt());
        if (registered.isEmpty()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header(HttpHeaders.RETRY_AFTER,
                            Long.toString(Math.max(1, sseProperties.reconnectDelay().toSeconds())))
                    .build();
        }

        var client = registered.get();
        try {
            var cursor = cursor(lastEventId, after);
            var replay = cursor.isPresent()
                    ? eventService.replayAfter(cursor.getAsLong(), effectiveScope)
                    : EventReplay.none();
            hub.start(client, replay);
        } catch (RuntimeException exception) {
            client.close(CloseReason.CLIENT_ERROR);
            throw exception;
        }

        var headers = new HttpHeaders();
        headers.setCacheControl(CacheControl.noStore());
        headers.set("X-Accel-Buffering", "no");
        return ResponseEntity.ok().headers(headers).body(client.emitter());
    }

    static OptionalLong cursor(String lastEventId, Long after) {
        var header = parse(lastEventId);
        var query = after == null || after < 0 ? OptionalLong.empty() : OptionalLong.of(after);

        if (header.isPresent() && query.isPresent()) {
            return OptionalLong.of(Math.max(header.getAsLong(), query.getAsLong()));
        }
        return header.isPresent() ? header : query;
    }

    private static OptionalLong parse(String value) {
        if (value == null || value.isBlank()) {
            return OptionalLong.empty();
        }
        try {
            var parsed = Long.parseLong(value.trim());
            return parsed < 0 ? OptionalLong.empty() : OptionalLong.of(parsed);
        } catch (NumberFormatException exception) {
            return OptionalLong.empty();
        }
    }
}

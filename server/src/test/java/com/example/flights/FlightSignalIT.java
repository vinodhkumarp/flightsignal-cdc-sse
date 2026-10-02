package com.example.flights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.example.flights.ops.FlightEventRetentionJob;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * End-to-end tests against a real PostgreSQL: Flyway migrations, the capture
 * trigger, LISTEN/NOTIFY, SSE delivery and replay, passenger search, and the
 * operational endpoints. Requires Docker; runs in {@code mvn verify}.
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "app.sse.heartbeat=1s",
            "management.endpoint.health.show-details=always"
        })
class FlightSignalIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private final HttpClient http = HttpClient.newHttpClient();
    private final List<Stream<String>> openStreams = new CopyOnWriteArrayList<>();

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    FlightEventRetentionJob retentionJob;

    @Autowired
    MeterRegistry meterRegistry;

    @BeforeEach
    void waitForListener() {
        await().atMost(Duration.ofSeconds(20)).until(() ->
                get("/actuator/health/readiness").body().contains("\"UP\""));
    }

    @AfterEach
    void closeStreams() {
        openStreams.forEach(Stream::close);
    }

    @Test
    void streamsACommittedFlightChangeToConnectedBrowsers() throws Exception {
        var stream = openStream(null, null);
        await().atMost(Duration.ofSeconds(10)).until(() -> stream.contains("event:ready"));

        var flightId = insertFlight("QF", "901", "SYD", "MEL");
        jdbc.update("UPDATE flight_instance SET gate = '41' WHERE flight_id = ?::uuid", flightId);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(stream.text()).contains("Flight QF901 from SYD to MEL has been added.");
            assertThat(stream.text()).contains("Gate for QF901 changed from 12 to 41.");
        });
    }

    @Test
    void doesNotReplayHistoryToAClientWithoutACursor() throws Exception {
        var broadcastBefore = broadcastCount();
        insertFlight("QF", "902", "SYD", "BNE");
        // Wait until the listener has fanned the event out, so it cannot
        // arrive on the new connection as a genuinely live event.
        await().atMost(Duration.ofSeconds(10)).until(() -> broadcastCount() > broadcastBefore);

        var stream = openStream(null, null);
        await().atMost(Duration.ofSeconds(10)).until(() -> stream.contains("event:ready"));

        assertThat(stream.text()).doesNotContain("QF902");
    }

    @Test
    void replaysEventsMissedSinceLastEventId() throws Exception {
        var flightId = insertFlight("QF", "903", "SYD", "AKL");
        var cursor = latestEventId();
        jdbc.update("UPDATE flight_instance SET gate = '7' WHERE flight_id = ?::uuid", flightId);
        jdbc.update("UPDATE flight_instance SET status = 'CANCELLED' WHERE flight_id = ?::uuid", flightId);

        var stream = openStream(Long.toString(cursor), null);

        await().atMost(Duration.ofSeconds(10)).until(() -> stream.contains("event:ready"));
        var beforeReady = stream.text().substring(0, stream.text().indexOf("event:ready"));
        assertThat(beforeReady)
                .contains("Gate for QF903 changed from 12 to 7.")
                .contains("Flight QF903 has been cancelled.");
    }

    @Test
    void ignoresUpdatesThatChangeNothing() {
        var flightId = insertFlight("QF", "904", "MEL", "PER");
        var before = latestEventId();

        jdbc.update("UPDATE flight_instance SET gate = gate WHERE flight_id = ?::uuid", flightId);

        assertThat(latestEventId()).isEqualTo(before);
        assertThat(jdbc.queryForObject(
                "SELECT version FROM flight_instance WHERE flight_id = ?::uuid",
                Long.class,
                flightId)).isEqualTo(1L);
    }

    @Test
    void findsPassengersByFlightCodeAndDate() throws Exception {
        var flightId = insertFlight("VA", "905", "SYD", "MEL");
        jdbc.update("""
                INSERT INTO flight_passenger (
                    flight_id, carrier_code, flight_number, service_date,
                    origin_airport, destination_airport, manifest_sequence,
                    booking_reference, given_name, family_name)
                SELECT flight_id, carrier_code, flight_number, service_date,
                       origin_airport, destination_airport, 1,
                       'ABC123', 'Amelia', 'Nguyen'
                FROM flight_instance WHERE flight_id = ?::uuid
                """, flightId);

        var response = get("/api/passengers?flightNumber=va905&travelDate=" + serviceDate());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"count\":1", "Amelia", "ABC123");
    }

    @Test
    void returnsProblemDetailsForInvalidRequests() throws Exception {
        var response = get("/api/passengers?flightNumber=x&travelDate=" + serviceDate());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(type ->
                assertThat(type).contains("application/problem+json"));
        assertThat(response.body()).contains("flightNumber must contain");
    }

    @Test
    void purgesEventsOlderThanTheRetentionPeriod() {
        var flightId = insertFlight("QF", "906", "SYD", "SIN");
        jdbc.update(
                "UPDATE app_internal.flight_change_event SET occurred_at = now() - interval '400 days'"
                        + " WHERE flight_id = ?::uuid",
                flightId);

        retentionJob.purgeExpiredEvents();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app_internal.flight_change_event WHERE flight_id = ?::uuid",
                Long.class,
                flightId)).isZero();
    }

    @Test
    void exposesHealthAndPrometheusMetrics() throws Exception {
        var health = get("/actuator/health");
        assertThat(health.body()).contains("flightListener", "\"UP\"");

        var metrics = get("/actuator/prometheus");
        assertThat(metrics.statusCode()).isEqualTo(200);
        assertThat(metrics.body())
                .contains("flightsignal_sse_clients")
                .contains("flightsignal_listener_connected");
    }

    private String insertFlight(String carrier, String number, String origin, String destination) {
        var departure = serviceDate().atTime(9, 0).toInstant(ZoneOffset.UTC);
        return jdbc.queryForObject("""
                INSERT INTO flight_instance (
                    carrier_code, flight_number, service_date,
                    origin_airport, destination_airport,
                    origin_timezone, destination_timezone,
                    scheduled_departure_utc, estimated_departure_utc,
                    scheduled_arrival_utc, estimated_arrival_utc, gate)
                VALUES (?, ?, ?, ?, ?, 'Australia/Sydney', 'Australia/Sydney',
                        ?::timestamptz, ?::timestamptz, ?::timestamptz, ?::timestamptz, '12')
                RETURNING flight_id::text
                """,
                String.class,
                carrier,
                number,
                serviceDate(),
                origin,
                destination,
                departure.toString(),
                departure.toString(),
                departure.plus(2, ChronoUnit.HOURS).toString(),
                departure.plus(2, ChronoUnit.HOURS).toString());
    }

    private double broadcastCount() {
        return meterRegistry.counter("flightsignal.events.broadcast").count();
    }

    private long latestEventId() {
        var id = jdbc.queryForObject(
                "SELECT max(event_id) FROM app_internal.flight_change_event",
                Long.class);
        return id == null ? 0 : id;
    }

    private static LocalDate serviceDate() {
        return LocalDate.now(ZoneOffset.UTC).plusDays(2);
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private SseStream openStream(String lastEventId, Long after) throws Exception {
        var uri = "http://localhost:" + port + "/api/events/stream"
                + (after == null ? "" : "?after=" + after);
        var request = HttpRequest.newBuilder(URI.create(uri))
                .header("Accept", "text/event-stream");
        if (lastEventId != null) {
            request.header("Last-Event-ID", lastEventId);
        }

        var response = http.send(request.GET().build(), HttpResponse.BodyHandlers.ofLines());
        assertThat(response.statusCode()).isEqualTo(200);

        var stream = new SseStream();
        var lines = response.body();
        openStreams.add(lines);
        Thread.ofVirtual().start(() -> {
            try {
                lines.forEach(stream.lines::add);
            } catch (RuntimeException closed) {
                // Stream closed by the test.
            }
        });
        return stream;
    }

    /** Lines received on one SSE connection. */
    private static final class SseStream {

        private final List<String> lines = new CopyOnWriteArrayList<>();

        boolean contains(String text) {
            return text().contains(text);
        }

        String text() {
            return String.join("\n", lines);
        }
    }
}

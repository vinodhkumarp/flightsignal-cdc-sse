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
import java.util.regex.Pattern;
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
 * trigger, LISTEN/NOTIFY, JWT authentication, station-scoped SSE delivery and
 * replay, passenger search, and the operational endpoints. Requires Docker;
 * runs in {@code mvn verify}.
 *
 * <p>Tokens come from the development issuer ({@code POST /api/dev/token}),
 * which produces JWTs shaped like the SSO tokens used in real environments.
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "app.sse.heartbeat=1s",
            "management.endpoint.health.show-details=always",
            "app.security.dev.enabled=true",
            "app.security.dev.secret=integration-test-secret-at-least-32-bytes-long"
        })
class FlightSignalIT {

    private static final Pattern ACCESS_TOKEN = Pattern.compile("\"accessToken\":\"([^\"]+)\"");

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

    private String headOffice;

    @BeforeEach
    void setUp() throws Exception {
        await().atMost(Duration.ofSeconds(20)).until(() ->
                get("/actuator/health/readiness", null).body().contains("\"UP\""));
        headOffice = token("Head Office", "*");
    }

    @AfterEach
    void closeStreams() {
        openStreams.forEach(Stream::close);
    }

    @Test
    void rejectsApiCallsWithoutAValidToken() throws Exception {
        assertThat(get("/api/events", null).statusCode()).isEqualTo(401);
        assertThat(get("/api/events", "not-a-jwt").statusCode()).isEqualTo(401);
        assertThat(get("/api/events/stream", null).statusCode()).isEqualTo(401);
        assertThat(get("/actuator/health", null).statusCode()).isEqualTo(200);
    }

    @Test
    void describesTheCallerFromTheirClaims() throws Exception {
        var response = get("/api/me", token("Priya Sharma", "syd", "SIN"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"name\":\"Priya Sharma\"")
                .contains("\"stations\":[\"SIN\",\"SYD\"]")
                .contains("\"allStations\":false");
    }

    @Test
    void deliversEachLegOfAMultiLegFlightOnlyToThatLegsStations() throws Exception {
        var sydney = openStream(token("Sydney Ops", "SYD"), null, null);
        var singapore = openStream(token("Singapore Ops", "SIN"), null, null);
        var london = openStream(token("London Ops", "LHR"), null, null);
        var auckland = openStream(token("Auckland Ops", "AKL"), null, null);
        await().atMost(Duration.ofSeconds(10)).until(() ->
                sydney.contains("event:ready")
                        && singapore.contains("event:ready")
                        && london.contains("event:ready")
                        && auckland.contains("event:ready"));

        // QF1 stored as two legs sharing the flight number.
        var firstLeg = insertFlight("QF", "1", "SYD", "SIN", null);
        var secondLeg = insertFlight("QF", "1", "SIN", "LHR", null);
        jdbc.update("UPDATE flight_instance SET gate = '7' WHERE flight_id = ?::uuid", firstLeg);
        jdbc.update("UPDATE flight_instance SET gate = '41' WHERE flight_id = ?::uuid", secondLeg);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(singapore.text())
                    .contains("Gate for QF1 changed from 12 to 7.")
                    .contains("Gate for QF1 changed from 12 to 41.");
            assertThat(sydney.text()).contains("Gate for QF1 changed from 12 to 7.");
            assertThat(london.text()).contains("Gate for QF1 changed from 12 to 41.");
        });
        // Give a wrong delivery time to arrive before asserting its absence.
        Thread.sleep(500);
        assertThat(sydney.text()).doesNotContain("changed from 12 to 41");
        assertThat(london.text()).doesNotContain("changed from 12 to 7");
        assertThat(auckland.text()).doesNotContain("QF1");
    }

    @Test
    void alertsOnwardLegStationsWhenAnEarlierLegIsDelayed() throws Exception {
        var firstLeg = insertFlight("QF", "2", "SYD", "SIN", null, 6);
        insertFlight("QF", "2", "SIN", "LHR", null, 10);
        var london = openStream(token("London Ops", "LHR"), null, null);
        await().atMost(Duration.ofSeconds(10)).until(() -> london.contains("event:ready"));

        jdbc.update("UPDATE flight_instance SET gate = '9' WHERE flight_id = ?::uuid", firstLeg);
        jdbc.update("""
                UPDATE flight_instance
                SET estimated_departure_utc = estimated_departure_utc + interval '45 minutes',
                    estimated_arrival_utc = estimated_arrival_utc + interval '45 minutes',
                    status = 'DELAYED'
                WHERE flight_id = ?::uuid
                """, firstLeg);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(london.text()).contains(
                        "Departure for QF2 has been delayed by 45 minutes."
                                + " Onward connections at LHR may be affected."));
        // The gate change on the first leg stays with SYD and SIN.
        assertThat(london.text()).doesNotContain("Gate for QF2");
    }

    @Test
    void deliversADiversionToBothTheOldAndTheNewStation() throws Exception {
        var flightId = insertFlight("VA", "77", "SYD", "SIN", null);
        var kualaLumpur = openStream(token("KL Ops", "KUL"), null, null);
        var singapore = openStream(token("Singapore Ops", "SIN"), null, null);
        await().atMost(Duration.ofSeconds(10)).until(() ->
                kualaLumpur.contains("event:ready") && singapore.contains("event:ready"));

        jdbc.update(
                "UPDATE flight_instance SET destination_airport = 'KUL', route_stations = '{SYD,KUL}'"
                        + " WHERE flight_id = ?::uuid",
                flightId);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(kualaLumpur.text()).contains("VA77");
            assertThat(singapore.text()).contains("VA77");
        });
    }

    @Test
    void filtersHistoryAndReplayByStation() throws Exception {
        var flightId = insertFlight("NZ", "104", "SYD", "AKL", null);
        var cursor = latestEventId();
        jdbc.update("UPDATE flight_instance SET status = 'CANCELLED' WHERE flight_id = ?::uuid", flightId);

        var auckland = token("Auckland Ops", "AKL");
        var london = token("London Ops", "LHR");

        assertThat(get("/api/events?limit=100", auckland).body()).contains("NZ104");
        assertThat(get("/api/events?limit=100", london).body()).doesNotContain("NZ104");

        var replayed = openStream(london, Long.toString(cursor), null);
        await().atMost(Duration.ofSeconds(10)).until(() -> replayed.contains("event:ready"));
        assertThat(replayed.text()).doesNotContain("NZ104");
    }

    @Test
    void rejectsNarrowingToAStationOutsideTheToken() throws Exception {
        var auckland = token("Auckland Ops", "AKL");

        assertThat(get("/api/events?stations=SYD", auckland).statusCode()).isEqualTo(403);
        assertThat(get("/api/events/stream?stations=SYD", auckland).statusCode()).isEqualTo(403);
        assertThat(get("/api/events?stations=AKL", auckland).statusCode()).isEqualTo(200);
    }

    @Test
    void doesNotReplayHistoryToAClientWithoutACursor() throws Exception {
        var broadcastBefore = broadcastCount();
        insertFlight("QF", "902", "SYD", "BNE", null);
        // Wait until the listener has fanned the event out, so it cannot
        // arrive on the new connection as a genuinely live event.
        await().atMost(Duration.ofSeconds(10)).until(() -> broadcastCount() > broadcastBefore);

        var stream = openStream(headOffice, null, null);
        await().atMost(Duration.ofSeconds(10)).until(() -> stream.contains("event:ready"));

        assertThat(stream.text()).doesNotContain("QF902");
    }

    @Test
    void replaysEventsMissedSinceLastEventId() throws Exception {
        var flightId = insertFlight("QF", "903", "SYD", "AKL", null);
        var cursor = latestEventId();
        jdbc.update("UPDATE flight_instance SET gate = '7' WHERE flight_id = ?::uuid", flightId);
        jdbc.update("UPDATE flight_instance SET status = 'CANCELLED' WHERE flight_id = ?::uuid", flightId);

        var stream = openStream(headOffice, Long.toString(cursor), null);

        await().atMost(Duration.ofSeconds(10)).until(() -> stream.contains("event:ready"));
        var beforeReady = stream.text().substring(0, stream.text().indexOf("event:ready"));
        assertThat(beforeReady)
                .contains("Gate for QF903 changed from 12 to 7.")
                .contains("Flight QF903 has been cancelled.");
    }

    @Test
    void ignoresUpdatesThatChangeNothing() {
        var flightId = insertFlight("QF", "904", "MEL", "PER", null);
        var before = latestEventId();

        jdbc.update("UPDATE flight_instance SET gate = gate WHERE flight_id = ?::uuid", flightId);

        assertThat(latestEventId()).isEqualTo(before);
        assertThat(jdbc.queryForObject(
                "SELECT version FROM flight_instance WHERE flight_id = ?::uuid",
                Long.class,
                flightId)).isEqualTo(1L);
    }

    @Test
    void findsPassengersOnlyForTheCallersStations() throws Exception {
        var flightId = insertFlight("VA", "905", "SYD", "MEL", null);
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
        var path = "/api/passengers?flightNumber=va905&travelDate=" + serviceDate();

        var sydney = get(path, token("Sydney Ops", "SYD"));
        assertThat(sydney.statusCode()).isEqualTo(200);
        assertThat(sydney.body()).contains("\"count\":1", "Amelia", "ABC123");

        assertThat(get(path, token("Perth Ops", "PER")).body()).contains("\"count\":0");
    }

    @Test
    void returnsProblemDetailsForInvalidRequests() throws Exception {
        var response = get("/api/passengers?flightNumber=x&travelDate=" + serviceDate(), headOffice);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(type ->
                assertThat(type).contains("application/problem+json"));
        assertThat(response.body()).contains("flightNumber must contain");
    }

    @Test
    void purgesEventsOlderThanTheRetentionPeriod() {
        var flightId = insertFlight("QF", "906", "SYD", "SIN", null);
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
    void exposesHealthAndPrometheusMetricsWithoutAToken() throws Exception {
        var health = get("/actuator/health", null);
        assertThat(health.body()).contains("flightListener", "\"UP\"");

        var metrics = get("/actuator/prometheus", null);
        assertThat(metrics.statusCode()).isEqualTo(200);
        assertThat(metrics.body())
                .contains("flightsignal_sse_clients")
                .contains("flightsignal_listener_connected");
    }

    private String token(String name, String... stations) throws Exception {
        var body = "{\"name\":\"" + name + "\",\"stations\":[\""
                + String.join("\",\"", stations) + "\"]}";
        var response = http.send(
                HttpRequest.newBuilder(uri("/api/dev/token"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        var matcher = ACCESS_TOKEN.matcher(response.body());
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private String insertFlight(
            String carrier,
            String number,
            String origin,
            String destination,
            String routeStations) {
        return insertFlight(carrier, number, origin, destination, routeStations, 9);
    }

    /** Inserts a two-hour leg departing at {@code departureHour} UTC. */
    private String insertFlight(
            String carrier,
            String number,
            String origin,
            String destination,
            String routeStations,
            int departureHour) {
        var departure = serviceDate().atTime(departureHour, 0).toInstant(ZoneOffset.UTC);
        return jdbc.queryForObject("""
                INSERT INTO flight_instance (
                    carrier_code, flight_number, service_date,
                    origin_airport, destination_airport, route_stations,
                    origin_timezone, destination_timezone,
                    scheduled_departure_utc, estimated_departure_utc,
                    scheduled_arrival_utc, estimated_arrival_utc, gate)
                VALUES (?, ?, ?, ?, ?, ?::text[], 'Australia/Sydney', 'Australia/Sydney',
                        ?::timestamptz, ?::timestamptz, ?::timestamptz, ?::timestamptz, '12')
                RETURNING flight_id::text
                """,
                String.class,
                carrier,
                number,
                serviceDate(),
                origin,
                destination,
                routeStations,
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

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private HttpResponse<String> get(String path, String token)
            throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(uri(path)).GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private SseStream openStream(String token, String lastEventId, String stations)
            throws Exception {
        var path = "/api/events/stream" + (stations == null ? "" : "?stations=" + stations);
        var request = HttpRequest.newBuilder(uri(path))
                .header("Accept", "text/event-stream")
                .header("Authorization", "Bearer " + token);
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

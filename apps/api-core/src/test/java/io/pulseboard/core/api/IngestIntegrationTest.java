package io.pulseboard.core.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.pulseboard.core.api.auth.AuthDtos.TokenResponse;
import io.pulseboard.core.api.ingest.IngestDtos.IngestResponse;
import io.pulseboard.core.api.ingest.IngestDtos.RejectedPoint;
import io.pulseboard.core.support.IngestPayloads;
import io.pulseboard.core.support.IngestPayloads.Point;
import io.pulseboard.core.support.IntegrationTest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/** {@code POST /api/v1/ingest}: storage, per-point rejection, tenant isolation, schema and size limits. */
class IngestIntegrationTest extends IntegrationTest {

    private static final String UNKNOWN_METRIC = "unknown metric";
    private static final String VALIDATION_PROBLEM = "urn:pulseboard:problem:validation";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @LocalServerPort
    private int port;

    private TokenResponse owner;
    private UUID metricId;
    private String apiKey;
    private Instant now;

    @BeforeEach
    void setUp() {
        owner = registerTenant();
        UUID serviceId = createService(owner.accessToken(), "checkout").id();
        metricId = createMetric(owner.accessToken(), serviceId, "latency_ms").id();
        apiKey = issueApiKey(owner.accessToken(), "agent").key();
        now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }

    @Test
    void storesPointsAndAnswersAccepted() {
        IngestResponse response = ingest(apiKey, IngestPayloads.batch(List.of(
                Point.of(metricId, now, 1.5),
                Point.of(metricId, now.plusSeconds(1), -2.25),
                Point.of(metricId, now.plusSeconds(2), 0))))
                .expectStatus().isAccepted()
                .expectBody(IngestResponse.class).returnResult().getResponseBody();

        assertThat(response.accepted()).isEqualTo(3);
        assertThat(response.rejected()).isEmpty();
        assertThat(jdbcTemplate.queryForList("SELECT value FROM metric_point WHERE metric_id = ? ORDER BY ts",
                Double.class, metricId)).containsExactly(1.5, -2.25, 0.0);
    }

    @Test
    void sameMetricAndTimestampIsUpsertedLastValueWins() {
        IngestResponse response = ingest(apiKey, IngestPayloads.batch(List.of(
                Point.of(metricId, now, 1), Point.of(metricId, now.plusSeconds(1), 5), Point.of(metricId, now, 2))))
                .expectStatus().isAccepted()
                .expectBody(IngestResponse.class).returnResult().getResponseBody();
        // accepted counts stored rows: the superseded duplicate is reported, not counted twice.
        assertThat(response.accepted()).isEqualTo(2);
        assertThat(response.rejected()).containsExactly(
                new RejectedPoint(0, "superseded by a later point with the same metricId and ts"));
        assertThat(storedValue(metricId, now)).isEqualTo(2.0);
        assertThat(rowCount(metricId)).isEqualTo(2);
        jdbcTemplate.update("DELETE FROM metric_point WHERE metric_id = ? AND ts <> ?", metricId,
                java.sql.Timestamp.from(now));

        ingest(apiKey, IngestPayloads.single(metricId, now, 3)).expectStatus().isAccepted();
        assertThat(storedValue(metricId, now)).isEqualTo(3.0);
        assertThat(rowCount(metricId)).isEqualTo(1);
    }

    @Test
    void foreignAndUnknownMetricsAreRejectedAlikeAndForeignDataIsUntouched() {
        // Tenant A stores a value; tenant B then tries to write to A's metric.
        ingest(apiKey, IngestPayloads.single(metricId, now, 42)).expectStatus().isAccepted();
        TokenResponse other = registerTenant();
        UUID otherMetric = createMetric(other.accessToken(),
                createService(other.accessToken(), "checkout").id(), "latency_ms").id();
        String otherKey = issueApiKey(other.accessToken(), "agent").key();

        IngestResponse response = ingest(otherKey, IngestPayloads.batch(List.of(
                Point.of(metricId, now, 99),
                Point.of(UUID.randomUUID(), now, 99),
                Point.of(otherMetric, now, 7))))
                .expectStatus().isAccepted()
                .expectBody(IngestResponse.class).returnResult().getResponseBody();

        assertThat(response.accepted()).isEqualTo(1);
        assertThat(response.rejected()).containsExactly(
                new RejectedPoint(0, UNKNOWN_METRIC), new RejectedPoint(1, UNKNOWN_METRIC));
        assertThat(storedValue(metricId, now)).as("foreign tenant's value").isEqualTo(42.0);
        assertThat(rowCount(metricId)).isEqualTo(1);
        assertThat(storedValue(otherMetric, now)).isEqualTo(7.0);
    }

    @Test
    void timestampsWithoutStorageAreRejectedPerPointWithoutFailingTheBatch() {
        IngestResponse response = ingest(apiKey, IngestPayloads.batch(List.of(
                Point.of(metricId, Instant.parse("2000-01-01T00:00:00Z"), 1),
                Point.of(metricId, now, 2),
                Point.of(metricId, Instant.parse("2200-01-01T00:00:00Z"), 3))))
                .expectStatus().isAccepted()
                .expectBody(IngestResponse.class).returnResult().getResponseBody();

        assertThat(response.accepted()).isEqualTo(1);
        assertThat(response.rejected()).extracting(RejectedPoint::index).containsExactly(0, 2);
        assertThat(response.rejected()).extracting(RejectedPoint::reason)
                .containsOnly("timestamp is outside the range accepted for storage");
        assertThat(rowCount(metricId)).isEqualTo(1);
    }

    @Test
    void valuesBeyondTheDoubleRangeOrTheMagnitudeBoundAreRejectedPerPoint() {
        IngestResponse response = ingest(apiKey, IngestPayloads.batch(List.of(
                new Point(metricId, now.toString(), "1e400"),
                new Point(metricId, now.plusSeconds(1).toString(), "-1e400"),
                new Point(metricId, now.plusSeconds(2).toString(), "1" + "0".repeat(400)),
                new Point(metricId, now.plusSeconds(3).toString(), "1.7976931348623157e308"),
                new Point(metricId, now.plusSeconds(4).toString(), "-2e100"),
                new Point(metricId, now.plusSeconds(5).toString(), "1e101"),
                new Point(metricId, now.plusSeconds(6).toString(), "1e100"),
                new Point(metricId, now.plusSeconds(7).toString(), "-1e100"))))
                .expectStatus().isAccepted()
                .expectBody(IngestResponse.class).returnResult().getResponseBody();

        String tooLarge = "value magnitude must not exceed 1e100";
        assertThat(response.accepted()).isEqualTo(2);
        assertThat(response.rejected()).containsExactly(
                new RejectedPoint(0, "value must be a finite number"),
                new RejectedPoint(1, "value must be a finite number"),
                new RejectedPoint(2, "value must be a finite number"),
                new RejectedPoint(3, tooLarge),
                new RejectedPoint(4, tooLarge),
                new RejectedPoint(5, tooLarge));
        assertThat(storedValue(metricId, now.plusSeconds(6))).isEqualTo(1e100);
    }

    @Test
    void nonStandardNumberLiteralsAreNotJson() {
        for (String literal : List.of("NaN", "Infinity", "-Infinity")) {
            ingest(apiKey, IngestPayloads.batch(List.of(new Point(metricId, now.toString(), literal))))
                    .expectStatus().isBadRequest()
                    .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                    .expectBody().jsonPath("$.type").isEqualTo(VALIDATION_PROBLEM);
        }
        assertThat(rowCount(metricId)).isZero();
    }

    @Test
    void schemaViolationsRejectTheWholeBatch() {
        String validPoint = "{\"metricId\":\"" + metricId + "\",\"ts\":\"" + now + "\",\"value\":1}";
        List<String> invalidBodies = List.of(
                "{}",
                "{\"points\":[]}",
                "{\"points\":[" + validPoint + "],\"extra\":true}",
                "{\"points\":[{\"metricId\":\"" + metricId + "\",\"ts\":\"" + now + "\"}]}",
                "{\"points\":[{\"metricId\":\"not-a-uuid\",\"ts\":\"" + now + "\",\"value\":1}]}",
                "{\"points\":[{\"metricId\":\"" + metricId + "\",\"ts\":\"yesterday\",\"value\":1}]}",
                "{\"points\":[{\"metricId\":\"" + metricId + "\",\"ts\":\"2026-11-10T10:00:00\",\"value\":1}]}",
                "{\"points\":[{\"metricId\":\"" + metricId + "\",\"ts\":\"" + now + "\",\"value\":\"1\"}]}",
                "{\"points\":[" + validPoint + "],\"source\":\"" + "x".repeat(129) + "\"}",
                "[" + validPoint + "]");
        for (String body : invalidBodies) {
            ingest(apiKey, body)
                    .expectStatus().isBadRequest()
                    .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                    .expectBody()
                    .jsonPath("$.type").isEqualTo(VALIDATION_PROBLEM)
                    .jsonPath("$.errors[0].field").exists()
                    .jsonPath("$.errors[0].message").exists();
        }
        ingest(apiKey, "{\"points\":[" + validPoint + "]}")
                .expectStatus().isAccepted();
        assertThat(rowCount(metricId)).isEqualTo(1);
    }

    @Test
    void schemaErrorsPointAtTheOffendingField() {
        ingest(apiKey, "{\"points\":[{\"metricId\":\"" + metricId + "\",\"ts\":\"" + now + "\",\"value\":1},"
                + "{\"metricId\":\"" + metricId + "\",\"ts\":\"yesterday\",\"value\":1}]}")
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errors[0].field").isEqualTo("/points/1/ts")
                // Messages are English whatever the server's default locale is.
                .jsonPath("$.errors[0].message").value(String.class,
                        message -> assertThat(message).contains("date-time"));
    }

    @Test
    void malformedJsonDuplicateKeysAndTrailingContentAreRejected() {
        String point = "{\"metricId\":\"" + metricId + "\",\"ts\":\"" + now + "\",\"value\":1}";
        for (String body : List.of(
                "{\"points\":[" + point,
                "{\"points\":[" + point + "],\"points\":[" + point + "]}",
                "{\"points\":[" + point + "]} {}",
                "")) {
            ingest(apiKey, body)
                    .expectStatus().isBadRequest()
                    .expectBody().jsonPath("$.type").isEqualTo(VALIDATION_PROBLEM);
        }
        assertThat(rowCount(metricId)).isZero();
    }

    @Test
    void moreThanOneThousandPointsAreRejected() {
        List<Point> points = new ArrayList<>();
        for (int i = 0; i < 1001; i++) {
            points.add(Point.of(metricId, now.plusSeconds(i), i));
        }
        ingest(apiKey, IngestPayloads.batch(points))
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errors[0].field").isEqualTo("/points");

        ingest(apiKey, IngestPayloads.batch(points.subList(0, 1000)))
                .expectStatus().isAccepted()
                .expectBody().jsonPath("$.accepted").isEqualTo(1000);
    }

    @Test
    void oversizeBodyIsRejectedBeforeParsing() {
        // Valid JSON padded with whitespace: only the raw size makes it invalid.
        String body = IngestPayloads.single(metricId, now, 1).replace("{\"points\"",
                "{" + " ".repeat(600 * 1024) + "\"points\"");

        ingest(apiKey, body)
                .expectStatus().isEqualTo(413)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.type").isEqualTo("urn:pulseboard:problem:payload-too-large");
        assertThat(rowCount(metricId)).isZero();
    }

    @Test
    void oversizeChunkedBodyIsRejected() throws IOException, InterruptedException {
        byte[] body = IngestPayloads.single(metricId, now, 1).replace("{\"points\"",
                "{" + " ".repeat(600 * 1024) + "\"points\"").getBytes(StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/ingest"))
                .timeout(Duration.ofSeconds(30))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header(API_KEY_HEADER, apiKey)
                // A publisher of unknown length makes the client use chunked transfer encoding.
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body)))
                .build();

        try (HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(413);
            assertThat(response.body()).contains("urn:pulseboard:problem:payload-too-large");
        }
        assertThat(rowCount(metricId)).isZero();
    }

    @Test
    void onlyJsonIsAccepted() {
        client.post().uri("/api/v1/ingest")
                .header(API_KEY_HEADER, apiKey)
                .contentType(MediaType.TEXT_PLAIN)
                .body(IngestPayloads.single(metricId, now, 1))
                .exchange()
                .expectStatus().isEqualTo(415);
    }

    @Test
    void ingestionRequiresAnApiKey() {
        client.post().uri("/api/v1/ingest")
                .contentType(MediaType.APPLICATION_JSON)
                .body(IngestPayloads.single(metricId, now, 1))
                .exchange()
                .expectStatus().isUnauthorized();
        client.post().uri("/api/v1/ingest")
                .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(IngestPayloads.single(metricId, now, 1))
                .exchange()
                .expectStatus().isForbidden();
        ingest("pb_" + "x".repeat(43), IngestPayloads.single(metricId, now, 1))
                .expectStatus().isUnauthorized();
        assertThat(rowCount(metricId)).isZero();
    }

    private Double storedValue(UUID metric, Instant ts) {
        return jdbcTemplate.queryForObject("SELECT value FROM metric_point WHERE metric_id = ? AND ts = ?",
                Double.class, metric, java.sql.Timestamp.from(ts));
    }

    private int rowCount(UUID metric) {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM metric_point WHERE metric_id = ?",
                Integer.class, metric);
        return count == null ? 0 : count;
    }
}

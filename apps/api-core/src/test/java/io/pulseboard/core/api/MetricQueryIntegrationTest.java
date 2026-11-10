package io.pulseboard.core.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.pulseboard.core.api.auth.AuthDtos.TokenResponse;
import io.pulseboard.core.api.metrics.MetricPointsDtos.MetricPointBucket;
import io.pulseboard.core.api.metrics.MetricPointsDtos.MetricPointsResponse;
import io.pulseboard.core.domain.catalog.Aggregation;
import io.pulseboard.core.support.IngestPayloads;
import io.pulseboard.core.support.IngestPayloads.Point;
import io.pulseboard.core.support.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * {@code GET /api/v1/metrics/{id}/points}: server-side downsampling with {@code date_bin}, checked against a
 * known series for every aggregation, the 2000-bucket limit, parameter validation and tenant isolation.
 */
class MetricQueryIntegrationTest extends IntegrationTest {

    /** One sample per minute for three hours. */
    private static final int SAMPLES = 180;
    private static final Duration SPACING = Duration.ofMinutes(1);
    private static final double TOLERANCE = 1e-9;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private TokenResponse owner;
    private String apiKey;
    private Instant base;
    private double[] values;
    private final Map<Aggregation, UUID> metrics = new EnumMap<>(Aggregation.class);

    @BeforeEach
    void setUp() {
        owner = registerTenant();
        UUID serviceId = createService(owner.accessToken(), "checkout").id();
        for (Aggregation aggregation : Aggregation.values()) {
            metrics.put(aggregation, createMetric(owner.accessToken(), serviceId,
                    "series_" + aggregation.name().toLowerCase(), aggregation.name()).id());
        }
        apiKey = issueApiKey(owner.accessToken(), "agent").key();

        // Start of the current UTC month: always inside a provisioned partition and aligned to every step.
        base = YearMonth.now(ZoneOffset.UTC).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Random random = new Random(20261110L);
        values = new double[SAMPLES];
        List<Point> points = new ArrayList<>();
        for (int i = 0; i < SAMPLES; i++) {
            values[i] = Math.round(random.nextGaussian() * 1000) / 10.0 + 50;
            for (UUID metric : metrics.values()) {
                points.add(Point.of(metric, base.plus(SPACING.multipliedBy(i)), values[i]));
            }
        }
        ingest(apiKey, IngestPayloads.batch(points))
                .expectStatus().isAccepted()
                .expectBody().jsonPath("$.accepted").isEqualTo(points.size());
    }

    @Test
    void everyAggregationMatchesTheKnownSeries() {
        Duration step = Duration.ofMinutes(15);
        int perBucket = (int) (step.toMinutes() / SPACING.toMinutes());
        for (Aggregation aggregation : Aggregation.values()) {
            MetricPointsResponse response = query(metrics.get(aggregation), base,
                    base.plus(SPACING.multipliedBy(SAMPLES)), "PT15M")
                    .expectStatus().isOk()
                    .expectBody(MetricPointsResponse.class).returnResult().getResponseBody();

            assertThat(response.aggregation()).isEqualTo(aggregation);
            assertThat(response.step()).isEqualTo("PT15M");
            assertThat(response.points()).hasSize(SAMPLES / perBucket);
            for (int b = 0; b < response.points().size(); b++) {
                MetricPointBucket bucket = response.points().get(b);
                double[] window = Arrays.copyOfRange(values, b * perBucket, (b + 1) * perBucket);
                assertThat(bucket.ts()).isEqualTo(base.plus(step.multipliedBy(b)));
                assertThat(bucket.count()).isEqualTo(perBucket);
                assertThat(bucket.value()).as("%s of bucket %d", aggregation, b)
                        .isCloseTo(expected(aggregation, window), within(TOLERANCE));
            }
        }
    }

    @Test
    void bucketsAreAlignedToTheEpochAndTheRangeIsHalfOpen() {
        // From minute 3 to minute 25: buckets start at 0 (7 samples: 3..9), 10 (10..19) and 20 (20..24).
        MetricPointsResponse response = query(metrics.get(Aggregation.SUM), base.plus(Duration.ofMinutes(3)),
                base.plus(Duration.ofMinutes(25)), "PT10M")
                .expectStatus().isOk()
                .expectBody(MetricPointsResponse.class).returnResult().getResponseBody();

        assertThat(response.points()).extracting(MetricPointBucket::ts).containsExactly(
                base, base.plus(Duration.ofMinutes(10)), base.plus(Duration.ofMinutes(20)));
        assertThat(response.points()).extracting(MetricPointBucket::count).containsExactly(7L, 10L, 5L);
        assertThat(response.points().get(2).value())
                .isCloseTo(Arrays.stream(values, 20, 25).sum(), within(TOLERANCE));
    }

    @Test
    void stepIsChosenAutomaticallyWithinTheBucketLimit() {
        MetricPointsResponse hour = query(metrics.get(Aggregation.AVG), base, base.plus(Duration.ofHours(1)), null)
                .expectStatus().isOk()
                .expectBody(MetricPointsResponse.class).returnResult().getResponseBody();
        assertThat(hour.step()).isEqualTo("PT5S");
        assertThat(hour.points()).hasSize(60);

        MetricPointsResponse year = query(metrics.get(Aggregation.MAX), base, base.plus(Duration.ofDays(365)), null)
                .expectStatus().isOk()
                .expectBody(MetricPointsResponse.class).returnResult().getResponseBody();
        assertThat(year.step()).isEqualTo("PT6H");
        assertThat(year.points()).hasSize(1);
        assertThat(year.points().getFirst().value())
                .isCloseTo(Arrays.stream(values).max().orElseThrow(), within(TOLERANCE));
    }

    @Test
    void explicitStepYieldingTooManyBucketsIsRejected() {
        query(metrics.get(Aggregation.AVG), base, base.plus(Duration.ofDays(1)), "PT1S")
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.type").isEqualTo("urn:pulseboard:problem:validation")
                .jsonPath("$.errors[0].field").isEqualTo("step")
                .jsonPath("$.errors[0].message").value(String.class,
                        message -> assertThat(message).contains("2000").contains("PT44S"));

        query(metrics.get(Aggregation.AVG), base, base.plus(Duration.ofDays(1)), "PT44S")
                .expectStatus().isOk();
    }

    @Test
    void invalidParametersAreRejected() {
        UUID metric = metrics.get(Aggregation.AVG);
        Instant to = base.plus(Duration.ofHours(1));
        query(metric, to, base, null).expectStatus().isBadRequest();
        query(metric, base, base, null).expectStatus().isBadRequest();
        query(metric, base, base.plus(Duration.ofDays(367)), null).expectStatus().isBadRequest();
        query(metric, base, to, "five minutes").expectStatus().isBadRequest();
        query(metric, base, to, "PT0.5S").expectStatus().isBadRequest();
        query(metric, base, to, "-PT5M").expectStatus().isBadRequest();
        client.get().uri("/api/v1/metrics/{id}/points?to={to}", metric, to)
                .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken()))
                .exchange()
                .expectStatus().isBadRequest();
        client.get().uri("/api/v1/metrics/{id}/points?from=yesterday&to={to}", metric, to)
                .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken()))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void offsetsInTheRangeAreHonoured() {
        String from = base.atOffset(ZoneOffset.ofHours(-3)).toString();
        String to = base.plus(Duration.ofMinutes(30)).atOffset(ZoneOffset.ofHours(-3)).toString();
        client.get().uri("/api/v1/metrics/" + metrics.get(Aggregation.SUM) + "/points?from=" + from + "&to=" + to
                        + "&step=PT30M")
                .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken()))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.points.length()").isEqualTo(1)
                .jsonPath("$.points[0].count").isEqualTo(30);
    }

    /**
     * Storage has microsecond precision. A bound with nanoseconds must select exactly the samples the request
     * describes, and the 2000-bucket check must see the same range as the SQL.
     */
    @Test
    void subMicrosecondBoundsSelectExactlyTheRequestedSamples() {
        UUID metric = metrics.get(Aggregation.SUM);
        for (long nanos : new long[] {100, 499, 500, 600}) {
            // from = base + n ns: the sample at base lies before from and must be excluded.
            MetricPointsResponse later = query(metric, base.plusNanos(nanos), base.plus(Duration.ofMinutes(10)),
                    "PT1M")
                    .expectStatus().isOk()
                    .expectBody(MetricPointsResponse.class).returnResult().getResponseBody();
            assertThat(later.points()).as("from = base + %d ns", nanos)
                    .extracting(MetricPointBucket::ts).doesNotContain(base);
            assertThat(later.points()).hasSize(9);
            assertThat(later.from()).isEqualTo(base.plusNanos(1000));

            // to = base + n ns: the sample at base lies before to and must be included.
            MetricPointsResponse first = query(metric, base.minus(Duration.ofMinutes(1)), base.plusNanos(nanos),
                    "PT1M")
                    .expectStatus().isOk()
                    .expectBody(MetricPointsResponse.class).returnResult().getResponseBody();
            assertThat(first.points()).as("to = base + %d ns", nanos).hasSize(1);
            assertThat(first.points().getFirst().value()).isEqualTo(values[0]);

            // [base, base + 2000 s + n ns) touches 2001 one-second buckets: rejected, like the SQL would see it.
            query(metric, base, base.plusSeconds(2000).plusNanos(nanos), "PT1S")
                    .expectStatus().isBadRequest()
                    .expectBody().jsonPath("$.errors[0].field").isEqualTo("step");
        }
        query(metric, base, base.plusSeconds(2000), "PT1S").expectStatus().isOk();
    }

    /**
     * Ingestion keeps |value| <= 1e100, so aggregates cannot overflow. Samples written by other means (here
     * straight into the table) must still never produce a non-number in JSON or a misleading 409.
     */
    @Test
    void aggregatesOfSamplesBeyondTheIngestionBoundNeverYieldNonNumbers() {
        TokenResponse other = registerTenant();
        UUID serviceId = createService(other.accessToken(), "legacy").id();
        UUID avg = createMetric(other.accessToken(), serviceId, "legacy_avg", "AVG").id();
        UUID p95 = createMetric(other.accessToken(), serviceId, "legacy_p95", "P95").id();
        insertDirectly(avg, base, 1e155);
        insertDirectly(avg, base.plusSeconds(1), 0);
        insertDirectly(p95, base, 1e308);
        insertDirectly(p95, base.plusSeconds(1), -1e308);
        Instant to = base.plus(Duration.ofMinutes(1));

        client.get().uri(pointsUri(avg, base, to, "PT1M"))
                .header(HttpHeaders.AUTHORIZATION, bearer(other.accessToken()))
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody().jsonPath("$.type").isEqualTo("urn:pulseboard:problem:aggregate-out-of-range");
        client.get().uri(pointsUri(p95, base, to, "PT1M"))
                .header(HttpHeaders.AUTHORIZATION, bearer(other.accessToken()))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.points[0].count").isEqualTo(2)
                .jsonPath("$.points[0].value").isEmpty();
    }

    private void insertDirectly(UUID metric, Instant ts, double value) {
        jdbcTemplate.update("INSERT INTO metric_point (metric_id, ts, value) VALUES (?, ?, ?)", metric,
                java.sql.Timestamp.from(ts), value);
    }

    @Test
    void emptyRangeYieldsNoBuckets() {
        Instant later = base.plus(Duration.ofDays(2));
        query(metrics.get(Aggregation.AVG), later, later.plus(Duration.ofHours(1)), null)
                .expectStatus().isOk()
                .expectBody().jsonPath("$.points").isEmpty();
    }

    @Test
    void metricsOfOtherTenantsAreNotFoundLikeUnknownOnes() {
        TokenResponse other = registerTenant();
        Instant to = base.plus(Duration.ofHours(1));

        client.get().uri(pointsUri(metrics.get(Aggregation.AVG), base, to, null))
                .header(HttpHeaders.AUTHORIZATION, bearer(other.accessToken()))
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.type").isEqualTo("urn:pulseboard:problem:not-found");
        client.get().uri(pointsUri(UUID.randomUUID(), base, to, null))
                .header(HttpHeaders.AUTHORIZATION, bearer(other.accessToken()))
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.type").isEqualTo("urn:pulseboard:problem:not-found");
    }

    @Test
    void queriesRequireAUserToken() {
        Instant to = base.plus(Duration.ofHours(1));
        client.get().uri(pointsUri(metrics.get(Aggregation.AVG), base, to, null))
                .exchange()
                .expectStatus().isUnauthorized();
        client.get().uri(pointsUri(metrics.get(Aggregation.AVG), base, to, null))
                .header(API_KEY_HEADER, apiKey)
                .exchange()
                .expectStatus().isForbidden();
    }

    private RestTestClient.ResponseSpec query(UUID metricId, Instant from, Instant to, String step) {
        return client.get().uri(pointsUri(metricId, from, to, step))
                .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken()))
                .exchange();
    }

    /** Instants print in UTC ({@code Z}), so the query needs no percent-encoding. */
    private static String pointsUri(UUID metricId, Instant from, Instant to, String step) {
        return "/api/v1/metrics/" + metricId + "/points?from=" + from + "&to=" + to
                + (step == null ? "" : "&step=" + step);
    }

    /** Reference implementation of each aggregation; P95 is the continuous percentile (linear interpolation). */
    private static double expected(Aggregation aggregation, double[] window) {
        return switch (aggregation) {
            case AVG -> Arrays.stream(window).average().orElseThrow();
            case SUM -> Arrays.stream(window).sum();
            case MAX -> Arrays.stream(window).max().orElseThrow();
            case P95 -> {
                double[] sorted = window.clone();
                Arrays.sort(sorted);
                double position = 0.95 * (sorted.length - 1);
                int lower = (int) Math.floor(position);
                int upper = (int) Math.ceil(position);
                yield sorted[lower] + (position - lower) * (sorted[upper] - sorted[lower]);
            }
        };
    }
}

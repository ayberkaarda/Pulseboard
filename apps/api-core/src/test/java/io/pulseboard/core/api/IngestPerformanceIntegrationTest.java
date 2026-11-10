package io.pulseboard.core.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.pulseboard.core.api.auth.AuthDtos.TokenResponse;
import io.pulseboard.core.support.IngestPayloads;
import io.pulseboard.core.support.IngestPayloads.Point;
import io.pulseboard.core.support.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Phase 2 verification: 10 000 points across 5 metrics are ingested over HTTP in under 3 seconds. */
class IngestPerformanceIntegrationTest extends IntegrationTest {

    private static final Logger LOG = LoggerFactory.getLogger(IngestPerformanceIntegrationTest.class);
    private static final int METRICS = 5;
    private static final int POINTS_PER_METRIC = 2000;
    private static final int BATCH_SIZE = 1000;
    private static final Duration BUDGET = Duration.ofSeconds(3);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void tenThousandPointsAcrossFiveMetricsInUnderThreeSeconds() {
        TokenResponse owner = registerTenant();
        UUID serviceId = createService(owner.accessToken(), "checkout").id();
        List<UUID> metrics = new ArrayList<>();
        for (int m = 0; m < METRICS; m++) {
            metrics.add(createMetric(owner.accessToken(), serviceId, "metric_" + m).id());
        }
        String apiKey = issueApiKey(owner.accessToken(), "agent").key();

        // Points interleave the metrics, so every batch writes to all five.
        Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        List<Point> points = new ArrayList<>(METRICS * POINTS_PER_METRIC);
        for (int i = 0; i < POINTS_PER_METRIC; i++) {
            for (int m = 0; m < METRICS; m++) {
                points.add(Point.of(metrics.get(m), base.plusSeconds(i), Math.sin(i / 10.0) * 100 + m));
            }
        }
        List<String> batches = new ArrayList<>();
        for (int from = 0; from < points.size(); from += BATCH_SIZE) {
            batches.add(IngestPayloads.batch(points.subList(from, from + BATCH_SIZE)));
        }

        long started = System.nanoTime();
        for (String batch : batches) {
            ingest(apiKey, batch)
                    .expectStatus().isAccepted()
                    .expectBody().jsonPath("$.accepted").isEqualTo(BATCH_SIZE).jsonPath("$.rejected").isEmpty();
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
        LOG.info("Ingested {} points across {} metrics in {} batches in {} ms", points.size(), METRICS,
                batches.size(), elapsed.toMillis());

        assertThat(elapsed).as("ingestion time").isLessThan(BUDGET);
        Integer stored = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM metric_point WHERE metric_id = ANY (?::uuid[])", Integer.class,
                (Object) metrics.stream().map(UUID::toString).toArray(String[]::new));
        assertThat(stored).isEqualTo(METRICS * POINTS_PER_METRIC);
    }
}

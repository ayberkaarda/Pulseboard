package io.pulseboard.core.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.pulseboard.core.api.auth.AuthDtos.TokenResponse;
import io.pulseboard.core.support.IntegrationTest;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * Cost limits of the metric query: a transaction-local statement timeout and a per-tenant query quota. Runs in
 * its own context with a tiny timeout and a quota of three.
 */
@TestPropertySource(properties = {
    "pulseboard.query.statement-timeout=20ms",
    "pulseboard.rate-limit.query.tenant-capacity=3"
})
class MetricQueryLimitsIntegrationTest extends IntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void aQueryExceedingTheStatementTimeoutIsCancelledWith503() {
        TokenResponse owner = registerTenant();
        UUID metric = createMetric(owner.accessToken(), createService(owner.accessToken(), "big").id(),
                "big_series", "P95").id();
        Instant base = YearMonth.now(ZoneOffset.UTC).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        // 500 000 samples, one per millisecond: sorting them for P95 takes far longer than 20 ms.
        jdbcTemplate.update("INSERT INTO metric_point (metric_id, ts, value) "
                + "SELECT ?, ?::timestamptz + g * interval '1 millisecond', g FROM generate_series(0, 499999) g",
                metric, java.sql.Timestamp.from(base));

        client.get().uri("/api/v1/metrics/" + metric + "/points?from=" + base + "&to=" + base.plusSeconds(3600)
                        + "&step=PT1H")
                .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken()))
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().exists(HttpHeaders.RETRY_AFTER)
                .expectBody().jsonPath("$.type").isEqualTo("urn:pulseboard:problem:query-timeout");

        // The timeout is local to the query's transaction; the pooled connections are not left with it.
        assertThat(jdbcTemplate.queryForObject("SHOW statement_timeout", String.class)).isEqualTo("0");
    }

    @Test
    void queriesAreLimitedPerTenant() {
        TokenResponse limited = registerTenant();
        UUID metric = createMetric(limited.accessToken(), createService(limited.accessToken(), "svc").id(),
                "small_series", "AVG").id();
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        String uri = "/api/v1/metrics/" + metric + "/points?from=" + from + "&to=" + from.plusSeconds(60);
        for (int i = 0; i < 3; i++) {
            client.get().uri(uri).header(HttpHeaders.AUTHORIZATION, bearer(limited.accessToken()))
                    .exchange().expectStatus().isOk();
        }
        client.get().uri(uri).header(HttpHeaders.AUTHORIZATION, bearer(limited.accessToken()))
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().exists(HttpHeaders.RETRY_AFTER)
                .expectBody().jsonPath("$.type").isEqualTo("urn:pulseboard:problem:rate-limited");

        // Another tenant has its own bucket.
        TokenResponse other = registerTenant();
        UUID otherMetric = createMetric(other.accessToken(), createService(other.accessToken(), "svc").id(),
                "small_series", "AVG").id();
        client.get().uri("/api/v1/metrics/" + otherMetric + "/points?from=" + from + "&to=" + from.plusSeconds(60))
                .header(HttpHeaders.AUTHORIZATION, bearer(other.accessToken()))
                .exchange()
                .expectStatus().isOk();
    }
}

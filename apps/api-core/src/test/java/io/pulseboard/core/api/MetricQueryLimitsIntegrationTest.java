package io.pulseboard.core.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import io.pulseboard.core.api.auth.AuthDtos.TokenResponse;
import io.pulseboard.core.support.IntegrationTest;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
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

    /** Session value the pool connection carries, so a leaked query timeout is told apart from it. */
    private static final String SESSION_TIMEOUT = "7s";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    /**
     * The pool is cut down to a single connection, so every statement of this test runs on the same backend. The
     * query timeout is transaction-local: after a committed query and after a cancelled, rolled back one the
     * session must still carry its own value.
     */
    @Test
    void theQueryTimeoutNeverLeaksIntoThePooledConnection() throws Exception {
        limitPoolToOneConnection();
        TokenResponse owner = registerTenant();
        UUID small = createMetric(owner.accessToken(), createService(owner.accessToken(), "small").id(),
                "small_series", "AVG").id();
        UUID big = createMetric(owner.accessToken(), createService(owner.accessToken(), "big").id(),
                "big_series", "P95").id();
        Instant base = YearMonth.now(ZoneOffset.UTC).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        insertDenseSeries(big, base);
        jdbcTemplate.execute("SET statement_timeout = '" + SESSION_TIMEOUT + "'");
        try {
            Integer backend = jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class);
            assertThat(jdbcTemplate.queryForObject("SHOW statement_timeout", String.class))
                    .isEqualTo(SESSION_TIMEOUT);

            client.get().uri(pointsUri(small, base))
                    .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken()))
                    .exchange()
                    .expectStatus().isOk();
            assertThat(jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class)).isEqualTo(backend);
            assertThat(jdbcTemplate.queryForObject("SHOW statement_timeout", String.class))
                    .as("session timeout after a committed query").isEqualTo(SESSION_TIMEOUT);

            client.get().uri(pointsUri(big, base))
                    .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken()))
                    .exchange()
                    .expectStatus().isEqualTo(503);
            assertThat(jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class)).isEqualTo(backend);
            assertThat(jdbcTemplate.queryForObject("SHOW statement_timeout", String.class))
                    .as("session timeout after a cancelled query").isEqualTo(SESSION_TIMEOUT);
        } finally {
            jdbcTemplate.execute("RESET statement_timeout");
        }
    }

    @Test
    void aQueryExceedingTheStatementTimeoutIsCancelledWith503() {
        TokenResponse owner = registerTenant();
        UUID metric = createMetric(owner.accessToken(), createService(owner.accessToken(), "big").id(),
                "big_series", "P95").id();
        Instant base = YearMonth.now(ZoneOffset.UTC).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        insertDenseSeries(metric, base);

        client.get().uri(pointsUri(metric, base))
                .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken()))
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().exists(HttpHeaders.RETRY_AFTER)
                .expectBody().jsonPath("$.type").isEqualTo("urn:pulseboard:problem:query-timeout");
    }

    /**
     * Flyway needs a second connection while the context starts, so the pool is shrunk once the application is
     * up: the other connections are retired and the pool refills to exactly one.
     */
    private void limitPoolToOneConnection() throws Exception {
        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
        pool.getHikariConfigMXBean().setMinimumIdle(1);
        pool.getHikariConfigMXBean().setMaximumPoolSize(1);
        pool.getHikariPoolMXBean().softEvictConnections();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (pool.getHikariPoolMXBean().getTotalConnections() != 1) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("The pool did not shrink to one connection");
            }
            Thread.sleep(50);
        }
        assertThat(pool.getHikariConfigMXBean().getMaximumPoolSize()).isEqualTo(1);
    }

    /** 500 000 samples, one per millisecond: sorting them for P95 takes far longer than 20 ms. */
    private void insertDenseSeries(UUID metric, Instant base) {
        jdbcTemplate.update("INSERT INTO metric_point (metric_id, ts, value) "
                + "SELECT ?, ?::timestamptz + g * interval '1 millisecond', g FROM generate_series(0, 499999) g",
                metric, java.sql.Timestamp.from(base));
    }

    private static String pointsUri(UUID metric, Instant base) {
        return "/api/v1/metrics/" + metric + "/points?from=" + base + "&to=" + base.plusSeconds(3600)
                + "&step=PT1H";
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

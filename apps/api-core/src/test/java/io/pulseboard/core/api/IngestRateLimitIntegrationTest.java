package io.pulseboard.core.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.pulseboard.core.api.auth.AuthDtos.TokenResponse;
import io.pulseboard.core.support.IngestPayloads;
import io.pulseboard.core.support.IntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.EntityExchangeResult;

/**
 * Per-tenant ingestion token bucket with the production quotas: FREE 60 and PRO 600 batches per minute. The bucket
 * refills continuously (one FREE token per second), so the number of admitted requests is bounded by the capacity
 * plus what refilled while the burst was running.
 */
class IngestRateLimitIntegrationTest extends IntegrationTest {

    private static final int BURST = 80;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${pulseboard.ingest.free-batch-capacity}")
    private int freeCapacity;

    @Value("${pulseboard.ingest.pro-batch-capacity}")
    private int proCapacity;

    @Test
    void freeTenantIsThrottledAtItsCapacityWith429ProblemAndRetryAfter() throws Exception {
        assertThat(freeCapacity).as("production FREE quota").isEqualTo(60);
        Tenant tenant = tenant();

        long started = System.nanoTime();
        List<EntityExchangeResult<String>> results = burst(tenant, BURST);
        double elapsedSeconds = (System.nanoTime() - started) / 1e9;

        List<EntityExchangeResult<String>> accepted = results.stream()
                .filter(result -> result.getStatus().value() == 202).toList();
        List<EntityExchangeResult<String>> throttled = results.stream()
                .filter(result -> result.getStatus().value() == 429).toList();
        long refilledDuringBurst = (long) Math.ceil(elapsedSeconds * freeCapacity / 60.0);
        assertThat(accepted.size() + throttled.size()).as("only 202 or 429").isEqualTo(BURST);
        assertThat(accepted.size()).as("admitted (burst took %.2f s)", elapsedSeconds)
                .isBetween(freeCapacity, freeCapacity + (int) refilledDuringBurst);
        assertThat(throttled).isNotEmpty();
        for (EntityExchangeResult<String> result : throttled) {
            assertThat(result.getResponseHeaders().getContentType())
                    .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
            assertThat(Long.parseLong(result.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER)))
                    .isBetween(1L, 60L);
            assertThat(result.getResponseBody())
                    .contains("urn:pulseboard:problem:rate-limited").contains("\"status\":429");
        }
        assertThat(storedRows(tenant.metricId())).isEqualTo(accepted.size());
    }

    @Test
    void proTenantHasTheLargerQuotaAndBucketsArePerTenant() throws Exception {
        assertThat(proCapacity).as("production PRO quota").isEqualTo(600);
        Tenant pro = tenant();
        jdbcTemplate.update("UPDATE tenant SET plan = 'PRO' WHERE id = (SELECT tenant_id FROM metric_definition "
                + "WHERE id = ?)", pro.metricId());
        Tenant free = tenant();

        assertThat(burst(pro, BURST)).allSatisfy(result -> assertThat(result.getStatus().value()).isEqualTo(202));
        // The PRO tenant's traffic does not drain the FREE tenant's bucket.
        ingest(free.apiKey(), IngestPayloads.single(free.metricId(), Instant.now(), 1)).expectStatus().isAccepted();
    }

    private List<EntityExchangeResult<String>> burst(Tenant tenant, int requests) throws Exception {
        Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        List<Future<EntityExchangeResult<String>>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < requests; i++) {
                String body = IngestPayloads.single(tenant.metricId(), base.plusSeconds(i), i);
                futures.add(executor.submit(() -> ingest(tenant.apiKey(), body)
                        .expectBody(String.class).returnResult()));
            }
        }
        List<EntityExchangeResult<String>> results = new ArrayList<>();
        for (Future<EntityExchangeResult<String>> future : futures) {
            results.add(future.get());
        }
        return results;
    }

    private Tenant tenant() {
        TokenResponse owner = registerTenant();
        UUID serviceId = createService(owner.accessToken(), "checkout").id();
        UUID metricId = createMetric(owner.accessToken(), serviceId, "requests", "SUM").id();
        return new Tenant(metricId, issueApiKey(owner.accessToken(), "agent").key());
    }

    private int storedRows(UUID metricId) {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM metric_point WHERE metric_id = ?",
                Integer.class, metricId);
        return count == null ? 0 : count;
    }

    private record Tenant(UUID metricId, String apiKey) {
    }
}

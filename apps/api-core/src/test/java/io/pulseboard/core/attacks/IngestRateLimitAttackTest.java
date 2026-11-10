package io.pulseboard.core.attacks;

import static org.assertj.core.api.Assertions.assertThat;

import io.pulseboard.core.support.IngestPayloads;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

/**
 * Attempts to get more ingestion batches than the plan allows: many keys of one tenant, forged forwarding and
 * tenant headers, oversize or malformed bodies once throttled, and a plan downgrade after a PRO burst.
 */
class IngestRateLimitAttackTest extends IngestQueryAttackSupport {

    @Value("${pulseboard.ingest.free-batch-capacity}")
    private int freeCapacity;

    @Test
    void manyKeysAndForgedHeadersStillShareTheTenantBucket() throws Exception {
        Tenant tenant = tenant("SUM");
        Tenant other = tenant("SUM");
        List<String> keys = List.of(tenant.apiKey(),
                issueApiKey(tenant.owner().accessToken(), "agent-2").key(),
                issueApiKey(tenant.owner().accessToken(), "agent-3").key());
        Instant base = monthStart();
        int burst = freeCapacity + 20;

        List<Callable<HttpResponse<String>>> tasks = new ArrayList<>();
        for (int i = 0; i < burst; i++) {
            String key = keys.get(i % keys.size());
            String body = IngestPayloads.single(tenant.metricId(), base.plusSeconds(i), i);
            String forged = "198.51.100." + (i % 250 + 1);
            tasks.add(() -> ingestRaw(key, body,
                    "X-Forwarded-For", forged,
                    "X-Real-IP", forged,
                    "Forwarded", "for=" + forged,
                    "X-Tenant-Id", other.owner().tenantId().toString(),
                    "X-Plan", "PRO"));
        }
        long started = System.nanoTime();
        List<HttpResponse<String>> results = parallel(tasks);
        double seconds = (System.nanoTime() - started) / 1e9;

        long admitted = results.stream().filter(r -> r.statusCode() == 202).count();
        long throttled = results.stream().filter(r -> r.statusCode() == 429).count();
        assertThat(admitted + throttled).as("only 202 or 429").isEqualTo(burst);
        assertThat(admitted).as("admitted across 3 keys (burst took %.2f s)", seconds)
                .isBetween((long) freeCapacity, freeCapacity + (long) Math.ceil(seconds * freeCapacity / 60.0));
        assertThat(storedRows(tenant.metricId())).isEqualTo((int) admitted);
        // The forged tenant header did not charge (or write to) the other tenant.
        assertThat(ingestRaw(other.apiKey(), IngestPayloads.single(other.metricId(), base, 1)).statusCode())
                .isEqualTo(202);
        assertThat(storedRows(other.metricId())).isEqualTo(1);
    }

    @Test
    void throttledCallerIsRejectedBeforeTheBodyIsReadOrParsed() throws Exception {
        Tenant tenant = tenant("SUM");
        drain(tenant);

        byte[] oversize = ("{\"points\":[" + " ".repeat(700 * 1024) + "]}").getBytes(StandardCharsets.UTF_8);
        HttpResponse<String> big = sendBytes(INGEST, oversize, false, API_KEY_HEADER, tenant.apiKey(),
                "Content-Type", JSON);
        HttpResponse<String> malformed = ingestRaw(tenant.apiKey(), "{not json");
        HttpResponse<String> chunked = sendBytes(INGEST, oversize, true, API_KEY_HEADER, tenant.apiKey(),
                "Content-Type", JSON);

        for (HttpResponse<String> response : List.of(big, malformed, chunked)) {
            assertThat(response.statusCode()).isEqualTo(429);
            assertThat(header(response, "Retry-After")).isNotBlank();
            assertThat(response.body()).contains("urn:pulseboard:problem:rate-limited");
        }
    }

    @Test
    void planDowngradeDoesNotCarryOverTheProBucket() throws Exception {
        Tenant tenant = tenant("SUM");
        setPlan(tenant, "PRO");
        Instant base = monthStart();
        List<HttpResponse<String>> pro = burst(tenant, base, 100);
        assertThat(pro).allSatisfy(r -> assertThat(r.statusCode()).isEqualTo(202));

        // About 500 PRO tokens are left in Redis; the FREE capacity must cap them.
        setPlan(tenant, "FREE");
        long started = System.nanoTime();
        List<HttpResponse<String>> free = burst(tenant, base.plusSeconds(1000), freeCapacity + 20);
        double seconds = (System.nanoTime() - started) / 1e9;
        long admitted = free.stream().filter(r -> r.statusCode() == 202).count();
        assertThat(admitted).as("admitted after downgrade (%.2f s)", seconds)
                .isLessThanOrEqualTo(freeCapacity + (long) Math.ceil(seconds * freeCapacity / 60.0));
    }

    @Test
    void invalidKeysNeverReachTheLimiterOrTheTenant() throws Exception {
        Tenant tenant = tenant("SUM");
        String body = IngestPayloads.single(tenant.metricId(), monthStart(), 1);
        List<Callable<HttpResponse<String>>> tasks = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            tasks.add(() -> ingestRaw("pb_" + UUID.randomUUID().toString().replace("-", "") + "0123456789a", body));
        }
        assertThat(parallel(tasks)).allSatisfy(r -> assertThat(r.statusCode()).isEqualTo(401));
        // The tenant's own bucket is untouched: a full FREE burst is still admitted.
        assertThat(burst(tenant, monthStart().plusSeconds(10), freeCapacity))
                .allSatisfy(r -> assertThat(r.statusCode()).isEqualTo(202));
    }

    private void drain(Tenant tenant) throws Exception {
        List<HttpResponse<String>> results = burst(tenant, monthStart(), freeCapacity + 10);
        assertThat(results).anySatisfy(r -> assertThat(r.statusCode()).isEqualTo(429));
    }

    private List<HttpResponse<String>> burst(Tenant tenant, Instant base, int requests) throws Exception {
        List<Callable<HttpResponse<String>>> tasks = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            String body = IngestPayloads.single(tenant.metricId(), base.plusSeconds(i), i);
            tasks.add(() -> ingestRaw(tenant.apiKey(), body));
        }
        return parallel(tasks);
    }
}

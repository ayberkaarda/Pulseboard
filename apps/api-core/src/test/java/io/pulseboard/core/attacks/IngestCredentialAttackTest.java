package io.pulseboard.core.attacks;

import static org.assertj.core.api.Assertions.assertThat;

import io.pulseboard.core.support.IngestPayloads;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Credential misuse on the P2 endpoints: revoked keys right after the cache was warmed (also while requests race
 * the revocation), keys in the wrong place, a JWT on ingestion and an API key on the user-only query, for every
 * routed spelling and HTTP method.
 */
class IngestCredentialAttackTest extends IngestQueryAttackSupport {

    private Tenant tenant;
    private String body;
    private String query;

    @BeforeEach
    void setUp() {
        tenant = tenant("MAX");
        body = IngestPayloads.single(tenant.metricId(), monthStart(), 1);
        query = range(monthStart(), monthStart().plusSeconds(3600), "PT1M");
    }

    @Test
    void revokedKeyIsRejectedRightAfterTheCacheWasWarmed() {
        assertThat(ingestRaw(tenant.apiKey(), body).statusCode()).isEqualTo(202);
        assertThat(ingestRaw(tenant.apiKey(), body).statusCode()).as("served from the warm cache").isEqualTo(202);

        assertThat(revoke().statusCode()).isEqualTo(204);

        HttpResponse<String> revoked = ingestRaw(tenant.apiKey(), body);
        HttpResponse<String> unknown = ingestRaw("pb_" + "C".repeat(43), body);
        assertThat(revoked.statusCode()).isEqualTo(401);
        assertThat(revoked.body()).isEqualTo(unknown.body());
        assertThat(revoked.body()).contains("Invalid API key");
    }

    @Test
    void noRequestStartedAfterTheRevocationReturnedIsAdmitted() throws Exception {
        setPlan(tenant, "PRO");
        assertThat(ingestRaw(tenant.apiKey(), body).statusCode()).isEqualTo(202);
        AtomicBoolean running = new AtomicBoolean(true);
        List<long[]> samples = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger admittedBefore = new AtomicInteger();
        long revokedAt;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int worker = 0; worker < 4; worker++) {
                executor.submit(() -> {
                    while (running.get()) {
                        long started = System.nanoTime();
                        int status = ingestRaw(tenant.apiKey(), body).statusCode();
                        samples.add(new long[] {started, status});
                    }
                    return null;
                });
            }
            Thread.sleep(300);
            assertThat(revoke().statusCode()).isEqualTo(204);
            revokedAt = System.nanoTime();
            Thread.sleep(500);
            running.set(false);
        }
        List<long[]> after = new ArrayList<>();
        for (long[] sample : samples) {
            if (sample[0] > revokedAt) {
                after.add(sample);
            } else if (sample[1] == 202) {
                admittedBefore.incrementAndGet();
            }
        }
        assertThat(admittedBefore.get()).as("the flood really used the cached key").isPositive();
        assertThat(after).as("requests started after the revocation returned").isNotEmpty()
                .allSatisfy(sample -> assertThat(sample[1]).isEqualTo(401L));
    }

    @Test
    void keyIsOnlyAcceptedInTheXApiKeyHeader() {
        // Header names are case-insensitive in HTTP, so this is the same header, not a bypass.
        assertThat(raw("POST", INGEST, body, "x-api-key", tenant.apiKey()).statusCode()).isEqualTo(202);
        assertThat(raw("POST", INGEST, body, "X-API-KEY", tenant.apiKey()).statusCode()).isEqualTo(202);

        assertThat(raw("POST", INGEST, body, "Api-Key", tenant.apiKey()).statusCode()).isEqualTo(401);
        assertThat(raw("POST", INGEST, body, "X-Api-Token", tenant.apiKey()).statusCode()).isEqualTo(401);
        assertThat(raw("POST", INGEST, body, "Authorization", "ApiKey " + tenant.apiKey()).statusCode())
                .isEqualTo(401);
        assertThat(raw("POST", INGEST, body, "Authorization", "Bearer " + tenant.apiKey()).statusCode())
                .isEqualTo(401);
        assertThat(raw("POST", INGEST + "?apiKey=" + tenant.apiKey(), body).statusCode()).isEqualTo(401);
        assertThat(raw("POST", INGEST + "?X-Api-Key=" + tenant.apiKey(), body).statusCode()).isEqualTo(401);
        assertThat(ingestRaw(tenant.apiKey().toUpperCase(Locale.ROOT), body).statusCode()).isEqualTo(401);
        assertThat(storedRows(tenant.metricId())).isEqualTo(1);
    }

    @Test
    void userTokenCannotIngestUnderAnySpelling() {
        String token = tenant.owner().accessToken();
        for (String path : List.of(INGEST, "/api/v1/%69ngest", "/api/v1/inges%74", "/api/%761/ingest")) {
            HttpResponse<String> response = raw("POST", path, body, "Authorization", "Bearer " + token);
            assertThat(response.statusCode()).as(path).isEqualTo(403);
            assertThat(response.body()).contains("urn:pulseboard:problem:forbidden");
        }
        HttpResponse<String> both = raw("POST", INGEST, body, API_KEY_HEADER, tenant.apiKey(),
                "Authorization", "Bearer " + token);
        assertThat(both.statusCode()).isEqualTo(401);
        assertThat(storedRows(tenant.metricId())).isZero();
    }

    @Test
    void apiKeyCannotRunTheUserQueryUnderAnySpelling() {
        UUID metricId = tenant.metricId();
        for (String path : List.of("/api/v1/metrics/" + metricId + "/points",
                "/api/v1/%6Detrics/" + metricId + "/points", "/api/v1/metrics/" + metricId + "/poin%74s")) {
            HttpResponse<String> response = raw("GET", path + query, null, API_KEY_HEADER, tenant.apiKey());
            assertThat(response.statusCode()).as(path).isEqualTo(403);
        }
        // A user token still works on the encoded spelling, so the 403 above is the role check, not routing.
        assertThat(getWithBearer("/api/v1/%6Detrics/" + metricId + "/points" + query,
                tenant.owner().accessToken()).statusCode()).isEqualTo(200);
    }

    @Test
    void apiKeyCannotRunTheUserQueryWithHead() {
        assertThat(ingestRaw(tenant.apiKey(), body).statusCode()).isEqualTo(202);

        HttpResponse<String> head = raw("HEAD", "/api/v1/metrics/" + tenant.metricId() + "/points" + query, null,
                API_KEY_HEADER, tenant.apiKey());

        assertThat(head.statusCode()).isEqualTo(403);
    }

    private HttpResponse<String> revoke() {
        return raw("DELETE", "/api/v1/api-keys/" + apiKeyId(), null,
                "Authorization", "Bearer " + tenant.owner().accessToken());
    }

    private UUID apiKeyId() {
        return jdbcTemplate.queryForObject("SELECT id FROM api_key WHERE tenant_id = ? AND name = 'agent'",
                UUID.class, tenant.owner().tenantId());
    }
}

package io.pulseboard.core.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.pulseboard.core.api.apikey.ApiKeyDtos.ApiKeyCreatedResponse;
import io.pulseboard.core.api.auth.AuthDtos.TokenResponse;
import io.pulseboard.core.support.IntegrationTest;
import io.pulseboard.core.support.LockBarrier;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Regression for api-key-usage-write-overwrites-revocation: recording key usage must never undo a revocation
 * that commits between the usage read and the usage write.
 */
class ApiKeyConcurrencyIntegrationTest extends IntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${spring.datasource.url}")
    private String jdbcUrl;

    @Test
    void revocationSurvivesConcurrentUsageWrite() throws Exception {
        TokenResponse owner = registerTenant();
        ApiKeyCreatedResponse key = issueApiKey(owner.accessToken(), "collector");

        Instant revokedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CompletableFuture<Integer> usage;
        try (LockBarrier barrier = LockBarrier.lockRow(jdbcUrl, "api_key", key.id())) {
            // The authenticated request reads the active key, then stops when it writes last_used_at.
            usage = CompletableFuture.supplyAsync(() -> client.get().uri("/api/v1/services")
                    .header(API_KEY_HEADER, key.key())
                    .exchange()
                    .returnResult()
                    .getStatus()
                    .value(), executor);
            barrier.awaitWaitingSessions(1);
            // The revocation commits while the usage write is pending.
            barrier.execute("UPDATE api_key SET revoked_at = ? WHERE id = ?", Timestamp.from(revokedAt), key.id());
            barrier.release();
        }
        int concurrentStatus = usage.get(30, TimeUnit.SECONDS);
        executor.shutdown();

        // The request had read the key before the revocation committed, so it is served.
        assertThat(concurrentStatus).as("status of the request racing the revocation").isEqualTo(200);
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT revoked_at, last_used_at FROM api_key WHERE id = ?", key.id());
        assertThat(((Timestamp) row.get("revoked_at")).toInstant())
                .as("revocation survived the concurrent usage write").isEqualTo(revokedAt);
        assertThat(row.get("last_used_at")).as("usage of a revoked key is not recorded").isNull();
        client.get().uri("/api/v1/services")
                .header(API_KEY_HEADER, key.key())
                .exchange()
                .expectStatus().isUnauthorized();
    }
}

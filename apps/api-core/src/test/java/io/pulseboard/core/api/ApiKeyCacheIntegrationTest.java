package io.pulseboard.core.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.pulseboard.core.api.apikey.ApiKeyDtos.ApiKeyCreatedResponse;
import io.pulseboard.core.api.auth.AuthDtos.TokenResponse;
import io.pulseboard.core.application.apikey.ApiKeyCache;
import io.pulseboard.core.application.apikey.ApiKeyService;
import io.pulseboard.core.application.apikey.ApiKeyService.AuthenticatedApiKey;
import io.pulseboard.core.application.common.SecureTokens;
import io.pulseboard.core.domain.tenancy.TenantId;
import io.pulseboard.core.support.IngestPayloads;
import io.pulseboard.core.support.IntegrationTest;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * API key authentication results are cached in Redis for 60 s, yet a revocation through the API takes effect on
 * the very next request.
 */
class ApiKeyCacheIntegrationTest extends IntegrationTest {

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApiKeyCache apiKeyCache;

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TokenResponse owner;
    private UUID metricId;
    private ApiKeyCreatedResponse key;

    @BeforeEach
    void setUp() {
        owner = registerTenant();
        metricId = createMetric(owner.accessToken(), createService(owner.accessToken(), "checkout").id(),
                "latency_ms").id();
        key = issueApiKey(owner.accessToken(), "agent");
    }

    @Test
    void successfulAuthenticationIsCachedAndServedWithoutTheDatabase() {
        ingest(key.key(), IngestPayloads.single(metricId, Instant.now(), 1)).expectStatus().isAccepted();
        String entry = redis.opsForValue().get(cacheKey(key.key()));
        assertThat(entry).isEqualTo("A|" + key.id() + "|" + tenantOf(metricId));
        assertThat(redis.getExpire(cacheKey(key.key()))).isBetween(1L, 60L);

        // A revocation written straight to the database (bypassing the API) is invisible until the entry
        // expires: proof that the cached result, not the database, authenticated the request.
        jdbcTemplate.update("UPDATE api_key SET revoked_at = now() WHERE id = ?", key.id());
        ingest(key.key(), IngestPayloads.single(metricId, Instant.now().plusSeconds(1), 2))
                .expectStatus().isAccepted();
    }

    @Test
    void revocationThroughTheApiRejectsACachedKeyImmediately() {
        ingest(key.key(), IngestPayloads.single(metricId, Instant.now(), 1)).expectStatus().isAccepted();
        assertThat(redis.opsForValue().get(cacheKey(key.key()))).startsWith("A|");

        client.delete().uri("/api/v1/api-keys/{id}", key.id())
                .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken()))
                .exchange()
                .expectStatus().isNoContent();

        ingest(key.key(), IngestPayloads.single(metricId, Instant.now().plusSeconds(1), 2))
                .expectStatus().isUnauthorized();
        client.get().uri("/api/v1/services")
                .header(API_KEY_HEADER, key.key())
                .exchange()
                .expectStatus().isUnauthorized();
        assertThat(redis.opsForValue().get(cacheKey(key.key()))).startsWith("R|");
        assertThat(redis.getExpire(cacheKey(key.key()))).isGreaterThan(3600L);
    }

    @Test
    void aLookupThatReadTheKeyBeforeTheRevocationCannotRepopulateTheCache() {
        String keyHash = SecureTokens.sha256Hex(key.key());
        // The revocation commits: database row and marker.
        jdbcTemplate.update("UPDATE api_key SET revoked_at = now() WHERE id = ?", key.id());
        apiKeyCache.markRevoked(keyHash);
        // A request that loaded the still-active key from the database before the revocation committed now
        // tries to cache it.
        apiKeyCache.putActiveIfAbsent(keyHash, new AuthenticatedApiKey(key.id(), TenantId.of(tenantOf(metricId))));

        assertThat(apiKeyCache.find(keyHash)).containsInstanceOf(ApiKeyCache.Revoked.class);
        client.get().uri("/api/v1/services")
                .header(API_KEY_HEADER, key.key())
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void revokedKeysAreNotCachedAsActive() {
        client.delete().uri("/api/v1/api-keys/{id}", key.id())
                .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken()))
                .exchange()
                .expectStatus().isNoContent();
        redis.delete(cacheKey(key.key()));

        ingest(key.key(), IngestPayloads.single(metricId, Instant.now(), 1)).expectStatus().isUnauthorized();
        assertThat(redis.opsForValue().get(cacheKey(key.key()))).isNull();
    }

    /**
     * Two revocations of the same key race: A writes its marker and then rolls back; B, blocked on the row lock
     * held by A, commits and writes its own marker. A's rollback cleanup runs only after B's marker is in place
     * (forced with latches). The cleanup must remove A's marker only, never B's.
     */
    @Test
    void rolledBackRevocationDoesNotRemoveTheMarkerOfACommittedOne() throws Exception {
        String cacheKey = cacheKey(key.key());
        TenantId tenantId = TenantId.of(tenantOf(metricId));
        AtomicReference<String> markerOfA = new AtomicReference<>();
        CountDownLatch revokedByA = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> revocationA = executor.submit(() -> transaction.executeWithoutResult(status -> {
                // Registered before the revocation's own cleanup, so it runs first on completion: it holds the
                // cleanup back until B's marker has replaced A's.
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int completionStatus) {
                        awaitUntil(() -> {
                            String current = redis.opsForValue().get(cacheKey);
                            return current != null && !current.equals(markerOfA.get());
                        });
                    }
                });
                apiKeyService.revoke(tenantId, key.id());
                markerOfA.set(redis.opsForValue().get(cacheKey));
                revokedByA.countDown();
                await(releaseA);
                status.setRollbackOnly();
            }));
            await(revokedByA);
            assertThat(markerOfA.get()).startsWith("R|");

            Future<Integer> revocationB = executor.submit(() -> client.delete().uri("/api/v1/api-keys/{id}", key.id())
                    .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken()))
                    .exchange()
                    .returnResult()
                    .getStatus()
                    .value());
            // B waits for the api_key row lock held by A's uncommitted update.
            awaitUntil(() -> {
                Integer waiting = jdbcTemplate.queryForObject("SELECT count(*) FROM pg_stat_activity "
                        + "WHERE datname = current_database() AND wait_event_type = 'Lock'", Integer.class);
                return waiting != null && waiting > 0;
            });
            releaseA.countDown();

            revocationA.get(30, TimeUnit.SECONDS);
            assertThat(revocationB.get(30, TimeUnit.SECONDS)).isEqualTo(204);
        }

        String marker = redis.opsForValue().get(cacheKey);
        assertThat(marker).as("marker of the committed revocation").startsWith("R|").isNotEqualTo(markerOfA.get());
        assertThat(jdbcTemplate.queryForObject("SELECT revoked_at IS NOT NULL FROM api_key WHERE id = ?",
                Boolean.class, key.id())).isTrue();
    }

    /**
     * A marker whose revocation did not commit (rolled back with a failed cleanup, or a cache write whose outcome
     * was unknown) must not lock out the still-active key: a marker only blocks caching, the database decides.
     */
    @Test
    void aStrayMarkerDoesNotLockOutAnActiveKey() {
        String keyHash = SecureTokens.sha256Hex(key.key());
        apiKeyCache.markRevoked(keyHash);

        ingest(key.key(), IngestPayloads.single(metricId, Instant.now(), 1)).expectStatus().isAccepted();
        client.get().uri("/api/v1/services")
                .header(API_KEY_HEADER, key.key())
                .exchange()
                .expectStatus().isOk();
        assertThat(redis.opsForValue().get(cacheKey(key.key()))).as("no positive entry replaces the marker")
                .startsWith("R|");
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(30, TimeUnit.SECONDS)).as("latch released in time").isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private static void awaitUntil(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("condition not reached within 30 s");
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
        }
    }

    private static String cacheKey(String plaintextKey) {
        return "apikey:" + SecureTokens.sha256Hex(plaintextKey);
    }

    private UUID tenantOf(UUID metric) {
        return jdbcTemplate.queryForObject("SELECT tenant_id FROM metric_definition WHERE id = ?", UUID.class,
                metric);
    }
}

package io.pulseboard.core.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import io.pulseboard.core.api.apikey.ApiKeyDtos.ApiKeyCreatedResponse;
import io.pulseboard.core.api.auth.AuthDtos.TokenResponse;
import io.pulseboard.core.application.apikey.ApiKeyCache;
import io.pulseboard.core.application.apikey.ApiKeyService;
import io.pulseboard.core.application.common.SecureTokens;
import io.pulseboard.core.domain.tenancy.TenantId;
import io.pulseboard.core.support.IntegrationTest;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A second revocation of an already revoked key that rolls back must not take the protection of the first,
 * committed revocation with it: a lookup that read the key while it was still active and writes its positive
 * cache entry late must still find the marker.
 */
class ApiKeyRevocationMarkerRaceIntegrationTest extends IntegrationTest {

    @MockitoSpyBean
    private ApiKeyCache apiKeyCache;

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void rolledBackRepeatedRevocationKeepsTheMarkerOfTheCommittedOne() throws Exception {
        TokenResponse owner = registerTenant();
        UUID metricId = createMetric(owner.accessToken(), createService(owner.accessToken(), "svc").id(), "m").id();
        ApiKeyCreatedResponse key = issueApiKey(owner.accessToken(), "agent");
        TenantId tenantId = TenantId.of(jdbcTemplate.queryForObject(
                "SELECT tenant_id FROM metric_definition WHERE id = ?", UUID.class, metricId));
        String cacheKey = "apikey:" + SecureTokens.sha256Hex(key.key());

        // The lookup has read the active key from the database and is held right before its cache write.
        AtomicBoolean firstWrite = new AtomicBoolean(true);
        CountDownLatch lookupReachedCache = new CountDownLatch(1);
        CountDownLatch releaseLookup = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (firstWrite.getAndSet(false)) {
                lookupReachedCache.countDown();
                assertThat(releaseLookup.await(30, TimeUnit.SECONDS)).as("lookup released in time").isTrue();
            }
            return invocation.callRealMethod();
        }).when(apiKeyCache).putActiveIfAbsent(any(), any());

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<Integer> lookup = executor.submit(() -> client.get().uri("/api/v1/services")
                    .header(API_KEY_HEADER, key.key())
                    .exchange()
                    .returnResult()
                    .getStatus()
                    .value());
            assertThat(lookupReachedCache.await(30, TimeUnit.SECONDS)).isTrue();

            // Revocation A commits and writes its marker.
            client.delete().uri("/api/v1/api-keys/{id}", key.id())
                    .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken()))
                    .exchange()
                    .expectStatus().isNoContent();
            String markerOfA = redis.opsForValue().get(cacheKey);
            assertThat(markerOfA).startsWith("R|");

            // Revocation B finds the key already revoked, then its transaction rolls back.
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                apiKeyService.revoke(tenantId, key.id());
                status.setRollbackOnly();
            });
            assertThat(redis.opsForValue().get(cacheKey)).as("marker of the committed revocation")
                    .isEqualTo(markerOfA);

            releaseLookup.countDown();
            assertThat(lookup.get(30, TimeUnit.SECONDS)).as("status of the lookup that read the active key")
                    .isEqualTo(200);
        }

        client.get().uri("/api/v1/services")
                .header(API_KEY_HEADER, key.key())
                .exchange()
                .expectStatus().isUnauthorized();
        assertThat(redis.opsForValue().get(cacheKey)).as("no positive entry").startsWith("R|");
    }
}

package io.pulseboard.core.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.pulseboard.core.api.auth.AuthDtos.TokenResponse;
import io.pulseboard.core.application.apikey.ApiKeyService;
import io.pulseboard.core.support.IngestPayloads;
import io.pulseboard.core.support.IntegrationTest;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Per-client limits on requests carrying {@code X-Api-Key}, applied before the key is looked up in the cache or
 * the database. Own context with small limits; every request comes from the loopback client.
 */
@TestPropertySource(properties = {
    "pulseboard.rate-limit.api-key.client-capacity=8",
    "pulseboard.rate-limit.api-key.invalid-capacity=3"
})
class ApiKeyClientRateLimitIntegrationTest extends IntegrationTest {

    @MockitoSpyBean
    private ApiKeyService apiKeyService;

    @Autowired
    private StringRedisTemplate redis;

    private UUID metricId;
    private String apiKey;

    @BeforeEach
    void setUp() {
        Set<String> buckets = redis.keys("rl:apikey-*");
        if (buckets != null && !buckets.isEmpty()) {
            redis.delete(buckets);
        }
        TokenResponse owner = registerTenant();
        metricId = createMetric(owner.accessToken(), createService(owner.accessToken(), "svc").id(), "m").id();
        apiKey = issueApiKey(owner.accessToken(), "agent").key();
        clearInvocations(apiKeyService);
    }

    @Test
    void invalidKeyBurstIsThrottledBeforeAnyLookup() {
        for (int i = 0; i < 3; i++) {
            ingest("pb_" + "guess".repeat(9) + i, IngestPayloads.single(metricId, Instant.now(), 1))
                    .expectStatus().isUnauthorized();
        }
        for (int i = 0; i < 5; i++) {
            ingest("pb_" + "guess".repeat(9) + (10 + i), IngestPayloads.single(metricId, Instant.now(), 1))
                    .expectStatus().isEqualTo(429)
                    .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                    .expectHeader().exists(HttpHeaders.RETRY_AFTER)
                    .expectBody().jsonPath("$.type").isEqualTo("urn:pulseboard:problem:rate-limited");
        }
        // While the failure bucket of this client is empty, even a valid key is not looked up.
        client.get().uri("/api/v1/services").header(API_KEY_HEADER, apiKey)
                .exchange()
                .expectStatus().isEqualTo(429);

        verify(apiKeyService, times(3)).authenticate(anyString());
    }

    @Test
    void everyApiKeyRequestTakesATokenFromTheClientBucket() {
        for (int i = 0; i < 8; i++) {
            client.get().uri("/api/v1/services").header(API_KEY_HEADER, apiKey)
                    .exchange()
                    .expectStatus().isOk();
        }
        client.get().uri("/api/v1/services").header(API_KEY_HEADER, apiKey)
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().exists(HttpHeaders.RETRY_AFTER);
        verify(apiKeyService, times(8)).authenticate(anyString());
        // Requests without an API key are not affected.
        assertThat(client.get().uri("/api/v1/services").exchange().returnResult().getStatus().value())
                .isEqualTo(401);
    }
}

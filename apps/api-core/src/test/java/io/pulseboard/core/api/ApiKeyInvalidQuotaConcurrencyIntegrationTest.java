package io.pulseboard.core.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.pulseboard.core.application.apikey.ApiKeyService;
import io.pulseboard.core.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * The quota for failed authentications is reserved before the lookup, so parallel requests with invalid keys
 * cannot all pass it before the first failure is counted.
 */
@TestPropertySource(properties = {
    "pulseboard.rate-limit.api-key.client-capacity=100",
    "pulseboard.rate-limit.api-key.invalid-capacity=3"
})
class ApiKeyInvalidQuotaConcurrencyIntegrationTest extends IntegrationTest {

    private static final int PARALLEL_REQUESTS = 10;

    @MockitoSpyBean
    private ApiKeyService apiKeyService;

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void clearBuckets() {
        Set<String> buckets = redis.keys("rl:apikey-*");
        if (buckets != null && !buckets.isEmpty()) {
            redis.delete(buckets);
        }
    }

    @Test
    void parallelInvalidKeysStartNoMoreLookupsThanTheQuotaAllows() throws Exception {
        CountDownLatch threeLookupsStarted = new CountDownLatch(3);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            threeLookupsStarted.countDown();
            assertThat(release.await(30, TimeUnit.SECONDS)).as("lookups released in time").isTrue();
            return invocation.callRealMethod();
        }).when(apiKeyService).authenticate(anyString());

        try (ExecutorService executor = Executors.newFixedThreadPool(PARALLEL_REQUESTS)) {
            List<Future<Integer>> responses = new ArrayList<>();
            for (int i = 0; i < PARALLEL_REQUESTS; i++) {
                String key = "pb_" + "guess".repeat(9) + i;
                responses.add(executor.submit(() -> client.get().uri("/api/v1/services")
                        .header(API_KEY_HEADER, key)
                        .exchange()
                        .returnResult()
                        .getStatus()
                        .value()));
            }
            assertThat(threeLookupsStarted.await(30, TimeUnit.SECONDS)).isTrue();
            // The three lookups stay held, yet every other request is already answered with 429.
            List<Integer> throttled = new ArrayList<>();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (throttled.size() < PARALLEL_REQUESTS - 3 && System.nanoTime() < deadline) {
                throttled.clear();
                for (Future<Integer> response : responses) {
                    if (response.isDone()) {
                        throttled.add(response.get());
                    }
                }
                Thread.sleep(20);
            }
            assertThat(throttled).hasSize(PARALLEL_REQUESTS - 3).containsOnly(429);
            verify(apiKeyService, times(3)).authenticate(anyString());

            release.countDown();
            List<Integer> all = new ArrayList<>();
            for (Future<Integer> response : responses) {
                all.add(response.get(30, TimeUnit.SECONDS));
            }
            assertThat(all).filteredOn(status -> status == 401).hasSize(3);
        }
        verify(apiKeyService, times(3)).authenticate(anyString());
    }
}

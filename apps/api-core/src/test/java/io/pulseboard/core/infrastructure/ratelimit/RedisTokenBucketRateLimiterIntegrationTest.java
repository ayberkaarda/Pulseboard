package io.pulseboard.core.infrastructure.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pulseboard.core.application.ratelimit.RateLimitExceededException;
import io.pulseboard.core.application.ratelimit.RateLimitPolicy;
import io.pulseboard.core.application.ratelimit.RateLimiter;
import io.pulseboard.core.support.IntegrationTest;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The Redis token bucket against a real Redis: capacity, refill, Retry-After and atomicity. */
class RedisTokenBucketRateLimiterIntegrationTest extends IntegrationTest {

    @Autowired
    private RateLimiter rateLimiter;

    @Test
    void allowsCapacityThenDeniesWithRetryAfter() {
        String subject = UUID.randomUUID().toString();
        RateLimitPolicy policy = new RateLimitPolicy(3, Duration.ofMinutes(1));

        for (int i = 0; i < 3; i++) {
            assertThat(rateLimiter.tryAcquire("test", subject, policy).allowed()).isTrue();
        }
        RateLimiter.Decision denied = rateLimiter.tryAcquire("test", subject, policy);

        assertThat(denied.allowed()).isFalse();
        // One token refills every 20 s at 3 tokens per minute.
        assertThat(denied.retryAfter()).isBetween(Duration.ofSeconds(15), Duration.ofSeconds(20));
        assertThatThrownBy(() -> rateLimiter.acquireOrThrow("test", subject, policy))
                .isInstanceOf(RateLimitExceededException.class)
                .satisfies(ex -> assertThat(((RateLimitExceededException) ex).retryAfterSeconds()).isBetween(15L,
                        20L));
    }

    @Test
    void bucketsAreIndependentPerNamespaceAndSubject() {
        String subject = UUID.randomUUID().toString();
        RateLimitPolicy policy = new RateLimitPolicy(1, Duration.ofHours(1));

        assertThat(rateLimiter.tryAcquire("test-a", subject, policy).allowed()).isTrue();
        assertThat(rateLimiter.tryAcquire("test-a", subject, policy).allowed()).isFalse();
        assertThat(rateLimiter.tryAcquire("test-b", subject, policy).allowed()).isTrue();
        assertThat(rateLimiter.tryAcquire("test-a", subject + "x", policy).allowed()).isTrue();
    }

    @Test
    void aRefundedTokenCanBeTakenAgainButNeverExceedsCapacity() {
        String subject = UUID.randomUUID().toString();
        RateLimitPolicy policy = new RateLimitPolicy(2, Duration.ofHours(1));
        rateLimiter.tryAcquire("test", subject, policy);
        rateLimiter.tryAcquire("test", subject, policy);
        assertThat(rateLimiter.tryAcquire("test", subject, policy).allowed()).isFalse();

        rateLimiter.refund("test", subject, policy);
        rateLimiter.refund("test", subject, policy);
        rateLimiter.refund("test", subject, policy);

        assertThat(rateLimiter.tryAcquire("test", subject, policy).allowed()).isTrue();
        assertThat(rateLimiter.tryAcquire("test", subject, policy).allowed()).isTrue();
        assertThat(rateLimiter.tryAcquire("test", subject, policy).allowed()).isFalse();
    }

    @Test
    void tokensRefillOverTime() throws InterruptedException {
        String subject = UUID.randomUUID().toString();
        RateLimitPolicy policy = new RateLimitPolicy(2, Duration.ofMillis(400));
        rateLimiter.tryAcquire("test", subject, policy);
        rateLimiter.tryAcquire("test", subject, policy);
        assertThat(rateLimiter.tryAcquire("test", subject, policy).allowed()).isFalse();

        Thread.sleep(300);

        assertThat(rateLimiter.tryAcquire("test", subject, policy).allowed()).isTrue();
    }

    @Test
    void concurrentAcquisitionNeverExceedsCapacity() throws Exception {
        String subject = UUID.randomUUID().toString();
        RateLimitPolicy policy = new RateLimitPolicy(5, Duration.ofHours(1));
        ExecutorService executor = Executors.newFixedThreadPool(16);
        try {
            Callable<Boolean> attempt = () -> rateLimiter.tryAcquire("test", subject, policy).allowed();
            long allowed = 0;
            for (Future<Boolean> result : executor.invokeAll(IntStream.range(0, 40).mapToObj(i -> attempt).toList())) {
                allowed += result.get() ? 1 : 0;
            }
            assertThat(allowed).isEqualTo(5);
        } finally {
            executor.shutdown();
        }
    }
}

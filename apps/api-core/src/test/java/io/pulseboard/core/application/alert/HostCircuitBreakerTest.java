package io.pulseboard.core.application.alert;

import static org.assertj.core.api.Assertions.assertThat;

import io.pulseboard.core.domain.tenancy.TenantId;
import io.pulseboard.core.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Eviction of the shared breaker map must never lift a running pause of another tenant. */
class HostCircuitBreakerTest {

    private static final TenantId VICTIM = TenantId.of(UUID.randomUUID());
    private static final TenantId NOISY = TenantId.of(UUID.randomUUID());

    private final MutableClock clock = new MutableClock(Instant.parse("2026-05-05T12:00:00Z"));
    private final AlertDispatchPolicy policy = new AlertDispatchPolicy(10, Duration.ofSeconds(60), 4,
            List.of(Duration.ofSeconds(10)), 1, Duration.ofMinutes(5), Duration.ofSeconds(20));

    @Test
    void aTenantFloodingNewHostsOnlyEvictsItsOwnEntries() {
        HostCircuitBreaker breaker = new HostCircuitBreaker(policy, clock, 6, 2);
        breaker.recordFailure(VICTIM, "victim.example.com");
        Instant pausedUntil = breaker.pausedUntil(VICTIM, "victim.example.com");
        assertThat(pausedUntil).isNotNull();

        for (int i = 0; i < 50; i++) {
            breaker.recordFailure(NOISY, "noisy-" + i + ".example.com");
        }

        assertThat(breaker.pausedUntil(VICTIM, "victim.example.com")).isEqualTo(pausedUntil);
        assertThat(breaker.size()).isLessThanOrEqualTo(3);
    }

    @Test
    void whenTheWholeMapIsFullTheOldestUnpausedEntryGoesNotAPause() {
        // Two failures open a breaker, so the noisy tenant's single failures never pause anything.
        AlertDispatchPolicy twoFailures = new AlertDispatchPolicy(10, Duration.ofSeconds(60), 4,
                List.of(Duration.ofSeconds(10)), 2, Duration.ofMinutes(5), Duration.ofSeconds(20));
        HostCircuitBreaker breaker = new HostCircuitBreaker(twoFailures, clock, 3, 10);
        breaker.recordFailure(VICTIM, "victim.example.com");
        breaker.recordFailure(VICTIM, "victim.example.com"); // oldest entry, and paused
        Instant pausedUntil = breaker.pausedUntil(VICTIM, "victim.example.com");
        assertThat(pausedUntil).isNotNull();

        for (int i = 0; i < 20; i++) {
            breaker.recordFailure(NOISY, "noisy-" + i + ".example.com");
        }

        assertThat(breaker.pausedUntil(VICTIM, "victim.example.com")).isEqualTo(pausedUntil);
        assertThat(breaker.size()).isEqualTo(3);
    }

    @Test
    void whenEverySlotHoldsAPauseTheNewKeyIsNotTrackedAndNothingIsLifted() {
        HostCircuitBreaker breaker = new HostCircuitBreaker(policy, clock, 2, 10);
        breaker.recordFailure(VICTIM, "a.example.com");
        breaker.recordFailure(VICTIM, "b.example.com");

        breaker.recordFailure(NOISY, "c.example.com");

        assertThat(breaker.pausedUntil(VICTIM, "a.example.com")).isNotNull();
        assertThat(breaker.pausedUntil(VICTIM, "b.example.com")).isNotNull();
        assertThat(breaker.pausedUntil(NOISY, "c.example.com")).isNull();
        assertThat(breaker.size()).isEqualTo(2);
    }

    @Test
    void anExpiredPauseCanBeEvictedToMakeRoom() {
        HostCircuitBreaker breaker = new HostCircuitBreaker(policy, clock, 1, 10);
        breaker.recordFailure(VICTIM, "a.example.com");
        clock.advance(Duration.ofMinutes(6));

        breaker.recordFailure(NOISY, "c.example.com");

        assertThat(breaker.pausedUntil(NOISY, "c.example.com")).isNotNull();
        assertThat(breaker.size()).isEqualTo(1);
    }
}

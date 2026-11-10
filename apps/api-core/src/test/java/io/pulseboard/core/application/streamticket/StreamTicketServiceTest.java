package io.pulseboard.core.application.streamticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pulseboard.core.application.ratelimit.RateLimitExceededException;
import io.pulseboard.core.application.ratelimit.RateLimitPolicy;
import io.pulseboard.core.application.ratelimit.RateLimiter;
import io.pulseboard.core.domain.tenancy.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StreamTicketServiceTest {

    private static final Instant NOW = Instant.parse("2026-05-01T12:00:00Z");
    private static final TenantId TENANT = TenantId.of(UUID.randomUUID());
    private static final UUID USER = UUID.randomUUID();

    private final FakeStore store = new FakeStore();
    private final FakeLimiter limiter = new FakeLimiter();
    private final StreamTicketPolicy policy = new StreamTicketPolicy(Duration.ofSeconds(30),
            new RateLimitPolicy(3, Duration.ofMinutes(1)));
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final StreamTicketService service = new StreamTicketService(store, limiter, policy, clock);

    @Test
    void storesOnlyTheDigestNeverTheTicket() {
        IssuedStreamTicket issued = service.issue(TENANT, USER, NOW.plusSeconds(900));

        assertThat(issued.value()).matches("[A-Za-z0-9_-]{43}");
        assertThat(store.entries).hasSize(1);
        String stored = store.entries.keySet().iterator().next();
        assertThat(stored).isEqualTo(StreamTicketService.digest(issued.value())).matches("[0-9a-f]{64}")
                .isNotEqualTo(issued.value());
        assertThat(store.ttls.get(stored)).isEqualTo(Duration.ofSeconds(30));
        assertThat(issued.expiresInSeconds()).isEqualTo(30);
        assertThat(issued.toString()).doesNotContain(issued.value());
    }

    @Test
    void aTicketCanBeConsumedExactlyOnce() {
        IssuedStreamTicket issued = service.issue(TENANT, USER, NOW.plusSeconds(900));

        Optional<StreamTicketClaims> first = service.consume(issued.value());
        Optional<StreamTicketClaims> second = service.consume(issued.value());

        assertThat(first).hasValueSatisfying(claims -> {
            assertThat(claims.tenantId()).isEqualTo(TENANT);
            assertThat(claims.userId()).isEqualTo(USER);
            assertThat(claims.jwtExpiresAt()).isEqualTo(NOW.plusSeconds(900));
        });
        assertThat(second).isEmpty();
    }

    @Test
    void ticketsAreUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            seen.add(service.issue(TENANT, UUID.randomUUID(), NOW.plusSeconds(900)).value());
        }
        assertThat(seen).hasSize(500);
    }

    @Test
    void theTimeToLiveNeverExceedsTheRemainingLifeOfTheAccessToken() {
        IssuedStreamTicket issued = service.issue(TENANT, USER, NOW.plusSeconds(5));

        assertThat(issued.expiresInSeconds()).isEqualTo(5);
        assertThat(store.ttls.values()).containsExactly(Duration.ofSeconds(5));
    }

    @Test
    void aTicketWhoseAccessTokenExpiredMeanwhileIsRefused() {
        IssuedStreamTicket issued = service.issue(TENANT, USER, NOW.plusSeconds(5));
        StreamTicketService later = new StreamTicketService(store, limiter, policy,
                Clock.fixed(NOW.plusSeconds(6), ZoneOffset.UTC));

        assertThat(later.consume(issued.value())).isEmpty();
    }

    @Test
    void malformedTicketsNeverReachTheStore() {
        for (String presented : new String[] {null, "", "short", "A".repeat(42), "A".repeat(44),
            "A".repeat(42) + "=", "A".repeat(42) + " ", "A".repeat(42) + "\n", "../" + "A".repeat(40)}) {
            assertThat(service.consume(presented)).as("presented: %s", presented).isEmpty();
        }
        assertThat(store.consumeCalls).isZero();
    }

    @Test
    void issuingIsRateLimitedPerUser() {
        for (int i = 0; i < 3; i++) {
            service.issue(TENANT, USER, NOW.plusSeconds(900));
        }

        assertThatThrownBy(() -> service.issue(TENANT, USER, NOW.plusSeconds(900)))
                .isInstanceOf(RateLimitExceededException.class);
        assertThat(store.entries).hasSize(3);
        assertThat(limiter.subjects).containsOnly(USER.toString());
        assertThat(service.issue(TENANT, UUID.randomUUID(), NOW.plusSeconds(900)).value()).isNotBlank();
    }

    private static final class FakeStore implements StreamTicketStore {

        final Map<String, StreamTicketClaims> entries = new HashMap<>();
        final Map<String, Duration> ttls = new HashMap<>();
        int consumeCalls;

        @Override
        public void store(String digest, StreamTicketClaims claims, Duration timeToLive) {
            entries.put(digest, claims);
            ttls.put(digest, timeToLive);
        }

        @Override
        public Optional<StreamTicketClaims> consume(String digest) {
            consumeCalls++;
            return Optional.ofNullable(entries.remove(digest));
        }
    }

    private static final class FakeLimiter implements RateLimiter {

        final Map<String, Integer> used = new HashMap<>();
        final Set<String> subjects = new HashSet<>();

        @Override
        public Decision tryAcquire(String namespace, String subject, RateLimitPolicy policy) {
            subjects.add(subject);
            int count = used.merge(subject, 1, Integer::sum);
            return new Decision(count <= policy.capacity(), Duration.ofSeconds(10));
        }

        @Override
        public Decision check(String namespace, String subject, RateLimitPolicy policy) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void refund(String namespace, String subject, RateLimitPolicy policy) {
            throw new UnsupportedOperationException();
        }
    }
}

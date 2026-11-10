package io.pulseboard.core.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.pulseboard.core.support.AnomalyIntegrationTest;
import io.pulseboard.core.support.SseTestClient;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** Instance-wide connection cap and maximum stream lifetime; own context with small limits. */
@TestPropertySource(properties = {
    "pulseboard.stream.max-total=2",
    "pulseboard.stream.max-per-tenant=5",
    "pulseboard.stream.max-lifetime=4s"
})
class AnomalyStreamLimitsIntegrationTest extends AnomalyIntegrationTest {

    @Test
    void theInstanceCapAppliesAcrossTenantsAndRecoversWhenAStreamEnds() {
        TenantFixture a = newTenant();
        TenantFixture b = newTenant();
        TenantFixture c = newTenant();
        SseTestClient first = SseTestClient.open(port, a.ownerToken());
        SseTestClient second = SseTestClient.open(port, b.ownerToken());
        try {
            assertThat(first.status()).isEqualTo(200);
            assertThat(second.status()).isEqualTo(200);

            try (SseTestClient refused = SseTestClient.open(port, c.ownerToken())) {
                assertThat(refused.status()).isEqualTo(429);
                assertThat(refused.header("Retry-After")).isEqualTo("5");
                assertThat(refused.errorBody()).contains("urn:pulseboard:problem:stream-limit");
            }
        } finally {
            first.close();
            second.close();
        }
        // Both ended (closed by the clients, or at the latest by the lifetime): a new stream is admitted again.
        await().atMost(PATIENCE).until(() -> {
            SseTestClient retry = SseTestClient.open(port, c.ownerToken());
            retry.close();
            return retry.status() == 200;
        });
    }

    @Test
    void aStreamIsClosedByTheServerAfterItsMaximumLifetime() {
        TenantFixture tenant = newTenant();
        Instant start = Instant.now();
        try (SseTestClient stream = SseTestClient.open(port, tenant.ownerToken())) {
            assertThat(stream.status()).isEqualTo(200);
            await().atMost(PATIENCE).until(() -> subscribers(channelOf(tenant.tenantId())) == 1);

            stream.awaitEnd(Duration.ofSeconds(15));

            assertThat(Duration.between(start, Instant.now())).isGreaterThanOrEqualTo(Duration.ofSeconds(3));
        }
        await().atMost(PATIENCE).until(() -> subscribers(channelOf(tenant.tenantId())) == 0);
    }
}

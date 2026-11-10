package io.pulseboard.core.api;

import static io.pulseboard.core.support.StreamTicketTestSupport.issueTicket;
import static io.pulseboard.core.support.StreamTicketTestSupport.openWithTicket;
import static io.pulseboard.core.support.StreamTicketTestSupport.requestTicket;
import static io.pulseboard.core.support.StreamTicketTestSupport.sha256Hex;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.pulseboard.core.application.anomaly.StreamAdmission;
import io.pulseboard.core.support.AnomalyIntegrationTest;
import io.pulseboard.core.support.SseTestClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

/** Ticket lifetime and its interplay with the connection caps; own context with a short ticket TTL and cap. */
@TestPropertySource(properties = {
    "pulseboard.stream.ticket.ttl=3s",
    "pulseboard.stream.max-per-tenant=1",
    "pulseboard.stream.max-lifetime=4s"
})
class StreamTicketLimitsIntegrationTest extends AnomalyIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private StreamAdmission admission;

    @Test
    void anExpiredTicketIsRefusedLikeAnyOtherBadTicket() {
        TenantFixture tenant = newTenant();
        HttpResponse<String> issued = requestTicket(port, "Authorization", "Bearer " + tenant.ownerToken());
        assertThat(issued.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(issued.body()).get("expiresInSeconds").asInt()).isEqualTo(3);
        String ticket = JSON.readTree(issued.body()).get("ticket").asString();
        String key = "stream:ticket:" + sha256Hex(ticket);
        assertThat(redis.hasKey(key)).isTrue();

        // Redis drops the entry when the time is up; no sleeping, just waiting for that fact.
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100))
                .until(() -> !Boolean.TRUE.equals(redis.hasKey(key)));

        try (SseTestClient expired = openWithTicket(port, ticket);
                SseTestClient garbage = openWithTicket(port, "A".repeat(43))) {
            assertThat(expired.status()).isEqualTo(401);
            assertThat(expired.errorBody()).contains("urn:pulseboard:problem:unauthorized");
            assertThat(garbage.status()).isEqualTo(401);
            assertThat(expired.errorBody()).isEqualTo(garbage.errorBody());
        }
    }

    @Test
    void aTicketRefusedByTheTenantCapIsBurnedAndTheCapIsNotLeaked() {
        TenantFixture tenant = newTenant();
        String first = issueTicket(port, tenant.ownerToken());
        String second = issueTicket(port, tenant.ownerToken());
        int openBefore = admission.openStreams();
        try (SseTestClient holder = openWithTicket(port, first)) {
            assertThat(holder.status()).isEqualTo(200);
            assertThat(admission.openStreams()).isEqualTo(openBefore + 1);

            try (SseTestClient capped = openWithTicket(port, second)) {
                assertThat(capped.status()).isEqualTo(429);
                assertThat(capped.errorBody()).contains("urn:pulseboard:problem:stream-limit");
            }

            // The refused request used the ticket up, so it cannot be tried again once the cap frees up ...
            assertThat(redis.hasKey("stream:ticket:" + sha256Hex(second))).isFalse();
            try (SseTestClient retry = openWithTicket(port, second)) {
                assertThat(retry.status()).isEqualTo(401);
            }
            // ... and the ticket path took no slot for the refused or the replayed request.
            assertThat(admission.openStreams()).isEqualTo(openBefore + 1);
        }
        // The holder ends at the latest with its maximum lifetime; then a fresh ticket is admitted again.
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofSeconds(1)).until(() -> {
            try (SseTestClient later = openWithTicket(port, issueTicket(port, tenant.ownerToken()))) {
                return later.status() == 200;
            }
        });
    }
}

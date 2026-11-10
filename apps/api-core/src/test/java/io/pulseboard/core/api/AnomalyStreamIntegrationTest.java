package io.pulseboard.core.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.pulseboard.core.api.auth.AuthDtos.TokenResponse;
import io.pulseboard.core.application.anomaly.StreamAdmission;
import io.pulseboard.core.infrastructure.security.JwtProperties;
import io.pulseboard.core.support.AnomalyIntegrationTest;
import io.pulseboard.core.support.SseTestClient;
import io.pulseboard.core.support.SseTestClient.Event;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The live anomaly stream over a real HTTP connection and a real Redis. */
class AnomalyStreamIntegrationTest extends AnomalyIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant T0 = Instant.parse("2026-03-01T00:00:00Z");

    @Autowired
    private StreamAdmission admission;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private JwtProperties jwtProperties;

    private void awaitSubscribers(TenantFixture tenant, long expected) {
        await().atMost(PATIENCE).pollInterval(Duration.ofMillis(25))
                .until(() -> subscribers(channelOf(tenant.tenantId())) == expected);
    }

    private SseTestClient connect(String token) {
        SseTestClient stream = SseTestClient.open(port, token);
        assertThat(stream.status()).as("stream status, body: %s", stream.errorBody()).isEqualTo(200);
        return stream;
    }

    @Test
    void anEventCarriesTheAnomalyIdNameAndTheSameJsonAsTheRestResource() {
        TenantFixture tenant = newTenant();
        StoredAnomaly stored = insertAnomaly(tenant, T0, "HIGH");
        try (SseTestClient stream = connect(tenant.ownerToken())) {
            assertThat(stream.header("Content-Type")).startsWith("text/event-stream");
            awaitSubscribers(tenant, 1);

            assertThat(notifySigned(notificationBody(stored)).statusCode()).isEqualTo(202);

            Event event = stream.awaitEvent(e -> stored.id().toString().equals(e.id()), PATIENCE);
            assertThat(event.name()).isEqualTo("anomaly");
            String rest = client.get().uri("/api/v1/anomalies/{id}", stored.id())
                    .header(HttpHeaders.AUTHORIZATION, bearer(tenant.ownerToken()))
                    .exchange().expectStatus().isOk()
                    .expectBody(String.class).returnResult().getResponseBody();
            assertThat(JSON.readTree(event.data())).isEqualTo(JSON.readTree(rest));
        }
    }

    @Test
    void eachTenantOnlyReceivesItsOwnEvents() {
        TenantFixture a = newTenant();
        TenantFixture b = newTenant();
        StoredAnomaly ofA = insertAnomaly(a, T0, "HIGH");
        StoredAnomaly ofB = insertAnomaly(b, T0, "LOW");
        try (SseTestClient streamA = connect(a.ownerToken()); SseTestClient streamB = connect(b.ownerToken())) {
            awaitSubscribers(a, 1);
            awaitSubscribers(b, 1);

            // One call announces both; each row goes to the channel of the tenant that owns it.
            assertThat(notifySigned(notificationBody(ofA, ofB)).statusCode()).isEqualTo(202);

            streamA.awaitEvent(e -> ofA.id().toString().equals(e.id()), PATIENCE);
            streamB.awaitEvent(e -> ofB.id().toString().equals(e.id()), PATIENCE);
            // Heartbeats are written in order after any event already queued, so a later ping proves that no
            // foreign event is still on its way.
            int pingsA = streamA.comments().size();
            await().atMost(PATIENCE).until(() -> streamA.comments().size() > pingsA + 1);
            assertThat(streamA.events()).extracting(Event::id).containsExactly(ofA.id().toString());
            assertThat(streamB.events()).extracting(Event::id).containsExactly(ofB.id().toString());
        }
    }

    @Test
    void aStreamNeverReceivesWhatIsPublishedOnAnotherTenantsChannel() {
        TenantFixture a = newTenant();
        TenantFixture b = newTenant();
        StoredAnomaly ofB = insertAnomaly(b, T0, "HIGH");
        try (SseTestClient streamA = connect(a.ownerToken())) {
            awaitSubscribers(a, 1);

            // A message that names tenant B is injected onto A's channel, as a faulty publisher could do.
            redis.convertAndSend(channelOf(a.tenantId()), "{\"id\":\"" + ofB.id() + "\",\"tenantId\":\""
                    + b.tenantId() + "\",\"severity\":\"HIGH\"}");
            // And a message that is not JSON at all.
            redis.convertAndSend(channelOf(a.tenantId()), "garbage");
            // A genuine one for A still arrives afterwards.
            StoredAnomaly ofA = insertAnomaly(a, T0.plusSeconds(60), "LOW");
            assertThat(notifySigned(notificationBody(ofA)).statusCode()).isEqualTo(202);

            streamA.awaitEvent(e -> ofA.id().toString().equals(e.id()), PATIENCE);
            assertThat(streamA.events()).extracting(Event::id).containsExactly(ofA.id().toString());
        }
    }

    @Test
    void anIdleStreamReceivesHeartbeatComments() {
        TenantFixture tenant = newTenant();
        try (SseTestClient stream = connect(tenant.ownerToken())) {
            stream.awaitComment("connected", PATIENCE);
            stream.awaitComment("ping", PATIENCE);
            int first = stream.comments().size();

            await().atMost(PATIENCE).until(() -> stream.comments().size() >= first + 2);
            assertThat(stream.comments()).contains("ping");
            assertThat(stream.events()).isEmpty();
        }
    }

    @Test
    void closingTheClientReleasesTheRedisSubscriptionAndTheSlot() {
        TenantFixture tenant = newTenant();
        SseTestClient stream = connect(tenant.ownerToken());
        awaitSubscribers(tenant, 1);

        stream.close();

        awaitSubscribers(tenant, 0);
        // The slot is free again: the tenant can open its full allowance afterwards.
        List<SseTestClient> again = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) {
                again.add(connect(tenant.ownerToken()));
            }
        } finally {
            again.forEach(SseTestClient::close);
        }
        awaitSubscribers(tenant, 0);
    }

    @Test
    void streamsOfOneTenantShareASingleRedisSubscription() {
        TenantFixture tenant = newTenant();
        StoredAnomaly first = insertAnomaly(tenant, T0, "LOW");
        StoredAnomaly second = insertAnomaly(tenant, T0.plusSeconds(60), "LOW");
        int openBefore = admission.openStreams();
        SseTestClient one = connect(tenant.ownerToken());
        SseTestClient two = connect(tenant.ownerToken());
        try {
            two.awaitComment("connected", PATIENCE);
            one.awaitComment("connected", PATIENCE);
            assertThat(subscribers(channelOf(tenant.tenantId()))).isEqualTo(1);

            notifySigned(notificationBody(first));
            one.awaitEvent(e -> first.id().toString().equals(e.id()), PATIENCE);
            two.awaitEvent(e -> first.id().toString().equals(e.id()), PATIENCE);

            one.close();
            // Wait until the server noticed the departure, then the other stream must still be subscribed.
            await().atMost(PATIENCE).until(() -> admission.openStreams() <= openBefore + 1);
            assertThat(subscribers(channelOf(tenant.tenantId()))).isEqualTo(1);
            notifySigned(notificationBody(second));
            two.awaitEvent(e -> second.id().toString().equals(e.id()), PATIENCE);

            two.close();
            awaitSubscribers(tenant, 0);
        } finally {
            one.close();
            two.close();
        }
    }

    @Test
    void severalEventsArriveInWindowOrder() {
        TenantFixture tenant = newTenant();
        List<StoredAnomaly> stored = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            stored.add(insertAnomaly(tenant, T0.plusSeconds(600L * i), "LOW"));
        }
        try (SseTestClient stream = connect(tenant.ownerToken())) {
            awaitSubscribers(tenant, 1);

            // Sent newest first; the server orders them by window before publishing.
            notifySigned(notificationBody(stored.reversed().toArray(StoredAnomaly[]::new)));

            await().atMost(PATIENCE).until(() -> stream.events().size() == 5);
            assertThat(stream.events()).extracting(Event::id)
                    .containsExactlyElementsOf(stored.stream().map(a -> a.id().toString()).toList());
        }
    }

    @Test
    void viewersMayStream() {
        TenantFixture tenant = newTenant();
        TokenResponse viewer = signInAs(tenant, "viewer@example.com", "VIEWER");
        try (SseTestClient stream = connect(viewer.accessToken())) {
            stream.awaitComment("connected", PATIENCE);
        }
    }

    @Test
    void theTokenIsTheOnlyAcceptedCredential() {
        TenantFixture tenant = newTenant();
        String apiKey = issueApiKey(tenant.ownerToken(), "agent").key();

        try (SseTestClient none = SseTestClient.open(port, null)) {
            assertThat(none.status()).isEqualTo(401);
            assertThat(none.header("Content-Type")).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        }
        try (SseTestClient garbage = SseTestClient.open(port, "not.a.jwt")) {
            assertThat(garbage.status()).isEqualTo(401);
        }
        client.get().uri("/api/v1/stream/anomalies").header(API_KEY_HEADER, apiKey).exchange()
                .expectStatus().isForbidden();
        // A query-string token is not a credential.
        client.get().uri("/api/v1/stream/anomalies?access_token={t}", tenant.ownerToken()).exchange()
                .expectStatus().isUnauthorized();
        assertThat(subscribers(channelOf(tenant.tenantId()))).isZero();
    }

    @Test
    void aTenantCannotOpenMoreStreamsThanItsLimitAndOthersAreUnaffected() {
        TenantFixture tenant = newTenant();
        TenantFixture other = newTenant();
        List<SseTestClient> open = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) {
                open.add(connect(tenant.ownerToken()));
            }

            try (SseTestClient refused = SseTestClient.open(port, tenant.ownerToken())) {
                assertThat(refused.status()).isEqualTo(429);
                assertThat(refused.header("Content-Type")).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                assertThat(refused.header("Retry-After")).isEqualTo("5");
                assertThat(JSON.readTree(refused.errorBody()).get("type").stringValue())
                        .isEqualTo("urn:pulseboard:problem:stream-limit");
            }
            try (SseTestClient unaffected = connect(other.ownerToken())) {
                unaffected.awaitComment("connected", PATIENCE);
            }

            // Closing one frees exactly one slot.
            open.removeFirst().close();
            await().atMost(PATIENCE).until(() -> {
                SseTestClient retry = SseTestClient.open(port, tenant.ownerToken());
                if (retry.status() == 200) {
                    open.add(retry);
                    return true;
                }
                retry.close();
                return false;
            });
            try (SseTestClient refusedAgain = SseTestClient.open(port, tenant.ownerToken())) {
                assertThat(refusedAgain.status()).isEqualTo(429);
            }
        } finally {
            open.forEach(SseTestClient::close);
        }
        awaitSubscribers(tenant, 0);
    }

    @Test
    void aStreamWorksUntilItsAccessTokenExpiresAndThenEndsAndReleasesItsSubscription() {
        TenantFixture tenant = newTenant();
        StoredAnomaly stored = insertAnomaly(tenant, T0, "HIGH");
        // JWT expiry has whole-second precision, so the value the server sees is the truncated one.
        Instant expiry = Instant.now().plusSeconds(6).truncatedTo(ChronoUnit.SECONDS);
        Instant issuedAt = expiry.minusSeconds(6);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .id(UUID.randomUUID().toString())
                .issuer(jwtProperties.issuer())
                .audience(List.of(jwtProperties.audience()))
                .subject(tenant.owner().userId().toString())
                .issuedAt(issuedAt)
                .notBefore(issuedAt)
                .expiresAt(expiry)
                .claim("tid", tenant.tenantId().toString())
                .claim("role", "OWNER")
                .build();
        String shortLived = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims)).getTokenValue();

        int openBefore = admission.openStreams();
        try (SseTestClient stream = connect(shortLived)) {
            awaitSubscribers(tenant, 1);

            // Before expiry the stream is fully usable: it delivers an event.
            assertThat(notifySigned(notificationBody(stored)).statusCode()).isEqualTo(202);
            stream.awaitEvent(e -> stored.id().toString().equals(e.id()), Duration.ofSeconds(4));

            // ... and it is still open at a checkpoint in the last quarter of the token lifetime, taken right after a
            // heartbeat that arrived there: a stream that closes early (for instance at half the lifetime) fails here.
            Instant lastQuarter = expiry.minus(Duration.between(issuedAt, expiry).dividedBy(4));
            Instant safetyLimit = expiry.minusMillis(400);
            await().atMost(Duration.between(Instant.now(), lastQuarter).plusSeconds(1))
                    .pollInterval(Duration.ofMillis(25)).until(() -> !Instant.now().isBefore(lastQuarter));
            int pings = stream.comments().size();
            await().atMost(Duration.ofSeconds(2)).pollInterval(Duration.ofMillis(10))
                    .until(() -> stream.comments().size() > pings);
            Instant checkpoint = Instant.now();
            assertThat(checkpoint).as("checkpoint is in the last quarter of the token lifetime")
                    .isAfterOrEqualTo(lastQuarter).isBefore(safetyLimit);
            assertThat(stream.hasEnded()).as("stream still open near expiry").isFalse();
            assertThat(subscribers(channelOf(tenant.tenantId()))).isEqualTo(1);

            // After expiry the stream ends and everything it held is released, all within ONE deadline.
            Instant deadline = expiry.plusSeconds(4);
            stream.awaitEnd(Duration.between(Instant.now(), deadline));
            await().atMost(remaining(deadline)).until(() -> subscribers(channelOf(tenant.tenantId())) == 0);
            await().atMost(remaining(deadline)).until(() -> admission.openStreams() <= openBefore);
            assertThat(Instant.now()).as("released within the single deadline")
                    .isBeforeOrEqualTo(deadline.plusSeconds(1));
        }
    }

    private static Duration remaining(Instant deadline) {
        Duration left = Duration.between(Instant.now(), deadline);
        return left.isNegative() || left.isZero() ? Duration.ofMillis(50) : left;
    }

    @Test
    void notifyingWhileNobodyListensIsFine() {
        TenantFixture tenant = newTenant();
        StoredAnomaly stored = insertAnomaly(tenant, T0, "LOW");

        assertThat(notifySigned(notificationBody(stored)).statusCode()).isEqualTo(202);
        assertThat(subscribers(channelOf(tenant.tenantId()))).isZero();
        JsonNode stillThere = JSON.readTree(client.get().uri("/api/v1/anomalies/{id}", stored.id())
                .header(HttpHeaders.AUTHORIZATION, bearer(tenant.ownerToken()))
                .exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody());
        assertThat(stillThere.get("id").stringValue()).isEqualTo(stored.id().toString());
    }
}

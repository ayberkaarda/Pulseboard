package io.pulseboard.core.api;

import static io.pulseboard.core.support.StreamTicketTestSupport.issueTicket;
import static io.pulseboard.core.support.StreamTicketTestSupport.openStream;
import static io.pulseboard.core.support.StreamTicketTestSupport.openWithTicket;
import static io.pulseboard.core.support.StreamTicketTestSupport.requestTicket;
import static io.pulseboard.core.support.StreamTicketTestSupport.sha256Hex;
import static io.pulseboard.core.support.StreamTicketTestSupport.statusOfGet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import io.pulseboard.core.api.auth.AuthDtos.TokenResponse;
import io.pulseboard.core.application.anomaly.StreamAdmission;
import io.pulseboard.core.infrastructure.security.JwtProperties;
import io.pulseboard.core.support.AnomalyIntegrationTest;
import io.pulseboard.core.support.SseTestClient;
import io.pulseboard.core.support.SseTestClient.Event;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** One-time stream tickets over real HTTP, a real Redis and real server-sent event connections. */
@ExtendWith(OutputCaptureExtension.class)
class StreamTicketIntegrationTest extends AnomalyIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant T0 = Instant.parse("2026-03-01T00:00:00Z");
    private static final String PROBLEM_UNAUTHORIZED = "urn:pulseboard:problem:unauthorized";

    @Autowired
    private StreamAdmission admission;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private JwtProperties jwtProperties;

    private static String bearerHeader(String token) {
        return "Bearer " + token;
    }

    @Test
    void aTicketOpensAStreamThatDeliversEventsOfTheTicketOwnersTenant() {
        TenantFixture tenant = newTenant();
        StoredAnomaly stored = insertAnomaly(tenant, T0, "HIGH");

        HttpResponse<String> issued = requestTicket(port, "Authorization", bearerHeader(tenant.ownerToken()));

        assertThat(issued.statusCode()).isEqualTo(200);
        assertThat(issued.headers().firstValue("Cache-Control")).hasValueSatisfying(value ->
                assertThat(value).contains("no-store"));
        JsonNode body = JSON.readTree(issued.body());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("ticket", "expiresInSeconds");
        assertThat(body.get("expiresInSeconds").asInt()).isEqualTo(30);
        String ticket = body.get("ticket").asString();
        assertThat(ticket).matches("[A-Za-z0-9_-]{43}");
        try (SseTestClient stream = openWithTicket(port, ticket)) {
            assertThat(stream.status()).as("body: %s", stream.errorBody()).isEqualTo(200);
            assertThat(stream.header("Content-Type")).startsWith("text/event-stream");
            await().atMost(PATIENCE).until(() -> subscribers(channelOf(tenant.tenantId())) == 1);

            assertThat(notifySigned(notificationBody(stored)).statusCode()).isEqualTo(202);

            Event event = stream.awaitEvent(e -> stored.id().toString().equals(e.id()), PATIENCE);
            assertThat(event.name()).isEqualTo("anomaly");
            assertThat(JSON.readTree(event.data()).get("tenantId").asString())
                    .isEqualTo(tenant.tenantId().toString());
        }
    }

    @Test
    void everyTicketIsDifferent() {
        TenantFixture tenant = newTenant();

        Set<String> tickets = new java.util.HashSet<>();
        for (int i = 0; i < 10; i++) {
            tickets.add(issueTicket(port, tenant.ownerToken()));
        }

        assertThat(tickets).hasSize(10);
    }

    @Test
    void aTicketWorksOnlyOnce() {
        TenantFixture tenant = newTenant();
        String ticket = issueTicket(port, tenant.ownerToken());
        try (SseTestClient first = openWithTicket(port, ticket)) {
            assertThat(first.status()).isEqualTo(200);

            try (SseTestClient replay = openWithTicket(port, ticket)) {
                assertThat(replay.status()).isEqualTo(401);
                assertThat(replay.errorBody()).contains(PROBLEM_UNAUTHORIZED);
            }
        }
        // Closing the first stream does not bring the ticket back.
        try (SseTestClient again = openWithTicket(port, ticket)) {
            assertThat(again.status()).isEqualTo(401);
        }
    }

    @Test
    void everyKindOfBadTicketGetsTheSameAnswer() {
        TenantFixture tenant = newTenant();
        String used = issueTicket(port, tenant.ownerToken());
        openWithTicket(port, used).close();
        String unknownButWellFormed = "A".repeat(43);
        List<String> bad = List.of(used, unknownButWellFormed, "garbage", "", "A".repeat(42), "A".repeat(44),
                "A".repeat(42) + "%20", "A".repeat(42) + "=", "..%2F..%2Fetc", "A".repeat(3_000));

        List<String> bodies = new ArrayList<>();
        for (String ticket : bad) {
            try (SseTestClient stream = openStream(port, "ticket=" + ticket)) {
                assertThat(stream.status()).as("ticket '%s'", abbreviate(ticket)).isEqualTo(401);
                bodies.add(stream.errorBody());
            }
        }
        assertThat(bodies).as("one body for every failure").hasSize(bad.size());
        assertThat(Set.copyOf(bodies)).hasSize(1);
        assertThat(bodies.getFirst()).contains(PROBLEM_UNAUTHORIZED);
        // What a caller sees without any credentials is the same document.
        try (SseTestClient anonymous = openStream(port, null)) {
            assertThat(anonymous.status()).isEqualTo(401);
            assertThat(anonymous.errorBody()).isEqualTo(bodies.getFirst());
        }
    }

    private static String abbreviate(String text) {
        return text.length() > 20 ? text.substring(0, 20) + "..." : text;
    }

    @Test
    void aRepeatedTicketParameterIsRefusedWithoutUsingEitherTicket() {
        TenantFixture tenant = newTenant();
        String first = issueTicket(port, tenant.ownerToken());
        String second = issueTicket(port, tenant.ownerToken());

        try (SseTestClient both = openStream(port, "ticket=" + first + "&ticket=" + second)) {
            assertThat(both.status()).isEqualTo(401);
        }

        assertThat(redis.hasKey("stream:ticket:" + sha256Hex(first))).isTrue();
        assertThat(redis.hasKey("stream:ticket:" + sha256Hex(second))).isTrue();
    }

    @Test
    void aTicketTogetherWithAnotherCredentialIsRefusedAndStaysUnused() {
        TenantFixture tenant = newTenant();
        String apiKey = issueApiKey(tenant.ownerToken(), "agent").key();
        String ticket = issueTicket(port, tenant.ownerToken());

        try (SseTestClient withBearer = openStream(port, "ticket=" + ticket,
                "Authorization", bearerHeader(tenant.ownerToken()))) {
            assertThat(withBearer.status()).isEqualTo(401);
            assertThat(withBearer.errorBody()).contains(PROBLEM_UNAUTHORIZED);
        }
        try (SseTestClient withKey = openStream(port, "ticket=" + ticket, "X-Api-Key", apiKey)) {
            assertThat(withKey.status()).isEqualTo(401);
        }
        // A garbage bearer token next to a good ticket is refused as well; nothing is guessed in favour of either.
        try (SseTestClient withGarbage = openStream(port, "ticket=" + ticket, "Authorization", "Bearer garbage")) {
            assertThat(withGarbage.status()).isEqualTo(401);
        }

        // The refusals did not touch the ticket: it is still good for a request that presents it alone.
        try (SseTestClient alone = openWithTicket(port, ticket)) {
            assertThat(alone.status()).isEqualTo(200);
        }
    }

    @Test
    void anAccessTokenInTheQueryStringIsNotAccepted() {
        TenantFixture tenant = newTenant();

        for (String name : List.of("access_token", "token", "bearer", "Authorization", "jwt")) {
            try (SseTestClient stream = openStream(port, name + "=" + tenant.ownerToken())) {
                assertThat(stream.status()).as(name).isEqualTo(401);
            }
        }
        // Not even as the ticket.
        try (SseTestClient stream = openStream(port, "ticket=" + tenant.ownerToken())) {
            assertThat(stream.status()).isEqualTo(401);
        }
    }

    @Test
    void aTicketIsBoundToItsTenantAndNeverShowsAnotherTenantsEvents() {
        TenantFixture a = newTenant();
        TenantFixture b = newTenant();
        StoredAnomaly ofA = insertAnomaly(a, T0, "HIGH");
        StoredAnomaly ofB = insertAnomaly(b, T0, "HIGH");
        String ticketOfA = issueTicket(port, a.ownerToken());

        try (SseTestClient stream = openWithTicket(port, ticketOfA)) {
            assertThat(stream.status()).isEqualTo(200);
            await().atMost(PATIENCE).until(() -> subscribers(channelOf(a.tenantId())) == 1);
            assertThat(subscribers(channelOf(b.tenantId()))).isZero();

            assertThat(notifySigned(notificationBody(ofB, ofA)).statusCode()).isEqualTo(202);

            stream.awaitEvent(e -> ofA.id().toString().equals(e.id()), PATIENCE);
            // Heartbeats follow every event already queued, so a later ping proves nothing foreign is on its way.
            int pings = stream.comments().size();
            await().atMost(PATIENCE).until(() -> stream.comments().size() > pings + 1);
            assertThat(stream.events()).extracting(Event::id).containsExactly(ofA.id().toString());
        }
    }

    @Test
    void aTicketCannotBeBorrowedForAnotherTenantByTamperingWithTheRequest() {
        TenantFixture a = newTenant();
        TenantFixture b = newTenant();
        String ticketOfA = issueTicket(port, a.ownerToken());

        try (SseTestClient stream = openStream(port, "ticket=" + ticketOfA + "&tenantId=" + b.tenantId()
                + "&tid=" + b.tenantId(), "X-Tenant-Id", b.tenantId().toString())) {
            assertThat(stream.status()).isEqualTo(200);
            await().atMost(PATIENCE).until(() -> subscribers(channelOf(a.tenantId())) == 1);
            assertThat(subscribers(channelOf(b.tenantId()))).isZero();
        }
    }

    @Test
    void aTicketIsIgnoredEverywhereExceptTheStreamRoute() {
        TenantFixture tenant = newTenant();
        String ticket = issueTicket(port, tenant.ownerToken());

        for (String path : List.of("/api/v1/anomalies", "/api/v1/services", "/api/v1/metrics",
                "/api/v1/api-keys", "/api/v1/stream/ticket", "/api/v1/stream/anomalies/")) {
            client.get().uri(path + "?ticket={t}", ticket).exchange()
                    .expectStatus().value(status -> assertThat(status).as(path).isIn(401, 404));
        }
        client.post().uri("/api/v1/stream/ticket?ticket={t}", ticket).exchange()
                .expectStatus().isUnauthorized();
        client.post().uri("/api/v1/stream/anomalies?ticket={t}", ticket).exchange()
                .expectStatus().value(status -> assertThat(status).isIn(401, 405));
        // A public route does not look at it either.
        client.get().uri("/v3/api-docs?ticket={t}", ticket).exchange().expectStatus().isOk();
        // None of those requests consumed it.
        assertThat(redis.hasKey("stream:ticket:" + sha256Hex(ticket))).isTrue();
    }

    @Test
    void aTicketDoesNotAuthenticateAnythingBeyondTheOpeningRequest() {
        TenantFixture tenant = newTenant();
        String ticket = issueTicket(port, tenant.ownerToken());
        try (SseTestClient stream = openWithTicket(port, ticket)) {
            assertThat(stream.status()).isEqualTo(200);
        }

        client.get().uri("/api/v1/anomalies").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/v1/anomalies?ticket={t}", ticket).exchange().expectStatus().isUnauthorized();
    }

    @Test
    void anApiKeyCannotIssueATicket() {
        TenantFixture tenant = newTenant();
        String apiKey = issueApiKey(tenant.ownerToken(), "agent").key();

        HttpResponse<String> response = requestTicket(port, "X-Api-Key", apiKey);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(redis.keys("stream:ticket:*")).isNotNull();
    }

    @Test
    void issuingNeedsAUserAccessToken() {
        assertThat(requestTicket(port).statusCode()).isEqualTo(401);
        assertThat(requestTicket(port, "Authorization", "Bearer garbage").statusCode()).isEqualTo(401);
        TenantFixture tenant = newTenant();
        // A ticket is not an access token either.
        String ticket = issueTicket(port, tenant.ownerToken());
        assertThat(requestTicket(port, "Authorization", bearerHeader(ticket)).statusCode()).isEqualTo(401);
    }

    @Test
    void everyRoleThatMayReadAnomaliesMayIssueATicket() {
        TenantFixture tenant = newTenant();
        TokenResponse viewer = signInAs(tenant, "viewer@example.com", "VIEWER");
        TokenResponse admin = signInAs(tenant, "admin@example.com", "ADMIN");

        for (String token : List.of(viewer.accessToken(), admin.accessToken(), tenant.ownerToken())) {
            String ticket = issueTicket(port, token);
            try (SseTestClient stream = openWithTicket(port, ticket)) {
                assertThat(stream.status()).isEqualTo(200);
            }
        }
    }

    @Test
    void issuingIsLimitedPerUserAndOneUsersLimitDoesNotAffectAnother() {
        TenantFixture tenant = newTenant();
        TokenResponse other = signInAs(tenant, "other@example.com", "VIEWER");
        for (int i = 0; i < 30; i++) {
            assertThat(requestTicket(port, "Authorization", bearerHeader(tenant.ownerToken())).statusCode())
                    .as("request %d", i + 1).isEqualTo(200);
        }

        HttpResponse<String> limited = requestTicket(port, "Authorization", bearerHeader(tenant.ownerToken()));

        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.headers().firstValue("Retry-After")).isPresent();
        assertThat(limited.headers().firstValue("Content-Type")).hasValueSatisfying(type ->
                assertThat(type).startsWith("application/problem+json"));
        assertThat(limited.body()).doesNotContain("\"ticket\"");
        assertThat(requestTicket(port, "Authorization", bearerHeader(other.accessToken())).statusCode())
                .isEqualTo(200);
    }

    @Test
    void redisHoldsOnlyTheDigestOfATicket() {
        TenantFixture tenant = newTenant();
        String ticket = issueTicket(port, tenant.ownerToken());
        String key = "stream:ticket:" + sha256Hex(ticket);

        assertThat(redis.hasKey(key)).isTrue();
        Set<String> keys = redis.keys("*");
        assertThat(keys).isNotNull().noneMatch(name -> name.contains(ticket));
        assertThat(keys).filteredOn(name -> name.startsWith("stream:ticket:"))
                .allMatch(name -> name.matches("stream:ticket:[0-9a-f]{64}"));
        String value = redis.opsForValue().get(key);
        assertThat(value).isNotNull().doesNotContain(ticket);
        JsonNode stored = JSON.readTree(value);
        assertThat(stored.propertyNames()).containsExactlyInAnyOrder("tenantId", "userId", "jwtExp");
        assertThat(stored.get("tenantId").asString()).isEqualTo(tenant.tenantId().toString());
        assertThat(stored.get("userId").asString()).isEqualTo(tenant.owner().userId().toString());
        Long ttl = redis.getExpire(key, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertThat(ttl).isNotNull().isBetween(1L, 30_000L);
    }

    @Test
    void usingATicketRemovesItFromRedis() {
        TenantFixture tenant = newTenant();
        String ticket = issueTicket(port, tenant.ownerToken());
        String key = "stream:ticket:" + sha256Hex(ticket);
        assertThat(redis.hasKey(key)).isTrue();

        try (SseTestClient stream = openWithTicket(port, ticket)) {
            assertThat(stream.status()).isEqualTo(200);
            assertThat(redis.hasKey(key)).isFalse();
        }
    }

    @Test
    void ofManyParallelRequestsWithOneTicketExactlyOneOpensAStream() throws Exception {
        int attempts = 8;
        for (int round = 0; round < 5; round++) {
            // A fresh tenant per round: a stream of an earlier round may still hold a slot of the tenant cap.
            TenantFixture tenant = newTenant();
            String ticket = issueTicket(port, tenant.ownerToken());
            CountDownLatch go = new CountDownLatch(1);
            List<SseTestClient> clients = Collections.synchronizedList(new ArrayList<>());
            try (ExecutorService pool = Executors.newFixedThreadPool(attempts)) {
                List<Future<Integer>> results = new ArrayList<>();
                for (int i = 0; i < attempts; i++) {
                    results.add(pool.submit(() -> {
                        go.await();
                        SseTestClient stream = openWithTicket(port, ticket);
                        clients.add(stream);
                        return stream.status();
                    }));
                }
                go.countDown();
                List<Integer> statuses = new ArrayList<>();
                for (Future<Integer> result : results) {
                    statuses.add(result.get());
                }

                assertThat(statuses).as("round %d", round).filteredOn(status -> status == 200).hasSize(1);
                assertThat(statuses).filteredOn(status -> status != 200).hasSize(attempts - 1)
                        .allMatch(status -> status == 401);
            } finally {
                clients.forEach(SseTestClient::close);
            }
        }
    }

    @Test
    void theStreamOfATicketEndsWhenTheAccessTokenThatRequestedItExpires() {
        TenantFixture tenant = newTenant();
        StoredAnomaly stored = insertAnomaly(tenant, T0, "HIGH");
        Instant expiry = Instant.now().plusSeconds(6).truncatedTo(ChronoUnit.SECONDS);
        String shortLived = tokenExpiringAt(tenant, expiry);
        String ticket = issueTicket(port, shortLived);
        int openBefore = admission.openStreams();

        try (SseTestClient stream = openWithTicket(port, ticket)) {
            assertThat(stream.status()).isEqualTo(200);
            await().atMost(PATIENCE).until(() -> subscribers(channelOf(tenant.tenantId())) == 1);
            assertThat(notifySigned(notificationBody(stored)).statusCode()).isEqualTo(202);
            stream.awaitEvent(e -> stored.id().toString().equals(e.id()), Duration.ofSeconds(4));
            assertThat(stream.hasEnded()).isFalse();

            stream.awaitEnd(Duration.between(Instant.now(), expiry).plusSeconds(4));

            assertThat(Instant.now()).isAfterOrEqualTo(expiry.minusMillis(500));
        }
        await().atMost(PATIENCE).until(() -> subscribers(channelOf(tenant.tenantId())) == 0
                && admission.openStreams() <= openBefore);
    }

    @Test
    void aTicketNeverOutlivesTheAccessTokenThatRequestedIt() {
        TenantFixture tenant = newTenant();
        Instant expiry = Instant.now().plusSeconds(5).truncatedTo(ChronoUnit.SECONDS);

        HttpResponse<String> issued = requestTicket(port, "Authorization",
                bearerHeader(tokenExpiringAt(tenant, expiry)));

        assertThat(issued.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(issued.body());
        assertThat(body.get("expiresInSeconds").asInt()).isBetween(1, 5);
        String key = "stream:ticket:" + sha256Hex(body.get("ticket").asString());
        assertThat(redis.getExpire(key, java.util.concurrent.TimeUnit.MILLISECONDS)).isBetween(1L, 5_000L);
    }

    @Test
    void theTicketNeverAppearsInTheLogs(CapturedOutput output) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        Logger security = context.getLogger("org.springframework.security");
        Logger web = context.getLogger("org.springframework.web");
        Logger tomcat = context.getLogger("org.apache");
        Logger app = context.getLogger("io.pulseboard");
        Level[] previous = {root.getLevel(), security.getLevel(), web.getLevel(), tomcat.getLevel(),
            app.getLevel()};
        TenantFixture tenant = newTenant();
        List<String> tickets = new ArrayList<>();
        try {
            root.setLevel(Level.DEBUG);
            security.setLevel(Level.TRACE);
            web.setLevel(Level.TRACE);
            tomcat.setLevel(Level.DEBUG);
            app.setLevel(Level.TRACE);

            String ok = issueTicket(port, tenant.ownerToken());
            tickets.add(ok);
            try (SseTestClient stream = openWithTicket(port, ok)) {
                assertThat(stream.status()).isEqualTo(200);
                stream.awaitComment("connected", PATIENCE);
            }
            try (SseTestClient replay = openWithTicket(port, ok)) {
                assertThat(replay.status()).isEqualTo(401);
            }
            String withHeader = issueTicket(port, tenant.ownerToken());
            tickets.add(withHeader);
            try (SseTestClient both = openStream(port, "ticket=" + withHeader,
                    "Authorization", bearerHeader(tenant.ownerToken()))) {
                assertThat(both.status()).isEqualTo(401);
            }
            String elsewhere = issueTicket(port, tenant.ownerToken());
            tickets.add(elsewhere);
            assertThat(statusOfGet(port, "/api/v1/anomalies?ticket=" + elsewhere)).isEqualTo(401);
            String malformed = "not-a-ticket-" + UUID.randomUUID();
            tickets.add(malformed);
            openStream(port, "ticket=" + malformed).close();

            // An encoded parameter name is the same parameter for the servlet, but not for a literal match.
            String encodedWithHeader = issueTicket(port, tenant.ownerToken());
            tickets.add(encodedWithHeader);
            try (SseTestClient both = openStream(port, "ti%63ket=" + encodedWithHeader,
                    "Authorization", bearerHeader(tenant.ownerToken()))) {
                assertThat(both.status()).isEqualTo(401);
            }
            try (SseTestClient later = openWithTicket(port, encodedWithHeader)) {
                // refused without consuming: the same ticket still works afterwards
                assertThat(later.status()).isEqualTo(200);
            }
            String encoded = issueTicket(port, tenant.ownerToken());
            tickets.add(encoded);
            try (SseTestClient stream = openStream(port, "%74icket=" + encoded)) {
                assertThat(stream.status()).isEqualTo(200);
            }
            String repeated = issueTicket(port, tenant.ownerToken());
            tickets.add(repeated);
            try (SseTestClient both = openStream(port, "ticket=" + repeated + "&%74icket=" + repeated)) {
                assertThat(both.status()).isEqualTo(401);
            }
            String mixedCase = issueTicket(port, tenant.ownerToken());
            tickets.add(mixedCase);
            assertThat(statusOfGet(port, "/api/v1/stream/anomalies?TiCkEt=" + mixedCase)).isEqualTo(401);
            assertThat(statusOfGet(port, "/api/v1/stream/anomalies?%54iCkEt=" + mixedCase)).isEqualTo(401);
            String doubleEncoded = issueTicket(port, tenant.ownerToken());
            tickets.add(doubleEncoded);
            assertThat(statusOfGet(port, "/api/v1/stream/anomalies?%2574icket=" + doubleEncoded)).isEqualTo(401);
            assertThat(statusOfGet(port, "/api/v1/stream/anomalies?%252574icket=" + doubleEncoded)).isEqualTo(401);
        } finally {
            root.setLevel(previous[0]);
            security.setLevel(previous[1]);
            web.setLevel(previous[2]);
            tomcat.setLevel(previous[3]);
            app.setLevel(previous[4]);
        }

        String logged = output.getAll();
        // The logging was verbose enough to mention these requests at all ...
        assertThat(logged).contains("/api/v1/stream/anomalies");
        // ... and still holds no ticket, in any of the tested situations.
        for (String ticket : tickets) {
            assertThat(logged.lines().filter(line -> line.contains(ticket)).toList())
                    .as("log lines holding a ticket").isEmpty();
        }
        assertThat(logged).doesNotContainPattern("ticket=[A-Za-z0-9_-]{20,}");
        // Request lines were logged with the value masked, so the redaction was exercised.
        assertThat(logged).contains("/api/v1/stream/anomalies?ticket=***");
        assertThat(logged).containsPattern("(?:%74icket|ti%63ket)=\\*\\*\\*");
    }

    private String tokenExpiringAt(TenantFixture tenant, Instant expiry) {
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
        return jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims)).getTokenValue();
    }

    @Test
    void theIssuingResponseIsNeverCachedAndNamesNoOtherUser() {
        TenantFixture tenant = newTenant();

        HttpResponse<String> issued = requestTicket(port, "Authorization", bearerHeader(tenant.ownerToken()));

        String cacheControl = issued.headers().allValues(HttpHeaders.CACHE_CONTROL).stream()
                .collect(Collectors.joining(","));
        assertThat(cacheControl).contains("no-store");
        assertThat(issued.body()).doesNotContain(tenant.owner().userId().toString());
        assertThat(issued.body()).doesNotContain(tenant.tenantId().toString());
    }
}

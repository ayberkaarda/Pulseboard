package io.pulseboard.core.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.pulseboard.core.support.AnomalyIntegrationTest;
import io.pulseboard.core.support.RawHttp;
import io.pulseboard.core.support.SseTestClient;
import io.pulseboard.core.support.SseTestClient.Event;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * POST /internal/v1/anomalies/notify: signature guard, body limits, idempotency, and the rule that only what the
 * database holds is published, and only to the tenant that owns the row.
 */
class AnomalyNotifyIntegrationTest extends AnomalyIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String NOTIFY = "/internal/v1/anomalies/notify";

    private void assertProblem(HttpResponse<String> response, int status, String type) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        assertThat(JSON.readTree(response.body()).get("type").stringValue())
                .isEqualTo("urn:pulseboard:problem:" + type);
    }

    @Test
    void aSignedNotificationIsAcceptedAndPublishedToTheOwningTenantOnly() {
        TenantFixture a = newTenant();
        TenantFixture b = newTenant();
        StoredAnomaly stored = insertAnomaly(a, Instant.parse("2026-05-01T10:00:00Z"), "MEDIUM");
        try (SseTestClient ownerStream = SseTestClient.open(port, a.ownerToken());
                SseTestClient otherStream = SseTestClient.open(port, b.ownerToken())) {
            assertThat(ownerStream.status()).isEqualTo(200);
            assertThat(otherStream.status()).isEqualTo(200);
            await().atMost(PATIENCE).until(() -> subscribers(channelOf(a.tenantId())) == 1
                    && subscribers(channelOf(b.tenantId())) == 1);

            // The sender claims HIGH; the database says MEDIUM, and the database wins.
            HttpResponse<String> response = notifySigned(notificationBody("HIGH", stored));

            assertThat(response.statusCode()).isEqualTo(202);
            assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
            JsonNode body = JSON.readTree(response.body());
            assertThat(body.get("accepted").intValue()).isEqualTo(1);
            assertThat(body.get("unknown").intValue()).isZero();

            Event event = ownerStream.awaitEvent(e -> stored.id().toString().equals(e.id()), PATIENCE);
            assertThat(event.name()).isEqualTo("anomaly");
            JsonNode data = JSON.readTree(event.data());
            assertThat(data.get("severity").stringValue()).isEqualTo("MEDIUM");
            assertThat(data.get("tenantId").stringValue()).isEqualTo(a.tenantId().toString());
            assertThat(data.get("serviceId").stringValue()).isEqualTo(a.serviceId().toString());
            // The other tenant's stream stays silent: a heartbeat proves it is alive and nothing else arrived.
            otherStream.awaitComment("ping", PATIENCE);
            assertThat(otherStream.events()).isEmpty();
        }
    }

    @Test
    void missingWrongAndMalformedSignaturesAreRejectedAlike() {
        TenantFixture tenant = newTenant();
        String body = notificationBody(insertAnomaly(tenant, Instant.parse("2026-05-01T10:00:00Z"), "LOW"));

        HttpResponse<String> missing = RawHttp.postJson(port, NOTIFY, body);
        HttpResponse<String> wrong = notifyWith(body, hmacHex("another-secret-another-secret-another-secret", body));
        HttpResponse<String> shortSignature = notifyWith(body, sign(body).substring(1));
        HttpResponse<String> upperCase = notifyWith(body, sign(body).toUpperCase(Locale.ROOT));
        HttpResponse<String> notHex = notifyWith(body, "z".repeat(64));
        HttpResponse<String> empty = notifyWith(body, "");

        for (HttpResponse<String> response : List.of(missing, wrong, shortSignature, upperCase, notHex,
                empty)) {
            assertProblem(response, 401, "unauthorized");
        }
        // One generic message, so the response does not say which check failed.
        assertThat(wrong.body()).isEqualTo(missing.body()).isEqualTo(shortSignature.body());
    }

    @Test
    void aTamperedBodyFailsTheSignatureOfTheOriginal() {
        TenantFixture tenant = newTenant();
        StoredAnomaly stored = insertAnomaly(tenant, Instant.parse("2026-05-01T10:00:00Z"), "LOW");
        String original = notificationBody(stored);
        String tampered = original.replace("\"score\":0.9", "\"score\":0.1");
        assertThat(tampered).isNotEqualTo(original);

        assertProblem(notifyWith(tampered, sign(original)), 401, "unauthorized");
        assertProblem(notifyWith(original + " ", sign(original)), 401, "unauthorized");
        assertThat(notifySigned(original).statusCode()).isEqualTo(202);
    }

    @Test
    void twoSignatureHeadersAreRejected() {
        TenantFixture tenant = newTenant();
        String body = notificationBody(insertAnomaly(tenant, Instant.parse("2026-05-01T10:00:00Z"), "LOW"));

        HttpResponse<String> response = RawHttp.postJson(port, NOTIFY, body,
                "X-Internal-Signature", sign(body), "X-Internal-Signature", sign(body));

        assertProblem(response, 401, "unauthorized");
    }

    @Test
    void aReplayIsHarmlessAndPublishesTheSameEventAgain() {
        TenantFixture tenant = newTenant();
        StoredAnomaly stored = insertAnomaly(tenant, Instant.parse("2026-05-01T10:00:00Z"), "HIGH");
        String body = notificationBody(stored);
        int rowsBefore = jdbc.queryForObject("SELECT count(*) FROM anomaly WHERE tenant_id = ?", Integer.class,
                tenant.tenantId());
        try (SseTestClient stream = SseTestClient.open(port, tenant.ownerToken())) {
            await().atMost(PATIENCE).until(
                    () -> subscribers(channelOf(tenant.tenantId())) == 1);

            HttpResponse<String> first = notifySigned(body);
            HttpResponse<String> replay = notifySigned(body);

            assertThat(first.statusCode()).isEqualTo(202);
            assertThat(replay.statusCode()).isEqualTo(202);
            assertThat(replay.body()).isEqualTo(first.body());
            await().atMost(PATIENCE).until(() -> stream.events().size() == 2);
            assertThat(stream.events()).extracting(Event::id).containsOnly(stored.id().toString());
            assertThat(stream.events().get(0).data()).isEqualTo(stream.events().get(1).data());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM anomaly WHERE tenant_id = ?", Integer.class,
                tenant.tenantId())).isEqualTo(rowsBefore);
    }

    @Test
    void anIdOfAnotherTenantIsUnknownAndNothingIsPublishedToEither() {
        TenantFixture a = newTenant();
        TenantFixture b = newTenant();
        StoredAnomaly ofA = insertAnomaly(a, Instant.parse("2026-05-01T10:00:00Z"), "HIGH");
        // B's tenant id is claimed for A's anomaly.
        StoredAnomaly claimedForB = new StoredAnomaly(ofA.id(), b.tenantId(), b.metricId(), ofA.windowStart(), "HIGH");
        try (SseTestClient streamA = SseTestClient.open(port, a.ownerToken());
                SseTestClient streamB = SseTestClient.open(port, b.ownerToken())) {
            await().atMost(PATIENCE).until(() -> subscribers(channelOf(a.tenantId())) == 1
                    && subscribers(channelOf(b.tenantId())) == 1);

            HttpResponse<String> response = notifySigned(notificationBody(claimedForB));

            assertThat(response.statusCode()).isEqualTo(202);
            JsonNode body = JSON.readTree(response.body());
            assertThat(body.get("accepted").intValue()).isZero();
            assertThat(body.get("unknown").intValue()).isEqualTo(1);
            streamA.awaitComment("ping", PATIENCE);
            streamB.awaitComment("ping", PATIENCE);
            assertThat(streamA.events()).isEmpty();
            assertThat(streamB.events()).isEmpty();
        }
    }

    @Test
    void unknownIdsAreCountedAndTheKnownOnesStillPublished() {
        TenantFixture tenant = newTenant();
        StoredAnomaly known = insertAnomaly(tenant, Instant.parse("2026-05-01T10:00:00Z"), "LOW");
        StoredAnomaly ghost = new StoredAnomaly(UUID.randomUUID(), tenant.tenantId(), tenant.metricId(),
                Instant.parse("2026-05-01T11:00:00Z"), "LOW");
        try (SseTestClient stream = SseTestClient.open(port, tenant.ownerToken())) {
            await().atMost(PATIENCE).until(
                    () -> subscribers(channelOf(tenant.tenantId())) == 1);

            HttpResponse<String> response = notifySigned(notificationBody(known, ghost, known));

            JsonNode body = JSON.readTree(response.body());
            assertThat(response.statusCode()).isEqualTo(202);
            // The duplicate entry of the known row is counted once.
            assertThat(body.get("accepted").intValue()).isEqualTo(1);
            assertThat(body.get("unknown").intValue()).isEqualTo(1);
            stream.awaitEvent(e -> known.id().toString().equals(e.id()), PATIENCE);
            assertThat(stream.events()).hasSize(1);
        }
    }

    @Test
    void invalidBodiesAreRejectedAfterTheSignatureIsChecked() {
        String notJson = "{not json";
        String empty = "{\"anomalies\":[]}";
        String extra = "{\"anomalies\":[{\"id\":\"" + UUID.randomUUID() + "\"}],\"x\":1}";
        String badUuid = "{\"anomalies\":[{\"id\":\"nope\",\"tenantId\":\"" + UUID.randomUUID() + "\","
                + "\"metricId\":\"" + UUID.randomUUID() + "\",\"windowStart\":\"2026-05-01T10:00:00Z\","
                + "\"windowEnd\":\"2026-05-01T10:05:00Z\",\"score\":0.5,\"detector\":\"ZSCORE\","
                + "\"severity\":\"LOW\",\"createdAt\":\"2026-05-01T10:06:00Z\"}]}";
        String badScore = badUuid.replace("\"nope\"", "\"" + UUID.randomUUID() + "\"").replace("0.5", "7");
        String duplicateKey = "{\"anomalies\":[],\"anomalies\":[]}";
        String tooMany = notificationBodyOf(501);

        for (String body : new String[] {notJson, empty, extra, badUuid, badScore, duplicateKey, tooMany, ""}) {
            HttpResponse<String> response = notifySigned(body);
            assertProblem(response, 400, "validation");
        }
        // Without a valid signature the same bodies never reach validation.
        assertProblem(notifyWith(notJson, "0".repeat(64)), 401, "unauthorized");
    }

    @Test
    void fiveHundredEntriesAreAcceptedButNotMore() {
        HttpResponse<String> ok = notifySigned(notificationBodyOf(500));

        assertThat(ok.statusCode()).isEqualTo(202);
        assertThat(JSON.readTree(ok.body()).get("unknown").intValue()).isEqualTo(500);
    }

    @Test
    void anOversizedBodyIsRejectedBeforeItIsHashed() {
        String padding = "x".repeat(internalProperties.maxBodyBytes() + 1);
        String body = "{\"anomalies\":[],\"padding\":\"" + padding + "\"}";

        // A correct signature does not help: the limit comes first.
        assertProblem(notifySigned(body), 413, "payload-too-large");
        // The size decision does not depend on the signature, but a missing header is rejected even earlier.
        assertProblem(RawHttp.postJson(port, NOTIFY, body), 401, "unauthorized");
        // A body exactly at the limit is read and checked (and then fails validation, not the size limit).
        String atLimit = "{\"anomalies\":[],\"p\":\"" + "x".repeat(internalProperties.maxBodyBytes() - 23) + "\"}";
        assertThat(atLimit.length()).isEqualTo(internalProperties.maxBodyBytes());
        assertProblem(notifySigned(atLimit), 400, "validation");
    }

    @Test
    void otherMethodsAndPathsUnderInternalNeedTheSignatureToo() {
        client.get().uri(NOTIFY).exchange().expectStatus().isUnauthorized();
        client.get().uri("/internal/v1/anything").exchange().expectStatus().isUnauthorized();
        HttpResponse<String> signedUnknownPath = RawHttp.postJson(port,
                "/internal/v1/unknown", "{}", "X-Internal-Signature", sign("{}"));
        assertThat(signedUnknownPath.statusCode()).isEqualTo(404);
    }

    private static String notificationBodyOf(int entries) {
        StringBuilder body = new StringBuilder("{\"anomalies\":[");
        for (int i = 0; i < entries; i++) {
            body.append(i == 0 ? "" : ",").append("{\"id\":\"").append(UUID.randomUUID())
                    .append("\",\"tenantId\":\"").append(UUID.randomUUID())
                    .append("\",\"metricId\":\"").append(UUID.randomUUID())
                    .append("\",\"windowStart\":\"2026-05-01T10:00:00Z\",\"windowEnd\":\"2026-05-01T10:05:00Z\","
                            + "\"score\":0.5,\"detector\":\"ZSCORE\",\"severity\":\"LOW\","
                            + "\"createdAt\":\"2026-05-01T10:06:00Z\"}");
        }
        return body.append("]}").toString();
    }
}

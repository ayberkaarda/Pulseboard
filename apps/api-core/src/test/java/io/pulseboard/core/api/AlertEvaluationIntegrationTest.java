package io.pulseboard.core.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.pulseboard.core.api.alert.AlertDtos.AlertRuleResponse;
import io.pulseboard.core.application.alert.webhook.WebhookSigner;
import io.pulseboard.core.support.AlertIntegrationTest;
import io.pulseboard.core.support.IngestPayloads;
import io.pulseboard.core.support.IngestPayloads.Point;
import io.pulseboard.core.support.WebhookReceiver;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import tools.jackson.databind.json.JsonMapper;

/** Rules fire from ingestion and from anomaly notifications, once, with the cooldown, and end up delivered. */
class AlertEvaluationIntegrationTest extends AlertIntegrationTest {

    @org.springframework.beans.factory.annotation.Autowired
    private JsonMapper jsonMapper;

    private static String stubOk(String path) {
        WebhookReceiver.server().stubFor(post(urlEqualTo(path)).willReturn(aResponse().withStatus(200)));
        return WebhookReceiver.url(path);
    }

    @Test
    void aThresholdRuleFiresOnIngestionAndIsDeliveredSignedToTheReceiver() throws Exception {
        TenantFixture tenant = newTenant();
        String path = hookPath();
        AlertRuleResponse rule = createRule(tenant.ownerToken(),
                thresholdBody(tenant.metricId(), "latency high", "GT", 100, stubOk(path)));
        String key = newApiKey(tenant);

        Instant ts = ingestNow(key, tenant.metricId(), 150.5).truncatedTo(ChronoUnit.MICROS);

        // The event exists as soon as the ingestion answered, PENDING and due, before any delivery.
        UUID eventId = onlyEvent(rule.id());
        assertThat(status(eventId)).isEqualTo("PENDING");
        assertThat(events(rule.id()).getFirst().get("source_ref")).isEqualTo(tenant.metricId() + "@" + ts);
        assertThat(requests(path)).isEmpty();

        dispatchUntil(() -> "SENT".equals(status(eventId)));

        List<LoggedRequest> received = requests(path);
        assertThat(received).hasSize(1);
        LoggedRequest request = received.getFirst();
        String timestamp = request.getHeader("X-Pulseboard-Timestamp");
        assertThat(request.getHeader("X-Pulseboard-Signature"))
                .isEqualTo(WebhookSigner.sign(signingSecret(), timestamp, request.getBody()))
                .matches("[0-9a-f]{64}");
        assertThat(Math.abs(Long.parseLong(timestamp) - Instant.now().getEpochSecond())).isLessThan(60);
        assertThat(request.getHeader("X-Pulseboard-Event-Id")).isEqualTo(eventId.toString());
        assertThat(request.getHeader("User-Agent")).isEqualTo("Pulseboard-Alerts");
        assertThat(request.getHeader(HttpHeaders.CONTENT_TYPE)).startsWith("application/json");

        Map<?, ?> body = jsonMapper.readValue(request.getBodyAsString(), Map.class);
        assertThat(body.get("eventId")).isEqualTo(eventId.toString());
        assertThat(body.get("ruleId")).isEqualTo(rule.id().toString());
        assertThat(body.get("ruleName")).isEqualTo("latency high");
        assertThat(body.get("tenantId")).isEqualTo(tenant.tenantId().toString());
        assertThat(body.get("serviceName")).isEqualTo("checkout");
        assertThat(body.get("condition")).isEqualTo("THRESHOLD");
        assertThat(((Number) body.get("value")).doubleValue()).isEqualTo(150.5);
        assertThat(((Number) body.get("threshold")).doubleValue()).isEqualTo(100.0);
        assertThat(body.get("operator")).isEqualTo("GT");
        assertThat(body.get("metric")).isEqualTo(Map.of("id", tenant.metricId().toString(),
                "name", "latency_p95_ms", "unit", "ms"));
        // Nothing but the documented members, and none of the secrets.
        assertThat(request.getBodyAsString()).doesNotContain(signingSecret()).doesNotContain(path)
                .doesNotContain("target");

        Map<String, Object> stored = events(rule.id()).getFirst();
        assertThat(stored.get("delivery_status")).isEqualTo("SENT");
        assertThat(stored.get("attempts")).isEqualTo(1);
        assertThat(stored.get("delivered_at")).isNotNull();
        assertThat(stored.get("last_error")).isNull();
    }

    @Test
    void theCooldownSilencesTheRuleUntilItExpires() {
        TenantFixture tenant = newTenant();
        AlertRuleResponse rule = createRule(tenant.ownerToken(),
                thresholdBody(tenant.metricId(), "cool", "GT", 10, stubOk(hookPath())));
        String key = newApiKey(tenant);

        ingestNow(key, tenant.metricId(), 50);
        assertThat(eventCount(rule.id())).isEqualTo(1);
        Long ttl = redis.getExpire("alert:cooldown:" + rule.id(), java.util.concurrent.TimeUnit.MILLISECONDS);
        assertThat(ttl).isBetween(1L, 60_000L);

        ingestNow(key, tenant.metricId(), 60);
        ingestNow(key, tenant.metricId(), 70);
        assertThat(eventCount(rule.id())).as("within the cooldown").isEqualTo(1);

        // Let the real key expire instead of deleting it.
        redis.expire("alert:cooldown:" + rule.id(), Duration.ofMillis(100));
        await().atMost(Duration.ofSeconds(5)).until(() -> !Boolean.TRUE.equals(
                redis.hasKey("alert:cooldown:" + rule.id())));
        ingestNow(key, tenant.metricId(), 80);

        assertThat(eventCount(rule.id())).as("after the cooldown").isEqualTo(2);
    }

    @Test
    void disabledRulesOtherMetricsAndOtherTenantsNeverFire() {
        TenantFixture tenant = newTenant();
        UUID otherMetric = newMetric(tenant, "billing", "queue_depth");
        AlertRuleResponse disabled = createRule(tenant.ownerToken(),
                thresholdBody(tenant.metricId(), "off", "GT", 0, stubOk(hookPath())));
        client.patch().uri("/api/v1/alert-rules/{id}", disabled.id())
                .header(HttpHeaders.AUTHORIZATION, bearer(tenant.ownerToken()))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(Map.of("enabled", false))
                .exchange().expectStatus().isOk();
        AlertRuleResponse onOtherMetric = createRule(tenant.ownerToken(),
                thresholdBody(otherMetric, "elsewhere", "GT", 0, stubOk(hookPath())));
        TenantFixture stranger = newTenant();
        AlertRuleResponse strangersRule = createRule(stranger.ownerToken(),
                thresholdBody(stranger.metricId(), "not yours", "GT", 0, stubOk(hookPath())));
        String key = newApiKey(tenant);

        ingestNow(key, tenant.metricId(), 1000);

        assertThat(eventCount(disabled.id())).as("disabled rule").isZero();
        assertThat(eventCount(onOtherMetric.id())).as("rule of another metric").isZero();
        assertThat(eventCount(strangersRule.id())).as("rule of another tenant").isZero();

        // A point sent to a metric of another tenant is rejected and raises nothing for that tenant either.
        ingest(newApiKey(tenant), IngestPayloads.single(stranger.metricId(), Instant.now(), 1000))
                .expectStatus().isAccepted()
                .expectBody().jsonPath("$.accepted").isEqualTo(0);
        assertThat(eventCount(strangersRule.id())).isZero();
    }

    @Test
    void comparisonsAtTheBoundaryFollowTheOperator() {
        TenantFixture tenant = newTenant();
        UUID second = newMetric(tenant, "billing", "queue_depth");
        String key = newApiKey(tenant);
        AlertRuleResponse gt = createRule(tenant.ownerToken(),
                thresholdBody(tenant.metricId(), "gt", "GT", 100, stubOk(hookPath())));
        AlertRuleResponse gte = createRule(tenant.ownerToken(),
                thresholdBody(tenant.metricId(), "gte", "GTE", 100, stubOk(hookPath())));
        AlertRuleResponse lt = createRule(tenant.ownerToken(),
                thresholdBody(second, "lt", "LT", 10, stubOk(hookPath())));
        AlertRuleResponse lte = createRule(tenant.ownerToken(),
                thresholdBody(second, "lte", "LTE", 10, stubOk(hookPath())));

        ingestNow(key, tenant.metricId(), 100);
        ingestNow(key, second, 10);

        assertThat(eventCount(gt.id())).as("GT at equality").isZero();
        assertThat(eventCount(gte.id())).as("GTE at equality").isEqualTo(1);
        assertThat(eventCount(lt.id())).as("LT at equality").isZero();
        assertThat(eventCount(lte.id())).as("LTE at equality").isEqualTo(1);

        ingestNow(key, tenant.metricId(), 100.0000001);
        ingestNow(key, second, 9.99);

        assertThat(eventCount(gt.id())).as("GT just above").isEqualTo(1);
        assertThat(eventCount(lt.id())).as("LT just below").isEqualTo(1);
    }

    @Test
    void aBatchFiresOncePerRuleWithTheLatestCrossingPointAndARepeatedBatchRaisesNothing() {
        TenantFixture tenant = newTenant();
        AlertRuleResponse rule = createRule(tenant.ownerToken(),
                thresholdBody(tenant.metricId(), "batch", "GT", 100, stubOk(hookPath())));
        String key = newApiKey(tenant);
        Instant base = Instant.now().minusSeconds(120).truncatedTo(ChronoUnit.MICROS);
        String batch = IngestPayloads.batch(List.of(
                Point.of(tenant.metricId(), base, 500),
                Point.of(tenant.metricId(), base.plusSeconds(10), 50),
                Point.of(tenant.metricId(), base.plusSeconds(20), 300),
                Point.of(tenant.metricId(), base.plusSeconds(30), 70)));

        ingest(key, batch).expectStatus().isAccepted();

        assertThat(eventCount(rule.id())).isEqualTo(1);
        Map<String, Object> event = events(rule.id()).getFirst();
        assertThat(event.get("source_ref")).isEqualTo(tenant.metricId() + "@" + base.plusSeconds(20));
        assertThat(event.get("payload").toString()).contains("\"value\": 300.0");

        // The agent sends the same batch again, after the cooldown: the point is the idempotency key.
        clearCooldown(rule.id());
        ingest(key, batch).expectStatus().isAccepted();
        assertThat(eventCount(rule.id())).isEqualTo(1);
        assertThat(redis.hasKey("alert:cooldown:" + rule.id())).as("cooldown released after a duplicate").isFalse();
    }

    @Test
    void backFilledPointsDoNotRaiseAlerts() {
        TenantFixture tenant = newTenant();
        AlertRuleResponse rule = createRule(tenant.ownerToken(),
                thresholdBody(tenant.metricId(), "history", "GT", 0, stubOk(hookPath())));

        ingest(newApiKey(tenant), IngestPayloads.single(tenant.metricId(), Instant.now().minus(90,
                ChronoUnit.MINUTES), 999)).expectStatus().isAccepted();

        assertThat(eventCount(rule.id())).isZero();
    }

    @Test
    void anomalySeverityRulesFireFromTheMinimumSeverityUpAndOncePerAnomaly() {
        TenantFixture tenant = newTenant();
        UUID otherMetric = newMetric(tenant, "billing", "queue_depth");
        AlertRuleResponse rule = createRule(tenant.ownerToken(),
                severityBody(tenant.metricId(), "medium and up", "MEDIUM", stubOk(hookPath())));
        Instant start = Instant.parse("2026-03-01T00:00:00Z");

        StoredAnomaly low = insertAnomaly(tenant, start, "LOW");
        assertThat(notifySigned(notificationBody(low)).statusCode()).isEqualTo(202);
        assertThat(eventCount(rule.id())).as("LOW is below MEDIUM").isZero();

        StoredAnomaly medium = insertAnomaly(tenant, start.plusSeconds(600), "MEDIUM");
        assertThat(notifySigned(notificationBody(medium)).statusCode()).isEqualTo(202);
        assertThat(eventCount(rule.id())).as("MEDIUM is the boundary").isEqualTo(1);
        Map<String, Object> event = events(rule.id()).getFirst();
        assertThat(event.get("source_ref")).isEqualTo(medium.id().toString());
        assertThat(event.get("payload").toString()).contains("\"anomalyId\": \"" + medium.id() + "\"")
                .contains("\"severity\": \"MEDIUM\"");

        // The analytics service replays the notification once the cooldown is over: no second event.
        clearCooldown(rule.id());
        assertThat(notifySigned(notificationBody(medium)).statusCode()).isEqualTo(202);
        assertThat(eventCount(rule.id())).isEqualTo(1);
        assertThat(redis.hasKey("alert:cooldown:" + rule.id())).as("cooldown released after a replay").isFalse();

        StoredAnomaly high = insertAnomaly(tenant, start.plusSeconds(1200), "HIGH");
        assertThat(notifySigned(notificationBody(high)).statusCode()).isEqualTo(202);
        assertThat(eventCount(rule.id())).as("HIGH after the cooldown").isEqualTo(2);

        clearCooldown(rule.id());
        StoredAnomaly elsewhere = insertAnomaly(tenant.tenantId(), otherMetric, start.plusSeconds(1800), "HIGH");
        assertThat(notifySigned(notificationBody(elsewhere)).statusCode()).isEqualTo(202);
        assertThat(eventCount(rule.id())).as("anomaly of another metric").isEqualTo(2);
    }

    @Test
    void theNotificationClaimingAnotherTenantRaisesNothing() {
        TenantFixture tenant = newTenant();
        TenantFixture stranger = newTenant();
        AlertRuleResponse rule = createRule(stranger.ownerToken(),
                severityBody(stranger.metricId(), "strangers", "LOW", stubOk(hookPath())));
        StoredAnomaly mine = insertAnomaly(tenant, Instant.parse("2026-03-02T00:00:00Z"), "HIGH");
        // Claims the stranger's tenant for an anomaly that belongs to this tenant.
        String forged = notificationBody(mine).replace(tenant.tenantId().toString(), stranger.tenantId().toString());

        assertThat(notifySigned(forged).statusCode()).isEqualTo(202);

        assertThat(eventCount(rule.id())).isZero();
    }

    @Test
    void anEmailLogRuleFiresAndIsMarkedSentWithoutANetworkCall() {
        TenantFixture tenant = newTenant();
        Map<String, Object> body = thresholdBody(tenant.metricId(), "mail", "GT", 1, "ops@example.com");
        body.put("channel", "EMAIL_LOG");
        AlertRuleResponse rule = createRule(tenant.ownerToken(), body);

        ingestNow(newApiKey(tenant), tenant.metricId(), 5);
        UUID eventId = onlyEvent(rule.id());
        dispatchUntil(() -> "SENT".equals(status(eventId)));

        assertThat(events(rule.id()).getFirst().get("attempts")).isEqualTo(1);
    }
}

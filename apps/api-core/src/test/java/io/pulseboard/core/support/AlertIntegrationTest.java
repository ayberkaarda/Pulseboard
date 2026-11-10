package io.pulseboard.core.support;

import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.pulseboard.core.api.alert.AlertDtos.AlertRuleResponse;
import io.pulseboard.core.application.alert.AlertDispatchService;
import io.pulseboard.core.infrastructure.alert.AlertProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Base class of the alerting tests: rules created through the API, batches ingested with an API key, events read
 * from the database, and the dispatcher driven by hand (the poller is off in the test profile). Webhooks go to the
 * shared {@link WebhookReceiver}; every test uses its own path.
 */
public abstract class AlertIntegrationTest extends AnomalyIntegrationTest {

    @Autowired
    protected AlertDispatchService dispatchService;

    @Autowired
    protected AlertProperties alertProperties;

    @DynamicPropertySource
    static void webhookPort(DynamicPropertyRegistry registry) {
        registry.add("pulseboard.alert.webhook.allowed-ports", () -> Integer.toString(WebhookReceiver.port()));
    }

    /**
     * Delivers whatever earlier tests left due, so a test only sees its own events. Retries that are scheduled for
     * later are not due and stay where they are.
     */
    @BeforeEach
    void drainDueEvents() {
        for (int round = 0; round < 50 && dispatchService.dispatchDue() > 0; round++) {
            continue;
        }
    }

    protected static String hookPath() {
        return "/hook/" + UUID.randomUUID();
    }

    protected Map<String, Object> thresholdBody(UUID metricId, String name, String operator, double threshold,
            String target) {
        Map<String, Object> body = new HashMap<>();
        body.put("metricId", metricId);
        body.put("name", name);
        body.put("condition", "THRESHOLD");
        body.put("operator", operator);
        body.put("threshold", threshold);
        body.put("cooldownSeconds", 60);
        body.put("channel", "WEBHOOK");
        body.put("target", target);
        return body;
    }

    protected Map<String, Object> severityBody(UUID metricId, String name, String minSeverity, String target) {
        Map<String, Object> body = new HashMap<>();
        body.put("metricId", metricId);
        body.put("name", name);
        body.put("condition", "ANOMALY_SEVERITY");
        body.put("minSeverity", minSeverity);
        body.put("cooldownSeconds", 60);
        body.put("channel", "WEBHOOK");
        body.put("target", target);
        return body;
    }

    protected RestTestClient.ResponseSpec postRule(String token, Map<String, Object> body) {
        return client.post().uri("/api/v1/alert-rules")
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange();
    }

    protected AlertRuleResponse createRule(String token, Map<String, Object> body) {
        return postRule(token, body)
                .expectStatus().isCreated()
                .expectBody(AlertRuleResponse.class)
                .returnResult()
                .getResponseBody();
    }

    protected String newApiKey(TenantFixture tenant) {
        return issueApiKey(tenant.ownerToken(), "ingest-" + UUID.randomUUID()).key();
    }

    /** Ingests one point stamped now; returns once the API answered 202. */
    protected Instant ingestNow(String apiKey, UUID metricId, double value) {
        Instant ts = Instant.now();
        ingest(apiKey, IngestPayloads.single(metricId, ts, value)).expectStatus().isAccepted();
        return ts;
    }

    protected void clearCooldown(UUID ruleId) {
        redis.delete("alert:cooldown:" + ruleId);
    }

    protected List<Map<String, Object>> events(UUID ruleId) {
        return jdbc.queryForList("SELECT id, delivery_status, attempts, last_error, source_ref, payload::text AS "
                + "payload, delivered_at FROM alert_event WHERE rule_id = ? ORDER BY triggered_at, id", ruleId);
    }

    protected int eventCount(UUID ruleId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM alert_event WHERE rule_id = ?", Integer.class,
                ruleId);
        return count == null ? 0 : count;
    }

    protected String status(UUID eventId) {
        return jdbc.queryForObject("SELECT delivery_status FROM alert_event WHERE id = ?", String.class, eventId);
    }

    protected UUID onlyEvent(UUID ruleId) {
        List<Map<String, Object>> found = events(ruleId);
        if (found.size() != 1) {
            throw new AssertionError("expected one event for rule " + ruleId + " but found " + found.size());
        }
        return (UUID) found.getFirst().get("id");
    }

    /** Polls the dispatcher until the condition holds, as the scheduler would do on its own. */
    protected void dispatchUntil(BooleanSupplier condition) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100)).until(() -> {
            dispatchService.dispatchDue();
            return condition.getAsBoolean();
        });
    }

    /** Records a PENDING event that is due now, as a firing rule would have done. */
    protected UUID insertEvent(TenantFixture tenant, UUID ruleId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO alert_event (id, tenant_id, rule_id, triggered_at, payload, next_attempt_at, source_ref)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)
                """, id, tenant.tenantId(), ruleId, java.sql.Timestamp.from(Instant.now()),
                "{\"eventId\": \"" + id + "\", \"note\": \"direct\"}",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), "direct-" + id);
        return id;
    }

    protected void makeDueNow(UUID eventId) {
        jdbc.update("UPDATE alert_event SET next_attempt_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), eventId);
    }

    protected static List<LoggedRequest> requests(String path) {
        return WebhookReceiver.server().findAll(
                com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(
                        com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo(path)));
    }

    protected String signingSecret() {
        return alertProperties.webhook().signingSecret();
    }
}

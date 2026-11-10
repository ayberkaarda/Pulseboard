package io.pulseboard.core.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import io.pulseboard.core.api.alert.AlertDtos.AlertRuleResponse;
import io.pulseboard.core.application.alert.AlertCooldown;
import io.pulseboard.core.application.alert.AlertTriggerService;
import io.pulseboard.core.application.common.DependencyUnavailableException;
import io.pulseboard.core.application.ratelimit.RateLimiter;
import io.pulseboard.core.application.ratelimit.RateLimiterUnavailableException;
import io.pulseboard.core.domain.alert.AlertEventRepository;
import io.pulseboard.core.domain.alert.AlertRuleRepository;
import io.pulseboard.core.support.AlertIntegrationTest;
import io.pulseboard.core.support.IngestPayloads;
import io.pulseboard.core.support.WebhookReceiver;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** A failing rule store, cooldown store, event store or budget store never disturbs ingestion or notification. */
class AlertResilienceIntegrationTest extends AlertIntegrationTest {

    @MockitoSpyBean
    private AlertRuleRepository ruleRepository;

    @MockitoSpyBean
    private AlertCooldown cooldown;

    @MockitoSpyBean
    private AlertEventRepository eventRepository;

    @MockitoSpyBean
    private RateLimiter rateLimiter;

    @MockitoSpyBean
    private AlertTriggerService triggerService;

    @AfterEach
    void restore() {
        reset(ruleRepository, cooldown, eventRepository, rateLimiter, triggerService);
    }

    private long storedPoints(UUID metricId) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM metric_point WHERE metric_id = ?", Long.class,
                metricId);
        return count == null ? 0 : count;
    }

    private AlertRuleResponse rule(TenantFixture tenant) {
        return createRule(tenant.ownerToken(),
                thresholdBody(tenant.metricId(), "r " + UUID.randomUUID(), "GT", 1, WebhookReceiver.url(hookPath())));
    }

    @Test
    void ingestionAnswersAsUsualWhenTheRuleLookupFails() {
        TenantFixture tenant = newTenant();
        AlertRuleResponse rule = rule(tenant);
        doThrow(new IllegalStateException("rule store down")).when(ruleRepository)
                .findEnabledByTenantIdAndMetricIdIn(any(), any(), any());

        ingest(newApiKey(tenant), IngestPayloads.single(tenant.metricId(), Instant.now(), 50))
                .expectStatus().isAccepted()
                .expectBody().jsonPath("$.accepted").isEqualTo(1).jsonPath("$.rejected.length()").isEqualTo(0);

        assertThat(storedPoints(tenant.metricId())).isEqualTo(1);
        assertThat(eventCount(rule.id())).isZero();
    }

    @Test
    void ingestionAnswersAsUsualWhenTheTriggerServiceThrows() {
        TenantFixture tenant = newTenant();
        AlertRuleResponse rule = rule(tenant);
        doThrow(new IllegalStateException("boom")).when(triggerService).fire(any(), any(), any());

        ingest(newApiKey(tenant), IngestPayloads.single(tenant.metricId(), Instant.now(), 50))
                .expectStatus().isAccepted().expectBody().jsonPath("$.accepted").isEqualTo(1);

        assertThat(storedPoints(tenant.metricId())).isEqualTo(1);
        assertThat(eventCount(rule.id())).isZero();
    }

    @Test
    void withTheCooldownStoreDownNoEventIsCreatedButIngestionSucceedsAndRecoversLater() {
        TenantFixture tenant = newTenant();
        AlertRuleResponse rule = rule(tenant);
        String key = newApiKey(tenant);
        doThrow(new DependencyUnavailableException("redis down", new IllegalStateException()))
                .when(cooldown).tryStart(any(), any());

        ingest(key, IngestPayloads.single(tenant.metricId(), Instant.now(), 50)).expectStatus().isAccepted()
                .expectBody().jsonPath("$.accepted").isEqualTo(1);

        assertThat(eventCount(rule.id())).as("fail closed: no event without the cooldown guard").isZero();
        assertThat(storedPoints(tenant.metricId())).isEqualTo(1);

        reset(cooldown);
        ingestNow(key, tenant.metricId(), 60);
        assertThat(eventCount(rule.id())).as("fires again once the store is back").isEqualTo(1);
    }

    @Test
    void withTheBudgetStoreDownNoEventIsCreatedAndTheCooldownIsReleased() {
        TenantFixture tenant = newTenant();
        AlertRuleResponse rule = rule(tenant);
        String key = newApiKey(tenant);
        doThrow(new RateLimiterUnavailableException("redis down", new IllegalStateException()))
                .when(rateLimiter).tryAcquire(eq("alert-events"), any(), any());

        ingestNow(key, tenant.metricId(), 50);

        assertThat(eventCount(rule.id())).isZero();
        assertThat(redis.hasKey("alert:cooldown:" + rule.id())).as("released, so a retry can fire").isFalse();

        reset(rateLimiter);
        ingestNow(key, tenant.metricId(), 60);
        assertThat(eventCount(rule.id())).isEqualTo(1);
    }

    @Test
    void whenTheEventCannotBeStoredTheCooldownIsReleasedSoARetryCanFire() {
        TenantFixture tenant = newTenant();
        AlertRuleResponse rule = rule(tenant);
        String key = newApiKey(tenant);
        doThrow(new IllegalStateException("database down")).when(eventRepository).insertIfAbsent(any(), any());

        ingest(key, IngestPayloads.single(tenant.metricId(), Instant.now(), 50)).expectStatus().isAccepted();

        assertThat(eventCount(rule.id())).isZero();
        assertThat(redis.hasKey("alert:cooldown:" + rule.id())).as("cooldown must not outlive a lost event")
                .isFalse();

        reset(eventRepository);
        ingestNow(key, tenant.metricId(), 60);
        assertThat(eventCount(rule.id())).isEqualTo(1);
    }

    @Test
    void theNotificationIsAnsweredNormallyWhenAnomalyRulesCannotBeEvaluated() {
        TenantFixture tenant = newTenant();
        AlertRuleResponse rule = createRule(tenant.ownerToken(),
                severityBody(tenant.metricId(), "sev", "LOW", WebhookReceiver.url(hookPath())));
        StoredAnomaly anomaly = insertAnomaly(tenant, Instant.parse("2026-04-01T00:00:00Z"), "HIGH");
        doThrow(new IllegalStateException("rule store down")).when(ruleRepository)
                .findEnabledByTenantIdAndMetricIdIn(any(), any(), any());

        var response = notifySigned(notificationBody(anomaly));

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(response.body()).contains("\"accepted\":1");
        assertThat(eventCount(rule.id())).isZero();

        reset(ruleRepository);
        assertThat(notifySigned(notificationBody(anomaly)).statusCode()).isEqualTo(202);
        assertThat(eventCount(rule.id())).as("a replay fires it").isEqualTo(1);
    }
}

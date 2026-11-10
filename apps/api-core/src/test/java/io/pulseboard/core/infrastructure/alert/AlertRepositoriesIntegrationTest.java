package io.pulseboard.core.infrastructure.alert;

import static org.assertj.core.api.Assertions.assertThat;

import io.pulseboard.core.api.alert.AlertDtos.AlertRuleResponse;
import io.pulseboard.core.domain.alert.AlertChannel;
import io.pulseboard.core.domain.alert.AlertCondition;
import io.pulseboard.core.domain.alert.AlertEventQuery;
import io.pulseboard.core.domain.alert.AlertEventRepository;
import io.pulseboard.core.domain.alert.AlertRuleDraft;
import io.pulseboard.core.domain.alert.AlertRuleRepository;
import io.pulseboard.core.domain.alert.ComparisonOperator;
import io.pulseboard.core.domain.alert.NewAlertEvent;
import io.pulseboard.core.domain.tenancy.TenantId;
import io.pulseboard.core.support.AlertIntegrationTest;
import io.pulseboard.core.support.WebhookReceiver;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The persistence ports themselves: idempotent inserts and tenant scoping of every statement. */
class AlertRepositoriesIntegrationTest extends AlertIntegrationTest {

    @Autowired
    private AlertEventRepository events;

    @Autowired
    private AlertRuleRepository rules;

    private AlertRuleResponse rule(TenantFixture tenant) {
        return createRule(tenant.ownerToken(), thresholdBody(tenant.metricId(), "r " + UUID.randomUUID(), "GT", 1,
                WebhookReceiver.url(hookPath())));
    }

    private static NewAlertEvent event(UUID ruleId, String source) {
        return new NewAlertEvent(UUID.randomUUID(), ruleId, Instant.now(), Map.of("k", "v"), source);
    }

    @Test
    void theSameTriggerIsRecordedOnceWithoutAnError() {
        TenantFixture tenant = newTenant();
        AlertRuleResponse rule = rule(tenant);
        TenantId tenantId = TenantId.of(tenant.tenantId());

        assertThat(events.insertIfAbsent(tenantId, event(rule.id(), "point-1"))).isTrue();
        assertThat(events.insertIfAbsent(tenantId, event(rule.id(), "point-1"))).isFalse();
        assertThat(events.insertIfAbsent(tenantId, event(rule.id(), "point-2"))).isTrue();

        assertThat(eventCount(rule.id())).isEqualTo(2);
    }

    @Test
    void anEventCannotBeRecordedForARuleOfAnotherTenant() {
        TenantFixture owner = newTenant();
        TenantFixture other = newTenant();
        AlertRuleResponse rule = rule(owner);

        assertThat(events.insertIfAbsent(TenantId.of(other.tenantId()), event(rule.id(), "x"))).isFalse();

        assertThat(eventCount(rule.id())).isZero();
    }

    @Test
    void eventsAreOnlyVisibleToTheirOwnTenant() {
        TenantFixture owner = newTenant();
        TenantFixture other = newTenant();
        AlertRuleResponse rule = rule(owner);
        NewAlertEvent stored = event(rule.id(), "x");
        events.insertIfAbsent(TenantId.of(owner.tenantId()), stored);
        TenantId strangers = TenantId.of(other.tenantId());

        assertThat(events.findByIdAndTenantId(stored.id(), TenantId.of(owner.tenantId()))).isPresent();
        assertThat(events.findByIdAndTenantId(stored.id(), strangers)).isEmpty();
        assertThat(events.search(strangers, new AlertEventQuery(null, null, null, null), 0, 100).totalItems())
                .isZero();
        assertThat(events.search(strangers, new AlertEventQuery(rule.id(), null, null, null), 0, 100).items())
                .isEmpty();
        assertThat(events.search(TenantId.of(owner.tenantId()), new AlertEventQuery(null, null, null, null), 0, 100)
                .totalItems()).isEqualTo(1);
    }

    @Test
    void rulesAreOnlyVisibleAndChangeableByTheirOwnTenant() {
        TenantFixture owner = newTenant();
        TenantFixture other = newTenant();
        AlertRuleResponse rule = rule(owner);
        TenantId ownerId = TenantId.of(owner.tenantId());
        TenantId strangers = TenantId.of(other.tenantId());
        AlertRuleDraft draft = new AlertRuleDraft(owner.metricId(), "changed", AlertCondition.THRESHOLD,
                ComparisonOperator.LT, 3.0, null, 120, AlertChannel.EMAIL_LOG, "x", true);

        assertThat(rules.findByIdAndTenantId(rule.id(), strangers)).isEmpty();
        assertThat(rules.findAllByTenantId(strangers)).isEmpty();
        assertThat(rules.findEnabledByTenantIdAndMetricIdIn(strangers, List.of(owner.metricId()),
                AlertCondition.THRESHOLD)).isEmpty();
        assertThat(rules.findEnabledByTenantIdAndMetricIdIn(ownerId, List.of(owner.metricId()),
                AlertCondition.THRESHOLD)).extracting("id").contains(rule.id());
        assertThat(rules.findEnabledByTenantIdAndMetricIdIn(ownerId, List.of(owner.metricId()),
                AlertCondition.ANOMALY_SEVERITY)).isEmpty();
        assertThat(rules.update(strangers, rule.id(), 0, draft, Instant.now())).isEmpty();
        assertThat(rules.setEnabled(strangers, rule.id(), false, null, Instant.now())).isEmpty();
        assertThat(rules.deleteByIdAndTenantId(rule.id(), strangers)).isZero();

        assertThat(rules.findByIdAndTenantId(rule.id(), ownerId)).get()
                .satisfies(unchanged -> {
                    assertThat(unchanged.name()).isEqualTo(rule.name());
                    assertThat(unchanged.enabled()).isTrue();
                    assertThat(unchanged.version()).isZero();
                });
    }

    @Test
    void creatingStopsAtTheLimitAndNamesAreUnique() {
        TenantFixture tenant = newTenant();
        TenantId tenantId = TenantId.of(tenant.tenantId());
        AlertRuleDraft first = new AlertRuleDraft(tenant.metricId(), "a", AlertCondition.THRESHOLD,
                ComparisonOperator.GT, 1.0, null, 60, AlertChannel.EMAIL_LOG, "x", true);
        AlertRuleDraft second = new AlertRuleDraft(tenant.metricId(), "b", AlertCondition.THRESHOLD,
                ComparisonOperator.GT, 1.0, null, 60, AlertChannel.EMAIL_LOG, "x", true);

        assertThat(rules.create(tenantId, UUID.randomUUID(), tenant.owner().userId(), first, Instant.now(), 1))
                .isPresent();
        assertThat(rules.create(tenantId, UUID.randomUUID(), tenant.owner().userId(), second, Instant.now(), 1))
                .isEmpty();
        assertThat(rules.create(tenantId, UUID.randomUUID(), tenant.owner().userId(), second, Instant.now(), 2))
                .isPresent();
    }
}

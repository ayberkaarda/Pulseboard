package io.pulseboard.core.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.pulseboard.core.api.alert.AlertDtos.AlertRuleResponse;
import io.pulseboard.core.support.AlertIntegrationTest;
import io.pulseboard.core.support.WebhookReceiver;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.TestPropertySource;

/** The per-tenant event budget (here: 2 events per hour) drops the excess, counts it, and logs it once. */
@TestPropertySource(properties = {"pulseboard.alert.events-per-period=2", "pulseboard.alert.events-period=1h"})
@ExtendWith(OutputCaptureExtension.class)
class AlertBudgetIntegrationTest extends AlertIntegrationTest {

    @Autowired
    private MeterRegistry meters;

    private double suppressed(String reason) {
        var counter = meters.find("pulseboard.alerts.events.suppressed").tag("reason", reason).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void eventsOverTheBudgetAreDroppedCountedAndLoggedOnce(CapturedOutput output) {
        TenantFixture tenant = newTenant();
        TenantFixture other = newTenant();
        List<AlertRuleResponse> rules = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            rules.add(createRule(tenant.ownerToken(), thresholdBody(tenant.metricId(), "r" + i, "GT", 1,
                    WebhookReceiver.url(hookPath()))));
        }
        AlertRuleResponse othersRule = createRule(other.ownerToken(), thresholdBody(other.metricId(), "mine", "GT", 1,
                WebhookReceiver.url(hookPath())));
        double droppedBefore = suppressed("budget");

        ingestNow(newApiKey(tenant), tenant.metricId(), 50);

        long created = rules.stream().filter(rule -> eventCount(rule.id()) == 1).count();
        assertThat(created).as("two events fit the budget").isEqualTo(2);
        assertThat(suppressed("budget") - droppedBefore).isEqualTo(2);
        for (AlertRuleResponse rule : rules) {
            assertThat(redis.hasKey("alert:cooldown:" + rule.id())).as("cooldown of a rule without an event")
                    .isEqualTo(eventCount(rule.id()) == 1);
        }
        ingestNow(newApiKey(tenant), tenant.metricId(), 60);
        assertThat(rules.stream().mapToInt(rule -> eventCount(rule.id())).sum()).isEqualTo(2);
        assertThat(output.getAll().split("Alert event budget exhausted", -1).length - 1)
                .as("logged once per period and tenant, even with two rounds of drops").isEqualTo(1);
        assertThat(output.getAll()).contains("tenant=" + tenant.tenantId());

        // Another tenant has its own budget.
        ingestNow(newApiKey(other), other.metricId(), 50);
        assertThat(eventCount(othersRule.id())).isEqualTo(1);
    }

    @Test
    void aDuplicateTriggerDoesNotUseUpTheBudget() {
        TenantFixture tenant = newTenant();
        AlertRuleResponse rule = createRule(tenant.ownerToken(), thresholdBody(tenant.metricId(), "dup", "GT", 1,
                WebhookReceiver.url(hookPath())));
        String key = newApiKey(tenant);
        java.time.Instant first = java.time.Instant.now().minusSeconds(30);
        String batch = io.pulseboard.core.support.IngestPayloads.single(tenant.metricId(), first, 50);

        ingest(key, batch).expectStatus().isAccepted();
        assertThat(eventCount(rule.id())).isEqualTo(1);
        clearCooldown(rule.id());
        // Replays of the same point: each takes a token and gives it back.
        for (int i = 0; i < 3; i++) {
            ingest(key, batch).expectStatus().isAccepted();
            clearCooldown(rule.id());
        }
        ingestNow(key, tenant.metricId(), 70);
        assertThat(eventCount(rule.id())).as("second distinct trigger still fits the budget of two").isEqualTo(2);

        clearCooldown(rule.id());
        ingestNow(key, tenant.metricId(), 80);
        assertThat(eventCount(rule.id())).as("the third is over the budget").isEqualTo(2);
    }
}

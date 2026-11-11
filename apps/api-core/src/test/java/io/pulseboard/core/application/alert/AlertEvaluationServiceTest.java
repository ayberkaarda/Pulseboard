package io.pulseboard.core.application.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.pulseboard.core.application.ratelimit.RateLimitPolicy;
import io.pulseboard.core.domain.alert.AlertChannel;
import io.pulseboard.core.domain.alert.AlertCondition;
import io.pulseboard.core.domain.alert.AlertRule;
import io.pulseboard.core.domain.alert.AlertRuleRepository;
import io.pulseboard.core.domain.anomaly.Anomaly;
import io.pulseboard.core.domain.anomaly.Detector;
import io.pulseboard.core.domain.anomaly.Severity;
import io.pulseboard.core.domain.tenancy.TenantId;
import io.pulseboard.core.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Limits of anomaly rule evaluation. */
class AlertEvaluationServiceTest {

    private static final TenantId TENANT = TenantId.of(UUID.randomUUID());
    private static final Instant NOW = Instant.parse("2026-05-05T12:00:00Z");

    private final AlertRuleRepository repository = mock(AlertRuleRepository.class);
    private final AlertTriggerService trigger = mock(AlertTriggerService.class);
    private final AlertPolicy policy = new AlertPolicy(100, 10, 1, Duration.ofSeconds(30), Duration.ofHours(1),
            Duration.ofMinutes(5), new RateLimitPolicy(100, Duration.ofMinutes(1)));
    private final AlertEvaluationService service = new AlertEvaluationService(mock(AlertRuleCache.class),
            repository, trigger, policy, new AlertMetrics(new SimpleMeterRegistry()), new MutableClock(NOW));

    private static AlertRule rule(UUID metricId, String name) {
        return new AlertRule(UUID.randomUUID(), TENANT, metricId, "metric", "ms", UUID.randomUUID(), "service",
                name, AlertCondition.ANOMALY_SEVERITY, null, null, Severity.LOW, 60, AlertChannel.WEBHOOK,
                "https://hooks.example.com/x", true, null, NOW, NOW, 0, null);
    }

    private static Anomaly anomaly(UUID metricId, Severity severity) {
        return new Anomaly(UUID.randomUUID(), TENANT, metricId, UUID.randomUUID(), "metric", "service",
                NOW.minusSeconds(60), NOW, 3.0, Detector.ZSCORE, severity, Map.of(), NOW, null, null);
    }

    @Test
    void matchesSuppressedByCooldownDoNotUseUpTheAllowanceOfLaterValidAlerts() {
        // Allowance: maxRulesPerBatch (1) * 5 = 5 firings per tenant and notification.
        UUID noisyMetric = UUID.randomUUID();
        UUID quietMetric = UUID.randomUUID();
        List<AlertRule> noisyRules = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            noisyRules.add(rule(noisyMetric, "noisy " + i));
        }
        AlertRule valid = rule(quietMetric, "valid");
        List<AlertRule> all = new ArrayList<>(noisyRules);
        all.add(valid);
        when(repository.findEnabledByTenantIdAndMetricIdIn(eq(TENANT), anyCollection(), any())).thenReturn(all);
        when(trigger.fire(any(), any(), any())).thenAnswer(call -> call.<AlertRule>getArgument(0) == valid);

        // The severe anomaly is visited first and matches eight rules that are all in cooldown (more than the
        // allowance); the milder one on another metric must still get its alert.
        service.onAnomalies(List.of(anomaly(quietMetric, Severity.MEDIUM), anomaly(noisyMetric, Severity.HIGH)));

        ArgumentCaptor<AlertRule> fired = ArgumentCaptor.forClass(AlertRule.class);
        verify(trigger, org.mockito.Mockito.atLeastOnce()).fire(fired.capture(), any(), any());
        assertThat(fired.getAllValues()).contains(valid);
    }

    @Test
    void theNumberOfMatchesLookedAtStaysBoundedEvenWhenNothingFires() {
        UUID metric = UUID.randomUUID();
        List<AlertRule> rules = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            rules.add(rule(metric, "r" + i));
        }
        when(repository.findEnabledByTenantIdAndMetricIdIn(eq(TENANT), anyCollection(), any())).thenReturn(rules);
        when(trigger.fire(any(), any(), any())).thenReturn(false);
        List<Anomaly> anomalies = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            anomalies.add(anomaly(metric, Severity.HIGH));
        }

        service.onAnomalies(anomalies);

        // 1000 matches exist; the hard cap is 5 firings * 10 matches each.
        verify(trigger, org.mockito.Mockito.times(50)).fire(any(), any(), any());
    }
}

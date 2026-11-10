package io.pulseboard.core.application.alert;

import io.pulseboard.core.domain.alert.AlertCondition;
import io.pulseboard.core.domain.alert.AlertRule;
import io.pulseboard.core.domain.alert.AlertRuleRepository;
import io.pulseboard.core.domain.anomaly.Anomaly;
import io.pulseboard.core.domain.metrics.MetricSample;
import io.pulseboard.core.domain.tenancy.TenantId;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Decides which rules fire.
 *
 * <p><b>THRESHOLD rules</b> are evaluated by ingestion for the points that were actually stored (not for rejected,
 * superseded or foreign ones), after the batch is committed. For every distinct metric of the batch that has enabled
 * rules, a rule fires at most once per batch, when at least one stored point satisfies its comparison; the
 * <em>latest</em> such point (by timestamp) is the reported value and its timestamp is the idempotency key, so a batch
 * that is sent again raises nothing. Points outside {@code [now - maxPointAge, now + maxPointFuture]} are ignored:
 * back-filled history does not raise alerts. The work per batch is bounded: at most {@code maxRulesPerMetric} rules per
 * metric and {@code maxRulesPerBatch} rules in total are looked at, the rules come from a short-lived cache, and a
 * batch holds at most 1000 points.
 *
 * <p><b>ANOMALY_SEVERITY rules</b> are evaluated for anomalies the analytics service reported: a rule on the anomaly's
 * metric fires when the severity is at least {@code minSeverity}; the anomaly id is the idempotency key.
 *
 * <p>Neither entry point ever throws: an evaluation problem is logged and counted and must not disturb ingestion or
 * the internal notification.
 */
@Service
public class AlertEvaluationService {

    private static final Logger LOG = LoggerFactory.getLogger(AlertEvaluationService.class);

    private final AlertRuleCache ruleCache;
    private final AlertRuleRepository ruleRepository;
    private final AlertTriggerService triggerService;
    private final AlertPolicy policy;
    private final AlertMetrics metrics;
    private final Clock clock;

    public AlertEvaluationService(AlertRuleCache ruleCache, AlertRuleRepository ruleRepository,
            AlertTriggerService triggerService, AlertPolicy policy, AlertMetrics metrics, Clock clock) {
        this.ruleCache = ruleCache;
        this.ruleRepository = ruleRepository;
        this.triggerService = triggerService;
        this.policy = policy;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** Evaluates THRESHOLD rules for the samples of a committed batch. */
    public void onSamplesStored(TenantId tenantId, Collection<MetricSample> stored) {
        try {
            evaluateSamples(tenantId, stored);
        } catch (RuntimeException ex) {
            metrics.evaluationError();
            LOG.warn("Threshold evaluation failed; ingestion is not affected: tenant={}", tenantId, ex);
        }
    }

    /** Evaluates ANOMALY_SEVERITY rules for anomalies that were stored (and read back from the database). */
    public void onAnomalies(Collection<Anomaly> anomalies) {
        try {
            evaluateAnomalies(anomalies);
        } catch (RuntimeException ex) {
            metrics.evaluationError();
            LOG.warn("Anomaly rule evaluation failed; the notification is not affected", ex);
        }
    }

    private void evaluateSamples(TenantId tenantId, Collection<MetricSample> stored) {
        Instant now = clock.instant();
        Instant earliest = now.minus(policy.maxPointAge());
        Instant latest = now.plus(policy.maxPointFuture());
        Map<UUID, List<MetricSample>> byMetric = new LinkedHashMap<>();
        for (MetricSample sample : stored) {
            if (!sample.timestamp().isBefore(earliest) && !sample.timestamp().isAfter(latest)) {
                byMetric.computeIfAbsent(sample.metricId(), id -> new ArrayList<>()).add(sample);
            }
        }
        if (byMetric.isEmpty()) {
            return;
        }
        Map<UUID, List<AlertRule>> rulesByMetric = ruleCache.thresholdRules(tenantId, byMetric.keySet());
        int budget = policy.maxRulesPerBatch();
        for (Map.Entry<UUID, List<MetricSample>> entry : byMetric.entrySet()) {
            List<AlertRule> rules = rulesByMetric.getOrDefault(entry.getKey(), List.of());
            for (int i = 0; i < Math.min(rules.size(), policy.maxRulesPerMetric()) && budget > 0; i++, budget--) {
                evaluateThreshold(rules.get(i), entry.getValue());
            }
        }
    }

    private void evaluateThreshold(AlertRule rule, List<MetricSample> samples) {
        if (rule.operator() == null || rule.threshold() == null) {
            return;
        }
        Optional<MetricSample> crossing = samples.stream()
                .filter(sample -> rule.operator().test(sample.value(), rule.threshold()))
                .max(Comparator.comparing(MetricSample::timestamp));
        if (crossing.isEmpty()) {
            return;
        }
        MetricSample point = crossing.get();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("value", point.value());
        details.put("threshold", rule.threshold());
        details.put("operator", rule.operator().name());
        details.put("observedAt", point.timestamp().toString());
        guarded(rule, point.timestamp().toString(), details);
    }

    private void evaluateAnomalies(Collection<Anomaly> anomalies) {
        Map<TenantId, Set<UUID>> metricsByTenant = new LinkedHashMap<>();
        for (Anomaly anomaly : anomalies) {
            metricsByTenant.computeIfAbsent(anomaly.tenantId(), id -> new LinkedHashSet<>()).add(anomaly.metricId());
        }
        Map<TenantId, Map<UUID, List<AlertRule>>> rules = new HashMap<>();
        metricsByTenant.forEach((tenantId, metricIds) -> {
            Map<UUID, List<AlertRule>> byMetric = new HashMap<>();
            for (AlertRule rule : ruleRepository.findEnabledByTenantIdAndMetricIdIn(tenantId, metricIds,
                    AlertCondition.ANOMALY_SEVERITY)) {
                byMetric.computeIfAbsent(rule.metricId(), id -> new ArrayList<>()).add(rule);
            }
            rules.put(tenantId, byMetric);
        });
        int budget = policy.maxRulesPerBatch() * 5;
        for (Anomaly anomaly : anomalies) {
            List<AlertRule> candidates = rules.getOrDefault(anomaly.tenantId(), Map.of())
                    .getOrDefault(anomaly.metricId(), List.of());
            for (int i = 0; i < Math.min(candidates.size(), policy.maxRulesPerMetric()) && budget > 0;
                    i++, budget--) {
                AlertRule rule = candidates.get(i);
                if (rule.minSeverity() != null && anomaly.severity().compareTo(rule.minSeverity()) >= 0) {
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("severity", anomaly.severity().name());
                    details.put("minSeverity", rule.minSeverity().name());
                    details.put("score", anomaly.score());
                    details.put("detector", anomaly.detector().name());
                    details.put("anomalyId", anomaly.id().toString());
                    details.put("windowStart", anomaly.windowStart().toString());
                    details.put("windowEnd", anomaly.windowEnd().toString());
                    guarded(rule, anomaly.id().toString(), details);
                }
            }
        }
    }

    /** One rule failing (for example a database error on its insert) does not stop the others. */
    private void guarded(AlertRule rule, String sourceRef, Map<String, Object> details) {
        try {
            triggerService.fire(rule, sourceRef, details);
        } catch (RuntimeException ex) {
            metrics.evaluationError();
            LOG.warn("Firing an alert rule failed: rule={} tenant={}", rule.id(), rule.tenantId(), ex);
        }
    }
}

package io.pulseboard.core.application.alert;

import io.pulseboard.core.application.common.DependencyUnavailableException;
import io.pulseboard.core.application.ratelimit.RateLimiter;
import io.pulseboard.core.application.ratelimit.RateLimiterUnavailableException;
import io.pulseboard.core.domain.alert.AlertEventRepository;
import io.pulseboard.core.domain.alert.AlertRule;
import io.pulseboard.core.domain.alert.NewAlertEvent;
import io.pulseboard.core.domain.tenancy.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Turns a matched rule into a PENDING alert event, once.
 *
 * <p>Order of the guards, cheapest decision first and every refusal undone:
 * <ol>
 *   <li>the rule's cooldown key is set atomically ({@code SET NX PX}); if it was already set the firing is
 *   suppressed;</li>
 *   <li>the tenant's event budget (token bucket, namespace {@code alert-events}) is charged; over budget the event is
 *   dropped, counted, and logged once per budget period and tenant;</li>
 *   <li>the event is inserted with {@code ON CONFLICT (rule_id, source_ref) DO NOTHING}, so replays of the same
 *   trigger create nothing.</li>
 * </ol>
 * When no event results (duplicate, budget, error) the cooldown key is released, so a retry of the same trigger can
 * still fire. Redis unavailable means the guards cannot be applied: nothing is created (fail closed for alerts) and the
 * caller is not affected.
 */
@Service
public class AlertTriggerService {

    /** Rate-limit bucket namespace; the subject is the tenant id. */
    public static final String BUDGET_NAMESPACE = "alert-events";

    private static final Logger LOG = LoggerFactory.getLogger(AlertTriggerService.class);
    private static final int MAX_TRACKED_TENANTS = 10_000;

    private final AlertEventRepository eventRepository;
    private final AlertCooldown cooldown;
    private final RateLimiter rateLimiter;
    private final AlertPolicy policy;
    private final AlertMetrics metrics;
    private final Clock clock;
    private final Map<TenantId, Instant> budgetLogged = new ConcurrentHashMap<>();

    public AlertTriggerService(AlertEventRepository eventRepository, AlertCooldown cooldown, RateLimiter rateLimiter,
            AlertPolicy policy, AlertMetrics metrics, Clock clock) {
        this.eventRepository = eventRepository;
        this.cooldown = cooldown;
        this.rateLimiter = rateLimiter;
        this.policy = policy;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * Fires the rule for one trigger.
     *
     * @param sourceRef identifies the trigger (point timestamp or anomaly id); the same value never fires a rule twice
     * @param details trigger specific members of the payload (values, severity, ...); must not contain secrets
     * @return whether a new event was recorded
     */
    public boolean fire(AlertRule rule, String sourceRef, Map<String, Object> details) {
        TenantId tenantId = rule.tenantId();
        try {
            if (!cooldown.tryStart(rule.id(), Duration.ofSeconds(rule.cooldownSeconds()))) {
                metrics.suppressed("cooldown");
                return false;
            }
        } catch (DependencyUnavailableException ex) {
            metrics.suppressed("store-unavailable");
            LOG.warn("Alert suppressed because the cooldown store is unavailable: rule={} tenant={}", rule.id(),
                    tenantId);
            return false;
        }
        boolean created = false;
        boolean charged = false;
        try {
            if (!withinBudget(tenantId)) {
                metrics.suppressed("budget");
                return false;
            }
            charged = true;
            Instant now = clock.instant();
            UUID eventId = UUID.randomUUID();
            created = eventRepository.insertIfAbsent(tenantId,
                    new NewAlertEvent(eventId, rule.id(), now, payload(rule, eventId, now, details), sourceRef));
            if (created) {
                metrics.created();
            } else {
                metrics.suppressed("duplicate");
            }
            return created;
        } catch (RateLimiterUnavailableException ex) {
            metrics.suppressed("store-unavailable");
            LOG.warn("Alert suppressed because the event budget store is unavailable: rule={} tenant={}",
                    rule.id(), tenantId);
            return false;
        } finally {
            if (!created) {
                if (charged) {
                    refundBudget(tenantId);
                }
                cooldown.release(rule.id());
            }
        }
    }

    private boolean withinBudget(TenantId tenantId) {
        RateLimiter.Decision decision = rateLimiter.tryAcquire(BUDGET_NAMESPACE, tenantId.toString(),
                policy.eventBudget());
        if (decision.allowed()) {
            return true;
        }
        Instant now = clock.instant();
        Instant last = budgetLogged.get(tenantId);
        if (last == null || last.plus(policy.eventBudget().refillPeriod()).isBefore(now)) {
            if (budgetLogged.size() > MAX_TRACKED_TENANTS) {
                budgetLogged.clear();
            }
            budgetLogged.put(tenantId, now);
            LOG.warn("Alert event budget exhausted, further events of this tenant are dropped until it refills: "
                    + "tenant={} budget={} per {}", tenantId, policy.eventBudget().capacity(),
                    policy.eventBudget().refillPeriod());
        }
        return false;
    }

    private void refundBudget(TenantId tenantId) {
        try {
            rateLimiter.refund(BUDGET_NAMESPACE, tenantId.toString(), policy.eventBudget());
        } catch (RateLimiterUnavailableException ex) {
            LOG.debug("Event budget token could not be refunded", ex);
        }
    }

    private static Map<String, Object> payload(AlertRule rule, UUID eventId, Instant triggeredAt,
            Map<String, Object> details) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", eventId.toString());
        payload.put("ruleId", rule.id().toString());
        payload.put("ruleName", rule.name());
        payload.put("tenantId", rule.tenantId().toString());
        Map<String, Object> metric = new LinkedHashMap<>();
        metric.put("id", rule.metricId().toString());
        metric.put("name", rule.metricName());
        metric.put("unit", rule.metricUnit());
        payload.put("metric", metric);
        payload.put("serviceName", rule.serviceName());
        payload.put("condition", rule.condition().name());
        payload.put("triggeredAt", triggeredAt.toString());
        payload.putAll(details);
        return payload;
    }
}

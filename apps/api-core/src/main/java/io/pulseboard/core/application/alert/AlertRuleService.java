package io.pulseboard.core.application.alert;

import io.pulseboard.core.application.alert.webhook.UnsafeWebhookTargetException;
import io.pulseboard.core.application.alert.webhook.WebhookTargetGuard;
import io.pulseboard.core.application.common.InvalidRequestException;
import io.pulseboard.core.application.common.InvalidRequestException.Violation;
import io.pulseboard.core.application.common.ResourceConflictException;
import io.pulseboard.core.application.common.ResourceNotFoundException;
import io.pulseboard.core.application.common.StaleVersionException;
import io.pulseboard.core.domain.alert.AlertChannel;
import io.pulseboard.core.domain.alert.AlertCondition;
import io.pulseboard.core.domain.alert.AlertRule;
import io.pulseboard.core.domain.alert.AlertRuleDraft;
import io.pulseboard.core.domain.alert.AlertRuleRepository;
import io.pulseboard.core.domain.catalog.MetricDefinitionRepository;
import io.pulseboard.core.domain.tenancy.TenantId;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Use cases for managing the alert rules of a tenant.
 *
 * <p>A rule can only watch a metric of the caller's tenant; a metric of another tenant is reported exactly like one
 * that does not exist. Webhook targets pass the {@link WebhookTargetGuard} (including a DNS check) before they are
 * stored, and again for every delivery.
 */
@Service
public class AlertRuleService {

    public static final int MIN_COOLDOWN_SECONDS = 60;
    public static final int MAX_COOLDOWN_SECONDS = 86_400;
    public static final int MAX_NAME_LENGTH = 100;
    public static final int MAX_LABEL_LENGTH = 254;

    private final AlertRuleRepository ruleRepository;
    private final MetricDefinitionRepository metricRepository;
    private final WebhookTargetGuard guard;
    private final AlertRuleCache cache;
    private final AlertPolicy policy;
    private final Clock clock;

    public AlertRuleService(AlertRuleRepository ruleRepository, MetricDefinitionRepository metricRepository,
            WebhookTargetGuard guard, AlertRuleCache cache, AlertPolicy policy, Clock clock) {
        this.ruleRepository = ruleRepository;
        this.metricRepository = metricRepository;
        this.guard = guard;
        this.cache = cache;
        this.policy = policy;
        this.clock = clock;
    }

    public List<AlertRule> list(TenantId tenantId) {
        return ruleRepository.findAllByTenantId(tenantId);
    }

    public AlertRule get(TenantId tenantId, UUID id) {
        return ruleRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Alert rule", id));
    }

    public AlertRule create(TenantId tenantId, UUID userId, AlertRuleCommand command) {
        AlertRuleDraft draft = validate(tenantId, command);
        AlertRule created;
        try {
            created = ruleRepository.create(tenantId, UUID.randomUUID(), userId, draft, clock.instant(),
                    policy.maxRulesPerTenant())
                    .orElseThrow(() -> new AlertRuleLimitExceededException(policy.maxRulesPerTenant()));
        } catch (DuplicateKeyException ex) {
            throw new ResourceConflictException("An alert rule with this name already exists");
        }
        cache.invalidate();
        return created;
    }

    /**
     * Replaces all editable fields.
     *
     * @param expectedVersion the version the caller read; a different stored version means someone else changed
     *     the rule meanwhile
     * @throws StaleVersionException when the stored version differs
     */
    public AlertRule update(TenantId tenantId, UUID id, long expectedVersion, AlertRuleCommand command) {
        get(tenantId, id);
        AlertRuleDraft draft = validate(tenantId, command);
        AlertRule updated;
        try {
            updated = ruleRepository.update(tenantId, id, expectedVersion, draft, clock.instant())
                    .orElseThrow(() -> new StaleVersionException(
                            "The alert rule was changed since you read it; reload it and apply your change again"));
        } catch (DuplicateKeyException ex) {
            throw new ResourceConflictException("An alert rule with this name already exists");
        }
        cache.invalidate();
        return updated;
    }

    /** Enables or disables a rule; a {@code null} version skips the concurrency check. */
    public AlertRule setEnabled(TenantId tenantId, UUID id, boolean enabled, Long expectedVersion) {
        get(tenantId, id);
        AlertRule updated = ruleRepository.setEnabled(tenantId, id, enabled, expectedVersion, clock.instant())
                .orElseThrow(() -> new StaleVersionException(
                        "The alert rule was changed since you read it; reload it and apply your change again"));
        cache.invalidate();
        return updated;
    }

    public void delete(TenantId tenantId, UUID id) {
        if (ruleRepository.deleteByIdAndTenantId(id, tenantId) == 0) {
            throw new ResourceNotFoundException("Alert rule", id);
        }
        cache.invalidate();
    }

    private AlertRuleDraft validate(TenantId tenantId, AlertRuleCommand command) {
        List<Violation> violations = new ArrayList<>();
        String name = command.name() == null ? "" : command.name().strip();
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH || hasControlCharacters(name)) {
            violations.add(new Violation("/name", "must be 1 to " + MAX_NAME_LENGTH + " characters without "
                    + "control characters"));
        }
        if (command.metricId() == null) {
            violations.add(new Violation("/metricId", "is required"));
        } else if (metricRepository.findByIdAndTenantId(command.metricId(), tenantId).isEmpty()) {
            violations.add(new Violation("/metricId", "unknown metric"));
        }
        if (command.condition() == null) {
            violations.add(new Violation("/condition", "is required"));
        } else if (command.condition() == AlertCondition.THRESHOLD) {
            if (command.operator() == null) {
                violations.add(new Violation("/operator", "is required for THRESHOLD rules"));
            }
            if (command.threshold() == null || !Double.isFinite(command.threshold())) {
                violations.add(new Violation("/threshold", "must be a finite number for THRESHOLD rules"));
            }
            if (command.minSeverity() != null) {
                violations.add(new Violation("/minSeverity", "must be empty for THRESHOLD rules"));
            }
        } else {
            if (command.minSeverity() == null) {
                violations.add(new Violation("/minSeverity", "is required for ANOMALY_SEVERITY rules"));
            }
            if (command.operator() != null) {
                violations.add(new Violation("/operator", "must be empty for ANOMALY_SEVERITY rules"));
            }
            if (command.threshold() != null) {
                violations.add(new Violation("/threshold", "must be empty for ANOMALY_SEVERITY rules"));
            }
        }
        if (command.cooldownSeconds() == null || command.cooldownSeconds() < MIN_COOLDOWN_SECONDS
                || command.cooldownSeconds() > MAX_COOLDOWN_SECONDS) {
            violations.add(new Violation("/cooldownSeconds",
                    "must be between " + MIN_COOLDOWN_SECONDS + " and " + MAX_COOLDOWN_SECONDS));
        }
        String target = command.target() == null ? "" : command.target().strip();
        if (command.channel() == null) {
            violations.add(new Violation("/channel", "is required"));
        } else if (command.channel() == AlertChannel.EMAIL_LOG) {
            if (target.isEmpty() || target.length() > MAX_LABEL_LENGTH || hasControlCharacters(target)) {
                violations.add(new Violation("/target", "must be 1 to " + MAX_LABEL_LENGTH
                        + " characters without control characters"));
            }
        } else if (!guard.deliveryConfigured()) {
            violations.add(new Violation("/channel", "the WEBHOOK channel is not available on this server"));
        } else if (violations.isEmpty()) {
            // The DNS lookup is the slow part, so it only runs for an otherwise valid request.
            try {
                guard.resolve(target);
            } catch (UnsafeWebhookTargetException ex) {
                violations.add(new Violation("/target", ex.getMessage()));
            }
        } else {
            try {
                guard.parse(target);
            } catch (UnsafeWebhookTargetException ex) {
                violations.add(new Violation("/target", ex.getMessage()));
            }
        }
        if (!violations.isEmpty()) {
            throw new InvalidRequestException("The alert rule is invalid", violations);
        }
        return new AlertRuleDraft(command.metricId(), name, command.condition(),
                command.condition() == AlertCondition.THRESHOLD ? command.operator() : null,
                command.condition() == AlertCondition.THRESHOLD ? command.threshold() : null,
                command.condition() == AlertCondition.ANOMALY_SEVERITY ? command.minSeverity() : null,
                command.cooldownSeconds(), command.channel(), target,
                command.enabled() == null || command.enabled());
    }

    private static boolean hasControlCharacters(String value) {
        return value.chars().anyMatch(Character::isISOControl);
    }
}

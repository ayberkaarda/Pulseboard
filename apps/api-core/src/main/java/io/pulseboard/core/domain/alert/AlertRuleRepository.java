package io.pulseboard.core.domain.alert;

import io.pulseboard.core.domain.tenancy.TenantId;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for {@link AlertRule}. Every method is scoped to a tenant; a rule of another tenant is
 * indistinguishable from one that does not exist.
 */
public interface AlertRuleRepository {

    /**
     * Inserts the rule unless the tenant already has {@code maxRules}; the count and the insert are serialised per
     * tenant, so concurrent creations cannot exceed the limit.
     *
     * @return the stored rule, or empty when the tenant is at its limit
     * @throws RuntimeException (a duplicate-key failure of the persistence framework) when the name is taken
     */
    Optional<AlertRule> create(TenantId tenantId, UUID id, UUID createdBy, AlertRuleDraft draft, Instant now,
            int maxRules);

    Optional<AlertRule> findByIdAndTenantId(UUID id, TenantId tenantId);

    /** All rules of the tenant, oldest first. */
    List<AlertRule> findAllByTenantId(TenantId tenantId);

    /** Enabled rules of the given metrics with the given condition; metrics of other tenants yield nothing. */
    List<AlertRule> findEnabledByTenantIdAndMetricIdIn(TenantId tenantId, Collection<UUID> metricIds,
            AlertCondition condition);

    /**
     * Replaces the editable fields when the stored version equals {@code expectedVersion}.
     *
     * @return the updated rule, or empty when the rule does not exist or the version differs
     */
    Optional<AlertRule> update(TenantId tenantId, UUID id, long expectedVersion, AlertRuleDraft draft, Instant now);

    /**
     * Sets the enabled flag. A version of {@code null} does not check the version.
     *
     * @return the updated rule, or empty when the rule does not exist or a given version differs
     */
    Optional<AlertRule> setEnabled(TenantId tenantId, UUID id, boolean enabled, Long expectedVersion, Instant now);

    /** @return number of rows removed (0 or 1) */
    int deleteByIdAndTenantId(UUID id, TenantId tenantId);
}

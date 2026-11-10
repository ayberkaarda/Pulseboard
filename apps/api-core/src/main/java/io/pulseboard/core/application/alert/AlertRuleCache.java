package io.pulseboard.core.application.alert;

import io.pulseboard.core.domain.alert.AlertCondition;
import io.pulseboard.core.domain.alert.AlertRule;
import io.pulseboard.core.domain.alert.AlertRuleRepository;
import io.pulseboard.core.domain.tenancy.TenantId;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Short-lived cache of the enabled THRESHOLD rules per metric, so an ingestion batch costs one query at most (none
 * when every metric was seen within the TTL). Metrics without rules are cached too, because that is the common
 * case. Every rule change on this instance clears the cache; other instances see a change after at most the TTL.
 */
@Component
public class AlertRuleCache {

    private static final int MAX_ENTRIES = 20_000;

    private record Key(TenantId tenantId, UUID metricId) {
    }

    private record Entry(List<AlertRule> rules, Instant expiresAt) {
    }

    private final AlertRuleRepository repository;
    private final AlertPolicy policy;
    private final Clock clock;
    private final Map<Key, Entry> entries = new ConcurrentHashMap<>();

    public AlertRuleCache(AlertRuleRepository repository, AlertPolicy policy, Clock clock) {
        this.repository = repository;
        this.policy = policy;
        this.clock = clock;
    }

    /** Enabled THRESHOLD rules per metric; metrics without rules map to an empty list. */
    public Map<UUID, List<AlertRule>> thresholdRules(TenantId tenantId, Collection<UUID> metricIds) {
        Instant now = clock.instant();
        Map<UUID, List<AlertRule>> result = new HashMap<>();
        List<UUID> missing = new ArrayList<>();
        for (UUID metricId : metricIds) {
            Entry entry = entries.get(new Key(tenantId, metricId));
            if (entry != null && entry.expiresAt().isAfter(now)) {
                result.put(metricId, entry.rules());
            } else {
                missing.add(metricId);
            }
        }
        if (!missing.isEmpty()) {
            Map<UUID, List<AlertRule>> loaded = new HashMap<>();
            for (AlertRule rule : repository.findEnabledByTenantIdAndMetricIdIn(tenantId, missing,
                    AlertCondition.THRESHOLD)) {
                loaded.computeIfAbsent(rule.metricId(), id -> new ArrayList<>()).add(rule);
            }
            if (entries.size() > MAX_ENTRIES) {
                entries.clear();
            }
            Instant expiresAt = now.plus(policy.ruleCacheTtl());
            for (UUID metricId : missing) {
                List<AlertRule> rules = List.copyOf(loaded.getOrDefault(metricId, List.of()));
                entries.put(new Key(tenantId, metricId), new Entry(rules, expiresAt));
                result.put(metricId, rules);
            }
        }
        return result;
    }

    /** Forgets everything; called after any rule change. */
    public void invalidate() {
        entries.clear();
    }
}

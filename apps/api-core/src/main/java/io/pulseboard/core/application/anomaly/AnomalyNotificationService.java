package io.pulseboard.core.application.anomaly;

import io.pulseboard.core.domain.anomaly.Anomaly;
import io.pulseboard.core.domain.anomaly.AnomalyRepository;
import io.pulseboard.core.domain.tenancy.TenantId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles the internal "anomalies were stored" notification from the analytics service.
 *
 * <p>The notification is only a hint: every id is read again from the database together with the tenant the
 * sender claims, and only what the database holds is published. Tenant, metric and severity values of the message
 * are never used. Nothing is inserted, so a replayed notification publishes the same events again and changes
 * nothing else; consumers identify events by the anomaly id.
 */
@Component
public class AnomalyNotificationService {

    private final AnomalyRepository anomalyRepository;
    private final AnomalyPublisher publisher;

    public AnomalyNotificationService(AnomalyRepository anomalyRepository, AnomalyPublisher publisher) {
        this.anomalyRepository = anomalyRepository;
        this.publisher = publisher;
    }

    @Transactional(readOnly = true)
    public NotificationResult notify(List<NotifiedAnomaly> notified) {
        Map<TenantId, Set<UUID>> idsByTenant = new LinkedHashMap<>();
        for (NotifiedAnomaly entry : notified) {
            idsByTenant.computeIfAbsent(entry.tenantId(), tenant -> new LinkedHashSet<>()).add(entry.id());
        }
        int requested = idsByTenant.values().stream().mapToInt(Set::size).sum();
        List<Anomaly> found = new ArrayList<>();
        idsByTenant.forEach((tenantId, ids) ->
                found.addAll(anomalyRepository.findAllByTenantIdAndIdIn(tenantId, ids)));
        // Oldest window first, so a stream consumer sees them in the order they happened.
        found.sort(Comparator.comparing(Anomaly::windowStart).thenComparing(Anomaly::id));
        found.forEach(publisher::publish);
        return new NotificationResult(found.size(), requested - found.size());
    }
}

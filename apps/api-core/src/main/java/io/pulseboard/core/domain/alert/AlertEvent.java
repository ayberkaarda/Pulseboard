package io.pulseboard.core.domain.alert;

import io.pulseboard.core.domain.shared.TenantScoped;
import io.pulseboard.core.domain.tenancy.TenantId;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * An alert that was raised by a rule, with its delivery state.
 *
 * @param ruleName name of the rule, joined in by the repository
 * @param payload the document that is delivered; contains no secret
 * @param attempts delivery attempts started so far
 * @param nextAttemptAt when the next attempt is due; meaningful while PENDING
 * @param lastError sanitised reason of the last failed attempt; {@code null} if there was none
 * @param deliveredAt when delivery succeeded; {@code null} unless SENT
 * @param sourceRef what caused the event: the timestamp of the metric point or the id of the anomaly
 */
@TenantScoped
public record AlertEvent(
        UUID id,
        TenantId tenantId,
        UUID ruleId,
        String ruleName,
        Instant triggeredAt,
        Map<String, Object> payload,
        DeliveryStatus deliveryStatus,
        int attempts,
        Instant nextAttemptAt,
        String lastError,
        Instant deliveredAt,
        String sourceRef) {

    public AlertEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(ruleName, "ruleName");
        Objects.requireNonNull(triggeredAt, "triggeredAt");
        Objects.requireNonNull(deliveryStatus, "deliveryStatus");
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        Objects.requireNonNull(sourceRef, "sourceRef");
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}

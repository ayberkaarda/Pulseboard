package io.pulseboard.core.domain.alert;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * An alert to be recorded as PENDING.
 *
 * @param sourceRef what caused the event; together with the rule it makes the event unique
 */
public record NewAlertEvent(UUID id, UUID ruleId, Instant triggeredAt, Map<String, Object> payload,
        String sourceRef) {

    public NewAlertEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(triggeredAt, "triggeredAt");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(sourceRef, "sourceRef");
    }
}

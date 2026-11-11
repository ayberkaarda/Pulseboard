package io.pulseboard.core.domain.alert;

import io.pulseboard.core.domain.shared.TenantScoped;
import io.pulseboard.core.domain.tenancy.TenantId;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * An alert event claimed for delivery, together with what the dispatcher needs from its rule.
 *
 * @param attempts attempts started, including the one this claim represents
 * @param claimedUntil end of the lease this claim holds; result writes must present it to prove ownership
 * @param target webhook URL or e-mail label; sensitive, never part of {@link #toString()}
 */
@TenantScoped
public record DueAlert(
        UUID eventId,
        TenantId tenantId,
        UUID ruleId,
        String ruleName,
        boolean ruleEnabled,
        AlertChannel channel,
        String target,
        Map<String, Object> payload,
        int attempts,
        Instant claimedUntil) {

    /** A claim without lease information; its results cannot be written back. */
    public DueAlert(UUID eventId, TenantId tenantId, UUID ruleId, String ruleName, boolean ruleEnabled,
            AlertChannel channel, String target, Map<String, Object> payload, int attempts) {
        this(eventId, tenantId, ruleId, ruleName, ruleEnabled, channel, target, payload, attempts, null);
    }

    public DueAlert {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    @Override
    public String toString() {
        return "DueAlert[eventId=" + eventId + ", tenantId=" + tenantId + ", channel=" + channel
                + ", attempts=" + attempts + "]";
    }
}

package io.pulseboard.core.domain.alert;

import java.time.Instant;
import java.util.UUID;

/**
 * Optional filters of an alert event listing; a {@code null} member does not filter.
 *
 * @param from inclusive lower bound of the trigger time
 * @param to exclusive upper bound of the trigger time
 */
public record AlertEventQuery(UUID ruleId, DeliveryStatus status, Instant from, Instant to) {
}

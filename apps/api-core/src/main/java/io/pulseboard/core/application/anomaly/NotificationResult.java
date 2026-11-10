package io.pulseboard.core.application.anomaly;

/**
 * Outcome of a notification.
 *
 * @param accepted distinct anomalies found in the database and published
 * @param unknown distinct entries whose id does not exist or belongs to another tenant than claimed
 */
public record NotificationResult(int accepted, int unknown) {
}

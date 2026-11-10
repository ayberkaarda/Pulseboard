package io.pulseboard.core.application.alert;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Limits of alert delivery.
 *
 * @param batchSize events claimed per poll
 * @param lease how long a claim holds an event before another dispatcher may take it over
 * @param maxAttempts delivery attempts per event, the first one included
 * @param backoff wait before attempt 2, 3, ...; the last value repeats when there are more attempts than values
 * @param hostFailureThreshold consecutive failures after which a host is left alone for {@code hostPause}
 * @param hostPause how long deliveries to a failing host are postponed
 * @param deliveryTimeout hard limit for one delivery, DNS lookup included
 */
public record AlertDispatchPolicy(
        int batchSize,
        Duration lease,
        int maxAttempts,
        List<Duration> backoff,
        int hostFailureThreshold,
        Duration hostPause,
        Duration deliveryTimeout) {

    public AlertDispatchPolicy {
        if (batchSize < 1 || maxAttempts < 1 || hostFailureThreshold < 1) {
            throw new IllegalArgumentException("dispatch limits must be positive");
        }
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(hostPause, "hostPause");
        Objects.requireNonNull(deliveryTimeout, "deliveryTimeout");
        backoff = List.copyOf(backoff);
        if (backoff.isEmpty()) {
            throw new IllegalArgumentException("backoff needs at least one value");
        }
        if (lease.compareTo(deliveryTimeout) <= 0) {
            throw new IllegalArgumentException("the lease must be longer than the delivery timeout");
        }
    }

    /** Wait after the {@code attempt}-th failed attempt (1-based). */
    public Duration backoffAfter(int attempt) {
        return backoff.get(Math.min(Math.max(attempt, 1), backoff.size()) - 1);
    }
}

package io.pulseboard.core.application.alert;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Counters of the alerting pipeline. Tags are fixed words, never ids, hosts or URLs. */
@Component
public class AlertMetrics {

    private final MeterRegistry registry;

    public AlertMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** An event was recorded. */
    public void created() {
        registry.counter("pulseboard.alerts.events.created").increment();
    }

    /** A firing did not become an event: cooldown, duplicate, budget or store (cooldown store unavailable). */
    public void suppressed(String reason) {
        registry.counter("pulseboard.alerts.events.suppressed", "reason", reason).increment();
    }

    /** A delivery attempt ended: sent, retry, failed or deferred. */
    public void delivery(String outcome) {
        registry.counter("pulseboard.alerts.deliveries", "outcome", outcome).increment();
    }

    /** An evaluation raised an unexpected error that was swallowed. */
    public void evaluationError() {
        registry.counter("pulseboard.alerts.evaluation.errors").increment();
    }
}

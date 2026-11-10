package io.pulseboard.core.application.alert;

import io.pulseboard.core.domain.alert.AlertOutbox;
import io.pulseboard.core.domain.alert.DueAlert;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Delivers due alert events (the outbox side).
 *
 * <p>One poll claims a small batch ({@link AlertOutbox#claimDue}, {@code FOR UPDATE SKIP LOCKED}, so dispatchers on
 * several instances take different events), runs the deliveries on a small fixed pool and records the outcome:
 * SENT, rescheduled with the configured backoff, FAILED once the attempts are used up or the failure is final.
 * A claim only lasts for the lease: when a dispatcher dies, or a delivery hangs, the event becomes claimable again
 * after the lease and the next claim counts as a further attempt.
 */
@Service
public class AlertDispatchService {

    private static final Logger LOG = LoggerFactory.getLogger(AlertDispatchService.class);

    private final AlertOutbox outbox;
    private final AlertDeliveryService deliveryService;
    private final AlertDispatchPolicy policy;
    private final AlertMetrics metrics;
    private final ExecutorService executor;
    private final Clock clock;

    public AlertDispatchService(AlertOutbox outbox, AlertDeliveryService deliveryService, AlertDispatchPolicy policy,
            AlertMetrics metrics, @Qualifier("alertDeliveryExecutor") ExecutorService alertDeliveryExecutor,
            Clock clock) {
        this.outbox = outbox;
        this.deliveryService = deliveryService;
        this.policy = policy;
        this.metrics = metrics;
        this.executor = alertDeliveryExecutor;
        this.clock = clock;
    }

    /**
     * Claims and delivers one batch.
     *
     * @return number of events that were claimed
     */
    public int dispatchDue() {
        Instant now = clock.instant();
        List<DueAlert> claimed = outbox.claimDue(now, now.plus(policy.lease()), policy.batchSize(),
                policy.maxAttempts());
        List<Future<?>> running = new ArrayList<>(claimed.size());
        for (DueAlert alert : claimed) {
            running.add(executor.submit(() -> process(alert)));
        }
        Duration wait = policy.deliveryTimeout().plusSeconds(5);
        for (Future<?> future : running) {
            try {
                future.get(wait.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException ex) {
                // The lease runs out and the event is claimed again.
                future.cancel(true);
                LOG.warn("A delivery did not finish in time; its claim will expire");
            } catch (ExecutionException ex) {
                LOG.warn("A delivery task failed unexpectedly", ex.getCause());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return claimed.size();
            }
        }
        return claimed.size();
    }

    private void process(DueAlert alert) {
        DeliveryOutcome outcome;
        try {
            outcome = deliveryService.deliver(alert);
        } catch (RuntimeException ex) {
            outcome = DeliveryOutcome.retry("internal error: " + ex.getClass().getSimpleName());
        }
        Instant now = clock.instant();
        switch (outcome.kind()) {
            case SENT -> {
                outbox.markSent(alert.eventId(), alert.tenantId(), now);
                metrics.delivery("sent");
            }
            case FAILED -> {
                outbox.markFailed(alert.eventId(), alert.tenantId(), outcome.error());
                metrics.delivery("failed");
            }
            case DEFER -> {
                outbox.defer(alert.eventId(), alert.tenantId(), outcome.until());
                metrics.delivery("deferred");
            }
            case RETRY -> {
                if (alert.attempts() >= policy.maxAttempts()) {
                    outbox.markFailed(alert.eventId(), alert.tenantId(), outcome.error());
                    metrics.delivery("failed");
                } else {
                    outbox.reschedule(alert.eventId(), alert.tenantId(),
                            now.plus(policy.backoffAfter(alert.attempts())), outcome.error());
                    metrics.delivery("retry");
                }
            }
            default -> throw new IllegalStateException("Unhandled outcome " + outcome.kind());
        }
        LOG.info("Alert delivery: event={} tenant={} channel={} attempt={} outcome={}", alert.eventId(),
                alert.tenantId(), alert.channel(), alert.attempts(), outcome.kind());
    }
}

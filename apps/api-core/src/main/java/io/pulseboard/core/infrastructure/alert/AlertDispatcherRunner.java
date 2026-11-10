package io.pulseboard.core.infrastructure.alert;

import io.pulseboard.core.application.alert.AlertDispatchService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Polls for due alert events with a fixed delay between polls. Runs on every instance that has
 * {@code pulseboard.alert.dispatcher.enabled} (default true); the claims of different instances never overlap.
 */
@Component
@ConditionalOnProperty(name = "pulseboard.alert.dispatcher.enabled", havingValue = "true", matchIfMissing = true)
public class AlertDispatcherRunner implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(AlertDispatcherRunner.class);
    /** After a full batch the next poll follows at once, at most this many times per tick. */
    private static final int MAX_BACK_TO_BACK_POLLS = 20;

    private final AlertDispatchService dispatchService;
    private final AlertProperties properties;
    private ScheduledExecutorService scheduler;

    public AlertDispatcherRunner(AlertDispatchService dispatchService, AlertProperties properties) {
        this.dispatchService = dispatchService;
        this.properties = properties;
    }

    @Override
    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "alert-dispatcher");
            thread.setDaemon(true);
            return thread;
        });
        long delay = properties.dispatcher().pollInterval().toMillis();
        scheduler.scheduleWithFixedDelay(this::poll, delay, delay, TimeUnit.MILLISECONDS);
    }

    private void poll() {
        try {
            for (int i = 0; i < MAX_BACK_TO_BACK_POLLS; i++) {
                if (dispatchService.dispatchDue() < properties.dispatcher().batchSize()) {
                    return;
                }
            }
        } catch (RuntimeException ex) {
            // The next tick tries again; an exception must not cancel the schedule.
            LOG.warn("Alert dispatch poll failed", ex);
        }
    }

    @Override
    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return scheduler != null;
    }
}

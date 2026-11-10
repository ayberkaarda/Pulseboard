package io.pulseboard.core.api.stream;

import io.pulseboard.core.application.anomaly.AnomalyEventSource;
import io.pulseboard.core.application.anomaly.StreamAdmission;
import io.pulseboard.core.application.anomaly.StreamAdmission.Permit;
import io.pulseboard.core.application.anomaly.StreamPolicy;
import io.pulseboard.core.domain.tenancy.TenantId;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Opens server-sent event streams of one tenant's anomalies.
 *
 * <p>Each stream owns a bounded queue and one virtual thread that does all writes to its client, so the Redis
 * delivery thread never blocks on a slow browser, events keep their order, and idle streams get their heartbeat
 * from the same loop. A stream ends when the client goes away (the next write fails), when the queue overflows (a
 * client too slow to keep up reconnects), or when its lifetime is over. Whatever the reason, closing releases the
 * connection slot and the Redis subscription exactly once.
 */
@Component
public class AnomalyStreams {

    private static final Logger LOG = LoggerFactory.getLogger(AnomalyStreams.class);
    private static final int QUEUE_CAPACITY = 256;
    private static final String EVENT_NAME = "anomaly";

    private final AnomalyEventSource eventSource;
    private final StreamAdmission admission;
    private final JsonMapper jsonMapper;

    public AnomalyStreams(AnomalyEventSource eventSource, StreamAdmission admission, JsonMapper jsonMapper) {
        this.eventSource = eventSource;
        this.admission = admission;
        this.jsonMapper = jsonMapper;
    }

    /**
     * Opens a stream for the tenant.
     *
     * @param tokenExpiry when the access token that opened the stream expires; the stream never outlives it
     * @throws io.pulseboard.core.application.anomaly.StreamLimitExceededException when a limit is reached
     */
    public SseEmitter open(TenantId tenantId, Instant tokenExpiry) {
        StreamPolicy policy = admission.policy();
        Permit permit = admission.acquire(tenantId);
        Connection connection = null;
        try {
            Duration lifetime = policy.maxLifetime();
            if (tokenExpiry != null) {
                Duration untilExpiry = Duration.between(Instant.now(), tokenExpiry);
                lifetime = untilExpiry.compareTo(lifetime) < 0 ? untilExpiry : lifetime;
            }
            SseEmitter emitter = new SseEmitter(Math.max(1, lifetime.toMillis()));
            Connection opened = new Connection(emitter, permit, policy.heartbeatInterval());
            connection = opened;
            opened.attach(eventSource.subscribe(tenantId, opened::offer));
            emitter.onCompletion(opened::close);
            emitter.onTimeout(opened::close);
            emitter.onError(error -> opened.close());
            opened.start();
            return emitter;
        } catch (RuntimeException ex) {
            if (connection != null) {
                connection.close();
            } else {
                permit.close();
            }
            throw ex;
        }
    }

    private final class Connection {

        private final SseEmitter emitter;
        private final Permit permit;
        private final long heartbeatNanos;
        private final BlockingQueue<String> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean subscriptionClosed = new AtomicBoolean();
        private volatile AnomalyEventSource.Subscription subscription;
        private volatile Thread writer;

        private Connection(SseEmitter emitter, Permit permit, Duration heartbeat) {
            this.emitter = emitter;
            this.permit = permit;
            this.heartbeatNanos = heartbeat.toNanos();
        }

        /**
         * Stores the subscription. The connection may have been closed while the subscription was being set up
         * (a burst that overflowed the queue); that close found nothing to release, so it is released here. Either
         * this method or {@link #close()} sees the other's write, and the flag makes the release happen once.
         */
        private void attach(AnomalyEventSource.Subscription newSubscription) {
            this.subscription = newSubscription;
            if (closed.get()) {
                closeSubscription();
            }
        }

        private void closeSubscription() {
            AnomalyEventSource.Subscription current = subscription;
            if (current != null && subscriptionClosed.compareAndSet(false, true)) {
                current.close();
            }
        }

        private void start() {
            if (closed.get()) {
                return;
            }
            writer = Thread.ofVirtual().name("anomaly-stream").start(this::writeLoop);
        }

        /** Called on the Redis delivery thread; never blocks. */
        private void offer(String json) {
            if (!closed.get() && !queue.offer(json)) {
                LOG.debug("Closing a stream whose client cannot keep up");
                close();
            }
        }

        private void writeLoop() {
            try {
                emitter.send(SseEmitter.event().comment("connected"));
                while (!closed.get()) {
                    String json = queue.poll(heartbeatNanos, TimeUnit.NANOSECONDS);
                    if (closed.get()) {
                        break;
                    }
                    if (json == null) {
                        emitter.send(SseEmitter.event().comment("ping"));
                    } else {
                        emitter.send(SseEmitter.event().name(EVENT_NAME).id(eventId(json)).data(json));
                    }
                }
            } catch (IOException | IllegalStateException ex) {
                LOG.debug("Anomaly stream ended: {}", ex.toString());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                close();
            }
        }

        private String eventId(String json) {
            try {
                JsonNode id = jsonMapper.readTree(json).get("id");
                return id == null ? "" : id.stringValue();
            } catch (JacksonException ex) {
                return "";
            }
        }

        private void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                closeSubscription();
            } finally {
                permit.close();
                Thread current = writer;
                if (current != null && current != Thread.currentThread()) {
                    current.interrupt();
                }
                try {
                    emitter.complete();
                } catch (RuntimeException ex) {
                    LOG.debug("Anomaly stream was already complete: {}", ex.toString());
                }
            }
        }
    }
}

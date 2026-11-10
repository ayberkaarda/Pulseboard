package io.pulseboard.core.infrastructure.stream;

import io.pulseboard.core.application.anomaly.AnomalyEventSource;
import io.pulseboard.core.application.common.DependencyUnavailableException;
import io.pulseboard.core.domain.tenancy.TenantId;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Redis backed {@link AnomalyEventSource}. All streams of a tenant share one Redis subscription to
 * {@code anomalies:{tenantId}} (exact channel, no pattern), which is added when the first stream of the tenant
 * opens and removed when the last one closes. A message is delivered only to the listeners of its own channel,
 * and is dropped when the tenant inside it differs from the channel's tenant.
 */
@Component
public class RedisAnomalyEventSource implements AnomalyEventSource {

    private static final Logger LOG = LoggerFactory.getLogger(RedisAnomalyEventSource.class);

    private final RedisMessageListenerContainer container;
    private final JsonMapper jsonMapper;
    private final Object lock = new Object();
    private final Map<TenantId, TenantChannel> channels = new HashMap<>();

    public RedisAnomalyEventSource(@Qualifier("anomalyListenerContainer") RedisMessageListenerContainer container,
            JsonMapper jsonMapper) {
        this.container = container;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public Subscription subscribe(TenantId tenantId, Consumer<String> listener) {
        synchronized (lock) {
            TenantChannel channel = channels.get(tenantId);
            boolean first = channel == null;
            if (first) {
                channel = new TenantChannel(tenantId);
            }
            channel.listeners.add(listener);
            if (first) {
                try {
                    container.addMessageListener(channel.messageListener, channel.topic);
                } catch (RuntimeException ex) {
                    channel.listeners.remove(listener);
                    try {
                        // A failed add may still have registered the listener with the container.
                        container.removeMessageListener(channel.messageListener, channel.topic);
                    } catch (RuntimeException cleanup) {
                        ex.addSuppressed(cleanup);
                    }
                    throw new DependencyUnavailableException("The message bus is unavailable; cannot subscribe", ex);
                }
                channels.put(tenantId, channel);
            }
            return new ActiveSubscription(channel, listener);
        }
    }

    /** Number of tenants with an active Redis subscription (for tests and diagnostics). */
    public int subscribedTenants() {
        synchronized (lock) {
            return channels.size();
        }
    }

    private void unsubscribe(TenantChannel channel, Consumer<String> listener) {
        synchronized (lock) {
            channel.listeners.remove(listener);
            if (channel.listeners.isEmpty() && channels.get(channel.tenantId) == channel) {
                channels.remove(channel.tenantId);
                try {
                    container.removeMessageListener(channel.messageListener, channel.topic);
                } catch (RuntimeException ex) {
                    LOG.warn("Could not remove the subscription of tenant {}", channel.tenantId, ex);
                }
            }
        }
    }

    private final class TenantChannel {

        private final TenantId tenantId;
        private final ChannelTopic topic;
        private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
        private final MessageListener messageListener = this::onMessage;

        private TenantChannel(TenantId tenantId) {
            this.tenantId = tenantId;
            this.topic = new ChannelTopic(RedisAnomalyChannels.forTenant(tenantId));
        }

        private void onMessage(Message message, byte[] pattern) {
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            if (!belongsToTenant(body)) {
                LOG.warn("Dropped a message on the channel of tenant {} that names another tenant", tenantId);
                return;
            }
            for (Consumer<String> listener : listeners) {
                try {
                    listener.accept(body);
                } catch (RuntimeException ex) {
                    LOG.warn("An anomaly listener failed", ex);
                }
            }
        }

        private boolean belongsToTenant(String body) {
            try {
                JsonNode tenant = jsonMapper.readTree(body).get("tenantId");
                return tenant != null && tenantId.toString().equals(tenant.stringValue());
            } catch (JacksonException ex) {
                return false;
            }
        }
    }

    private final class ActiveSubscription implements Subscription {

        private final TenantChannel channel;
        private final Consumer<String> listener;
        private final AtomicBoolean closed = new AtomicBoolean();

        private ActiveSubscription(TenantChannel channel, Consumer<String> listener) {
            this.channel = channel;
            this.listener = listener;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                unsubscribe(channel, listener);
            }
        }
    }
}

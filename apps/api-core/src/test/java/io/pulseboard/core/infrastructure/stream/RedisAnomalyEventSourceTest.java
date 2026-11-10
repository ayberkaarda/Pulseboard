package io.pulseboard.core.infrastructure.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import io.pulseboard.core.application.anomaly.AnomalyEventSource;
import io.pulseboard.core.domain.tenancy.TenantId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import tools.jackson.databind.json.JsonMapper;

/** Registry behaviour of the Redis event source when the bus fails while a subscription is being set up. */
class RedisAnomalyEventSourceTest {

    private static final TenantId TENANT = TenantId.of(UUID.randomUUID());

    private final RedisMessageListenerContainer container = mock(RedisMessageListenerContainer.class);
    private final RedisAnomalyEventSource source = new RedisAnomalyEventSource(container, JsonMapper.builder().build());

    @Test
    void aSubscribeThatFailsOnTheBusLeavesNoListenerAndNoChannelBehind() {
        doThrow(new IllegalStateException("redis down"))
                .when(container).addMessageListener(any(MessageListener.class), any(ChannelTopic.class));
        List<String> failedListenerSeen = new ArrayList<>();

        assertThatThrownBy(() -> source.subscribe(TENANT, failedListenerSeen::add)).isNotNull();
        assertThat(source.subscribedTenants()).isZero();

        // The bus recovers: the next subscriber registers a fresh channel and is the only one that receives.
        doNothing().when(container).addMessageListener(any(MessageListener.class), any(ChannelTopic.class));
        List<String> received = new ArrayList<>();
        AnomalyEventSource.Subscription subscription = source.subscribe(TENANT, received::add);
        ArgumentCaptor<MessageListener> registered = ArgumentCaptor.forClass(MessageListener.class);
        org.mockito.Mockito.verify(container, org.mockito.Mockito.times(2))
                .addMessageListener(registered.capture(), any(ChannelTopic.class));
        String body = "{\"tenantId\":\"" + TENANT + "\"}";
        registered.getAllValues().get(1).onMessage(
                new DefaultMessage(("anomalies:" + TENANT).getBytes(), body.getBytes()), null);
        // Even if the failed attempt's bus listener were invoked, the failed subscriber is no longer registered.
        registered.getAllValues().get(0).onMessage(
                new DefaultMessage(("anomalies:" + TENANT).getBytes(), body.getBytes()), null);

        assertThat(received).containsExactly(body);
        assertThat(failedListenerSeen).as("listener of the failed subscribe").isEmpty();
        subscription.close();
        assertThat(source.subscribedTenants()).isZero();
    }
}

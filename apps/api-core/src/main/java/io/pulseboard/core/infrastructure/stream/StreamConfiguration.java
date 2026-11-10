package io.pulseboard.core.infrastructure.stream;

import io.pulseboard.core.application.anomaly.StreamPolicy;
import io.pulseboard.core.application.streamticket.StreamTicketPolicy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/** The shared Redis subscription container and the stream limits. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({StreamProperties.class, StreamTicketProperties.class})
public class StreamConfiguration {

    @Bean
    RedisMessageListenerContainer anomalyListenerContainer(RedisConnectionFactory connectionFactory) {
        // The container hands every message to its task executor, and its default starts a new thread per message,
        // so two messages of one channel could reach their listeners out of order. A single dispatch thread keeps
        // the published order. Listeners only parse and enqueue (never block), so one thread is enough. The thread
        // that waits for messages from Redis is separate (the subscription executor), or it would occupy this one.
        ExecutorService dispatcher = Executors.newSingleThreadExecutor(Thread.ofPlatform()
                .name("anomaly-dispatch").daemon().factory());
        RedisMessageListenerContainer container = new RedisMessageListenerContainer() {
            @Override
            public void destroy() throws Exception {
                super.destroy();
                dispatcher.shutdown();
            }
        };
        container.setConnectionFactory(connectionFactory);
        container.setTaskExecutor(dispatcher);
        container.setSubscriptionExecutor(new SimpleAsyncTaskExecutor("anomaly-subscription-"));
        return container;
    }

    @Bean
    StreamPolicy streamPolicy(StreamProperties properties) {
        return properties.toPolicy();
    }

    @Bean
    StreamTicketPolicy streamTicketPolicy(StreamTicketProperties properties) {
        return properties.toPolicy();
    }
}

package io.pulseboard.core.infrastructure.stream;

import io.pulseboard.core.application.anomaly.StreamPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/** The shared Redis subscription container and the stream limits. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StreamProperties.class)
public class StreamConfiguration {

    @Bean
    RedisMessageListenerContainer anomalyListenerContainer(RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        return container;
    }

    @Bean
    StreamPolicy streamPolicy(StreamProperties properties) {
        return properties.toPolicy();
    }
}

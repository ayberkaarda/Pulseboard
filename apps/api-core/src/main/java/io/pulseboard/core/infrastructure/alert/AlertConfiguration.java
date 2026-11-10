package io.pulseboard.core.infrastructure.alert;

import io.pulseboard.core.application.alert.AlertDispatchPolicy;
import io.pulseboard.core.application.alert.AlertPolicy;
import io.pulseboard.core.application.alert.webhook.WebhookPolicy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Exposes the configured alerting policies to the application layer. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AlertProperties.class)
public class AlertConfiguration {

    @Bean
    AlertPolicy alertPolicy(AlertProperties properties) {
        return properties.toPolicy();
    }

    @Bean
    AlertDispatchPolicy alertDispatchPolicy(AlertProperties properties) {
        return properties.toDispatchPolicy();
    }

    @Bean
    WebhookPolicy webhookPolicy(AlertProperties properties) {
        return properties.toWebhookPolicy();
    }

    /** The small, fixed pool that runs deliveries: a slow receiver can occupy at most this many threads. */
    @Bean(destroyMethod = "shutdownNow")
    ExecutorService alertDeliveryExecutor(AlertProperties properties) {
        AtomicInteger counter = new AtomicInteger();
        return Executors.newFixedThreadPool(properties.dispatcher().concurrency(), runnable -> {
            Thread thread = new Thread(runnable, "alert-delivery-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }
}

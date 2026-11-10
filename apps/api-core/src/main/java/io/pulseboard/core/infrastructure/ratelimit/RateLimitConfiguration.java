package io.pulseboard.core.infrastructure.ratelimit;

import io.pulseboard.core.application.apikey.ApiKeyClientPolicy;
import io.pulseboard.core.application.auth.AuthThrottlingPolicy;
import io.pulseboard.core.application.ingest.IngestionPolicy;
import io.pulseboard.core.application.query.MetricQueryPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Exposes the configured throttling policies to the application layer. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AuthRateLimitProperties.class, IngestProperties.class,
    QueryRateLimitProperties.class, ApiKeyClientRateLimitProperties.class})
public class RateLimitConfiguration {

    @Bean
    AuthThrottlingPolicy authThrottlingPolicy(AuthRateLimitProperties properties) {
        return properties.toPolicy();
    }

    @Bean
    IngestionPolicy ingestionPolicy(IngestProperties properties) {
        return properties.toPolicy();
    }

    @Bean
    MetricQueryPolicy metricQueryPolicy(QueryRateLimitProperties properties) {
        return properties.toPolicy();
    }

    @Bean
    ApiKeyClientPolicy apiKeyClientPolicy(ApiKeyClientRateLimitProperties properties) {
        return properties.toPolicy();
    }
}

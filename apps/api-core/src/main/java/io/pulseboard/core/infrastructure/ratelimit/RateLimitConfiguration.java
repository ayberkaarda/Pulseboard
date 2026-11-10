package io.pulseboard.core.infrastructure.ratelimit;

import io.pulseboard.core.application.auth.AuthThrottlingPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Exposes the configured authentication throttling policy to the application layer. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthRateLimitProperties.class)
public class RateLimitConfiguration {

    @Bean
    AuthThrottlingPolicy authThrottlingPolicy(AuthRateLimitProperties properties) {
        return properties.toPolicy();
    }
}

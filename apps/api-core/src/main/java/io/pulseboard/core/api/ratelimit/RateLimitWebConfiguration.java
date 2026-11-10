package io.pulseboard.core.api.ratelimit;

import io.pulseboard.core.application.auth.AuthThrottlingPolicy;
import io.pulseboard.core.application.ratelimit.RateLimiter;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers the per-client throttle for every handler; the interceptor itself selects annotated handlers. */
@Configuration(proxyBeanMethods = false)
public class RateLimitWebConfiguration implements WebMvcConfigurer {

    private final RateLimiter rateLimiter;
    private final AuthThrottlingPolicy authThrottlingPolicy;

    public RateLimitWebConfiguration(RateLimiter rateLimiter, AuthThrottlingPolicy authThrottlingPolicy) {
        this.rateLimiter = rateLimiter;
        this.authThrottlingPolicy = authThrottlingPolicy;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new ClientRateLimitInterceptor(rateLimiter, authThrottlingPolicy));
    }
}

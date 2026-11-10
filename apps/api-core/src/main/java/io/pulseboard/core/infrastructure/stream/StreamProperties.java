package io.pulseboard.core.infrastructure.stream;

import io.pulseboard.core.application.anomaly.StreamPolicy;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Live stream settings bound from {@code pulseboard.stream.*}.
 *
 * @param maxPerTenant concurrent streams per tenant
 * @param maxTotal concurrent streams per instance
 * @param heartbeatInterval idle time before a keep-alive comment is sent
 * @param maxLifetime longest lifetime of one stream
 */
@ConfigurationProperties("pulseboard.stream")
public record StreamProperties(int maxPerTenant, int maxTotal, Duration heartbeatInterval, Duration maxLifetime) {

    public StreamProperties {
        if (maxPerTenant < 1 || maxTotal < 1) {
            throw new IllegalArgumentException("pulseboard.stream connection limits must be at least 1");
        }
        if (heartbeatInterval == null || heartbeatInterval.isNegative() || heartbeatInterval.isZero()) {
            throw new IllegalArgumentException("pulseboard.stream.heartbeat-interval must be a positive duration");
        }
        if (maxLifetime == null || maxLifetime.isNegative() || maxLifetime.isZero()) {
            throw new IllegalArgumentException("pulseboard.stream.max-lifetime must be a positive duration");
        }
    }

    public StreamPolicy toPolicy() {
        return new StreamPolicy(maxPerTenant, maxTotal, heartbeatInterval, maxLifetime);
    }
}

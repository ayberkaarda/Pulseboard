package io.pulseboard.core.infrastructure.cache;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * API key cache settings bound from {@code pulseboard.api-key-cache.*}.
 *
 * @param ttl how long a successful authentication is cached
 * @param revokedMarkerTtl how long a revocation marker is kept; it must outlive every positive entry and every
 *     lookup that may still be in flight when the key is revoked
 */
@ConfigurationProperties("pulseboard.api-key-cache")
public record ApiKeyCacheProperties(Duration ttl, Duration revokedMarkerTtl) {

    public ApiKeyCacheProperties {
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("pulseboard.api-key-cache.ttl must be a positive duration");
        }
        if (revokedMarkerTtl == null || revokedMarkerTtl.compareTo(ttl.multipliedBy(10)) < 0) {
            throw new IllegalArgumentException(
                    "pulseboard.api-key-cache.revoked-marker-ttl must be at least ten times the ttl");
        }
    }
}

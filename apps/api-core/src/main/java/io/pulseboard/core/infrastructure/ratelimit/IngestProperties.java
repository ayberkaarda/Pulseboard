package io.pulseboard.core.infrastructure.ratelimit;

import io.pulseboard.core.application.ingest.IngestionPolicy;
import io.pulseboard.core.application.ratelimit.RateLimitPolicy;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Ingestion settings bound from {@code pulseboard.ingest.*}.
 *
 * @param maxBodyBytes maximum size of a raw ingestion request body
 * @param freeBatchCapacity batches a FREE tenant may send per {@code refillPeriod}
 * @param proBatchCapacity batches a PRO tenant may send per {@code refillPeriod}
 * @param refillPeriod time to refill a tenant's bucket completely
 */
@ConfigurationProperties("pulseboard.ingest")
public record IngestProperties(
        int maxBodyBytes,
        int freeBatchCapacity,
        int proBatchCapacity,
        Duration refillPeriod) {

    public IngestionPolicy toPolicy() {
        return new IngestionPolicy(new RateLimitPolicy(freeBatchCapacity, refillPeriod),
                new RateLimitPolicy(proBatchCapacity, refillPeriod), maxBodyBytes);
    }
}

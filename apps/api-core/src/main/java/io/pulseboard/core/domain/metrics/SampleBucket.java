package io.pulseboard.core.domain.metrics;

import java.time.Instant;

/**
 * One downsampling bucket.
 *
 * @param start start of the bucket (buckets are aligned to the Unix epoch)
 * @param value the samples of the bucket combined with the metric's aggregation, or {@code null} when the
 *     aggregate is not a finite double (only possible for samples written outside the ingestion bounds)
 * @param sampleCount number of samples in the bucket (always at least one)
 */
public record SampleBucket(Instant start, Double value, long sampleCount) {

    public SampleBucket {
        if (value != null && !Double.isFinite(value)) {
            value = null;
        }
    }
}

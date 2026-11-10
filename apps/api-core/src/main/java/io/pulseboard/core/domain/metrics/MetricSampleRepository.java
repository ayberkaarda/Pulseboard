package io.pulseboard.core.domain.metrics;

import io.pulseboard.core.domain.catalog.Aggregation;
import io.pulseboard.core.domain.tenancy.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Persistence port for the {@code metric_point} time series. Every method is scoped to a tenant: samples are only
 * written to, and read from, metrics owned by that tenant.
 */
public interface MetricSampleRepository {

    /**
     * Inserts the samples, replacing the value of an existing sample with the same metric and timestamp. Samples
     * are written in list order, so the last of several samples with the same metric and timestamp wins.
     *
     * @return one outcome per sample, in the order of {@code samples}
     */
    List<SampleWriteOutcome> upsert(TenantId tenantId, List<MetricSample> samples);

    /**
     * Combines the samples of {@code metricId} with {@code from <= timestamp < to} into buckets of {@code step},
     * aligned to the Unix epoch. Buckets without samples are omitted.
     *
     * @return buckets in ascending order of their start
     */
    List<SampleBucket> downsample(TenantId tenantId, UUID metricId, Aggregation aggregation, Instant from,
            Instant to, Duration step);
}

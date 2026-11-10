package io.pulseboard.core.domain.metrics;

import io.pulseboard.core.domain.shared.TenantScoped;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * One time-series sample of a metric. Samples belong to the tenant that owns the metric definition, so every
 * repository access to them is tenant-scoped.
 *
 * <p>Values are bounded by {@link #MAX_ABSOLUTE_VALUE}. The bound keeps every aggregate the query side computes in
 * double precision finite: PostgreSQL's {@code avg} accumulates the sum of squares, so samples near the double
 * range (for example 1e155 next to 0) would overflow it, and {@code percentile_cont} interpolation of two
 * opposite extremes overflows to infinity. With {@code |value| <= 1e100}, a bucket would need more than 1e108
 * samples to overflow.
 *
 * @param metricId the metric definition the sample belongs to
 * @param timestamp sample time, truncated to microseconds (the precision of the database)
 * @param value a finite number with {@code |value| <= MAX_ABSOLUTE_VALUE}
 */
@TenantScoped
public record MetricSample(UUID metricId, Instant timestamp, double value) {

    /** Largest accepted absolute sample value. */
    public static final double MAX_ABSOLUTE_VALUE = 1e100;

    public MetricSample {
        Objects.requireNonNull(metricId, "metricId");
        // The database stores microseconds; truncating here keeps rounding from moving a sample across a bound.
        timestamp = Objects.requireNonNull(timestamp, "timestamp").truncatedTo(ChronoUnit.MICROS);
        if (!isStorable(value)) {
            throw new IllegalArgumentException("value must be finite and at most " + MAX_ABSOLUTE_VALUE
                    + " in absolute value");
        }
    }

    /** Whether {@code value} can be stored: finite and within {@link #MAX_ABSOLUTE_VALUE}. */
    public static boolean isStorable(double value) {
        return Double.isFinite(value) && Math.abs(value) <= MAX_ABSOLUTE_VALUE;
    }
}

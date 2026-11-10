package io.pulseboard.core.application.query;

import io.pulseboard.core.application.common.InvalidRequestException;
import io.pulseboard.core.application.common.InvalidRequestException.Violation;
import io.pulseboard.core.application.common.ResourceNotFoundException;
import io.pulseboard.core.application.ratelimit.RateLimiter;
import io.pulseboard.core.domain.catalog.MetricDefinition;
import io.pulseboard.core.domain.catalog.MetricDefinitionRepository;
import io.pulseboard.core.domain.metrics.MetricSampleRepository;
import io.pulseboard.core.domain.tenancy.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Reads metric samples downsampled on the server.
 *
 * <p>Buckets are aligned to the Unix epoch, so the same step always yields the same bucket boundaries no matter
 * which range is requested (charts with a sliding window do not jitter). A response never has more than
 * {@link #MAX_BUCKETS} buckets:
 *
 * <ul>
 *   <li>without {@code step}, the smallest step of a fixed ladder (1 s up to 7 d) that stays within the limit is
 *   chosen;</li>
 *   <li>an explicit {@code step} that would exceed the limit is rejected with 400 naming the smallest allowed
 *   step. An explicit resolution is never silently changed, because the caller would misread the series.</li>
 * </ul>
 */
@Service
public class MetricQueryService {

    /** Upper bound of buckets per response. */
    public static final int MAX_BUCKETS = 2000;
    /** Longest range a single query may cover. */
    public static final Duration MAX_RANGE = Duration.ofDays(366);
    /** Smallest bucket width. */
    public static final Duration MIN_STEP = Duration.ofSeconds(1);

    private static final Instant EARLIEST = Instant.parse("1970-01-01T00:00:00Z");
    private static final Instant LATEST = Instant.parse("9999-12-31T23:59:59Z");

    private static final List<Duration> STEP_LADDER = List.of(
            Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(15),
            Duration.ofSeconds(30), Duration.ofMinutes(1), Duration.ofMinutes(2), Duration.ofMinutes(5),
            Duration.ofMinutes(10), Duration.ofMinutes(15), Duration.ofMinutes(30), Duration.ofHours(1),
            Duration.ofHours(2), Duration.ofHours(3), Duration.ofHours(6), Duration.ofHours(12), Duration.ofDays(1),
            Duration.ofDays(2), Duration.ofDays(7));

    /** Rate-limit bucket namespace; the subject is the tenant id. */
    public static final String RATE_LIMIT_NAMESPACE = "query";

    private final MetricDefinitionRepository metricDefinitionRepository;
    private final MetricSampleRepository sampleRepository;
    private final RateLimiter rateLimiter;
    private final MetricQueryPolicy policy;

    public MetricQueryService(MetricDefinitionRepository metricDefinitionRepository,
            MetricSampleRepository sampleRepository, RateLimiter rateLimiter, MetricQueryPolicy policy) {
        this.metricDefinitionRepository = metricDefinitionRepository;
        this.sampleRepository = sampleRepository;
        this.rateLimiter = rateLimiter;
        this.policy = policy;
    }

    /**
     * Downsamples the samples of a metric of the tenant in {@code [from, to)}.
     *
     * <p>The tenant's query bucket is charged first, before any database work. {@code from} and {@code to} are
     * rounded up to the next microsecond (the storage precision): a stored sample {@code s} satisfies
     * {@code s >= from} exactly when {@code s >= ceil(from)}, and {@code s < to} exactly when {@code s < ceil(to)},
     * so the bucket count that is validated and the rows the SQL selects always describe the same range. The
     * returned {@code from}/{@code to} are the normalised bounds. No transaction is held across the two reads;
     * each runs in its own.
     *
     * @param step bucket width, or {@code null} to choose one automatically
     * @throws ResourceNotFoundException when the metric does not exist for the tenant (also for other tenants'
     *     metrics)
     * @throws InvalidRequestException when the range or the step is invalid
     * @throws io.pulseboard.core.application.ratelimit.RateLimitExceededException when the tenant's bucket is empty
     */
    public MetricSeries points(TenantId tenantId, UUID metricId, Instant requestedFrom, Instant requestedTo,
            Duration step) {
        rateLimiter.acquireOrThrow(RATE_LIMIT_NAMESPACE, tenantId.toString(), policy.perTenant());
        if (requestedFrom == null || requestedTo == null) {
            throw invalid("from", "from and to are required");
        }
        validateRange(requestedFrom, requestedTo);
        Instant from = ceilToMicros(requestedFrom);
        Instant to = ceilToMicros(requestedTo);
        // A range between two consecutive microseconds holds no storable instant: valid, and empty.
        boolean empty = !from.isBefore(to);
        Duration effectiveStep;
        if (step != null) {
            effectiveStep = validateStep(from, to, step);
        } else {
            effectiveStep = empty ? MIN_STEP : automaticStep(from, to);
        }
        MetricDefinition metric = metricDefinitionRepository.findByIdAndTenantId(metricId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Metric", metricId));
        return new MetricSeries(metric.getId(), metric.getAggregation(), from, to, effectiveStep, empty ? List.of()
                : sampleRepository.downsample(tenantId, metric.getId(), metric.getAggregation(), from, to,
                        effectiveStep));
    }

    /** Number of epoch-aligned buckets of width {@code step} that intersect {@code [from, to)}. */
    static long bucketCount(Instant from, Instant to, Duration step) {
        long stepMicros = toMicros(step);
        long first = Math.floorDiv(toMicros(from), stepMicros);
        long last = Math.floorDiv(toMicros(to) - 1, stepMicros);
        return last - first + 1;
    }

    /** Smallest microsecond instant that is not before {@code instant}. */
    public static Instant ceilToMicros(Instant instant) {
        Instant truncated = instant.truncatedTo(ChronoUnit.MICROS);
        return truncated.equals(instant) ? instant : truncated.plus(1, ChronoUnit.MICROS);
    }

    private static void validateRange(Instant from, Instant to) {
        if (from.isBefore(EARLIEST) || to.isAfter(LATEST)) {
            throw invalid("from", "from and to must lie between " + EARLIEST + " and " + LATEST);
        }
        if (!from.isBefore(to)) {
            throw invalid("to", "must be after from");
        }
        if (Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
            throw invalid("to", "the range must not exceed " + MAX_RANGE.toDays() + " days");
        }
    }

    private static Duration validateStep(Instant from, Instant to, Duration step) {
        if (step.compareTo(MIN_STEP) < 0 || step.compareTo(MAX_RANGE) > 0 || step.getNano() != 0) {
            throw invalid("step", "must be a whole number of seconds between " + MIN_STEP + " and " + MAX_RANGE);
        }
        if (bucketCount(from, to, step) > MAX_BUCKETS) {
            throw invalid("step", "yields more than " + MAX_BUCKETS + " buckets for this range; use at least "
                    + minimumStep(from, to));
        }
        return step;
    }

    private static Duration automaticStep(Instant from, Instant to) {
        return STEP_LADDER.stream()
                .filter(candidate -> bucketCount(from, to, candidate) <= MAX_BUCKETS)
                .findFirst()
                .orElseGet(() -> minimumStep(from, to));
    }

    /** Smallest whole-second step that keeps the bucket count within the limit. */
    private static Duration minimumStep(Instant from, Instant to) {
        long seconds = Math.max(1, Duration.between(from, to).toSeconds() / MAX_BUCKETS);
        while (bucketCount(from, to, Duration.ofSeconds(seconds)) > MAX_BUCKETS) {
            seconds++;
        }
        return Duration.ofSeconds(seconds);
    }

    private static long toMicros(Instant instant) {
        return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000L), instant.getNano() / 1_000);
    }

    private static long toMicros(Duration duration) {
        return Math.addExact(Math.multiplyExact(duration.getSeconds(), 1_000_000L), duration.getNano() / 1_000);
    }

    private static InvalidRequestException invalid(String parameter, String message) {
        return new InvalidRequestException("The query parameters are invalid",
                List.of(new Violation(parameter, message)));
    }
}

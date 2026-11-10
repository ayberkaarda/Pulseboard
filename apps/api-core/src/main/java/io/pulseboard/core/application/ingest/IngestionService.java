package io.pulseboard.core.application.ingest;

import io.pulseboard.core.application.auth.AuthenticationFailedException;
import io.pulseboard.core.application.ratelimit.RateLimiter;
import io.pulseboard.core.domain.metrics.MetricSample;
import io.pulseboard.core.domain.metrics.MetricSampleRepository;
import io.pulseboard.core.domain.metrics.SampleWriteOutcome;
import io.pulseboard.core.domain.tenancy.Plan;
import io.pulseboard.core.domain.tenancy.TenantId;
import io.pulseboard.core.domain.tenancy.TenantRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Ingests metric batches sent by machine callers (API keys).
 *
 * <p>Order of checks: the tenant's token bucket (FREE and PRO plans differ) is charged first, so a throttled
 * caller never costs a body read, a parse or a database write. The body is then read with a size limit, parsed
 * and validated against the JSON Schema; a body that is not a valid batch is rejected as a whole. Points that are
 * valid JSON but cannot be stored are rejected individually and the rest of the batch is written:
 *
 * <ul>
 *   <li>a metric id that does not exist, or belongs to another tenant (both get the same reason);</li>
 *   <li>a timestamp no time partition covers (storage is provisioned monthly, see
 *   {@code ensure_metric_partitions}); checking this up front keeps one stray timestamp from failing the whole
 *   batch;</li>
 *   <li>a value outside the IEEE 754 double range (it would be stored as infinity);</li>
 *   <li>a finite value whose magnitude exceeds {@link MetricSample#MAX_ABSOLUTE_VALUE} (1e100), so that every
 *   aggregate the query side computes stays finite;</li>
 *   <li>a timestamp the schema accepts but Java cannot represent (for example a leap second);</li>
 *   <li>a point superseded by a later point of the same batch with the same metric and timestamp (last write
 *   wins; {@code accepted} counts stored rows, so the duplicate is not counted twice).</li>
 * </ul>
 */
@Service
public class IngestionService {

    /** Rate-limit bucket namespace; the subject is the tenant id. */
    public static final String RATE_LIMIT_NAMESPACE = "ingest";

    static final String UNKNOWN_METRIC = "unknown metric";
    static final String OUTSIDE_STORAGE_RANGE = "timestamp is outside the range accepted for storage";
    static final String NON_FINITE_VALUE = "value must be a finite number";
    static final String INVALID_TIMESTAMP = "timestamp cannot be represented";
    static final String VALUE_TOO_LARGE = "value magnitude must not exceed 1e100";
    static final String SUPERSEDED = "superseded by a later point with the same metricId and ts";

    /** RFC 3339 date-time; the separator and the zone designator may be lower case. */
    private static final DateTimeFormatter RFC_3339 = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .append(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            .toFormatter();

    private final TenantRepository tenantRepository;
    private final MetricSampleRepository sampleRepository;
    private final MetricBatchParser batchParser;
    private final RateLimiter rateLimiter;
    private final IngestionPolicy policy;

    public IngestionService(TenantRepository tenantRepository, MetricSampleRepository sampleRepository,
            MetricBatchParser batchParser, RateLimiter rateLimiter, IngestionPolicy policy) {
        this.tenantRepository = tenantRepository;
        this.sampleRepository = sampleRepository;
        this.batchParser = batchParser;
        this.rateLimiter = rateLimiter;
        this.policy = policy;
    }

    /** Maximum accepted size of a raw request body, in bytes. */
    public int maxBodyBytes() {
        return policy.maxBodyBytes();
    }

    /**
     * Ingests one batch for {@code tenantId}.
     *
     * @throws io.pulseboard.core.application.ratelimit.RateLimitExceededException when the tenant's bucket is empty
     * @throws PayloadTooLargeException when the body exceeds {@link #maxBodyBytes()}
     * @throws io.pulseboard.core.application.common.InvalidRequestException when the body is not a valid batch
     */
    public IngestionResult ingest(TenantId tenantId, BatchBody body) {
        Plan plan = tenantRepository.findPlanById(tenantId.value())
                .orElseThrow(() -> new AuthenticationFailedException("Credentials are not bound to a tenant"));
        rateLimiter.acquireOrThrow(RATE_LIMIT_NAMESPACE, tenantId.toString(), policy.forPlan(plan));

        MetricBatch batch = batchParser.parse(body.read());

        List<IngestionResult.Rejection> rejected = new ArrayList<>();
        List<MetricSample> samples = new ArrayList<>(batch.points().size());
        List<Integer> sampleIndexes = new ArrayList<>(batch.points().size());
        for (MetricBatch.Point point : batch.points()) {
            Instant timestamp = parseTimestamp(point.timestamp());
            if (timestamp == null) {
                rejected.add(new IngestionResult.Rejection(point.index(), INVALID_TIMESTAMP));
            } else if (!Double.isFinite(point.value())) {
                rejected.add(new IngestionResult.Rejection(point.index(), NON_FINITE_VALUE));
            } else if (!MetricSample.isStorable(point.value())) {
                rejected.add(new IngestionResult.Rejection(point.index(), VALUE_TOO_LARGE));
            } else {
                samples.add(new MetricSample(UUID.fromString(point.metricId()), timestamp, point.value()));
                sampleIndexes.add(point.index());
            }
        }

        int accepted = 0;
        if (!samples.isEmpty()) {
            List<SampleWriteOutcome> outcomes = sampleRepository.upsert(tenantId, samples);
            for (int i = 0; i < outcomes.size(); i++) {
                switch (outcomes.get(i)) {
                    case STORED -> accepted++;
                    case UNKNOWN_METRIC -> rejected.add(new IngestionResult.Rejection(sampleIndexes.get(i),
                            UNKNOWN_METRIC));
                    case OUTSIDE_STORAGE_RANGE -> rejected.add(new IngestionResult.Rejection(sampleIndexes.get(i),
                            OUTSIDE_STORAGE_RANGE));
                    case SUPERSEDED -> rejected.add(new IngestionResult.Rejection(sampleIndexes.get(i),
                            SUPERSEDED));
                    default -> throw new IllegalStateException("Unhandled outcome " + outcomes.get(i));
                }
            }
        }
        rejected.sort((left, right) -> Integer.compare(left.index(), right.index()));
        return new IngestionResult(accepted, rejected);
    }

    private static Instant parseTimestamp(String value) {
        try {
            return OffsetDateTime.parse(value, RFC_3339).toInstant();
        } catch (DateTimeParseException ex) {
            return null;
        }
    }
}
